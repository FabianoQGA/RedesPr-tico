import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;

public final class RedeLab {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private RedeLab() {}

    public static void main(String[] args) {
        if (args.length == 0 || args.length == 1 && args[0].equals("cliente")) {
            SwingUtilities.invokeLater(Janela::abrir);
            return;
        }

        try {
            switch (args[0]) {
                case "servidor" -> servidor(args);
                case "enviar" -> {
                    if (args.length != 4) throw new IllegalArgumentException("Forneça destino, porta e arquivo.");
                    enviar(endereco(args), Path.of(args[3]));
                }
                case "sondar" -> {
                    if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("Forneça destino, porta e quantidade opcional.");
                    sondar(endereco(args), inteiro(args, 3, 12));
                }
                case "demo" -> demo(args);
                case "ajuda", "--help", "-h" -> ajuda();
                default -> throw new IllegalArgumentException("Modo desconhecido. Digite ajuda.");
            }
        } catch (Exception erro) {
            Throwable causa = erro instanceof ExecutionException && erro.getCause() != null ? erro.getCause() : erro;
            log("ERRO " + causa.getClass().getSimpleName() + ": " + causa.getMessage());
            System.exit(1);
        }
    }

    private static void servidor(String[] args) throws Exception {
        if (args.length > 5) throw new IllegalArgumentException("Argumentos demais para o servidor.");
        String bind = args.length > 1 ? args[1] : "0.0.0.0";
        int porta = inteiro(args, 2, Protocolo.PORTA_PADRAO);
        Path arquivos = Path.of(args.length > 3 ? args[3] : "recebidos");
        int descarte = inteiro(args, 4, 0);
        try (var servidor = new Servidor(Protocolo.ipv4(bind, porta), arquivos, descarte, RedeLab::log)) {
            Runtime.getRuntime().addShutdownHook(new Thread(servidor::close, "finalizar-servidor"));
            servidor.aguardar();
        }
    }

    private static void demo(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6) throw new IllegalArgumentException("Forneça destino, porta, arquivo e as quantidades opcionais.");
        InetSocketAddress destino = endereco(args);
        Path arquivo = Path.of(args[3]);
        int repeticoes = inteiro(args, 4, 4);
        int sondas = inteiro(args, 5, 20);
        if (repeticoes < 1 || repeticoes > 100) throw new IllegalArgumentException("Use de 1 a 100 envios TCP.");
        if (sondas < 1 || sondas > 200) throw new IllegalArgumentException("Use de 1 a 200 sondas UDP.");
        log("Demo: " + repeticoes + " envios TCP e " + sondas + " sondas UDP em tarefas paralelas.");
        var tarefas = Executors.newFixedThreadPool(2);
        try {
            var tcp = tarefas.submit(() -> {
                for (int i = 1; i <= repeticoes; i++) {
                    log("TCP envio " + i + "/" + repeticoes);
                    enviar(destino, arquivo);
                    if (i < repeticoes) Thread.sleep(250);
                }
                return null;
            });
            var udp = tarefas.submit(() -> {
                sondar(destino, sondas);
                return null;
            });
            tcp.get();
            udp.get();
            log("Demo concluída.");
        } finally {
            tarefas.shutdownNow();
        }
    }

    private static void enviar(InetSocketAddress destino, Path arquivo) throws Exception {
        Cliente.Transferencia r = Cliente.enviar(destino, arquivo);
        log("TCP " + textoTcp(r));
    }

    private static void sondar(InetSocketAddress destino, int quantidade) throws Exception {
        Cliente.Resumo r = Cliente.sondar(destino, quantidade, 180, 700,
                sonda -> log("UDP " + textoSonda(sonda)));
        log("UDP " + textoResumo(r));
    }

    static String textoTcp(Cliente.Transferencia r) {
        return String.format(PT_BR, "ACK recebido | %s | %d bytes | %.2f ms%nSHA-256 %s",
                r.nome(), r.bytes(), r.tempoMs(), r.sha256());
    }

    static String textoSonda(Cliente.Medicao sonda) {
        return sonda.respondida()
                ? String.format(PT_BR, "#%02d | RTT %.2f ms", sonda.sequencia(), sonda.tempoMs())
                : String.format(PT_BR, "#%02d | sem resposta", sonda.sequencia());
    }

    static String textoResumo(Cliente.Resumo r) {
        String totais = r.recebidas() + "/" + r.enviadas() + " respostas, " + r.semResposta() + " sem resposta";
        return r.recebidas() == 0 ? totais
                : totais + String.format(PT_BR, " | RTT mín. %.2f, médio %.2f, máx. %.2f ms",
                        r.menorMs(), r.mediaMs(), r.maiorMs());
    }

    private static InetSocketAddress endereco(String[] args) throws Exception {
        return Protocolo.ipv4(args[1], inteiro(args, 2, Protocolo.PORTA_PADRAO));
    }

    private static int inteiro(String[] args, int posicao, int padrao) {
        if (args.length <= posicao) return padrao;
        try {
            return Integer.parseInt(args[posicao]);
        } catch (NumberFormatException erro) {
            throw new IllegalArgumentException("Esperado número inteiro: " + args[posicao]);
        }
    }

    private static void log(String mensagem) {
        System.out.println(HORA.format(LocalTime.now()) + " " + mensagem);
    }

    private static void ajuda() {
        System.out.println("""
                RedeLab · Transferência TCP e sondas UDP

                  java -jar RedeLab.jar cliente
                  java -jar RedeLab.jar servidor [bind] [porta] [pasta] [descartarCada]
                  java -jar RedeLab.jar enviar <destino> <porta> <arquivo>
                  java -jar RedeLab.jar sondar <destino> <porta> [sondas]
                  java -jar RedeLab.jar demo <destino> <porta> <arquivo> [enviosTcp] [sondasUdp]

                Servidor: padrão IPv4 0.0.0.0, porta 5000, pasta recebidos e sem descarte.
                descartarCada=4 descarta cada quarta sonda UDP para demonstrar perda.
                """);
    }
}
