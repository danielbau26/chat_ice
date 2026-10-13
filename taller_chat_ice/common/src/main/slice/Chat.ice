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

    // ===================== LLAMADAS DE VOZ (RF-05 / RF-06) =====================
    // Por Ice solo viaja la SEÑALIZACIÓN (llamar, aceptar, rechazar, colgar).
    // El audio viaja aparte, directo entre clientes por datagramas UDP.

    // Dirección UDP donde un participante recibe audio.
    // El cliente solo informa su puerto: el host lo pone el servidor a partir de la
    // conexión Ice (así no depende de que el cliente adivine su propia IP).
    struct AudioEndpoint {
        string nickname;   // dueño del endpoint
        string host;       // IP donde recibe audio
        int port;          // puerto UDP donde recibe audio
    };

    // Lista de endpoints: participantes de una llamada grupal
    sequence<AudioEndpoint> EndpointList;

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

        // NUEVO (llamadas 1 a 1): alguien te está llamando.
        // Se responde con acceptCall o rejectCall
        void incomingCall(string caller);

        // NUEVO: la persona a la que llamaste aceptó; trae su endpoint UDP
        // para empezar a enviarle audio
        void callAccepted(AudioEndpoint peer);

        // NUEVO: la persona a la que llamaste rechazó la llamada
        void callRejected(string callee);

        // NUEVO: la otra persona colgó, canceló la llamada o se desconectó
        void callEnded(string peer);

        // NUEVO (llamadas grupales): alguien entró a la llamada de una sala;
        // trae su endpoint para empezar a enviarle audio también a él (malla P2P)
        void groupMemberJoined(string roomName, AudioEndpoint member);

        // NUEVO: alguien salió de la llamada de una sala; se deja de enviarle audio
        void groupMemberLeft(string roomName, string nickname);

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

        // ---------- Llamadas 1 a 1 (RF-05) ----------

        // Inicia una llamada: el destinatario recibe incomingCall.
        // udpPort es el puerto donde el que llama recibirá el audio
        void startCall(
            string caller,
            string callee,
            int udpPort
        ) throws ChatError;

        // El destinatario acepta: informa su puerto UDP y recibe el endpoint
        // de quien llamó. A quien llamó le llega callAccepted con el de él
        AudioEndpoint acceptCall(
            string callee,
            string caller,
            int udpPort
        ) throws ChatError;

        // El destinatario rechaza: a quien llamó le llega callRejected
        void rejectCall(
            string callee,
            string caller
        ) throws ChatError;

        // Cuelga (o cancela si aún estaba sonando): al otro le llega callEnded
        void hangup(
            string nickname
        ) throws ChatError;

        // ---------- Llamadas grupales (RF-06, malla P2P) ----------

        // Entra a la llamada de una sala (o la inicia si no hay nadie).
        // Devuelve los endpoints de los que ya están; a ellos les llega groupMemberJoined
        EndpointList joinGroupCall(
            string nickname,
            string roomName,
            int udpPort
        ) throws ChatError;

        // Sale de la llamada de la sala sin afectar a los demás:
        // a los que siguen les llega groupMemberLeft
        void leaveGroupCall(
            string nickname,
            string roomName
        ) throws ChatError;
    };
};