package files;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.UUID;

import com.zeroc.Ice.LocalException;

import Chat.ChatError;
import Chat.ChatServerPrx;
import Chat.FileChunk;
import ui.Console;

/**
 * Envía archivos por Ice dividiéndolos en bloques (chunking).
 *
 * Por qué se divide: Ice limita el tamaño máximo de cada mensaje con la propiedad
 * Ice.MessageSizeMax (por defecto 1024 KB). Si se enviara un archivo grande en una sola
 * llamada con sequence<byte>, Ice lanzaría MemoryLimitException. Enviando bloques de
 * 64 KB, cada llamada queda muy por debajo de ese límite sin importar el tamaño del archivo.
 *
 * Cada bloque viaja con metadatos (FileChunk en Chat.ice):
 *  - transferId: identifica a qué envío pertenece el bloque (UUID único por archivo)
 *  - fileName, fileSize: para nombrar el archivo y verificar integridad al final
 *  - index, totalChunks: posición del bloque y cuántos son en total
 */
public class FileSender {

    /** Tamaño de cada bloque: 64 KB */
    public static final int CHUNK_SIZE = 64 * 1024;

    /**
     * Envía el archivo en un hilo aparte para que la consola siga respondiendo
     * mientras se transfiere (el usuario puede seguir chateando).
     *
     * @param chat - proxy del servidor
     * @param sender - nickname de quien envía
     * @param target - "usuario" para privado o "#sala" para una sala
     * @param filePath - ruta del archivo en disco
     */
    public static void sendAsync(ChatServerPrx chat, String sender, String target, String filePath) {
        Thread thread = new Thread(() -> send(chat, sender, target, filePath), "File-Sender");
        thread.setDaemon(true);
        thread.start();
    }

    private static void send(ChatServerPrx chat, String sender, String target, String filePath) {

        //Si el destino empieza por # es una sala; si no, un usuario
        boolean toRoom = target.startsWith("#");
        String destination = toRoom ? target.substring(1) : target;

        Path path;

        try {
            path = Paths.get(stripQuotes(filePath));
        } catch (InvalidPathException e) {
            Console.show("Error: ruta inválida " + filePath);
            return;
        }

        if (!Files.isRegularFile(path)) {
            Console.show("Error: no existe el archivo " + path.toAbsolutePath());
            return;
        }

        try {
            long fileSize = Files.size(path);
            String fileName = path.getFileName().toString();

            //Número de bloques redondeando hacia arriba; mínimo 1 para archivos vacíos
            int totalChunks = (int) Math.max(1, (fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
            String transferId = UUID.randomUUID().toString();

            Console.show("Enviando " + fileName + " (" + fileSize + " bytes, " + totalChunks + " bloques) a " + target + "...");

            try (InputStream in = new BufferedInputStream(Files.newInputStream(path))) {

                byte[] buffer = new byte[CHUNK_SIZE];

                for (int i = 0; i < totalChunks; i++) {

                    //readNBytes lee hasta llenar el buffer o hasta el final del archivo
                    int read = in.readNBytes(buffer, 0, CHUNK_SIZE);

                    FileChunk chunk = new FileChunk();
                    chunk.transferId = transferId;
                    chunk.fileName = fileName;
                    chunk.fileSize = fileSize;
                    chunk.index = i;
                    chunk.totalChunks = totalChunks;
                    //copia solo los bytes leídos (el último bloque suele ser más pequeño)
                    chunk.data = Arrays.copyOf(buffer, read);

                    //Llamada síncrona: no se envía el siguiente bloque hasta que el servidor
                    //terminó de reenviar este, lo que mantiene el orden y evita saturar la red
                    if (toRoom) {
                        chat.sendFileChunkToRoom(sender, destination, chunk);
                    } else {
                        chat.sendFileChunkToUser(sender, destination, chunk);
                    }
                }
            }

            Console.show("[OK] " + fileName + " enviado a " + target);

        } catch (ChatError e) {
            //Error controlado del servidor: destinatario desconectado, no perteneces a la sala, etc.
            Console.show("Error al enviar archivo: " + e.reason);

        } catch (IOException e) {
            Console.show("Error leyendo el archivo: " + e.getMessage());

        } catch (LocalException e) {
            Console.show("Error de conexión al enviar archivo (" + e.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Quita comillas alrededor de la ruta, por si el usuario la escribe como "C:\mis archivos\foto.png"
     */
    private static String stripQuotes(String path) {
        String trimmed = path.trim();

        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }

        return trimmed;
    }
}
