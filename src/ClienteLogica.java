/*
 * ═══════════════════════════════════════════════════════════════
 *  ClienteLogica.java — LÓGICA do cliente  (zero Swing)
 * ═══════════════════════════════════════════════════════════════
 *
 *  Tecnologias PPD usadas aqui:
 *
 *  1. RMI — acesso ao servidor
 *     • criar()       → localiza o Registry no host informado e obtém o stub
 *     • conectar()    → registra o cliente no servidor (Req. 7)
 *     • mudarStatus() → alterna online/offline (Req. 2)
 *     • enviar()      → chama servico.enviar() via stub RMI (Req. 3/6)
 *     • desconectar() → avisa o servidor e desfaz a exportação do callback
 *
 *  2. Callback RMI — CallbackImpl (inner class)
 *     • Exportado como objeto remoto (UnicastRemoteObject)
 *     • O servidor armazena o stub e chama receberMensagem() / atualizarStatus()
 *       diretamente na thread RMI do cliente
 *     • Os métodos apenas disparam os callbacks registrados pela UI
 *
 */
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ClienteLogica {

    // ── Estado ────────────────────────────────────────────────────────────────
    private final String           nome;
    private final ServicoMensagens servico;   // stub RMI para o servidor
    private CallbackImpl           callback;   // objeto exportado (servidor chama de volta)
    private boolean                online = true;

    // ── Callbacks registrados pela UI ─────────────────────────────────────────
    // Acesso sempre dentro de "synchronized (this)" para evitar race condition
    // entre a thread RMI (que chama o callback) e a EDT (que registra o callback).
    private Consumer<MensagemEvento>    onMensagem;
    private BiConsumer<String, Boolean> onStatus;

    // Buffer para mensagens que chegam antes de onMensagem ser registrado.
    // Situação típica: o servidor drena a fila durante conectar(), mas a UI
    // ainda não criou o listener. Ao registrar onMensagem, o buffer é reentregue.
    private final List<MensagemEvento> bufferMsgs = new ArrayList<>();

    // ── Construtor privado: use criar() ───────────────────────────────────────
    private ClienteLogica(String nome, ServicoMensagens servico) {
        this.nome    = nome.toLowerCase();
        this.servico = servico;
    }

    // ════════════════════════════════════════════════════════════════
    //  Registro de callbacks pela UI
    // ════════════════════════════════════════════════════════════════

    /**
     * Registra o listener de mensagens.
     * Se houver mensagens bufferizadas (chegaram durante conectar() antes
     * da UI existir), elas são reentregues imediatamente.
     */
    public synchronized void onMensagem(Consumer<MensagemEvento> cb) {
        this.onMensagem = cb;
        // Reentrega mensagens que chegaram antes do callback ser registrado
        if (!bufferMsgs.isEmpty()) {
            List<MensagemEvento> pendentes = new ArrayList<>(bufferMsgs);
            bufferMsgs.clear();
            pendentes.forEach(cb::accept);
        }
    }

    public synchronized void onStatus(BiConsumer<String, Boolean> cb) {
        this.onStatus = cb;
    }

    // ════════════════════════════════════════════════════════════════
    //  Factory
    // ════════════════════════════════════════════════════════════════

    /**
     * Localiza o Registry RMI no host informado e obtém o stub do serviço.
     * Lança exceção se o servidor não estiver rodando.
     */
    public static ClienteLogica criar(String nome, String host) throws Exception {
        Registry         registry = LocateRegistry.getRegistry(host, Config.RMI_PORTA);
        ServicoMensagens servico  = (ServicoMensagens) registry.lookup(Config.RMI_SERVICO);
        return new ClienteLogica(nome, servico);
    }

    // ════════════════════════════════════════════════════════════════
    //  API pública  (chamadas RMI — executar fora da EDT)
    // ════════════════════════════════════════════════════════════════

    /**
     * Exporta o CallbackImpl e registra no servidor.
     * Req. 7: servidor cria a fila JMS do cliente ao receber esta chamada.
     * O servidor também drena a fila aqui — mensagens que chegarem antes
     * de onMensagem ser definido ficam no bufferMsgs e são reentregues
     * quando a UI chamar onMensagem(cb).
     * Retorna null em caso de sucesso ou a mensagem de erro.
     */
    public String conectar() {
        try {
            callback = new CallbackImpl();
            servico.conectar(nome, callback);
            online = true;
            return null;
        } catch (RemoteException ex) {
            try { UnicastRemoteObject.unexportObject(callback, true); } catch (Exception ignored) {}
            return ex.getMessage();
        }
    }

    /** Req. 2: alterna presença online/offline via RMI. */
    public void mudarStatus(boolean ficarOnline) throws RemoteException {
        servico.mudarStatus(nome, ficarOnline);
        online = ficarOnline;
    }

    /**
     * Req. 3 / 6: envia mensagem via RMI.
     * O servidor decide se entrega direto (online) ou enfileira (offline).
     * Retorna true se entregue na hora, false se foi para a fila.
     */
    public boolean enviar(String destinatario, String texto) throws RemoteException {
        return servico.enviar(nome, destinatario, texto);
    }

    /** Consulta presença de um contato no servidor via RMI. */
    public boolean estaOnline(String contato) {
        try   { return servico.estaOnline(contato); }
        catch (RemoteException e) { return false; }
    }

    /** Remove o cliente do servidor e desfaz a exportação do callback. */
    public void desconectar() {
        try { servico.desconectar(nome); } catch (RemoteException ignored) {}
        if (callback != null)
            try { UnicastRemoteObject.unexportObject(callback, true); } catch (Exception ignored) {}
    }

    public String  getNome()  { return nome; }
    public boolean isOnline() { return online; }

    // ════════════════════════════════════════════════════════════════
    //  Contatos (gerenciados localmente, persistidos em arquivo)
    // ════════════════════════════════════════════════════════════════

    public List<String> carregarContatos() {
        List<String> lista = new ArrayList<>();
        Path arq = Paths.get("contatos-" + nome + ".txt");
        if (Files.exists(arq)) {
            try {
                for (String l : Files.readAllLines(arq, StandardCharsets.UTF_8)) {
                    String c = l.trim();
                    if (!c.isEmpty()) lista.add(c);
                }
            } catch (IOException ignored) {}
        }
        return lista;
    }

    public void salvarContatos(List<String> contatos) {
        try { Files.write(Paths.get("contatos-" + nome + ".txt"), contatos, StandardCharsets.UTF_8); }
        catch (IOException ignored) {}
    }

    // ════════════════════════════════════════════════════════════════
    //  CallbackImpl — objeto RMI exportado pelo cliente
    //  O SERVIDOR chama estes métodos remotamente para:
    //    • Entregar mensagem (Req. 3 direto / Req. 6 da fila)
    //    • Atualizar status de presença de outros clientes
    //  Roda na thread RMI do cliente → UI deve usar invokeLater
    // ════════════════════════════════════════════════════════════════

    private class CallbackImpl extends UnicastRemoteObject implements ClienteCallback {

        private static final long serialVersionUID = 1L;
        CallbackImpl() throws RemoteException { super(); }

        @Override
        public void receberMensagem(String remetente, String texto, long timestamp, boolean daFila) {
            MensagemEvento evt = new MensagemEvento(remetente, texto, timestamp, daFila);
            synchronized (ClienteLogica.this) {
                if (onMensagem != null) onMensagem.accept(evt);
                else                   bufferMsgs.add(evt);  // guarda até UI registrar o listener
            }
        }

        @Override
        public void atualizarStatus(String contato, boolean estaOnline) {
            synchronized (ClienteLogica.this) {
                if (onStatus != null) onStatus.accept(contato, estaOnline);
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  MensagemEvento — dado transportado ao callback da UI
    // ════════════════════════════════════════════════════════════════

    public static class MensagemEvento {
        public final String  remetente;
        public final String  texto;
        public final long    timestamp;
        public final boolean daFila;

        public MensagemEvento(String remetente, String texto, long timestamp, boolean daFila) {
            this.remetente = remetente;
            this.texto     = texto;
            this.timestamp = timestamp;
            this.daFila    = daFila;
        }
    }
}
