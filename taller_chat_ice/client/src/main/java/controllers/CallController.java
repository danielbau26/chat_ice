package controllers;

import java.net.SocketException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import Chat.AudioEndpoint;
import Chat.ChatError;
import Chat.ChatServerPrx;
import ui.Console;

/**
 * Coordina las llamadas de voz en el cliente: 1 a 1 (RF-05) y grupales (RF-06).
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
 *
 * Llamada grupal = malla P2P (mesh): al entrar, el servidor devuelve los endpoints de
 * los que ya están y se agrega cada uno como destino del audio. Cuando alguien entra o
 * sale, llega groupMemberJoined / groupMemberLeft y se agrega o quita ese destino.
 * El audio de todos se recibe en el mismo socket y AudioReceiver lo mezcla.
 *
 * Solo se puede estar en una llamada a la vez (1 a 1 o grupal).
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

    /** Sala de la llamada grupal en la que estás; null si no estás en una */
    private String groupRoom;

    /** Participantes de la llamada grupal (sin incluirte): nickname -> endpoint UDP */
    private final Map<String, AudioEndpoint> groupMembers = new HashMap<>();

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
            if (isBusy()) {
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
            if (isBusy()) {
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
     * /hangup: cuelga la llamada actual (o la cancela si aún estaba sonando).
     * Si estás en una llamada grupal, sales de ella.
     */
    public void hangup() throws ChatError {
        String previous;
        String room;

        synchronized (this) {
            previous = peer;
            room = groupRoom;
        }

        if (room != null) {
            leaveGroupCall();
            return;
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
            if (peer == null && groupRoom == null) {
                Console.show("No estás en una llamada");
                return;
            }

            voice.setMuted(muted);
        }

        Console.show(muted ? "[Llamada] Micrófono silenciado" : "[Llamada] Micrófono activo");
    }

    // ===================== LLAMADA GRUPAL (RF-06) =====================

    /**
     * /groupcall <sala>: entra a la llamada de voz de una sala (o la inicia si no hay nadie)
     */
    public void joinGroupCall(String roomName) throws ChatError {
        int port;

        synchronized (this) {
            if (isBusy()) {
                return;
            }

            port = openVoice();

            if (port < 0) {
                return;
            }

            //Se reserva antes de llamar al servidor: si alguien entra justo después,
            //su groupMemberJoined ya encuentra la llamada registrada
            groupRoom = roomName;
            groupMembers.clear();
        }

        AudioEndpoint[] others;

        try {
            //El servidor devuelve los que ya estaban en la llamada
            others = chat.joinGroupCall(nickname, roomName, port);
        } catch (ChatError e) {
            endLocal();
            throw e;
        }

        synchronized (this) {
            if (!roomName.equals(groupRoom)) {
                return;
            }

            for (AudioEndpoint other : others) {
                addGroupMember(other);
            }

            voice.startAudio();
        }

        if (others.length == 0) {
            Console.show("[Llamada #" + roomName + "] Iniciaste la llamada grupal. Esperando a que otros se unan con /groupcall " + roomName);
        } else {
            Console.show("[Llamada #" + roomName + "] Te uniste. En la llamada: " + namesOf(others));
        }

        Console.show("[Llamada #" + roomName + "] Usa /mute, /unmute o /leavecall");
    }

    /**
     * /leavecall: sale de la llamada grupal sin afectar a los demás
     */
    public void leaveGroupCall() throws ChatError {
        String room;

        synchronized (this) {
            room = groupRoom;
        }

        if (room == null) {
            Console.show("No estás en una llamada grupal");
            return;
        }

        endLocal();

        try {
            chat.leaveGroupCall(nickname, room);
        } catch (ChatError e) {
            //El servidor ya te había sacado (por ejemplo, saliste de la sala)
        }

        Console.show("[Llamada #" + room + "] Saliste de la llamada grupal");
    }

    /**
     * Se llama cuando el usuario sale de una sala (/leave): el servidor ya lo sacó de
     * la llamada grupal de esa sala, aquí solo se libera el audio local
     */
    public void onLeftRoom(String roomName) {
        synchronized (this) {
            if (!roomName.equals(groupRoom)) {
                return;
            }
        }

        endLocal();
        Console.show("[Llamada #" + roomName + "] Saliste de la llamada grupal al salir de la sala");
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

    /**
     * Alguien entró a la llamada grupal: se le empieza a enviar audio también
     */
    public void onGroupMemberJoined(String roomName, AudioEndpoint member) {
        synchronized (this) {
            if (!roomName.equals(groupRoom)) {
                return;
            }

            addGroupMember(member);
        }

        Console.show("[Llamada #" + roomName + "] " + member.nickname + " entró a la llamada");
    }

    /**
     * Alguien salió de la llamada grupal: se le deja de enviar audio
     */
    public void onGroupMemberLeft(String roomName, String member) {
        synchronized (this) {
            if (!roomName.equals(groupRoom)) {
                return;
            }

            AudioEndpoint endpoint = groupMembers.remove(member);

            if (endpoint != null) {
                voice.removeTarget(endpoint.host, endpoint.port);
            }
        }

        Console.show("[Llamada #" + roomName + "] " + member + " salió de la llamada");
    }

    // ===================== AYUDANTES =====================

    /**
     * Revisa si ya estás en una llamada y avisa. Debe llamarse con el candado tomado.
     * @return - true si estás ocupado
     */
    private boolean isBusy() {
        if (peer != null) {
            Console.show("Ya estás en una llamada con " + peer + ". Usa /hangup primero");
            return true;
        }

        if (groupRoom != null) {
            Console.show("Ya estás en la llamada de #" + groupRoom + ". Usa /leavecall primero");
            return true;
        }

        return false;
    }

    /**
     * Agrega un participante como destino del audio. Debe llamarse con el candado tomado.
     */
    private void addGroupMember(AudioEndpoint member) {
        if (member.nickname.equals(nickname)) {
            return;
        }

        groupMembers.put(member.nickname, member);
        voice.addTarget(member.host, member.port);
    }

    private String namesOf(AudioEndpoint[] endpoints) {
        List<String> names = new ArrayList<>();

        for (AudioEndpoint endpoint : endpoints) {
            names.add(endpoint.nickname);
        }

        return String.join(", ", names);
    }

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
        groupRoom = null;
        groupMembers.clear();
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
