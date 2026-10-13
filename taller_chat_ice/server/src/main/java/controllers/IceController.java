package controllers;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

import com.zeroc.Ice.ConnectionInfo;
import com.zeroc.Ice.Current;
import com.zeroc.Ice.IPConnectionInfo;

import Chat.ChatError;
import Chat.ChatServer;
import Chat.ClientCallbackPrx;
import Chat.NicknameInUse;
import model.Room;
import model.User;
import services.CallServices;
import services.ChatServices;
import Chat.FileChunk;
import Chat.AudioEndpoint;

public class IceController implements ChatServer {

    private final ChatServices chatServices;
    private final CallServices callServices;

    public IceController(ChatServices chatServices, CallServices callServices) {
        this.chatServices = chatServices;
        this.callServices = callServices;
    }


    // Current trae información de la llamada que llega, incluida la conexión (current.con)
    @Override
    public void login(String nickname, ClientCallbackPrx callback, Current current) throws NicknameInUse, ChatError {

        try {
            // Amarra el callback a la conexión por la que llegó el login,
            // para que el servidor pueda llamar al cliente por esa misma conexión
            ClientCallbackPrx fixedCallback = callback.ice_fixed(current.con);
            chatServices.login(nickname, fixedCallback);
        } catch (IllegalStateException e) {
            NicknameInUse error = new NicknameInUse();
            error.nickname = nickname;
            throw error;
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public void logout(String nickname, Current current) throws ChatError {

        try {
            chatServices.logout(nickname);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public String[] getUsers(Current current) {
        try {
            List<User> users = chatServices.getUsers();
            String[] nicknames = new String[users.size()];

            for (int i = 0; i < users.size(); i++) {
                nicknames[i] = users.get(i).getNickname();
            }

            return nicknames;
        } catch (Exception e) {
            //getUsers no declara excepciones en Chat.ice: si se lanzara una RuntimeException
            //al cliente le llegaría un UnknownException sin mensaje. Se registra y se
            //devuelve una lista vacía.
            System.err.println("No se pudo consultar los usuarios: " + e.getMessage());
            return new String[0];
        }
    }

    @Override
    public void sendPrivateMessage(String sender, String recipient, String text, Current current) throws ChatError {

        try {
            chatServices.sendPrivateMessage(sender, recipient, text);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public void createRoom(String nickname, String roomName, Current current) throws ChatError {

        try {
            chatServices.createRoom(nickname, roomName);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public String[] getRooms(Current current) {
        try {
            List<Room> rooms = chatServices.getRooms();
            String[] names = new String[rooms.size()];

            for (int i = 0; i < rooms.size(); i++) {
                names[i] = rooms.get(i).getName();
            }

            return names;
        } catch (Exception e) {
            //Igual que getUsers: se registra y se devuelve una lista vacía
            System.err.println("No se pudo consultar las salas: " + e.getMessage());
            return new String[0];
        }
    }

    @Override
    public void joinRoom(String nickname, String roomName, Current current) throws ChatError {

        try {
            chatServices.joinRoom(nickname, roomName);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public void leaveRoom(String nickname, String roomName, Current current) throws ChatError {

        try {
            chatServices.leaveRoom(nickname, roomName);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public void sendRoomMessage(String sender, String roomName, String text, Current current) throws ChatError {

        try {
            chatServices.sendRoomMessage(
                    sender, roomName, text
            );
        } catch (Exception e) {
            throw createError(e);
        }
    }
    @Override
    public void sendFileChunkToUser(String sender, String recipient, FileChunk chunk, Current current) throws ChatError {

        try {
            chatServices.sendFileChunkToUser(sender, recipient, chunk);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public void sendFileChunkToRoom(String sender, String roomName, FileChunk chunk, Current current) throws ChatError {

        try {
            chatServices.sendFileChunkToRoom(sender, roomName, chunk);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    // ===================== LLAMADAS DE VOZ (RF-05 / RF-06) =====================
    // Solo señalización: el audio viaja por UDP directo entre los clientes.
    // El cliente envía su puerto UDP; la IP la saca el servidor de la conexión Ice
    // (clientHost), así no depende de que el cliente conozca su propia IP.

    @Override
    public void startCall(String caller, String callee, int udpPort, Current current) throws ChatError {
        try {
            callServices.startCall(caller, callee, clientHost(current), udpPort);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public AudioEndpoint acceptCall(String callee, String caller, int udpPort, Current current) throws ChatError {
        try {
            return callServices.acceptCall(callee, caller, clientHost(current), udpPort);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public void rejectCall(String callee, String caller, Current current) throws ChatError {
        try {
            callServices.rejectCall(callee, caller);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public void hangup(String nickname, Current current) throws ChatError {
        try {
            callServices.hangup(nickname);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public AudioEndpoint[] joinGroupCall(String nickname, String roomName, int udpPort, Current current) throws ChatError {
        try {
            List<AudioEndpoint> others = callServices.joinGroupCall(nickname, roomName, clientHost(current), udpPort);
            //EndpointList en Slice es AudioEndpoint[] en Java
            return others.toArray(new AudioEndpoint[0]);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    @Override
    public void leaveGroupCall(String nickname, String roomName, Current current) throws ChatError {
        try {
            callServices.leaveGroupCall(nickname, roomName);
        } catch (Exception e) {
            throw createError(e);
        }
    }

    /**
     * IP del cliente que hizo la llamada, sacada de la conexión Ice (current.con).
     *
     * Si el cliente corre en el mismo PC que el servidor, la conexión llega desde
     * 127.0.0.1; esa dirección no le sirve a un cliente de OTRO PC para enviarle audio,
     * así que en ese caso se usa la IP de red local de este PC.
     */
    private String clientHost(Current current) {
        String host = null;

        if (current.con != null) {
            //La información puede venir "envuelta" (por ejemplo en SSL/WS): se busca la parte IP
            ConnectionInfo info = current.con.getInfo();

            while (info != null && !(info instanceof IPConnectionInfo)) {
                info = info.underlying;
            }

            if (info != null) {
                host = ((IPConnectionInfo) info).remoteAddress;
            }
        }

        if (host == null || host.isEmpty()) {
            throw new IllegalStateException("No se pudo determinar la dirección del cliente");
        }

        //IPv4 representada como IPv6 (ej: ::ffff:192.168.1.5)
        if (host.startsWith("::ffff:")) {
            host = host.substring("::ffff:".length());
        }

        if (isLoopback(host)) {
            String lan = localNetworkAddress();

            if (lan != null) {
                host = lan;
            }
        }

        return host;
    }

    private boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Primera IPv4 de red local (ej: 192.168.x.x) de una interfaz activa que no sea virtual
     * @return - la IP o null si no se encuentra
     */
    private String localNetworkAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String name = nic.getDisplayName() == null ? "" : nic.getDisplayName().toLowerCase();

                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()
                        || name.contains("virtual") || name.contains("vmware") || name.contains("vethernet")) {
                    continue;
                }

                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("No se pudo obtener la IP local: " + e.getMessage());
        }

        return null;
    }

    private ChatError createError(Exception e) {
        ChatError error = new ChatError();

        error.reason = e.getMessage();

        if (error.reason == null) {
            error.reason = "Error al procesar la operación";
        }

        return error;
    }
}