/*
 * ServidorUI.java — Janela do servidor (apenas UI).
 *
 * Cria um ServidorLogica, registra os callbacks de log/sucesso/falha
 * e chama logica.iniciar(). Não contém nenhuma lógica de rede.
 */
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ServidorUI extends JFrame {

    private static final long             serialVersionUID = 1L;
    private static final SimpleDateFormat HORA = new SimpleDateFormat("HH:mm:ss");

    private final ServidorLogica logica = new ServidorLogica();

    private JLabel    lblStatus;
    private JTextArea areaLog;

    public ServidorUI() {
        super("Servidor de Mensagens");
        construirUI();
        registrarCallbacks();
        logica.iniciar();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                logica.parar();
                dispose();
            }
        });
    }

    private void registrarCallbacks() {
        // Cada linha de log chega aqui (pode vir de thread de fundo → invokeLater)
        logica.onLog(msg -> SwingUtilities.invokeLater(() -> {
            areaLog.append("[" + HORA.format(new Date()) + "] " + msg + "\n");
            areaLog.setCaretPosition(areaLog.getDocument().getLength());
        }));

        // Tudo subiu com sucesso
        logica.onSucesso(() -> SwingUtilities.invokeLater(() -> {
            lblStatus.setText("● em execução — porta " + Config.RMI_PORTA);
            lblStatus.setForeground(new Color(0, 130, 0));
        }));

        // Falha na inicialização
        logica.onFalha(erro -> SwingUtilities.invokeLater(() -> {
            lblStatus.setText("● falha ao iniciar");
            lblStatus.setForeground(Color.RED);
            JOptionPane.showMessageDialog(this,
                "Erro ao iniciar o servidor:\n" + erro,
                "Erro", JOptionPane.ERROR_MESSAGE);
        }));
    }

    private void construirUI() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(580, 420);
        setLocationRelativeTo(null);

        JLabel titulo = new JLabel("Servidor de Mensagens Offline", SwingConstants.CENTER);
        titulo.setFont(titulo.getFont().deriveFont(Font.BOLD, 14f));
        titulo.setBorder(BorderFactory.createEmptyBorder(12, 8, 2, 8));

        lblStatus = new JLabel("Iniciando...", SwingConstants.CENTER);
        lblStatus.setFont(lblStatus.getFont().deriveFont(12f));
        lblStatus.setForeground(new Color(180, 120, 0));
        lblStatus.setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));

        areaLog = new JTextArea();
        areaLog.setEditable(false);
        areaLog.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane scroll = new JScrollPane(areaLog);
        scroll.setBorder(BorderFactory.createTitledBorder("Log de eventos"));

        JPanel painelLog = new JPanel(new BorderLayout());
        painelLog.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        painelLog.add(scroll, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(titulo,    BorderLayout.NORTH);
        add(lblStatus, BorderLayout.CENTER);

        // Reorganiza: titulo + status no topo, log no centro
        JPanel top = new JPanel(new BorderLayout());
        top.add(titulo,    BorderLayout.NORTH);
        top.add(lblStatus, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(top,      BorderLayout.NORTH);
        add(painelLog, BorderLayout.CENTER);
    }
}
