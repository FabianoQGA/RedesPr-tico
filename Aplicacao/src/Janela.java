import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.Border;

final class Janela extends JFrame {
    private static final Color MARINHO = new Color(0x102639);
    private static final Color PETROLEO = new Color(0x075B55);
    private static final Color AZUL = new Color(0x2452A1);
    private static final Color FUNDO = new Color(0xEAF0F4);
    private static final Color BORDA = new Color(0xCCD8DF);
    private static final Color CINZA = new Color(0x4B6274);
    private static final Color NEVE = new Color(0xEEF7F9);
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final JTextField campoDestino = new JTextField("127.0.0.1", 16);
    private final JSpinner campoPorta = new JSpinner(new SpinnerNumberModel(5000, 1, 65535, 1));
    private final JSpinner campoSondas = new JSpinner(new SpinnerNumberModel(12, 1, 200, 1));
    private final JLabel nomeArquivo = rotulo("Nenhum arquivo selecionado", 13, false, CINZA);
    private final JLabel estadoTcp = rotulo("Aguardando arquivo", 13, true, PETROLEO);
    private final JLabel estadoUdp = rotulo("Aguardando início", 13, true, AZUL);
    private final JButton escolher = botao("Selecionar arquivo", false, PETROLEO);
    private final JButton enviar = botao("Enviar via TCP", true, PETROLEO);
    private final JButton sondar = botao("Iniciar sondas", true, AZUL);
    private final JButton paralelo = botao("Executar em paralelo", true, MARINHO);
    private final JTextArea registros = new JTextArea(9, 70);
    private boolean tcpAtivo;
    private boolean udpAtivo;
    private Path arquivo;

    static void abrir() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignorado) {
            // O estilo padrão do Swing continua disponível.
        }
        new Janela().setVisible(true);
    }

    private Janela() {
        super("RedeLab | Cliente de redes");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(875, 660));

        JPanel base = new JPanel(new BorderLayout());
        base.setBackground(FUNDO);
        base.add(cabecalho(), BorderLayout.NORTH);

        JPanel corpo = pilha(FUNDO);
        corpo.setBorder(margem(18, 22));
        corpo.add(destino());
        corpo.add(Box.createVerticalStrut(14));
        JPanel protocolos = new JPanel(new GridLayout(1, 2, 14, 0));
        protocolos.setOpaque(false);
        protocolos.add(cartaoTcp());
        protocolos.add(cartaoUdp());
        protocolos.setMaximumSize(new Dimension(Integer.MAX_VALUE, protocolos.getPreferredSize().height));
        corpo.add(protocolos);
        corpo.add(Box.createVerticalStrut(14));
        corpo.add(registro());
        base.add(corpo, BorderLayout.CENTER);

        escolher.addActionListener(e -> escolherArquivo());
        enviar.addActionListener(e -> executar(true, false));
        sondar.addActionListener(e -> executar(false, true));
        paralelo.addActionListener(e -> executar(true, true));
        atualizarBotoes();
        setContentPane(base);
        pack();
        setSize(Math.max(960, getWidth()), Math.max(700, getHeight()));
        setLocationRelativeTo(null);

        registrar("Cliente pronto. Confirme o destino e inicie o servidor antes do teste.");
    }

    private JPanel cabecalho() {
        JPanel cabecalho = pilha(MARINHO);
        cabecalho.setBorder(margem(17, 23));
        cabecalho.add(rotulo("RedeLab", 28, true, Color.WHITE));
        cabecalho.add(Box.createVerticalStrut(4));
        cabecalho.add(rotulo("Transferência confiável por TCP e medição de latência por UDP", 14, false, NEVE));
        return cabecalho;
    }

    private JPanel destino() {
        JPanel painel = cartao();
        var linha = new JPanel(new BorderLayout(14, 0));
        linha.setOpaque(false);
        var campos = faixa(11);
        JLabel destino = rotulo("Destino IPv4", 14, true, MARINHO);
        destino.setLabelFor(campoDestino);
        JLabel porta = rotulo("Porta", 14, true, MARINHO);
        porta.setLabelFor(campoPorta);
        campoDestino.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        campoPorta.setEditor(new JSpinner.NumberEditor(campoPorta, "0"));
        campoPorta.setPreferredSize(new Dimension(74, 28));
        campos.add(destino);
        campos.add(campoDestino);
        campos.add(porta);
        campos.add(campoPorta);
        linha.add(campos, BorderLayout.CENTER);
        linha.add(paralelo, BorderLayout.EAST);
        painel.add(linha);
        painel.setMaximumSize(new Dimension(Integer.MAX_VALUE, painel.getPreferredSize().height));
        return painel;
    }

    private JPanel cartaoTcp() {
        JPanel painel = cartao();
        painel.add(rotulo("TCP  /  Transferência de arquivo", 18, true, PETROLEO));
        painel.add(Box.createVerticalStrut(7));
        painel.add(rotulo("O servidor verifica o SHA-256 antes de enviar o ACK.", 13, false, CINZA));
        painel.add(Box.createVerticalStrut(14));
        painel.add(nomeArquivo);
        painel.add(Box.createVerticalStrut(10));
        JPanel acoes = faixa(6);
        acoes.add(escolher);
        acoes.add(enviar);
        painel.add(acoes);
        painel.add(Box.createVerticalStrut(12));
        painel.add(estadoTcp);
        return painel;
    }

    private JPanel cartaoUdp() {
        JPanel painel = cartao();
        painel.add(rotulo("UDP  /  Sondas de latência", 18, true, AZUL));
        painel.add(Box.createVerticalStrut(7));
        painel.add(rotulo("Mede o RTT das respostas e conta as sondas sem resposta.", 13, false, CINZA));
        painel.add(Box.createVerticalStrut(9));
        JPanel contagem = faixa(6);
        JLabel quantidade = rotulo("Sondas", 13, true, MARINHO);
        quantidade.setLabelFor(campoSondas);
        campoSondas.setPreferredSize(new Dimension(60, 26));
        contagem.add(quantidade);
        contagem.add(campoSondas);
        painel.add(contagem);
        painel.add(Box.createVerticalStrut(7));
        JPanel acoes = faixa(0);
        acoes.add(sondar);
        painel.add(acoes);
        painel.add(Box.createVerticalStrut(12));
        painel.add(estadoUdp);
        return painel;
    }

    private JPanel registro() {
        JPanel painel = new JPanel(new BorderLayout(0, 9));
        painel.setBackground(Color.WHITE);
        painel.setBorder(bordaCartao());
        JPanel topo = new JPanel(new BorderLayout());
        topo.setOpaque(false);
        topo.add(rotulo("Registro de atividades", 17, true, MARINHO), BorderLayout.WEST);
        JButton limpar = botao("Limpar", false, MARINHO);
        limpar.addActionListener(e -> registros.setText(""));
        topo.add(limpar, BorderLayout.EAST);
        painel.add(topo, BorderLayout.NORTH);
        registros.setEditable(false);
        registros.setLineWrap(true);
        registros.setWrapStyleWord(true);
        registros.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        registros.setForeground(NEVE);
        registros.setBackground(MARINHO);
        registros.setMargin(margem(10, 12).getBorderInsets(registros));
        registros.getAccessibleContext().setAccessibleName("Registro de atividades TCP e UDP");
        JScrollPane rolagem = new JScrollPane(registros);
        rolagem.setBorder(BorderFactory.createEmptyBorder());
        painel.add(rolagem, BorderLayout.CENTER);
        return painel;
    }

    private void escolherArquivo() {
        JFileChooser seletor = new JFileChooser();
        seletor.setDialogTitle("Selecione o arquivo a enviar");
        if (arquivo != null) seletor.setSelectedFile(arquivo.toFile());
        if (seletor.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            arquivo = seletor.getSelectedFile().toPath();
            String nome = arquivo.getFileName().toString();
            nomeArquivo.setText(nome.length() > 44 ? nome.substring(0, 41) + "..." : nome);
            nomeArquivo.setToolTipText(arquivo.toString());
            estadoTcp.setText("Pronto para enviar");
            atualizarBotoes();
        }
    }

    private void executar(boolean tcp, boolean udp) {
        try {
            campoPorta.commitEdit();
            if (udp) campoSondas.commitEdit();
            String host = campoDestino.getText().strip();
            if (host.isEmpty()) throw new IllegalArgumentException("Informe um destino IPv4.");
            int porta = (Integer) campoPorta.getValue();
            int quantidade = (Integer) campoSondas.getValue();
            registrar((tcp && udp ? "TCP + UDP" : tcp ? "TCP" : "UDP") + " → " + host + ":" + porta);
            if (tcp) iniciarTcp(host, porta, arquivo);
            if (udp) iniciarUdp(host, porta, quantidade);
        } catch (Exception erro) {
            JOptionPane.showMessageDialog(this, "Verifique o endereço, a porta e a quantidade de sondas.",
                    "Dados do teste", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void iniciarTcp(String host, int porta, Path caminho) {
        tcpAtivo = true;
        estadoTcp.setText("Enviando arquivo...");
        atualizarBotoes();
        new SwingWorker<Cliente.Transferencia, Void>() {
            @Override
            protected Cliente.Transferencia doInBackground() throws Exception {
                return Cliente.enviar(Protocolo.ipv4(host, porta), caminho);
            }

            @Override
            protected void done() {
                try {
                    Cliente.Transferencia r = get();
                    estadoTcp.setText("Confirmado pelo servidor");
                    registrar("TCP " + RedeLab.textoTcp(r));
                } catch (InterruptedException | ExecutionException erro) {
                    estadoTcp.setText("Transferência não confirmada");
                    registrar("TCP: " + mensagem(erro));
                } finally {
                    tcpAtivo = false;
                    atualizarBotoes();
                }
            }
        }.execute();
    }

    private void iniciarUdp(String host, int porta, int quantidade) {
        udpAtivo = true;
        estadoUdp.setText("Medindo latência...");
        atualizarBotoes();
        new SwingWorker<Cliente.Resumo, Cliente.Medicao>() {
            @Override
            protected Cliente.Resumo doInBackground() throws Exception {
                return Cliente.sondar(Protocolo.ipv4(host, porta), quantidade, 200, 700,
                        sonda -> publish(sonda));
            }

            @Override
            protected void process(List<Cliente.Medicao> medicoes) {
                for (Cliente.Medicao sonda : medicoes) registrar("UDP " + RedeLab.textoSonda(sonda));
            }

            @Override
            protected void done() {
                try {
                    Cliente.Resumo r = get();
                    estadoUdp.setText(r.recebidas() + " de " + r.enviadas() + " respostas");
                    registrar("UDP " + RedeLab.textoResumo(r));
                } catch (InterruptedException | ExecutionException erro) {
                    estadoUdp.setText("Falha nas sondas");
                    registrar("UDP: " + mensagem(erro));
                } finally {
                    udpAtivo = false;
                    atualizarBotoes();
                }
            }
        }.execute();
    }

    private static String mensagem(Exception erro) {
        Throwable causa = erro.getCause() == null ? erro : erro.getCause();
        return causa.getMessage() == null ? causa.getClass().getSimpleName() : causa.getMessage();
    }

    private void registrar(String mensagem) {
        for (String linha : mensagem.split("\\R")) {
            registros.append(HORA.format(LocalTime.now()) + "  " + linha + System.lineSeparator());
        }
        registros.setCaretPosition(registros.getDocument().getLength());
    }

    private void atualizarBotoes() {
        escolher.setEnabled(!tcpAtivo);
        enviar.setEnabled(arquivo != null && !tcpAtivo);
        sondar.setEnabled(!udpAtivo);
        campoSondas.setEnabled(!udpAtivo);
        paralelo.setEnabled(arquivo != null && !tcpAtivo && !udpAtivo);
        campoDestino.setEnabled(!tcpAtivo && !udpAtivo);
        campoPorta.setEnabled(!tcpAtivo && !udpAtivo);
    }

    private static JPanel faixa(int intervalo) {
        JPanel painel = new JPanel(new FlowLayout(FlowLayout.LEFT, intervalo, 0));
        painel.setAlignmentX(LEFT_ALIGNMENT);
        painel.setOpaque(false);
        return painel;
    }

    private static JPanel pilha(Color cor) {
        JPanel painel = new JPanel();
        painel.setLayout(new BoxLayout(painel, BoxLayout.Y_AXIS));
        painel.setBackground(cor);
        return painel;
    }

    private static JPanel cartao() {
        JPanel painel = pilha(Color.WHITE);
        painel.setBorder(bordaCartao());
        return painel;
    }

    private static Border bordaCartao() {
        return BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDA), margem(12, 15));
    }

    private static Border margem(int vertical, int horizontal) {
        return BorderFactory.createEmptyBorder(vertical, horizontal, vertical, horizontal);
    }

    private static JLabel rotulo(String texto, int pontos, boolean negrito, Color cor) {
        JLabel rotulo = new JLabel(texto);
        rotulo.setFont(new Font("Segoe UI", negrito ? Font.BOLD : Font.PLAIN, pontos));
        rotulo.setForeground(cor);
        return rotulo;
    }

    private static JButton botao(String texto, boolean preenchido, Color cor) {
        JButton botao = new JButton(texto);
        botao.setFont(new Font("Segoe UI", Font.BOLD, 13));
        botao.setBackground(preenchido ? cor : Color.WHITE);
        botao.setForeground(preenchido ? Color.WHITE : cor);
        botao.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(preenchido ? cor : BORDA), margem(7, 10)));
        botao.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return botao;
    }
}
