package model;

import java.util.ArrayList;
import java.util.List;

public class Room {

    private final String name;
    private final List<String> members;

    public Room(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre de la sala no puede estar vacío");
        }

        this.name = name.trim();
        this.members = new ArrayList<>();
    }

    public String getName() {
        return name;
    }

    public synchronized void addMember(String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new IllegalArgumentException("El nickname no puede estar vacío");
        }

        if (!members.contains(nickname)) {
            members.add(nickname);
        }
    }

    public synchronized void removeMember(String nickname) {
        members.remove(nickname);
    }

    public synchronized boolean hasMember(String nickname) {
        return members.contains(nickname);
    }

    public synchronized List<String> getMembers() {
        return new ArrayList<>(members);
    }
}
