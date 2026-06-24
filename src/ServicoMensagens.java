/*
 * ServicoMensagens.java — Contrato RMI do Servidor de Mensagens (Req. 4).
 *
 * Todos os métodos são chamados pelos clientes via stub RMI.
 * O sentido inverso (servidor → cliente) usa ClienteCallback.
 */
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ServicoMensagens extends Remote {

    /**
     * Registra o cliente, cria sua fila JMS (Req. 7) e entrega mensagens pendentes.
     * Lança RemoteException("NOME_EM_USO") se o nome já estiver conectado.
     */
    void conectar(String nome, ClienteCallback cb) throws RemoteException;

    /** Alterna o status online/offline do cliente (Req. 2). */
    void mudarStatus(String nome, boolean online) throws RemoteException;

    /**
     * Envia uma mensagem:
     *  - destinatário ONLINE → callback imediato (Req. 3);
     *  - destinatário OFFLINE → fila JMS (Req. 6).
     * Retorna true se entregue na hora, false se foi para a fila.
     */
    boolean enviar(String remetente, String destinatario, String texto) throws RemoteException;

    /** Consulta o status de presença de um nome de contato. */
    boolean estaOnline(String nome) throws RemoteException;

    /** Remove o cliente (fila é preservada para mensagens futuras). */
    void desconectar(String nome) throws RemoteException;
}
