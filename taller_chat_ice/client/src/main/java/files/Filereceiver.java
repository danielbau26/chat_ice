package files;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import Chat.FileChunk;
import ui.Console;

/**
 * Recibe los bloques de archivo que llegan por el callback y reconstruye los archivos.
 *
 * Pueden llegar varios archivos a la vez (de distintos usuarios o salas), así que cada
 * transferencia se identifica por su transferId. Los bloques se guardan en memoria en la
 * posición indicada por su índice; cuando están todos, se escriben en orden en disco y
 * se verifica que el tamaño total coincida con fileSize (integridad: sin truncamiento).
 *
 * Los métodos de esta clase los ejecutan los hilos de Ice, por eso se usa
 * ConcurrentHashMap y cada transferencia sincroniza su propio estado.
 *
 * Los archivos se guardan en: archivos_recibidos/<nickname>/
 */
public class FileReceiver {

    private static final String BASE_DIR = "archivos_recibidos";

    /**
     * Transferencias en curso: transferId -> archivo en reconstrucción
     */
    private final Map<String, IncomingFile> transfers = new ConcurrentHashMap<>();

    /**
     * Nickname del usuario, para separar las carpetas si hay varios clientes en el mismo PC
     */
    private volatile String owner = "sin_sesion";

    public void setOwner(String nickname) {
        this.owner = nickname;
    }

    /**
     * Procesa un bloque recibido
     *
     * @param sender   - quien envía
     * @param roomName - sala de origen, o "" si es privado
     * @param chunk    - bloque con datos y metadatos
     */
    public void receive(String sender, String roomName, FileChunk chunk) {

        String origin = roomName.isEmpty() ? "[Privado] " + sender : "[#" + roomName + "] " + sender;

        //computeIfAbsent es atómico: si dos bloques del mismo archivo llegan a la vez,
        //solo se crea un IncomingFile
        IncomingFile incoming = transfers.computeIfAbsent(chunk.transferId, id -> {
            Console.show(origin + " te está enviando " + chunk.fileName + " (" + chunk.fileSize + " bytes)...");
            return new IncomingFile(chunk.fileName, chunk.fileSize, chunk.totalChunks);
        });

        boolean complete;

        try {
            complete = incoming.add(chunk.index, chunk.data);
        } catch (IllegalArgumentException e) {
            transfers.remove(chunk.transferId);
            Console.show("Error recibiendo " + chunk.fileName + ": " + e.getMessage());
            return;
        }

        if (!complete) {
            return;
        }

        //Ya llegaron todos los bloques: se saca de las transferencias en curso y se guarda
        transfers.remove(chunk.transferId);

        try {
            Path saved = incoming.save(Paths.get(BASE_DIR, owner));
            Console.show(origin + " te envió " + chunk.fileName + " -> guardado en " + saved.toAbsolutePath());
        } catch (IOException | IllegalStateException e) {
            Console.show("Error guardando " + chunk.fileName + ": " + e.getMessage());
        }
    }
}