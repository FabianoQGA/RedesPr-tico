import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class VerificarRedeLab {
    public static void main(String[] args) throws Exception {
        Path teste = Files.createTempDirectory("redelab-verificacao-");
        try {
            verificar(teste);
        } finally {
            try (var arquivos = Files.walk(teste)) {
                for (Path arquivo : arquivos.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(arquivo);
                }
            }
        }
    }

    private static void verificar(Path teste) throws Exception {
        int porta;
        try (var livre = new ServerSocket(0)) {
            porta = livre.getLocalPort();
        }
        var endereco = Protocolo.ipv4("127.0.0.1", porta);
        Path recebidos = teste.resolve("recebidos");
        Path amostra = teste.resolve("arquivo-de-prova.bin");
        byte[] dados = new byte[384 * 1024];
        new Random(27).nextBytes(dados);
        Files.write(amostra, dados);

        var tarefas = Executors.newFixedThreadPool(2);
        var parcialEnviado = new CountDownLatch(1);
        var liberarTransferencia = new CountDownLatch(1);
        try (var servidor = new Servidor(endereco, recebidos, 3, System.out::println)) {
            var lenta = tarefas.submit(() -> {
                try (var socket = new Socket()) {
                    socket.connect(endereco, 2000);
                    socket.setSoTimeout(5000);
                    var entrada = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                    var saida = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                    escreverCabecalho(saida, "prova-em-andamento.bin", dados.length);
                    saida.write(dados, 0, 16 * 1024);
                    saida.flush();
                    parcialEnviado.countDown();
                    exigir(liberarTransferencia.await(6, TimeUnit.SECONDS), "O teste bloqueou a conexão lenta.");
                    saida.write(dados, 16 * 1024, dados.length - 16 * 1024);
                    saida.write(MessageDigest.getInstance("SHA-256").digest(dados));
                    saida.flush();
                    return lerAck(entrada, true);
                }
            });
            exigir(parcialEnviado.await(5, TimeUnit.SECONDS), "Cliente TCP lento não iniciou.");

            var udp = tarefas.submit(() -> {
                var semResposta = new ArrayList<Integer>();
                var resumo = Cliente.sondar(endereco, 6, 35, 200, medicao -> {
                    if (!medicao.respondida()) semResposta.add(medicao.sequencia());
                });
                exigir(resumo.recebidas() == 4 && semResposta.equals(List.of(3, 6)), "UDP não distinguiu as perdas induzidas.");
                exigir(resumo.menorMs() >= 0 && resumo.maiorMs() >= resumo.mediaMs(), "RTT UDP inconsistente.");
                return resumo;
            });
            var paralelo = Cliente.enviar(endereco, amostra);
            exigir(Files.mismatch(amostra, recebidos.resolve(paralelo.nome())) == -1, "Bytes TCP divergentes.");
            exigir(udp.get(5, TimeUnit.SECONDS).semResposta() == 2, "Resultados UDP não confirmados.");
            exigir(!lenta.isDone(), "A transferência deveria continuar pendente durante as sondas.");
            liberarTransferencia.countDown();
            String nome = lenta.get(5, TimeUnit.SECONDS);
            exigir(Files.mismatch(amostra, recebidos.resolve(nome)) == -1, "Arquivos da conexão lenta não conferem.");

            try (var socket = new Socket()) {
                socket.connect(endereco, 2000);
                socket.setSoTimeout(5000);
                var entrada = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                var saida = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                escreverCabecalho(saida, "hash-incorreto.bin", 1);
                saida.write(42);
                saida.write(new byte[Protocolo.SHA256_BYTES]);
                saida.flush();
                lerAck(entrada, false);
                exigir(entrada.read() == -1, "Servidor não fechou conexão rejeitada.");
            }
            try (var arquivos = Files.list(recebidos)) {
                exigir(arquivos.noneMatch(f -> f.getFileName().toString().endsWith("hash-incorreto.bin")),
                        "Arquivo com hash divergente foi aceito.");
            }
            System.out.println("OK: duas conexões TCP simultâneas gravaram arquivos idênticos à origem.");
            System.out.println("OK: sondas UDP responderam durante TCP pendente. Perdas induzidas: #3 e #6.");
            System.out.println("OK: o servidor rejeitou o arquivo com SHA-256 incorreto.");
        } finally {
            liberarTransferencia.countDown();
            tarefas.shutdownNow();
        }
    }

    private static void escreverCabecalho(DataOutputStream saida, String nome, long tamanho) throws Exception {
        byte[] bytes = Protocolo.nomeValido(nome);
        saida.writeInt(Protocolo.TCP_MAGIC);
        saida.writeShort(bytes.length);
        saida.write(bytes);
        saida.writeLong(tamanho);
    }

    private static String lerAck(DataInputStream entrada, boolean esperado) throws Exception {
        exigir(entrada.readInt() == Protocolo.TCP_MAGIC, "Identificador de ACK incorreto.");
        exigir(entrada.readBoolean() == esperado, "Status de ACK inesperado.");
        return entrada.readUTF();
    }

    private static void exigir(boolean condicao, String erro) {
        if (!condicao) throw new AssertionError(erro);
    }
}
