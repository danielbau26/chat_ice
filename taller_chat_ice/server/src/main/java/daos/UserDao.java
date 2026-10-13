package daos;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import model.User;

public class UserDao implements Dao<User, String> {

    private final ConcurrentHashMap<String, User> users;

    public UserDao() {
        this.users = new ConcurrentHashMap<>();
    }

    @Override
    public User save(User user) throws Exception {
        if (user == null) {
            throw new IllegalArgumentException("El usuario no puede ser null");
        }

        // Guarda solo si el nickname no existe, en un solo paso (thread-safe)
        if (users.putIfAbsent(user.getNickname(), user) != null) {
            throw new IllegalStateException("El nickname ya está en uso");
        }

        return user;
    }

    @Override
    public User findById(String nickname) throws Exception {
        return users.get(nickname);
    }

    @Override
    public List<User> findAll() throws Exception {
        return new ArrayList<>(users.values());
    }

    @Override
    public void update(User user) throws Exception {
        if (user == null) {
            throw new IllegalArgumentException("El usuario no puede ser null");
        }

        User previous = users.replace(user.getNickname(), user);

        if (previous == null) {
            throw new IllegalStateException("El usuario no existe");
        }
    }

    @Override
    public void delete(String nickname) throws Exception {
        users.remove(nickname);
    }
}