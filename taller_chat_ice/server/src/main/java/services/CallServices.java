package services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.zeroc.Ice.LocalException;

import Chat.AudioEndpoint;
import model.Call;
import model.GroupCall;
import model.Room;
import model.User;

/**
 * Lógica de SEÑALIZACIÓN de las llamadas de voz (RF-05 y RF-06).
 *
 * El audio NO pasa por aquí: viaja por UDP directamente entre los clientes.
 * Este servicio solo coordina quién llama a quién y reparte los endpoints UDP
 * (IP y puerto) para que los clientes sepan a dónde enviar el audio.
 *
 * Sigue el mismo patrón que ChatServices:
 *  1. Dentro de synchronized(this): validar y modificar el estado de las llamadas.
 *  2. Fuera del synchronized: invocar los callbacks de los clientes (red).
 *
 * Para consultar usuarios y salas usa los métodos públicos de ChatServices ANTES de
 * tomar su propio candado, así nunca se tienen los dos candados al tiempo (evita deadlocks).
 *
 * Reglas:
 *  - Un usuario solo puede estar en una llamada a la vez (1 a 1 o grupal).
 *  - Solo los miembros de una sala pueden entrar a su llamada grupal.
 */
public class CallServices {

    private final ChatServices chatServices;

    /**
     * Llamadas 1 a 1 en curso. Cada llamada se guarda dos veces, una por cada
     * participante (caller y callee), para encontrarla rápido desde cualquiera de los dos.
     */
    private final Map<String, Call> calls = new ConcurrentHashMap<>();

    /** Llamadas grupales en curso: nombre de la sala -> llamada */
    private final Map<String, GroupCall> groupCalls = new ConcurrentHashMap<>();

    public CallServices(ChatServices chatServices) {
        this.chatServices = chatServices;
    }

    // ===================== LLAMADAS 1 A 1 (RF-05) =====================

    /**
     * Inicia una llamada: queda sonando hasta que el destinatario acepte o rechace
     * @param caller - quien llama
     * @param callee - a quien se llama
     * @param callerHost - IP de quien llama (la obtiene el controlador de la conexión Ice)
     * @param callerPort - puerto UDP donde quien llama recibirá el audio
     */
    public void startCall(String caller, String callee, String callerHost, int callerPort) throws Exception {

        User callerUser = requireUser(caller);
        User calleeUser = requireUser(callee);

        caller = callerUser.getNickname();
        callee = calleeUser.getNickname();

        if (caller.equals(callee)) {
            throw new IllegalArgumentException("No puedes llamarte a ti mismo");
        }

        validatePort(callerPort);

        Call call = new Call(caller, callee, endpoint(caller, callerHost, callerPort));

        synchronized (this) {
            requireFree(caller, "Ya estás en una llamada");
            requireFree(callee, callee + " está ocupado en otra llamada");

            calls.put(caller, call);
            calls.put(callee, call);
        }

        //Avisar al destinatario fuera del candado
        try {
            calleeUser.getCallback().incomingCall(caller);
        } catch (LocalException e) {
            //No se pudo avisar: se deshace la llamada
            removeCall(call);
            throw new IllegalStateException("No se pudo contactar a " + callee);
        }
    }

    /**
     * El destinatario acepta la llamada
     * @param callee - quien acepta
     * @param caller - quien había llamado
     * @param calleeHost - IP de quien acepta
     * @param calleePort - puerto UDP donde quien acepta recibirá el audio
     * @return - endpoint de quien llamó, para empezar a enviarle audio
     */
    public AudioEndpoint acceptCall(String callee, String caller, String calleeHost, int calleePort) throws Exception {

        requireUser(callee);
        User callerUser = requireUser(caller);

        validatePort(calleePort);

        AudioEndpoint calleeEndpoint = endpoint(callee, calleeHost, calleePort);
        Call call;

        synchronized (this) {
            call = calls.get(callee);

            if (call == null || !call.getCallee().equals(callee) || !call.getCaller().equals(caller)) {
                throw new IllegalStateException("No tienes una llamada entrante de " + caller);
            }

            call.accept(calleeEndpoint);
        }

        //Avisar a quien llamó que ya puede empezar a enviar audio
        try {
            callerUser.getCallback().callAccepted(calleeEndpoint);
        } catch (LocalException e) {
            removeCall(call);
            throw new IllegalStateException(caller + " ya no está disponible");
        }

        return call.getCallerEndpoint();
    }

    /**
     * El destinatario rechaza la llamada
     */
    public void rejectCall(String callee, String caller) throws Exception {

        requireUser(callee);
        User callerUser = requireUser(caller);

        synchronized (this) {
            Call call = calls.get(callee);

            if (call == null || !call.getCallee().equals(callee) || !call.getCaller().equals(caller)
                    || call.getState() != Call.State.RINGING) {
                throw new IllegalStateException("No tienes una llamada entrante de " + caller);
            }

            calls.remove(caller);
            calls.remove(callee);
        }

        notifySafely(callerUser, user -> user.getCallback().callRejected(callee));
    }

    /**
     * Cuelga la llamada 1 a 1 (o la cancela si aún estaba sonando).
     * Al otro participante le llega callEnded.
     */
    public void hangup(String nickname) throws Exception {

        requireUser(nickname);

        Call call;

        synchronized (this) {
            call = calls.get(nickname);

            if (call == null) {
                throw new IllegalStateException("No estás en una llamada");
            }

            calls.remove(call.getCaller());
            calls.remove(call.getCallee());
        }

        String peer = call.peerOf(nickname);
        notifySafely(findUser(peer), user -> user.getCallback().callEnded(nickname));
    }

    // ===================== LLAMADAS GRUPALES (RF-06, MALLA P2P) =====================

    /**
     * Entra a la llamada grupal de una sala; si no existe, la crea
     * @param nickname - quien entra
     * @param roomName - sala
     * @param host - IP del participante
     * @param port - puerto UDP donde recibirá el audio
     * @return - endpoints de los que ya estaban, para empezar a enviarles audio
     */
    public List<AudioEndpoint> joinGroupCall(String nickname, String roomName, String host, int port) throws Exception {

        nickname = requireUser(nickname).getNickname();
        Room room = requireRoom(roomName);
        roomName = room.getName();

        if (!room.hasMember(nickname)) {
            throw new IllegalStateException("No perteneces a la sala " + roomName);
        }

        validatePort(port);

        AudioEndpoint newcomer = endpoint(nickname, host, port);
        List<AudioEndpoint> others;

        synchronized (this) {
            requireFree(nickname, "Ya estás en una llamada");

            GroupCall groupCall = groupCalls.computeIfAbsent(roomName, GroupCall::new);
            others = groupCall.getParticipants();
            groupCall.addParticipant(newcomer);
        }

        //Avisar a los que ya estaban para que también le envíen audio al nuevo
        final String finalRoomName = roomName;
        for (AudioEndpoint other : others) {
            notifySafely(findUser(other.nickname), user -> user.getCallback().groupMemberJoined(finalRoomName, newcomer));
        }

        return others;
    }

    /**
     * Sale de la llamada grupal de una sala sin afectar a los demás
     */
    public void leaveGroupCall(String nickname, String roomName) throws Exception {

        requireUser(nickname);

        List<AudioEndpoint> remaining;

        synchronized (this) {
            GroupCall groupCall = groupCalls.get(roomName);

            if (groupCall == null || !groupCall.removeParticipant(nickname)) {
                throw new IllegalStateException("No estás en la llamada de " + roomName);
            }

            remaining = groupCall.getParticipants();

            //Si ya no queda nadie, la llamada de la sala termina
            if (groupCall.isEmpty()) {
                groupCalls.remove(roomName);
            }
        }

        notifyGroupMemberLeft(roomName, nickname, remaining);
    }

    // ===================== DESCONEXIONES =====================

    /**
     * Termina todas las llamadas de un usuario que hizo logout o perdió la conexión.
     * La llamará ChatServices (paso siguiente) DESPUÉS de soltar su propio candado.
     */
    public void onUserDisconnected(String nickname) {

        Call call;
        List<String> leftRooms = new ArrayList<>();
        List<List<AudioEndpoint>> remainingByRoom = new ArrayList<>();

        synchronized (this) {
            //Llamada 1 a 1
            call = calls.get(nickname);

            if (call != null) {
                calls.remove(call.getCaller());
                calls.remove(call.getCallee());
            }

            //Llamadas grupales (en teoría solo una, pero se recorren todas por seguridad)
            for (GroupCall groupCall : new ArrayList<>(groupCalls.values())) {
                if (groupCall.removeParticipant(nickname)) {
                    leftRooms.add(groupCall.getRoomName());
                    remainingByRoom.add(groupCall.getParticipants());

                    if (groupCall.isEmpty()) {
                        groupCalls.remove(groupCall.getRoomName());
                    }
                }
            }
        }

        if (call != null) {
            String peer = call.peerOf(nickname);
            notifySafely(findUser(peer), user -> user.getCallback().callEnded(nickname));
        }

        for (int i = 0; i < leftRooms.size(); i++) {
            notifyGroupMemberLeft(leftRooms.get(i), nickname, remainingByRoom.get(i));
        }
    }

    /**
     * Saca a un usuario de la llamada grupal de una sala cuando abandona la sala
     * (si no estaba en la llamada no hace nada). La llamará ChatServices.leaveRoom.
     */
    public void onUserLeftRoom(String nickname, String roomName) {
        try {
            leaveGroupCall(nickname, roomName);
        } catch (Exception e) {
            //No estaba en la llamada de esa sala: no hay nada que hacer
        }
    }

    // ===================== AYUDANTES =====================

    /**
     * Lanza error si el usuario ya está en una llamada 1 a 1 o grupal.
     * Debe llamarse dentro de synchronized(this).
     */
    private void requireFree(String nickname, String message) {
        if (calls.containsKey(nickname)) {
            throw new IllegalStateException(message);
        }

        for (GroupCall groupCall : groupCalls.values()) {
            if (groupCall.hasParticipant(nickname)) {
                throw new IllegalStateException(message);
            }
        }
    }

    /**
     * Elimina una llamada 1 a 1 (si sigue registrada)
     */
    private synchronized void removeCall(Call call) {
        calls.remove(call.getCaller(), call);
        calls.remove(call.getCallee(), call);
    }

    private void notifyGroupMemberLeft(String roomName, String nickname, List<AudioEndpoint> remaining) {
        for (AudioEndpoint other : remaining) {
            notifySafely(findUser(other.nickname), user -> user.getCallback().groupMemberLeft(roomName, nickname));
        }
    }

    /**
     * Invoca un callback sin propagar errores de red: si el cliente se cayó,
     * el PresenceMonitor lo detectará y lo sacará.
     */
    private void notifySafely(User user, CallbackAction action) {
        if (user == null) {
            return;
        }

        try {
            action.run(user);
        } catch (LocalException e) {
            System.err.println("No se pudo notificar a " + user.getNickname() + " sobre la llamada");
        }
    }

    @FunctionalInterface
    private interface CallbackAction {
        void run(User user);
    }

    private AudioEndpoint endpoint(String nickname, String host, int port) {
        AudioEndpoint endpoint = new AudioEndpoint();
        endpoint.nickname = nickname;
        endpoint.host = host;
        endpoint.port = port;
        return endpoint;
    }

    private void validatePort(int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Puerto UDP inválido: " + port);
        }
    }

    /**
     * Busca un usuario conectado (usa ChatServices, que tiene su propio candado)
     * @return - el usuario o null si no está conectado
     */
    private User findUser(String nickname) {
        try {
            for (User user : chatServices.getUsers()) {
                if (user.getNickname().equals(nickname)) {
                    return user;
                }
            }
        } catch (Exception e) {
            //se trata como no conectado
        }

        return null;
    }

    private User requireUser(String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new IllegalArgumentException("El nickname no puede estar vacío");
        }

        User user = findUser(nickname.trim());

        if (user == null) {
            throw new IllegalStateException(nickname + " no está conectado");
        }

        return user;
    }

    private Room requireRoom(String roomName) throws Exception {
        if (roomName == null || roomName.trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre de la sala no puede estar vacío");
        }

        for (Room room : chatServices.getRooms()) {
            if (room.getName().equals(roomName.trim())) {
                return room;
            }
        }

        throw new IllegalStateException("La sala no existe");
    }
}
