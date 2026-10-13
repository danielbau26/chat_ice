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
     * Si una transferencia pasa este tiempo sin recibir bloques (por ejemplo, el emisor
     * se desconectó a la mitad), se descarta para no ocupar memoria para siempre
     */
    private static final long TRANSFER_TIMEOUT_MS = 60_000;

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

        //Antes de procesar, se descartan las transferencias abandonadas
        discardStaleTransfers();

        //Valida los metadatos antes de reservar memoria para el archivo
        String invalid = validate(chunk);

        if (invalid != null) {
            Console.show("Error: bloque inválido de " + chunk.fileName + " (" + invalid + ")");
            return;
        }

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
    /**
     * Comprueba que los metadatos del bloque sean coherentes.
     * Evita, por ejemplo, que un totalChunks enorme cree un arreglo gigante en memoria.
     * @param chunk - bloque recibido
     * @return - null si es válido, o el motivo por el que no lo es
     */
    private String validate(FileChunk chunk) {
        if (chunk.transferId == null || chunk.transferId.isEmpty()) {
            return "sin identificador de transferencia";
        }

        if (chunk.data == null || chunk.data.length > FileSender.CHUNK_SIZE) {
            return "tamaño de bloque inválido";
        }

        if (chunk.fileSize < 0) {
            return "tamaño de archivo negativo";
        }

        //Debe coincidir con la cuenta que hace FileSender (división redondeando hacia arriba)
        long expectedChunks = Math.max(1, (chunk.fileSize + FileSender.CHUNK_SIZE - 1) / FileSender.CHUNK_SIZE);

        if (chunk.totalChunks != expectedChunks) {
            return "número de bloques no coincide con el tamaño";
        }

        return null;
    }

    /**
     * Elimina las transferencias que llevan más de TRANSFER_TIMEOUT_MS sin recibir bloques
     */
    private void discardStaleTransfers() {
        long now = System.currentTimeMillis();

        transfers.entrySet().removeIf(entry -> {
            IncomingFile incoming = entry.getValue();

            if (now - incoming.getLastUpdate() > TRANSFER_TIMEOUT_MS) {
                Console.show("Transferencia de " + incoming.getFileName() + " incompleta: se descartó");
                return true;
            }

            return false;
        });
    }

    /**
     * Estado de un archivo que se está reconstruyendo
     */
    private static class IncomingFile {

        private final String fileName;
        private final long fileSize;
        private final byte[][] chunks;
        private int received;

        /** Momento en que llegó el último bloque (lo leen otros hilos, por eso volatile) */
        private volatile long lastUpdate;

        IncomingFile(String fileName, long fileSize, int totalChunks) {
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.chunks = new byte[totalChunks][];
            this.received = 0;
            this.lastUpdate = System.currentTimeMillis();
        }

        long getLastUpdate() {
            return lastUpdate;
        }

        String getFileName() {
            return fileName;
        }

        /**
         * Guarda un bloque en su posición
         *
         * @return - true si ya llegaron todos los bloques
         */
        synchronized boolean add(int index, byte[] data) {
            if (index < 0 || index >= chunks.length) {
                throw new IllegalArgumentException("bloque fuera de rango");
            }

            lastUpdate = System.currentTimeMillis();

            //Si un bloque llegara repetido, no se cuenta dos veces
            if (chunks[index] == null) {
                chunks[index] = data;
                received++;
            }

            return received == chunks.length;
        }
        /**
         * Escribe los bloques en orden en el disco y verifica el tamaño
         * @param dir - carpeta destino
         * @return - ruta del archivo guardado
         */
        synchronized Path save(Path dir) throws IOException {
            Files.createDirectories(dir);

            Path target = uniquePath(dir, safeName(fileName));
            long written = 0;

            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
                for (int i = 0; i < chunks.length; i++) {
                    out.write(chunks[i]);
                    written += chunks[i].length;
                }
            }

            //Verificación de integridad: los bytes escritos deben coincidir con el tamaño original
            if (written != fileSize) {
                Files.deleteIfExists(target);
                throw new IllegalStateException("tamaño incorrecto (esperado " + fileSize + ", recibido " + written + ")");
            }

            return target;
        }

        /**
         * Reemplaza caracteres no válidos en nombres de archivo de Windows
         * y evita rutas como "../../algo"
         */
        private static String safeName(String name) {
            String clean = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            return clean.isEmpty() ? "archivo" : clean;
        }

        /**
         * Si ya existe un archivo con ese nombre, agrega (1), (2)... para no sobrescribirlo
         */
        private static Path uniquePath(Path dir, String name) {
            Path candidate = dir.resolve(name);

            if (!Files.exists(candidate)) {
                return candidate;
            }

            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";

            int counter = 1;

            while (Files.exists(candidate)) {
                candidate = dir.resolve(base + " (" + counter + ")" + ext);
                counter++;
            }

            return candidate;
        }
    }
}


