package daos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import model.Room;

public class RoomDao implements Dao<Room, String> {

    private final ConcurrentHashMap<String, Room> rooms;

    public RoomDao() {
        this.rooms = new ConcurrentHashMap<>();
    }

    @Override
    public synchronized Room save(Room room) throws Exception {
        if (room == null) {
            throw new IllegalArgumentException("La sala no puede ser null");
        }

        String name = room.getName();

        if (rooms.containsKey(name)) {
            throw new IllegalStateException("Ya existe una sala con ese nombre");
        }

        rooms.put(name, room);

        return room;
    }

    @Override
    public synchronized Room findById(String name) throws Exception {
        return rooms.get(name);
    }

    @Override
    public synchronized List<Room> findAll() throws Exception {
        return new ArrayList<>(rooms.values());
    }

    @Override
    public synchronized void update(Room room) throws Exception {
        if (room == null) {
            throw new IllegalArgumentException("La sala no puede ser null");
        }

        String name = room.getName();

        if (!rooms.containsKey(name)) {
            throw new IllegalStateException("La sala no existe");
        }

        rooms.put(name, room);
    }

    @Override
    public synchronized void delete(String name) throws Exception {
        rooms.remove(name);
    }
}
