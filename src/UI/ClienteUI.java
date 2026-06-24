/*
 * ClienteUI.java — Janela do cliente (apenas UI).
 *
 * Exibe a lista de contatos, a conversa selecionada e os controles.
 * Toda chamada RMI é delegada a ClienteLogica e executada em thread própria.
 * Toda atualização de componentes vem via SwingUtilities.invokeLater().
 */
import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

public class ClienteUI extends JFrame {

    private static final long             serialVersionUID = 1L;
    private static final SimpleDateFormat HORA = new SimpleDateFormat("HH:mm");

    private final ClienteLogica modelo;

    private final Map<String, StringBuilder> historicos     = new LinkedHashMap<>();
    private final Map<String, Boolean>       statusContatos = new HashMap<>();

    private DefaultListModel<String> modeloLista;
    private JList<String>            listaContatos;
    private JLabel                   lblMeuStatus;
    private JButton                  btnStatus;
    private JLabel                   lblTitulo;
    private JTextArea                areaConversa;
    private JTextField               campoMensagem;

    public ClienteUI(ClienteLogica modelo) {
        super("Chat — " + modelo.getNome());
        this.modelo = modelo;
        construirUI();
        registrarCallbacks();
        carregarContatos();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                new Thread(modelo::desconectar).start();
                dispose();
            }
        });
    }

    // ── Callbacks do modelo → atualização de UI ───────────────────────────────

    private void registrarCallbacks() {
        // Mensagem recebida (chega em thread RMI → invokeLater)
        modelo.onMensagem(evt -> SwingUtilities.invokeLater(() -> {
            garantirContato(evt.remetente);
            String hora  = HORA.format(new Date(evt.timestamp));
            String extra = evt.daFila ? " [offline→fila]" : "";
            anexar(evt.remetente, "[" + hora + "]" + extra + " " + evt.remetente + ": " + evt.texto);
        }));

        // Status de contato atualizado (chega em thread RMI → invokeLater)
        modelo.onStatus((contato, online) -> SwingUtilities.invokeLater(() -> {
            statusContatos.put(contato, online);
            listaContatos.repaint();
            if (contato.equals(contatoAtual())) atualizarTitulo(contato);
        }));
    }

    // ── Ações da UI (chamadas RMI em thread própria) ──────────────────────────

    /** Req. 2: alterna online/offline. */
    private void aoAlternarStatus() {
        btnStatus.setEnabled(false);
        boolean novoEstado = !modelo.isOnline();
        new Thread(() -> {
            try {
                modelo.mudarStatus(novoEstado);
                SwingUtilities.invokeLater(() -> {
                    btnStatus.setEnabled(true);
                    if (modelo.isOnline()) {
                        lblMeuStatus.setText("● online");
                        lblMeuStatus.setForeground(new Color(0, 130, 0));
                        btnStatus.setText("Ir Offline");
                    } else {
                        lblMeuStatus.setText("● offline");
                        lblMeuStatus.setForeground(new Color(160, 30, 30));
                        btnStatus.setText("Voltar Online");
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    btnStatus.setEnabled(true);
                    JOptionPane.showMessageDialog(this, "Erro: " + ex.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    /** Req. 3 / 6: envia mensagem. */
    private void aoEnviar() {
        String dest  = contatoAtual();
        String texto = campoMensagem.getText().trim();
        if (dest == null || texto.isEmpty()) return;
        campoMensagem.setText("");
        anexar(dest, "[" + HORA.format(new Date()) + "] Você: " + texto);
        new Thread(() -> {
            try {
                boolean entregue = modelo.enviar(dest, texto);
                if (!entregue)
                    SwingUtilities.invokeLater(() ->
                        anexar(dest, "  ('" + dest + "' estava offline — msg guardada na fila)"));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                    anexar(dest, "  (falha ao enviar: " + ex.getMessage() + ")"));
            }
        }).start();
    }

    /** Req. 8: adiciona contato. */
    private void aoAdicionarContato() {
        String novo = JOptionPane.showInputDialog(this, "Nome do contato:", "Adicionar", JOptionPane.PLAIN_MESSAGE);
        if (novo == null || novo.trim().isEmpty()) return;
        novo = novo.trim().toLowerCase();
        if (novo.equals(modelo.getNome()) || modeloLista.contains(novo)) return;
        final String nome = novo;
        modeloLista.addElement(nome);
        historicos.putIfAbsent(nome, new StringBuilder());
        salvarContatos();
        new Thread(() -> {
            boolean onl = modelo.estaOnline(nome);
            SwingUtilities.invokeLater(() -> { statusContatos.put(nome, onl); listaContatos.repaint(); });
        }).start();
    }

    /** Req. 8: remove contato selecionado. */
    private void aoRemoverContato() {
        String sel = contatoAtual();
        if (sel == null) return;
        int ok = JOptionPane.showConfirmDialog(this, "Remover '" + sel + "'?", "Confirmar", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;
        modeloLista.removeElement(sel);
        historicos.remove(sel);
        statusContatos.remove(sel);
        salvarContatos();
        lblTitulo.setText("Selecione um contato");
        areaConversa.setText("");
    }

    // ── Auxiliares ────────────────────────────────────────────────────────────

    private void carregarContatos() {
        for (String c : modelo.carregarContatos()) {
            modeloLista.addElement(c);
            historicos.putIfAbsent(c, new StringBuilder());
        }
        new Thread(() -> {
            List<String> copia = new ArrayList<>();
            for (int i = 0; i < modeloLista.size(); i++) copia.add(modeloLista.get(i));
            for (String c : copia) {
                boolean onl = modelo.estaOnline(c);
                SwingUtilities.invokeLater(() -> { statusContatos.put(c, onl); listaContatos.repaint(); });
            }
        }).start();
    }

    private void salvarContatos() {
        List<String> lista = new ArrayList<>();
        for (int i = 0; i < modeloLista.size(); i++) lista.add(modeloLista.get(i));
        modelo.salvarContatos(lista);
    }

    private void garantirContato(String nome) {
        if (!modeloLista.contains(nome)) {
            modeloLista.addElement(nome);
            historicos.putIfAbsent(nome, new StringBuilder());
            salvarContatos();
        }
    }

    private void selecionarContato(String nome) {
        if (nome == null) { lblTitulo.setText("Selecione um contato"); areaConversa.setText(""); return; }
        atualizarTitulo(nome);
        StringBuilder h = historicos.get(nome);
        areaConversa.setText(h != null ? h.toString() : "");
        areaConversa.setCaretPosition(areaConversa.getDocument().getLength());
        campoMensagem.requestFocusInWindow();
    }

    private void atualizarTitulo(String nome) {
        Boolean onl = statusContatos.get(nome);
        lblTitulo.setText(nome + (Boolean.TRUE.equals(onl) ? "  ● online" : "  ○ offline"));
    }

    private void anexar(String contato, String linha) {
        historicos.computeIfAbsent(contato, k -> new StringBuilder()).append(linha).append('\n');
        if (contato.equals(contatoAtual())) {
            areaConversa.append(linha + "\n");
            areaConversa.setCaretPosition(areaConversa.getDocument().getLength());
        }
    }

    private String contatoAtual() { return listaContatos.getSelectedValue(); }

    // ── Construção da UI ──────────────────────────────────────────────────────

    private void construirUI() {
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(760, 520);
        setMinimumSize(new Dimension(600, 400));
        setLocationRelativeTo(null);

        // ── Painel esquerdo ───────────────────────────────────────────────────
        lblMeuStatus = new JLabel("● online");
        lblMeuStatus.setForeground(new Color(0, 130, 0));
        lblMeuStatus.setFont(lblMeuStatus.getFont().deriveFont(Font.BOLD, 12f));

        btnStatus = new JButton("Ir Offline");
        btnStatus.addActionListener(e -> aoAlternarStatus());

        JPanel painelStatus = new JPanel(new BorderLayout(0, 4));
        painelStatus.setBorder(BorderFactory.createTitledBorder(modelo.getNome()));
        painelStatus.add(lblMeuStatus, BorderLayout.CENTER);
        painelStatus.add(btnStatus,    BorderLayout.SOUTH);

        modeloLista   = new DefaultListModel<>();
        listaContatos = new JList<>(modeloLista);
        listaContatos.setCellRenderer(new RendererContato());
        listaContatos.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        listaContatos.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) selecionarContato(listaContatos.getSelectedValue());
        });

        JScrollPane scrollLista = new JScrollPane(listaContatos);
        scrollLista.setBorder(BorderFactory.createTitledBorder("Contatos"));

        JButton btnAdd = new JButton("+ Adicionar");
        JButton btnRem = new JButton("Remover");
        btnAdd.addActionListener(e -> aoAdicionarContato());
        btnRem.addActionListener(e -> aoRemoverContato());

        JPanel painelBotoes = new JPanel(new GridLayout(1, 2, 4, 0));
        painelBotoes.setBorder(new EmptyBorder(4, 0, 0, 0));
        painelBotoes.add(btnAdd);
        painelBotoes.add(btnRem);

        JPanel painelEsq = new JPanel(new BorderLayout(0, 6));
        painelEsq.setPreferredSize(new Dimension(190, 0));
        painelEsq.setBorder(new EmptyBorder(8, 8, 8, 4));
        painelEsq.add(painelStatus,  BorderLayout.NORTH);
        painelEsq.add(scrollLista,   BorderLayout.CENTER);
        painelEsq.add(painelBotoes,  BorderLayout.SOUTH);

        // ── Painel direito ────────────────────────────────────────────────────
        lblTitulo = new JLabel("Selecione um contato");
        lblTitulo.setFont(lblTitulo.getFont().deriveFont(Font.BOLD, 13f));
        lblTitulo.setBorder(new EmptyBorder(0, 2, 4, 0));

        areaConversa = new JTextArea();
        areaConversa.setEditable(false);
        areaConversa.setLineWrap(true);
        areaConversa.setWrapStyleWord(true);
        areaConversa.setFont(new Font("Monospaced", Font.PLAIN, 12));

        campoMensagem = new JTextField();
        JButton btnEnviar = new JButton("Enviar");
        campoMensagem.addActionListener(e -> aoEnviar());
        btnEnviar.addActionListener(e -> aoEnviar());

        JPanel painelEnvio = new JPanel(new BorderLayout(6, 0));
        painelEnvio.setBorder(new EmptyBorder(6, 0, 0, 0));
        painelEnvio.add(campoMensagem, BorderLayout.CENTER);
        painelEnvio.add(btnEnviar,     BorderLayout.EAST);

        JPanel painelDir = new JPanel(new BorderLayout(0, 0));
        painelDir.setBorder(new EmptyBorder(8, 4, 8, 8));
        painelDir.add(lblTitulo,             BorderLayout.NORTH);
        painelDir.add(new JScrollPane(areaConversa), BorderLayout.CENTER);
        painelDir.add(painelEnvio,           BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, painelEsq, painelDir);
        split.setDividerLocation(195);
        split.setDividerSize(4);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
    }

    // ── Renderer da lista (Req. 1: estado sempre visível) ────────────────────

    private class RendererContato extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            String  nome   = String.valueOf(value);
            boolean online = Boolean.TRUE.equals(statusContatos.get(nome));
            setText((online ? "● " : "○ ") + nome);
            if (!selected) setForeground(online ? new Color(0, 110, 0) : Color.GRAY);
            setBorder(new EmptyBorder(4, 8, 4, 8));
            return this;
        }
    }
}
