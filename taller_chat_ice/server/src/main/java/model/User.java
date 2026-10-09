package model;

public class User {

    private final String nickname;

    public User(String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "El nickname no puede estar vacío"
            );
        }

        this.nickname = nickname.trim();
    }

    public String getNickname() {
        return nickname;
    }
}