package services;

import java.util.List;

import daos.RoomDao;
import daos.UserDao;
import model.Room;
import model.User;

public class ChatServices {

    private final UserDao userDao;
    private final RoomDao roomDao;

    public ChatServices() {
        this.userDao = new UserDao();
        this.roomDao = new RoomDao();
    }

    public synchronized User login(String nickname) throws Exception {
        User user = new User(nickname);

        return userDao.save(user);
    }

    public synchronized void logout(String nickname) throws Exception {
        requireUser(nickname);

        List<Room> rooms = roomDao.findAll();

        for (int i = 0; i < rooms.size(); i++) {
            Room room = rooms.get(i);
            room.removeMember(nickname);
        }

        userDao.delete(nickname);
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
}