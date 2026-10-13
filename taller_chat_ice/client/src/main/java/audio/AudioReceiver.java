package audio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import ui.Console;

/**
 * Recibe audio por UDP y lo reproduce en los parlantes (RF-05 / RF-06).
 *
 * Usa DOS hilos:
 *  - Hilo de recepción: se bloquea en socket.receive(); cada datagrama que llega se
 *    guarda en la cola de quien lo envió (se identifica por su IP:puerto de origen).
 *  - Hilo de reproducción: cada 20 ms toma un paquete de cada persona, los MEZCLA
 *    (suma las muestras) y escribe el resultado en los parlantes (SourceDataLine).
 *
 * ¿Por qué mezclar? En una llamada grupal llega audio de varias personas al mismo
 * tiempo. Si se reprodujeran los paquetes uno tras otro, las voces se cortarían y el
 * audio se atrasaría. Sumando las muestras, se escuchan todos a la vez, como en la vida real.
 * En una llamada 1 a 1 simplemente hay una sola persona en la mezcla.
 */
public class AudioReceiver {

    private final DatagramSocket socket;

    /** Una cola de audio por cada persona que envía (clave: su IP:puerto) */
    private final Map<SocketAddress, Source> sources = new ConcurrentHashMap<>();

    private volatile boolean running;
    private volatile SourceDataLine speakers;
    private Thread receiveThread;
    private Thread playbackThread;

    public AudioReceiver(DatagramSocket socket) {
        this.socket = socket;
    }

    public void start() {
        running = true;

        receiveThread = new Thread(this::receiveLoop, "Audio-Receiver");
        receiveThread.setDaemon(true);
        receiveThread.start();

        playbackThread = new Thread(this::playbackLoop, "Audio-Playback");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    /**
     * Detiene los hilos. El socket lo cierra UDPController (eso desbloquea receive()).
     */
    public void stop() {
        running = false;

        SourceDataLine line = speakers;

        if (line != null) {
            line.stop();
            line.close();
        }

        if (playbackThread != null) {
            playbackThread.interrupt();
        }

        if (receiveThread != null) {
            receiveThread.interrupt();
        }
    }

    // ===================== RECEPCIÓN =====================

    private void receiveLoop() {
        byte[] buffer = new byte[AudioConfig.PACKET_BYTES];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running) {
            try {
                packet.setLength(buffer.length);
                socket.receive(packet);

                if (packet.getLength() <= AudioConfig.HEADER_BYTES) {
                    continue;
                }

                int sequence = ByteBuffer.wrap(buffer).getInt(0);
                byte[] frame = Arrays.copyOfRange(buffer, AudioConfig.HEADER_BYTES, packet.getLength());

                sources.computeIfAbsent(packet.getSocketAddress(), address -> new Source())
                        .offer(sequence, frame);

            } catch (IOException e) {
                //El socket se cerró al colgar: fin normal del hilo
                if (running) {
                    Console.show("Error recibiendo audio: " + e.getMessage());
                }
                return;
            }
        }
    }

    // ===================== REPRODUCCIÓN =====================

    private void playbackLoop() {
        try {
            SourceDataLine line = AudioSystem.getSourceDataLine(AudioConfig.FORMAT);
            line.open(AudioConfig.FORMAT, AudioConfig.LINE_BUFFER_BYTES);
            line.start();
            speakers = line;

            byte[] output = new byte[AudioConfig.FRAME_BYTES];

            while (running) {
                discardSilentSources();

                //Toma el siguiente paquete de cada persona que está hablando
                List<byte[]> frames = new ArrayList<>();

                for (Source source : sources.values()) {
                    byte[] frame = source.poll();

                    if (frame != null) {
                        frames.add(frame);
                    }
                }

                if (frames.isEmpty()) {
                    //Nadie está enviando en este momento: se espera un poco
                    Thread.sleep(5);
                    continue;
                }

                int length = mix(frames, output);

                //write se bloquea si el buffer de los parlantes está lleno: eso marca el ritmo
                line.write(output, 0, length);
            }

        } catch (LineUnavailableException | IllegalArgumentException e) {
            Console.show("Error: no se pudieron abrir los parlantes (" + e.getMessage() + ")");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

        } finally {
            SourceDataLine line = speakers;

            if (line != null) {
                line.close();
            }
        }
    }

    /**
     * Mezcla varios paquetes de audio en uno solo sumando sus muestras.
     *
     * Cada muestra es un entero de 16 bits con signo en little-endian (2 bytes).
     * La suma puede pasarse del rango [-32768, 32767]; en ese caso se recorta
     * ("clipping") para evitar que el número dé la vuelta y suene como un chasquido.
     *
     * @param frames - paquetes a mezclar (uno por persona)
     * @param output - donde se escribe el resultado
     * @return - cantidad de bytes válidos en output
     */
    static int mix(List<byte[]> frames, byte[] output) {
        int length = 0;

        for (byte[] frame : frames) {
            length = Math.max(length, Math.min(frame.length, output.length));
        }

        length -= length % 2; //número par de bytes (muestras completas)

        for (int i = 0; i < length; i += 2) {
            int sum = 0;

            for (byte[] frame : frames) {
                if (i + 1 < frame.length) {
                    //little-endian: primero el byte bajo, luego el alto (con signo)
                    sum += (short) ((frame[i] & 0xFF) | (frame[i + 1] << 8));
                }
            }

            if (sum > Short.MAX_VALUE) {
                sum = Short.MAX_VALUE;
            } else if (sum < Short.MIN_VALUE) {
                sum = Short.MIN_VALUE;
            }

            output[i] = (byte) sum;
            output[i + 1] = (byte) (sum >> 8);
        }

        return length;
    }

    /**
     * Deja de mezclar a quien lleva un rato sin enviar audio (por ejemplo, salió de la llamada)
     */
    private void discardSilentSources() {
        long now = System.currentTimeMillis();
        sources.values().removeIf(source -> now - source.lastPacketTime > AudioConfig.SOURCE_TIMEOUT_MS);
    }

    /**
     * Audio pendiente de una persona (una "fuente")
     */
    static class Source {

        /** Paquetes en espera. Tamaño fijo: si se llena, se descarta el más viejo */
        private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(AudioConfig.MAX_QUEUED_FRAMES);

        private int lastSequence = -1;
        volatile long lastPacketTime = System.currentTimeMillis();

        /**
         * Agrega un paquete. Se descartan los que llegan tarde o repetidos
         * (secuencia menor o igual a la última recibida), porque UDP puede desordenarlos.
         */
        synchronized void offer(int sequence, byte[] frame) {
            lastPacketTime = System.currentTimeMillis();

            //Si la secuencia "vuelve a empezar" (el otro reinició el envío) se acepta
            boolean restarted = lastSequence - sequence > 1000;

            if (sequence <= lastSequence && !restarted) {
                return;
            }

            lastSequence = sequence;

            //Si hay demasiado acumulado se descarta lo más viejo para no aumentar el retraso
            while (!queue.offer(frame)) {
                queue.poll();
            }
        }

        byte[] poll() {
            return queue.poll();
        }
    }
}
