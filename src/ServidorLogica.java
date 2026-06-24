/*
 * ═══════════════════════════════════════════════════════════════
 *  ServidorLogica.java — LÓGICA do servidor  (zero Swing)
 * ═══════════════════════════════════════════════════════════════
 *
 *  Tecnologias PPD usadas aqui:
 *
 *  1. MOM / ActiveMQ
 *     • Broker embutido gerencia uma Queue JMS por cliente (Req. 5)
 *     • garantirFila()  → cria a fila ao registrar o cliente (Req. 7)
 *     • enfileirar()    → deposita mensagem quando o destino está offline (Req. 6)
 *     • drenarFila()    → entrega as mensagens pendentes ao cliente que volta online
 *
 *  2. RMI — ServicoImpl (inner class)
 *     • Publicado no Registry na porta RMI_PORTA (Req. 4)
 *     • conectar()    → registra o cliente e entrega mensagens pendentes
 *     • mudarStatus() → alterna online/offline (Req. 2)
 *     • enviar()      → chama rotear() para decidir callback x fila (Req. 3/6)
 *     • estaOnline()  → consulta de presença
 *     • desconectar() → remove o cliente
 *
 *  3. Callbacks RMI (ClienteCallback)
 *     • Cada cliente exporta um objeto remoto e o entrega ao conectar
 *     • rotear() usa esse objeto para entrega direta quando o destino está online
 *     • notificarStatus() avisa os demais clientes de mudança de presença
 */
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.broker.BrokerService;

import javax.jms.*;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ServidorLogica {

    // ── Estado (thread-safe: acessado por múltiplas threads RMI) ─────────────
    private BrokerService broker;
    private Connection    jmsConn;
    private Registry      registry;
    private ServicoImpl   servico;

    private final Map<String, ClienteCallback> callbacks = new ConcurrentHashMap<>();
    private final Set<String>                  online    = ConcurrentHashMap.newKeySet();
    private final Set<String>                  filas     = ConcurrentHashMap.newKeySet();

    // ── Callbacks para a UI (registrados antes de chamar iniciar()) ───────────
    private Consumer<String> onLog;      // cada linha de log
    private Runnable         onSucesso;  // chamado quando tudo subiu
    private Consumer<String> onFalha;   // chamado com a mensagem de erro

    public void onLog    (Consumer<String> cb) { this.onLog     = cb; }
    public void onSucesso(Runnable         cb) { this.onSucesso = cb; }
    public void onFalha  (Consumer<String> cb) { this.onFalha   = cb; }

    // ════════════════════════════════════════════════════════════════
    //  API pública
    // ════════════════════════════════════════════════════════════════

    /** Inicia broker MOM + JMS + RMI em thread de fundo. */
    public void iniciar() {
        Thread t = new Thread(() -> {
            try {
                // 1 ── Broker ActiveMQ (MOM) ──────────────────────────────────
                log("Iniciando broker ActiveMQ (MOM)...");
                broker = new BrokerService();
                broker.setPersistent(false);   // in-memory: sem arquivos em disco
                broker.setUseJmx(false);
                broker.addConnector(Config.BROKER_URL);
                broker.start();
                broker.waitUntilStarted();     // aguarda o broker estar 100% pronto antes de continuar
                log("Broker MOM no ar: " + Config.BROKER_URL);

                // 2 ── Conexão JMS (usada pelo servidor para enfileirar/drenar) ─
                jmsConn = new ActiveMQConnectionFactory(Config.BROKER_URL).createConnection();
                jmsConn.start();
                log("Conexão JMS aberta");

                // 3 ── Registry e serviço RMI ──────────────────────────────────
                System.setProperty("java.rmi.server.hostname", "localhost");
                try {
                    registry = LocateRegistry.createRegistry(Config.RMI_PORTA);
                } catch (RemoteException e) {
                    registry = LocateRegistry.getRegistry(Config.RMI_PORTA); // porta já ocupada: reusa
                }
                servico = new ServicoImpl();
                registry.rebind(Config.RMI_SERVICO, servico);
                log("Serviço RMI '" + Config.RMI_SERVICO + "' publicado na porta " + Config.RMI_PORTA);
                log("Aguardando clientes...");

                if (onSucesso != null) onSucesso.run();

            } catch (Exception ex) {
                log("ERRO ao iniciar: " + ex.getMessage());
                if (onFalha != null) onFalha.accept(ex.getMessage());
            }
        }, "servidor-init");
        t.setDaemon(true);
        t.start();
    }

    /** Para o servidor limpando todos os recursos. */
    public void parar() {
        try { if (registry != null) registry.unbind(Config.RMI_SERVICO); }   catch (Exception ignored) {}
        try { if (servico  != null) UnicastRemoteObject.unexportObject(servico, true); } catch (Exception ignored) {}
        try { if (jmsConn  != null) jmsConn.close(); }                         catch (Exception ignored) {}
        try { if (broker   != null) { broker.stop(); broker.waitUntilStopped(); } } catch (Exception ignored) {}
    }

    // ════════════════════════════════════════════════════════════════
    //  ServicoImpl — implementação RMI (inner class)
    //  Cada método é chamado remotamente por um cliente via stub RMI.
    //  Roda em threads do pool RMI → estado compartilhado usa
    //  ConcurrentHashMap; Session JMS criada por operação (não thread-safe).
    // ════════════════════════════════════════════════════════════════

    private class ServicoImpl extends UnicastRemoteObject implements ServicoMensagens {

        private static final long serialVersionUID = 1L;
        ServicoImpl() throws RemoteException { super(); }

        /** Req. 7: cria fila, registra callback, drena mensagens pendentes. */
        @Override
        public synchronized void conectar(String nome, ClienteCallback cb) throws RemoteException {
            nome = nome.toLowerCase();
            if (callbacks.containsKey(nome))
                throw new RemoteException("NOME_EM_USO");

            try { garantirFila(nome); } catch (JMSException e) {
                throw new RemoteException("Erro MOM: " + e.getMessage());
            }
            callbacks.put(nome, cb);
            online.add(nome);
            notificarStatus(nome, true);
            int n = drenarFila(nome, cb);
            log("'" + nome + "' conectou" + (n > 0 ? " (" + n + " msgs da fila)" : ""));
        }

        /** Req. 2: alterna presença; ao voltar online drena a fila. */
        @Override
        public void mudarStatus(String nome, boolean ficarOnline) {
            nome = nome.toLowerCase();
            if (ficarOnline) {
                online.add(nome);
                notificarStatus(nome, true);
                ClienteCallback cb = callbacks.get(nome);
                if (cb != null) {
                    int n = drenarFila(nome, cb);
                    log("'" + nome + "' voltou online" + (n > 0 ? " (" + n + " msgs)" : ""));
                }
            } else {
                online.remove(nome);
                notificarStatus(nome, false);
                log("'" + nome + "' foi offline");
            }
        }

        /** Req. 3 / 6: delega ao roteamento. */
        @Override
        public boolean enviar(String de, String para, String texto) throws RemoteException {
            return rotear(de.toLowerCase(), para.toLowerCase(), texto);
        }

        @Override
        public boolean estaOnline(String nome) {
            return online.contains(nome.toLowerCase());
        }

        @Override
        public void desconectar(String nome) {
            nome = nome.toLowerCase();
            callbacks.remove(nome);
            if (online.remove(nome)) notificarStatus(nome, false);
            log("'" + nome + "' desconectou (fila preservada)");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Roteamento
    // ════════════════════════════════════════════════════════════════

    /**
     * Req. 3: destino ONLINE  → chama callback RMI direto.
     * Req. 6: destino OFFLINE → enfileira no MOM.
     */
    private boolean rotear(String de, String para, String texto) throws RemoteException {
        long ts = System.currentTimeMillis();
        if (online.contains(para)) {
            ClienteCallback cb = callbacks.get(para);
            if (cb != null) {
                try {
                    cb.receberMensagem(de, texto, ts, false);
                    log("[DIRETO] " + de + " → " + para);
                    return true;
                } catch (RemoteException e) {
                    callbacks.remove(para);
                    online.remove(para);
                    notificarStatus(para, false);
                }
            }
        }
        try { enfileirar(de, para, texto, ts); } catch (JMSException e) {
            throw new RemoteException("Erro MOM: " + e.getMessage());
        }
        log("[FILA] " + de + " → " + para);
        return false;
    }

    // ════════════════════════════════════════════════════════════════
    //  Operações JMS (cada uma abre/fecha sua própria Session)
    // ════════════════════════════════════════════════════════════════

    /** Req. 7: materializa a Queue no broker (consumer imediato cria a fila). */
    private void garantirFila(String nome) throws JMSException {
        if (!filas.add(nome)) return;
        Session s = jmsConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        try     { s.createConsumer(s.createQueue(Config.nomeFila(nome))).close(); }
        finally { s.close(); }
        log("Fila '" + Config.nomeFila(nome) + "' criada no MOM");
    }

    /** Req. 6: deposita mensagem na fila do destinatário. */
    private void enfileirar(String de, String para, String texto, long ts) throws JMSException {
        Session s = jmsConn.createSession(false, Session.AUTO_ACKNOWLEDGE);
        try {
            MessageProducer prod = s.createProducer(s.createQueue(Config.nomeFila(para)));
            TextMessage     msg  = s.createTextMessage(texto);
            msg.setStringProperty("remetente", de);
            msg.setLongProperty("timestamp",   ts);
            prod.send(msg);
            prod.close();
        } finally { s.close(); }
    }

    /**
     * Consome a fila e entrega via callback.
     * CLIENT_ACKNOWLEDGE: o ack só ocorre após entrega confirmada —
     * se o cliente cair no meio, as msgs não confirmadas voltam à fila.
     */
    private int drenarFila(String nome, ClienteCallback cb) {
        int n = 0;
        try {
            Session s = jmsConn.createSession(false, Session.CLIENT_ACKNOWLEDGE);
            try {
                MessageConsumer cons = s.createConsumer(s.createQueue(Config.nomeFila(nome)));
                Message m;
                while ((m = cons.receive(300)) != null) {
                    if (m instanceof TextMessage) {
                        TextMessage tm = (TextMessage) m;
                        cb.receberMensagem(
                            tm.getStringProperty("remetente"),
                            tm.getText(),
                            tm.getLongProperty("timestamp"),
                            true
                        );
                        m.acknowledge();
                        n++;
                    }
                }
                cons.close();
            } finally { s.close(); }
        } catch (Exception e) { log("Erro ao drenar fila de '" + nome + "': " + e.getMessage()); }
        return n;
    }

    // ════════════════════════════════════════════════════════════════
    //  Notificação de presença
    // ════════════════════════════════════════════════════════════════

    /** Avisa todos os outros clientes conectados que 'nome' mudou de status. */
    private void notificarStatus(String nome, boolean ficouOnline) {
        for (Map.Entry<String, ClienteCallback> e : callbacks.entrySet()) {
            if (e.getKey().equals(nome)) continue;
            try   { e.getValue().atualizarStatus(nome, ficouOnline); }
            catch (RemoteException ignored) {}
        }
    }

    private void log(String msg) { if (onLog != null) onLog.accept(msg); }
}
