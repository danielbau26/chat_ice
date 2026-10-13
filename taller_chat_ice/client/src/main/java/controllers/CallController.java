package controllers;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import Chat.AudioEndpoint;
import Chat.ChatError;
import Chat.ChatServerPrx;
import ui.Console;

/**
 * Coordina las llamadas de voz 1 a 1 en el cliente (RF-05).
 *
 * Une las dos mitades:
 *  - Señalización por Ice: startCall / acceptCall / rejectCall / hangup (al servidor)
 *    y los callbacks incomingCall / callAccepted / callRejected / callEnded (del servidor).
 *  - Audio por UDP: UDPController (micrófono -> red -> parlantes).
 *
 * Lo usan dos tipos de hilo:
 *  - El hilo de la consola, con los comandos /call, /accept, /reject, /hangup, /mute.
 *  - Los hilos de Ice, cuando llegan los callbacks.
 * Por eso el estado se protege con synchronized.
 *
 * IMPORTANTE: el candado NUNCA se mantiene durante una llamada remota a Ice
 * (chat.startCall, chat.acceptCall...). Si se hiciera y llegara un callback en ese
 * momento, el hilo de Ice quedaría esperando el candado y podría bloquearse la conexión.
 * Por eso cada operación hace: (1) revisar/reservar el estado con candado,
 * (2) llamar al servidor sin candado, (3) actualizar el estado con candado.
 */
public class CallController {

    private final UDPController voice = new UDPController();

    /** Personas que te están llamando y aún no respondes */
    private final Set<String> incoming = ConcurrentHashMap.newKeySet();

    private ChatServerPrx chat;
    private String nickname;

    /** Con quién es la llamada (sonando o activa); null si no hay llamada */
    private String peer;

    /** true mientras llamaste a alguien y todavía no responde */
    private boolean ringingOut;

    /**
     * Se llama después del login, cuando ya se conoce el proxy y el nickname
     */
    public synchronized void init(ChatServerPrx chat, String nickname) {
        this.chat = chat;
        this.nickname = nickname;
    }

    // ===================== COMANDOS (hilo de la consola) =====================

    /**
     * /call <usuario>: inicia una llamada
     */
    public void call(String callee) throws ChatError {
        int port;

        synchronized (this) {
            if (peer != null) {
                Console.show("Ya estás en una llamada con " + peer + ". Usa /hangup primero");
                return;
            }

            port = openVoice();

            if (port < 0) {
                return;
            }

            //Se reserva el estado ANTES de llamar al servidor: si el otro acepta muy
            //rápido, el callback callAccepted ya encuentra la llamada registrada
            peer = callee;
            ringingOut = true;
        }

        try {
            chat.startCall(nickname, callee, port);
            Console.show("[Llamada] Llamando a " + callee + "... (/hangup para cancelar)");
        } catch (ChatError e) {
            endLocal();
            throw e;
        }
    }

    /**
     * /accept <usuario>: acepta una llamada entrante
     */
    public void accept(String caller) throws ChatError {
        if (caller == null) {
            caller = onlyIncomingCaller();

            if (caller == null) {
                Console.show("Uso: /accept <usuario>");
                return;
            }
        }

        int port;

        synchronized (this) {
            if (peer != null) {
                Console.show("Ya estás en una llamada con " + peer + ". Usa /hangup primero");
                return;
            }

            port = openVoice();

            if (port < 0) {
                return;
            }

            peer = caller;
            ringingOut = false;
        }

        incoming.remove(caller);
        AudioEndpoint callerEndpoint;

        try {
            //El servidor devuelve la IP y el puerto UDP de quien llamó
            callerEndpoint = chat.acceptCall(nickname, caller, port);
        } catch (ChatError e) {
            endLocal();
            throw e;
        }

        synchronized (this) {
            //Si mientras tanto el otro colgó, callEnded ya limpió el estado
            if (!caller.equals(peer)) {
                return;
            }

            voice.addTarget(callerEndpoint.host, callerEndpoint.port);
            voice.startAudio();
        }

        Console.show("[Llamada] En llamada con " + caller + ". Usa /mute, /unmute o /hangup");
    }

    /**
     * /reject <usuario>: rechaza una llamada entrante
     */
    public void reject(String caller) throws ChatError {
        if (caller == null) {
            caller = onlyIncomingCaller();

            if (caller == null) {
                Console.show("Uso: /reject <usuario>");
                return;
            }
        }

        incoming.remove(caller);
        chat.rejectCall(nickname, caller);
        Console.show("[Llamada] Rechazaste la llamada de " + caller);
    }

    /**
     * /hangup: cuelga la llamada actual (o la cancela si aún estaba sonando)
     */
    public void hangup() throws ChatError {
        String previous;

        synchronized (this) {
            previous = peer;
        }

        if (previous == null) {
            Console.show("No estás en una llamada");
            return;
        }

        //Primero se corta el audio local para que sea inmediato
        endLocal();

        try {
            chat.hangup(nickname);
        } catch (ChatError e) {
            //El servidor ya la había terminado (por ejemplo, el otro colgó al mismo tiempo)
        }

        Console.show("[Llamada] Llamada con " + previous + " terminada");
    }

    /**
     * /mute y /unmute
     */
    public void setMuted(boolean muted) {
        synchronized (this) {
            if (peer == null) {
                Console.show("No estás en una llamada");
                return;
            }

            voice.setMuted(muted);
        }

        Console.show(muted ? "[Llamada] Micrófono silenciado" : "[Llamada] Micrófono activo");
    }

    /**
     * Al cerrar sesión o perder la conexión: libera micrófono, parlantes y socket.
     * (El servidor cuelga la llamada por su cuenta al detectar el logout o la caída.)
     */
    public void shutdown() {
        endLocal();
        incoming.clear();
    }

    // ===================== CALLBACKS (hilos de Ice) =====================

    /**
     * Alguien te está llamando
     */
    public void onIncomingCall(String caller) {
        incoming.add(caller);
        Console.show("[Llamada] " + caller + " te está llamando. Usa /accept " + caller + " o /reject " + caller);
    }

    /**
     * La persona a la que llamaste aceptó: ya se puede enviar audio a su endpoint
     */
    public void onCallAccepted(AudioEndpoint endpoint) {
        synchronized (this) {
            if (!ringingOut || !endpoint.nickname.equals(peer)) {
                return;
            }

            ringingOut = false;
            voice.addTarget(endpoint.host, endpoint.port);
            voice.startAudio();
        }

        Console.show("[Llamada] " + endpoint.nickname + " aceptó. En llamada (usa /mute, /unmute o /hangup)");
    }

    /**
     * La persona a la que llamaste rechazó
     */
    public void onCallRejected(String callee) {
        synchronized (this) {
            if (!callee.equals(peer)) {
                return;
            }
        }

        endLocal();
        Console.show("[Llamada] " + callee + " rechazó la llamada");
    }

    /**
     * La otra persona colgó, canceló mientras sonaba o se desconectó
     */
    public void onCallEnded(String other) {
        //Si todavía no habías respondido, era una llamada entrante que se canceló
        if (incoming.remove(other)) {
            Console.show("[Llamada] " + other + " canceló la llamada");
            return;
        }

        synchronized (this) {
            if (!other.equals(peer)) {
                return;
            }
        }

        endLocal();
        Console.show("[Llamada] " + other + " colgó. Llamada terminada");
    }

    // ===================== AYUDANTES =====================

    /**
     * Abre el socket UDP. Debe llamarse con el candado tomado.
     * @return - el puerto, o -1 si no se pudo abrir
     */
    private int openVoice() {
        try {
            return voice.open();
        } catch (SocketException e) {
            Console.show("Error: no se pudo abrir el canal de voz UDP (" + e.getMessage() + ")");
            return -1;
        }
    }

    /**
     * Limpia el estado de la llamada y libera el audio (sin avisar al servidor)
     */
    private synchronized void endLocal() {
        peer = null;
        ringingOut = false;
        voice.close();
    }

    /**
     * Si solo hay una llamada entrante, devuelve quién llama (para /accept sin nombre)
     */
    private String onlyIncomingCaller() {
        List<String> callers = new ArrayList<>(incoming);
        return callers.size() == 1 ? callers.get(0) : null;
    }
}
