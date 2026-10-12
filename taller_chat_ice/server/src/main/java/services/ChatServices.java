package services;

import java.util.ArrayList;
import java.util.List;

import com.zeroc.Ice.LocalException;
import daos.RoomDao;
import daos.UserDao;
import model.Room;
import model.User;
import Chat.FileChunk;
import Chat.ClientCallbackPrx;

public class ChatServices {

    private final UserDao userDao;
    private final RoomDao roomDao;

    public ChatServices() {
        this.userDao = new UserDao();
        this.roomDao = new RoomDao();
    }

    public User login(String nickname, ClientCallbackPrx callback) throws Exception {

        User user;

        synchronized (this) {
            user = new User(nickname, callback);
            userDao.save(user);
        }

        notifyPresence();

        return user;
    }

    public void logout(String nickname) throws Exception {
        synchronized (this) {
            User user = requireUser(nickname);
            nickname = user.getNickname();

            List<Room> rooms = roomDao.findAll();

            for (int i = 0; i < rooms.size(); i++) {
                Room room = rooms.get(i);
                room.removeMember(nickname);
            }

            userDao.delete(nickname);
        }

        notifyPresence();
    }

    public synchronized List<User> getUsers() throws Exception {
        return userDao.findAll();
    }

    public synchronized Room createRoom(String nickname, String roomName) throws Exception {
        requireUser(nickname);

        Room room = new Room(roomName);

        room.addMember(nickname);

        return roomDao.save(room);
    }

    public synchronized List<Room> getRooms() throws Exception {
        return roomDao.findAll();
    }

    public synchronized void joinRoom(String nickname, String roomName) throws Exception {
        requireUser(nickname);

        Room room = requireRoom(roomName);

        room.addMember(nickname);
    }

    public synchronized void leaveRoom(String nickname, String roomName) throws Exception {
        requireUser(nickname);

        Room room = requireRoom(roomName);

        if (!room.hasMember(nickname)) {
            throw new IllegalStateException("El usuario no pertenece a esta sala");
        }

        room.removeMember(nickname);
    }

    /**
     * Busca un usuario y comprueba que esté conectado
     * @param nickname - del usuario a buscar
     * @return - el usuario conectado con el nickname solicitado o null
     * @throws Exception
     */
    private User requireUser(String nickname) throws Exception {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new IllegalArgumentException("El nickname no puede estar vacío");
        }

        User user = userDao.findById(nickname);

        if (user == null) {
            throw new IllegalStateException("El usuario no está conectado");
        }

        return user;
    }

    /**
     * Busca la sala por el nombre para sabre si existe
     * @param roomName - el nombre de la sala
     * @return - la sala relacionada al nombre o null
     * @throws Exception
     */
    private Room requireRoom(String roomName) throws Exception {
        if (roomName == null || roomName.trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre de la sala no puede estar vacío");
        }

        Room room = roomDao.findById(roomName);

        if (room == null) {
            throw new IllegalStateException("La sala no existe");
        }

        return room;
    }

    /**
     * Enviar un mensaje privado desde un usuario a otro
     * @param sender - nickname de quien envia
     * @param recipient - nickname de quien recibe
     * @param message- contenido del mensaje
     * @throws Exception
     */
    public void sendPrivateMessage(String sender, String recipient, String message) throws Exception {

        User recipientUser;

        synchronized (this) {
            User senderUser = requireUser(sender);
            recipientUser = requireUser(recipient);

            if (message == null || message.trim().isEmpty()) {
                throw new IllegalArgumentException("El mensaje no puede estar vacío");
            }

            sender = senderUser.getNickname();
        }

        try {
            recipientUser.getCallback().privateMessage(sender, message);
        } catch (LocalException e) {
            throw new IllegalStateException("No se pudo entregar el mensaje al destinatario", e);
        }
    }

    /**
     * Enviar un mensaje de un usuario a una sala
     * @param sender - nickname de quien envia
     * @param roomName - nombre de la sala
     * @param message - contenido del mensaje
     * @throws Exception
     */
    public void sendRoomMessage(String sender, String roomName, String message) throws Exception {

        //usurios que recibiran el mensaje
        List<User> recipients = new ArrayList<>();

        synchronized (this) {
            //usuario que envio el mensaje
            User senderUser = requireUser(sender);
            //Sala a donde se envio el mensaje
            Room room = requireRoom(roomName);

            sender = senderUser.getNickname();
            roomName = room.getName();

            if (!room.hasMember(sender)) {
                throw new IllegalStateException("El usuario no pertenece a esta sala");
            }

            if (message == null || message.trim().isEmpty()) {
                throw new IllegalArgumentException("El mensaje no puede estar vacío");
            }

            //nombres de los integrantes de la sala
            List<String> members = room.getMembers();

            for (int i = 0; i < members.size(); i++) {
                String nickname = members.get(i);

                if (!nickname.equals(sender)) {
                    User user = userDao.findById(nickname);

                    if (user != null) {
                        recipients.add(user);
                    }
                }
            }
        }

        //registra si hay algun error al enviar algun mensaje
        boolean deliveryFailed = false;

        for (int i = 0; i < recipients.size(); i++) {
            User user = recipients.get(i);

            try {
                user.getCallback().roomMessage(sender, roomName, message);
            } catch (LocalException e) {
                deliveryFailed = true;
            }
        }

        if (deliveryFailed) {
            throw new IllegalStateException("No se pudo entregar el mensaje a todos los integrantes");
        }
    }
    /**
     * Reenvía un bloque de archivo a un usuario en privado.
     * El servidor no guarda el archivo: valida el bloque y se lo pasa al destinatario.
     * Se envía roomName vacío ("") para que el receptor sepa que es privado.
     */
    public void sendFileChunkToUser(String sender, String recipient, FileChunk chunk) throws Exception {

        User recipientUser;

        synchronized (this) {
            User senderUser = requireUser(sender);
            recipientUser = requireUser(recipient);
            validateChunk(chunk);

            sender = senderUser.getNickname();
        }

        //Se llama al callback fuera del synchronized para no bloquear a los demás
        //usuarios mientras el bloque viaja por la red
        try {
            recipientUser.getCallback().fileChunk(sender, "", chunk);
        } catch (LocalException e) {
            throw new IllegalStateException("No se pudo entregar el archivo al destinatario", e);
        }
    }

    /**
     * Reenvía un bloque de archivo a todos los miembros de una sala, menos al emisor.
     * Solo un miembro de la sala puede enviarle archivos.
     */
    public void sendFileChunkToRoom(String sender, String roomName, FileChunk chunk) throws Exception {

        List<User> recipients;

        synchronized (this) {
            User senderUser = requireUser(sender);
            Room room = requireRoom(roomName);

            sender = senderUser.getNickname();
            roomName = room.getName();

            if (!room.hasMember(sender)) {
                throw new IllegalStateException("El usuario no pertenece a esta sala");
            }

            validateChunk(chunk);

            recipients = collectRoomRecipients(room, sender);
        }

        boolean deliveryFailed = false;

        for (int i = 0; i < recipients.size(); i++) {
            User user = recipients.get(i);

            try {
                user.getCallback().fileChunk(sender, roomName, chunk);
            } catch (LocalException e) {
                deliveryFailed = true;
            }
        }

        if (deliveryFailed) {
            throw new IllegalStateException("No se pudo entregar el archivo a todos los integrantes");
        }
    }
    /**
     * Devuelve los usuarios conectados de una sala, excepto el emisor.
     * Debe llamarse dentro de un bloque synchronized.
     */
    private List<User> collectRoomRecipients(Room room, String sender) throws Exception {
        List<User> recipients = new ArrayList<>();
        List<String> members = room.getMembers();

        for (int i = 0; i < members.size(); i++) {
            String nickname = members.get(i);

            if (!nickname.equals(sender)) {
                User user = userDao.findById(nickname);

                if (user != null) {
                    recipients.add(user);
                }
            }
        }

        return recipients;
    }

    /**
     * Verifica que el bloque tenga datos y metadatos coherentes
     */
    private void validateChunk(FileChunk chunk) {
        if (chunk == null || chunk.data == null) {
            throw new IllegalArgumentException("El bloque del archivo está vacío");
        }

        if (chunk.fileName == null || chunk.fileName.trim().isEmpty()) {
            throw new IllegalArgumentException("El archivo no tiene nombre");
        }

        if (chunk.transferId == null || chunk.transferId.trim().isEmpty()) {
            throw new IllegalArgumentException("El bloque no tiene identificador de transferencia");
        }

        if (chunk.totalChunks <= 0 || chunk.index < 0 || chunk.index >= chunk.totalChunks) {
            throw new IllegalArgumentException("Índice de bloque inválido");
        }
    }

    /**
     * Obtiene los nombres de los usuarios que se encuentran conectados y los envia a cada usuario conectado
     * @throws Exception
     */
    private void notifyPresence() throws Exception {
        List<User> users;
        String[] nicknames;

        synchronized (this) {
            users = userDao.findAll();
            nicknames = new String[users.size()];

            for (int i = 0; i < users.size(); i++) {
                nicknames[i] = users.get(i).getNickname();
            }
        }

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);

            try {
                user.getCallback().presenceChanged(nicknames);
            } catch (com.zeroc.Ice.LocalException e) {
                System.err.println("No se pudo notificar a " + user.getNickname());
            }
        }
    }

    public void removeDisconnectedUser(User disconnectedUser) throws Exception {

        synchronized (this) {
            String nickname = disconnectedUser.getNickname();
            User currentUser = userDao.findById(nickname);

            if (currentUser != disconnectedUser) {
                return;
            }

            List<Room> rooms = roomDao.findAll();

            for (int i = 0; i < rooms.size(); i++) {
                rooms.get(i).removeMember(nickname);
            }

            userDao.delete(nickname);
        }

        notifyPresence();
    }
}