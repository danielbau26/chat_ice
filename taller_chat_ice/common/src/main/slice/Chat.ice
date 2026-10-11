module Chat
{
    sequence<string> StringList;

    // NUEVO: una lista de bytes. Así se representan los datos de un archivo en Slice.
    // En Java se convierte en byte[]
    sequence<byte> FileData;

    // NUEVO: un bloque (pedazo) de archivo con su información
    struct FileChunk {
        string transferId;   // id único del envío, para saber a qué archivo pertenece el bloque
        string fileName;     // nombre del archivo, ej: "foto.png"
        long fileSize;       // tamaño total del archivo en bytes, para verificar al final
        int index;           // número de este bloque (0, 1, 2...)
        int totalChunks;     // cuántos bloques son en total
        FileData data;       // los bytes de este pedazo
    };

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

        // NUEVO: el servidor le entrega un bloque de archivo al cliente.
        // roomName va vacío ("") si el archivo es privado
        void fileChunk(
            string sender,
            string roomName,
            FileChunk chunk
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

        // NUEVO: el cliente manda un bloque de archivo a un usuario
        void sendFileChunkToUser(
            string sender,
            string recipient,
            FileChunk chunk
        ) throws ChatError;

        // NUEVO: el cliente manda un bloque de archivo a una sala
        void sendFileChunkToRoom(
            string sender,
            string roomName,
            FileChunk chunk
        ) throws ChatError;
    };
};