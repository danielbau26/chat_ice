package controllers;

import java.util.List;

import com.zeroc.Ice.Current;

import Chat.ChatError;
import Chat.ChatServer;
import Chat.ClientCallbackPrx;
import Chat.NicknameInUse;
import model.Room;
import model.User;
import services.ChatServices;
import Chat.FileChunk;
import Chat.AudioEndpoint;

public class IceController implements ChatServer {

    private final ChatServices chatServices;

    public IceController(ChatServices chatServices) {
        this.chatServices = chatServices;
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
    // Por ahora solo cumplen el contrato para que el proyecto compile.
    // La lógica se implementa en CallServices (siguiente paso).

    @Override
    public void startCall(String caller, String callee, int udpPort, Current current) throws ChatError {
        throw notImplemented();
    }

    @Override
    public AudioEndpoint acceptCall(String callee, String caller, int udpPort, Current current) throws ChatError {
        throw notImplemented();
    }

    @Override
    public void rejectCall(String callee, String caller, Current current) throws ChatError {
        throw notImplemented();
    }

    @Override
    public void hangup(String nickname, Current current) throws ChatError {
        throw notImplemented();
    }

    @Override
    public AudioEndpoint[] joinGroupCall(String nickname, String roomName, int udpPort, Current current) throws ChatError {
        throw notImplemented();
    }

    @Override
    public void leaveGroupCall(String nickname, String roomName, Current current) throws ChatError {
        throw notImplemented();
    }

    /**
     * Error temporal mientras las llamadas no estén implementadas
     */
    private ChatError notImplemented() {
        ChatError error = new ChatError();
        error.reason = "Las llamadas de voz aún no están implementadas";
        return error;
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