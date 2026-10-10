package model;

import Chat.ClientCallbackPrx;

public class User {

    private String nickname;
    //Es como notificacion
    private ClientCallbackPrx callback;

    public User(String nickname, ClientCallbackPrx callback) {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new IllegalArgumentException("El nickname no puede estar vacío");
        }

        if (callback == null) {
            throw new IllegalArgumentException("El callback no puede ser null");
        }

        this.nickname = nickname.trim();
        this.callback = callback;
    }



    public String getNickname() {
        return nickname;
    }

    public ClientCallbackPrx getCallback() {
        return callback;
    }
}