import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class Servidor implements AutoCloseable {
    private final ServerSocket tcp;
    private final DatagramSocket udp;
    private final Path destino;
    private final int descartarCada;
    private final Consumer<String> log;
    private final Set<Socket> conexoes = ConcurrentHashMap.newKeySet();
    private final CountDownLatch encerrado = new CountDownLatch(1);
    private final AtomicBoolean rodando = new AtomicBoolean(true);
    private final ThreadPoolExecutor trabalhadores = new ThreadPoolExecutor(
            8, 8, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
            Executors.defaultThreadFactory());

    Servidor(InetSocketAddress endereco, Path destino, int descartarCada, Consumer<String> log)
            throws IOException {
        if (descartarCada < 0) throw new IllegalArgumentException("O intervalo de descarte deve ser maior ou igual a zero.");
        this.destino = Files.createDirectories(destino.toAbsolutePath().normalize());
        this.descartarCada = descartarCada;
        this.log = log;

        tcp = new ServerSocket();
        DatagramSocket socketUdp = null;
        try {
            tcp.bind(endereco);
            socketUdp = new DatagramSocket(null);
            socketUdp.bind(endereco);
        } catch (IOException erro) {
            tcp.close();
            if (socketUdp != null) socketUdp.close();
            throw erro;
        }
        udp = socketUdp;
        log.accept("Servidor TCP e UDP ativo em " + endereco.getAddress().getHostAddress()
                + ":" + endereco.getPort());
        log.accept("Arquivos recebidos em " + this.destino);
        if (descartarCada > 0) log.accept("Modo didático UDP: uma sonda descartada a cada " + descartarCada + ".");
        new Thread(this::aceitarTcp, "servidor-aceite-tcp").start();
        new Thread(this::responderUdp, "servidor-sondas-udp").start();
    }

    void aguardar() throws InterruptedException {
        encerrado.await();
    }

    private void aceitarTcp() {
        while (rodando.get()) {
            try {
                Socket conexao = tcp.accept();
                conexoes.add(conexao);
                if (!rodando.get()) {
                    conexoes.remove(conexao);
                    conexao.close();
                    break;
                }
                try {
                    trabalhadores.execute(() -> receberTcp(conexao));
                } catch (RejectedExecutionException erro) {
                    conexoes.remove(conexao);
                    conexao.close();
                    if (rodando.get()) log.accept("TCP: fila de conexões cheia.");
                }
            } catch (SocketException erro) {
                if (rodando.get()) {
                    log.accept("TCP: aceite interrompido: " + erro.getMessage());
                    close();
                }
            } catch (IOException erro) {
                if (rodando.get()) log.accept("TCP: falha no aceite: " + erro.getMessage());
            }
        }
    }

    private void receberTcp(Socket conexao) {
        Path parcial = null;
        try (conexao) {
            conexao.setSoTimeout(Protocolo.TCP_TIMEOUT_MS);
            var entrada = new DataInputStream(new BufferedInputStream(conexao.getInputStream()));
            var saida = new DataOutputStream(new BufferedOutputStream(conexao.getOutputStream()));
            boolean confirmado = false;
            try {
                if (entrada.readInt() != Protocolo.TCP_MAGIC) throw new IOException("Protocolo TCP desconhecido.");
                int largura = entrada.readUnsignedShort();
                if (largura < 1 || largura > Protocolo.LIMITE_NOME_BYTES) throw new IOException("Nome de arquivo inválido.");
                String nome = new String(Protocolo.lerExato(entrada, largura), StandardCharsets.UTF_8);
                Protocolo.nomeValido(nome);
                long tamanho = entrada.readLong();
                if (tamanho < 0 || tamanho > Protocolo.LIMITE_ARQUIVO) throw new IOException("Tamanho de arquivo fora do limite de 100 MiB.");
                log.accept("TCP recebido de " + conexao.getRemoteSocketAddress() + ": " + nome + " (" + tamanho + " bytes)");

                parcial = Files.createTempFile(destino, ".transferencia-", ".part");
                byte[] calculado;
                try (OutputStream arquivo = new BufferedOutputStream(Files.newOutputStream(parcial))) {
                    calculado = Protocolo.copiarComHash(entrada, arquivo, tamanho);
                }
                byte[] informado = Protocolo.lerExato(entrada, Protocolo.SHA256_BYTES);
                if (!MessageDigest.isEqual(calculado, informado)) throw new IOException("SHA-256 divergente.");

                String nomeSalvo = UUID.randomUUID().toString().substring(0, 8) + "-" + nome;
                Files.move(parcial, destino.resolve(nomeSalvo));
                parcial = null;
                confirmado = true;
                Protocolo.responderTcp(saida, true, nomeSalvo);
                log.accept("TCP ACK enviado: " + nomeSalvo + " | SHA-256 " + Protocolo.hex(calculado));
            } catch (IOException erro) {
                if (!confirmado && rodando.get()) {
                    try {
                        Protocolo.responderTcp(saida, false, "Transferência rejeitada. Consulte o servidor.");
                    } catch (IOException ignorado) {
                        // A conexão pode já estar fechada no lado cliente.
                    }
                }
                if (rodando.get()) log.accept("TCP: " + erro.getMessage());
            }
        } catch (IOException erro) {
            if (rodando.get()) log.accept("TCP: " + erro.getMessage());
        } finally {
            conexoes.remove(conexao);
            if (parcial != null) {
                try {
                    Files.deleteIfExists(parcial);
                } catch (IOException erro) {
                    log.accept("Falha ao remover transferência incompleta: " + parcial);
                }
            }
        }
    }

    private void responderUdp() {
        byte[] bytes = new byte[256];
        var pacote = new DatagramPacket(bytes, bytes.length);
        while (rodando.get()) {
            try {
                pacote.setLength(bytes.length);
                udp.receive(pacote);
                Protocolo.Sonda sonda = Protocolo.lerSonda(bytes, pacote.getOffset(), pacote.getLength(), Protocolo.SONDA);
                if (sonda == null) continue;
                if (descartarCada > 0 && sonda.sequencia() % descartarCada == 0) {
                    log.accept("UDP #" + sonda.sequencia() + " descartada para demonstração.");
                    continue;
                }
                byte[] resposta = Protocolo.datagrama(Protocolo.RESPOSTA, sonda.nonce(), sonda.sequencia());
                udp.send(new DatagramPacket(resposta, resposta.length, pacote.getSocketAddress()));
                log.accept("UDP #" + sonda.sequencia() + " respondida para " + pacote.getSocketAddress());
            } catch (SocketException erro) {
                if (rodando.get()) {
                    log.accept("UDP: recepção interrompida: " + erro.getMessage());
                    close();
                }
            } catch (IOException erro) {
                if (rodando.get()) log.accept("UDP: " + erro.getMessage());
            }
        }
    }

    @Override
    public void close() {
        if (!rodando.compareAndSet(true, false)) return;
        udp.close();
        try {
            tcp.close();
        } catch (IOException ignorado) {}
        trabalhadores.shutdownNow();
        for (Socket conexao : conexoes) {
            try {
                conexao.close();
            } catch (IOException ignorado) {}
        }
        encerrado.countDown();
    }
}
