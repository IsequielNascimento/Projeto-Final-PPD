/*
 * Config.java — Constantes globais do sistema.
 *
 * RMI_PORTA  : porta do Registry RMI (servidor de presença — Req. 4)
 * BROKER_URL : endereço do broker ActiveMQ embutido (MOM — Req. 5)
 * RMI_SERVICO: nome com que o serviço é publicado no Registry
 */
public final class Config {

    public static final int    RMI_PORTA   = 1099;
    public static final String RMI_SERVICO = "ServidorMensagens";
    public static final String BROKER_URL  = "tcp://localhost:61616";

    private Config() {}

    /** Nome da fila JMS de cada cliente: "fila.<nome>" */
    public static String nomeFila(String nome) {
        return "fila." + nome.toLowerCase();
    }
}
