import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class Cliente {
    private Cliente() {}

    static Transferencia enviar(InetSocketAddress destino, Path caminho) throws IOException {
        if (!Files.isRegularFile(caminho)) throw new IOException("Selecione um arquivo regular.");
        byte[] nome = Protocolo.nomeValido(caminho.getFileName().toString());
        long tamanho = Files.size(caminho);
        if (tamanho > Protocolo.LIMITE_ARQUIVO) throw new IOException("O arquivo excede 100 MiB.");
        long inicio = System.nanoTime();

        try (var socket = new Socket()) {
            socket.connect(destino, 5_000);
            socket.setSoTimeout(Protocolo.TCP_TIMEOUT_MS);
            var entrada = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            var saida = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            saida.writeInt(Protocolo.TCP_MAGIC);
            saida.writeShort(nome.length);
            saida.write(nome);
            saida.writeLong(tamanho);
            byte[] hash;
            try (var arquivo = Files.newInputStream(caminho)) {
                hash = Protocolo.copiarComHash(arquivo, saida, tamanho);
                if (arquivo.read() != -1) throw new IOException("O tamanho do arquivo mudou durante o envio.");
            }
            saida.write(hash);
            saida.flush();
            if (entrada.readInt() != Protocolo.TCP_MAGIC) throw new IOException("Confirmação TCP inválida.");
            boolean sucesso = entrada.readBoolean();
            String detalhe = entrada.readUTF();
            if (!sucesso) throw new IOException(detalhe);
            return new Transferencia(detalhe, tamanho, Protocolo.hex(hash), milissegundos(System.nanoTime() - inicio));
        }
    }

    static Resumo sondar(InetSocketAddress destino, int quantidade, int intervalo, int timeout,
                        Consumer<Medicao> aoMedir) throws IOException, InterruptedException {
        if (quantidade < 1 || quantidade > 200) throw new IllegalArgumentException("Use de 1 a 200 sondas.");
        if (intervalo < 1 || intervalo > 10_000 || timeout < 1 || timeout > 10_000) {
            throw new IllegalArgumentException("Intervalo e timeout devem estar entre 1 e 10.000 ms.");
        }
        int recebidas = 0;
        double acumulado = 0;
        double menor = Double.POSITIVE_INFINITY;
        double maior = 0;
        long nonce = ThreadLocalRandom.current().nextLong();

        try (var socket = new DatagramSocket()) {
            // A conexão do socket UDP define o destino e filtra quem pode responder.
            // Não cria sessão de transporte nem passa a garantir entrega.
            socket.connect(destino);
            byte[] bytes = new byte[256];
            var pacote = new DatagramPacket(bytes, bytes.length);
            for (int seq = 1; seq <= quantidade; seq++) {
                if (Thread.interrupted()) throw new InterruptedException();
                long envio = System.nanoTime();
                byte[] consulta = Protocolo.datagrama(Protocolo.SONDA, nonce, seq);
                double rtt;
                try {
                    socket.send(new DatagramPacket(consulta, consulta.length, destino));
                    rtt = aguardarSonda(socket, pacote, nonce, seq, envio, timeout);
                } catch (PortUnreachableException erro) {
                    rtt = -1;
                }
                aoMedir.accept(new Medicao(seq, rtt));
                if (rtt >= 0) {
                    recebidas++;
                    acumulado += rtt;
                    menor = Math.min(menor, rtt);
                    maior = Math.max(maior, rtt);
                }
                if (seq < quantidade) {
                    long restante = envio + TimeUnit.MILLISECONDS.toNanos(intervalo) - System.nanoTime();
                    if (restante > 0) TimeUnit.NANOSECONDS.sleep(restante);
                }
            }
        }
        return new Resumo(quantidade, recebidas, recebidas == 0 ? 0 : menor,
                recebidas == 0 ? 0 : acumulado / recebidas, maior);
    }

    private static double aguardarSonda(DatagramSocket socket, DatagramPacket pacote, long nonce,
                                       int seq, long envio, int timeout) throws IOException {
        long prazo = envio + TimeUnit.MILLISECONDS.toNanos(timeout);
        while (true) {
            long restante = prazo - System.nanoTime();
            if (restante <= 0) return -1;
            int ms = (int) Math.max(1, (restante + 999_999) / 1_000_000);
            socket.setSoTimeout(ms);
            pacote.setLength(pacote.getData().length);
            try {
                socket.receive(pacote);
            } catch (SocketTimeoutException erro) {
                return -1;
            }
            Protocolo.Sonda sonda = Protocolo.lerSonda(pacote.getData(), pacote.getOffset(),
                    pacote.getLength(), Protocolo.RESPOSTA);
            if (sonda != null && sonda.nonce() == nonce && sonda.sequencia() == seq) {
                long recebido = System.nanoTime();
                return recebido <= prazo ? milissegundos(recebido - envio) : -1;
            }
        }
    }

    private static double milissegundos(long nanos) {
        return nanos / 1_000_000.0;
    }

    record Transferencia(String nome, long bytes, String sha256, double tempoMs) {}
    record Medicao(int sequencia, double tempoMs) {
        boolean respondida() { return tempoMs >= 0; }
    }
    record Resumo(int enviadas, int recebidas, double menorMs, double mediaMs, double maiorMs) {
        int semResposta() { return enviadas - recebidas; }
    }
}
