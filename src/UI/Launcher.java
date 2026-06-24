/*
 * Launcher.java — Janela inicial (apenas UI).
 *
 * Duas ações:
 *  1. Abre ServidorUI (que cria e inicia ServidorLogica)
 *  2. Abre ClienteUI  (que cria ClienteLogica, conecta via RMI e exibe a janela)
 */
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class Launcher extends JFrame {

    private static final long serialVersionUID = 1L;

    public Launcher() {
        super("Sistema de Mensagens Offline");
        construirUI();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { System.exit(0); }
        });
    }

    private void construirUI() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(400, 240);
        setResizable(false);
        setLocationRelativeTo(null);

        JLabel titulo = new JLabel("Sistema de Mensagens Offline", SwingConstants.CENTER);
        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 15f));
        titulo.setBorder(BorderFactory.createEmptyBorder(20, 10, 4, 10));

        JLabel sub = new JLabel("RMI  +  ActiveMQ (MOM / JMS)", SwingConstants.CENTER);
        sub.setFont(sub.getFont().deriveFont(Font.ITALIC, 11f));
        sub.setForeground(Color.GRAY);
        sub.setBorder(BorderFactory.createEmptyBorder(0, 10, 16, 10));

        JButton btnServidor = new JButton("Iniciar Servidor de Mensagens");
        JButton btnCliente  = new JButton("+ Novo Cliente");
        btnServidor.setPreferredSize(new Dimension(220, 36));
        btnCliente.setPreferredSize(new Dimension(140, 36));

        btnServidor.addActionListener(e -> aoAbrirServidor());
        btnCliente.addActionListener(e  -> aoAbrirCliente());

        JPanel botoes = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 8));
        botoes.add(btnServidor);
        botoes.add(btnCliente);

        JLabel rodape = new JLabel("Fechar esta janela encerra o programa.", SwingConstants.CENTER);
        rodape.setFont(rodape.getFont().deriveFont(Font.ITALIC, 10f));
        rodape.setForeground(Color.GRAY);
        rodape.setBorder(BorderFactory.createEmptyBorder(4, 10, 12, 10));

        setLayout(new BorderLayout());
        JPanel top = new JPanel(new BorderLayout());
        top.add(titulo, BorderLayout.NORTH);
        top.add(sub,    BorderLayout.SOUTH);
        add(top,    BorderLayout.NORTH);
        add(botoes, BorderLayout.CENTER);
        add(rodape, BorderLayout.SOUTH);
    }

    private void aoAbrirServidor() {
        ServidorUI janela = new ServidorUI();
        janela.setVisible(true);
        janela.toFront();
    }

    private void aoAbrirCliente() {
        JTextField campoNome = new JTextField(14);
        JTextField campoHost = new JTextField("localhost", 14);

        JPanel form = new JPanel(new GridLayout(2, 2, 8, 6));
        form.add(new JLabel("Nome de contato:"));  form.add(campoNome);
        form.add(new JLabel("Host do servidor:")); form.add(campoHost);

        int op = JOptionPane.showConfirmDialog(this, form,
            "Entrar no chat", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (op != JOptionPane.OK_OPTION) return;

        String nome = campoNome.getText().trim();
        String host = campoHost.getText().trim();
        if (nome.isEmpty()) return;
        if (host.isEmpty()) host = "localhost";

        final String nFinal = nome;
        final String hFinal = host.isEmpty() ? "localhost" : host;

        // Localizar o Registry e conectar FORA da EDT (RMI bloqueia)
        new Thread(() -> {
            try {
                ClienteLogica logica = ClienteLogica.criar(nFinal, hFinal);
                String erro = logica.conectar();
                if (erro != null) {
                    final String e = erro;
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                        "Falha ao conectar:\n" + e, "Erro", JOptionPane.ERROR_MESSAGE));
                    return;
                }
                // Criar e exibir a janela na EDT
                SwingUtilities.invokeLater(() -> {
                    ClienteUI janela = new ClienteUI(logica);
                    janela.setVisible(true);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                    "Servidor não encontrado em '" + hFinal + "'.\n"
                    + "Certifique-se de iniciar o servidor primeiro.\n\n"
                    + "Detalhe: " + ex.getMessage(),
                    "Erro de conexão", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Launcher().setVisible(true));
    }
}
