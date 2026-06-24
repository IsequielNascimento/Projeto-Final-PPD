/*
 * ClienteCallback.java — Interface de callback do cliente (RMI).
 *
 * Cada ClienteChat exporta um objeto remoto com esta interface e entrega ao
 * servidor ao conectar. É por aqui que o servidor "empurra" eventos ao cliente:
 *  - mensagens novas chegam na hora (Req. 3) ou da fila offline (Req. 6);
 *  - mudanças de status dos contatos atualizam a lista na UI (Req. 1).
 */
import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ClienteCallback extends Remote {

    /** Entrega uma mensagem. daFila=true quando veio da fila offline. */
    void receberMensagem(String remetente, String texto, long timestamp, boolean daFila)
            throws RemoteException;

    /** Avisa que um contato mudou de status (online/offline). */
    void atualizarStatus(String contato, boolean online) throws RemoteException;
}
