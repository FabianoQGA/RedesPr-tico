import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Formatos de quadro compartilhados entre o cliente e o servidor. */
final class Protocolo {
    static final int TCP_MAGIC = 0x524C5431; // RLT1, versão 1
    static final int UDP_MAGIC = 0x524C5531; // RLU1, versão 1
    static final byte SONDA = 1;
    static final byte RESPOSTA = 2;
    static final int UDP_BYTES = Integer.BYTES + 1 + Long.BYTES + Integer.BYTES;
    static final int SHA256_BYTES = 32;
    static final int PORTA_PADRAO = 5000;
    static final int LIMITE_NOME_BYTES = 255;
    static final int TCP_TIMEOUT_MS = 12_000;
    static final long LIMITE_ARQUIVO = 100L * 1024 * 1024;

    private Protocolo() {}

    static InetSocketAddress ipv4(String host, int porta) throws UnknownHostException {
        String nome = host.strip();
        if (nome.isEmpty()) throw new UnknownHostException("Informe um destino IPv4.");
        validarPorta(porta);
        for (InetAddress endereco : InetAddress.getAllByName(nome)) {
            if (endereco instanceof Inet4Address) return new InetSocketAddress(endereco, porta);
        }
        throw new UnknownHostException("Sem endereço IPv4 para " + nome + ".");
    }

    static void validarPorta(int porta) {
        if (porta < 1 || porta > 65535) {
            throw new IllegalArgumentException("A porta precisa estar entre 1 e 65535.");
        }
    }

    static byte[] nomeValido(String nome) throws IOException {
        byte[] bytes = nome.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > LIMITE_NOME_BYTES
                || nome.endsWith(".") || nome.endsWith(" ")
                || nome.chars().anyMatch(c -> Character.isISOControl(c) || "<>:\"/\\|?*".indexOf(c) >= 0)) {
            throw new IOException("Nome de arquivo inválido para transferência.");
        }
        return bytes;
    }

    static byte[] lerExato(DataInputStream entrada, int quantidade) throws IOException {
        byte[] dados = entrada.readNBytes(quantidade);
        if (dados.length != quantidade) throw new EOFException("Quadro TCP incompleto.");
        return dados;
    }

    static byte[] copiarComHash(InputStream entrada, OutputStream saida, long tamanho) throws IOException {
        MessageDigest digest = sha256();
        byte[] bloco = new byte[16 * 1024];
        for (long restantes = tamanho; restantes > 0;) {
            int lidos = entrada.read(bloco, 0, (int) Math.min(bloco.length, restantes));
            if (lidos < 0) throw new EOFException("Arquivo incompleto.");
            saida.write(bloco, 0, lidos);
            digest.update(bloco, 0, lidos);
            restantes -= lidos;
        }
        return digest.digest();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException erro) {
            throw new IllegalStateException("SHA-256 indisponível.", erro);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    static void responderTcp(DataOutputStream saida, boolean sucesso, String detalhe) throws IOException {
        saida.writeInt(TCP_MAGIC);
        saida.writeBoolean(sucesso);
        saida.writeUTF(detalhe);
        saida.flush();
    }

    static byte[] datagrama(byte tipo, long nonce, int sequencia) {
        return ByteBuffer.allocate(UDP_BYTES)
                .putInt(UDP_MAGIC).put(tipo).putLong(nonce).putInt(sequencia).array();
    }

    static Sonda lerSonda(byte[] bytes, int inicio, int quantidade, byte tipo) {
        if (quantidade != UDP_BYTES) return null;
        ByteBuffer buffer = ByteBuffer.wrap(bytes, inicio, quantidade);
        if (buffer.getInt() != UDP_MAGIC || buffer.get() != tipo) return null;
        long nonce = buffer.getLong();
        int sequencia = buffer.getInt();
        return sequencia > 0 ? new Sonda(nonce, sequencia) : null;
    }

    record Sonda(long nonce, int sequencia) {}
}
