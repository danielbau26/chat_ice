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

public class IceController implements ChatServer {

    private final ChatServices chatServices;

    public IceController(ChatServices chatServices) {
        this.chatServices = chatServices;
    }


    //No entedí que es current
    @Override
    public void login(String nickname, ClientCallbackPrx callback, Current current) throws NicknameInUse, ChatError {

        try {
            chatServices.login(nickname, callback);
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
            throw new RuntimeException("No se pudo consultar los usuarios", e);
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
            throw new RuntimeException(
                    "No se pudo consultar las salas", e
            );
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

    private ChatError createError(Exception e) {
        ChatError error = new ChatError();

        error.reason = e.getMessage();

        if (error.reason == null) {
            error.reason = "Error al procesar la operación";
        }

        return error;
    }
}