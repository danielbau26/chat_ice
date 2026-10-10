module Chat
{
    sequence<string> StringList;

    //Excepciones basicas
    exception ChatError{
        string reason;
    };

    //Es para cuando un nickname este ocupado (exception)
    exception NicknameInUse{
        string nickname;
    };

    //notificaciones que recibe cada cliente
    interface ClientCallback{
        void presenceChanged(StringList users);

        void privateMessage(
            string sender,
            string text
        );

        void roomMessage(
            string sender,
            string roomName,
            string text
        );

        //Permitir comprobar si el cliente esta en linea
        void ping();
    };

    //Las operaciones que puede hacer el cliente
    interface ChatServer{
        void login(
            string nickname,
            ClientCallback* callback
        ) throws NicknameInUse, ChatError;

        void logout(
            string nickname
        ) throws ChatError;

        StringList getUsers();

        void sendPrivateMessage(
            string sender,
            string recipient,
            string text
        ) throws ChatError;

        void createRoom(
            string nickname,
            string roomName
        ) throws ChatError;

        StringList getRooms();

        void joinRoom(
            string nickname,
            string roomName
        ) throws ChatError;

        void leaveRoom(
            string nickname,
            string roomName
        ) throws ChatError;

        void sendRoomMessage(
            string sender,
            string roomName,
            string text
        ) throws ChatError;
    };
};