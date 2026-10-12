package model;

import Chat.AudioEndpoint;

/**
 * Llamada de voz 1 a 1 (RF-05).
 *
 * El servidor no transmite audio: solo guarda quién llama a quién, en qué estado está
 * la llamada y los endpoints UDP (IP y puerto) que los clientes se intercambian para
 * enviarse el audio directamente.
 *
 * Ciclo de vida:
 *  - RINGING: el que llama ya envió startCall, el destinatario aún no responde
 *  - ACTIVE: el destinatario aceptó; los dos ya tienen el endpoint del otro
 *  La llamada termina (se elimina) cuando alguien rechaza, cuelga o se desconecta.
 */
public class Call {

    public enum State {
        RINGING,
        ACTIVE
    }

    private final String caller;
    private final String callee;

    /** Dónde recibe audio quien llama (se conoce desde startCall) */
    private final AudioEndpoint callerEndpoint;

    /** Dónde recibe audio el destinatario (se conoce al aceptar) */
    private AudioEndpoint calleeEndpoint;

    private State state;

    public Call(String caller, String callee, AudioEndpoint callerEndpoint) {
        if (caller == null || callee == null || callerEndpoint == null) {
            throw new IllegalArgumentException("Datos de la llamada incompletos");
        }

        this.caller = caller;
        this.callee = callee;
        this.callerEndpoint = callerEndpoint;
        this.state = State.RINGING;
    }

    public String getCaller() {
        return caller;
    }

    public String getCallee() {
        return callee;
    }

    public synchronized AudioEndpoint getCallerEndpoint() {
        return callerEndpoint;
    }

    public synchronized AudioEndpoint getCalleeEndpoint() {
        return calleeEndpoint;
    }

    public synchronized State getState() {
        return state;
    }

    /**
     * El destinatario aceptó: guarda su endpoint y la llamada queda activa
     * @param endpoint - dónde recibe audio el destinatario
     */
    public synchronized void accept(AudioEndpoint endpoint) {
        if (state != State.RINGING) {
            throw new IllegalStateException("La llamada ya fue respondida");
        }

        this.calleeEndpoint = endpoint;
        this.state = State.ACTIVE;
    }

    /**
     * @return - true si el usuario participa en esta llamada
     */
    public boolean involves(String nickname) {
        return caller.equals(nickname) || callee.equals(nickname);
    }

    /**
     * @return - el nickname de la otra persona de la llamada
     */
    public String peerOf(String nickname) {
        return caller.equals(nickname) ? callee : caller;
    }
}
