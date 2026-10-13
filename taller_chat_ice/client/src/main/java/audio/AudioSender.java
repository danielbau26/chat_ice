package audio;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;

import ui.Console;

/**
 * Captura audio del micrófono y lo envía por UDP (RF-05 / RF-06).
 *
 * Corre en su propio hilo:
 *  1. Lee 20 ms de audio del micrófono (TargetDataLine). La lectura se bloquea hasta
 *     que el micrófono produce esos 20 ms, así que el ciclo va "al ritmo" del audio.
 *  2. Arma un datagrama: [número de secuencia (4 bytes)][640 bytes de audio].
 *  3. Lo envía con DatagramSocket a CADA destino de la lista (malla P2P: en una llamada
 *     1 a 1 hay un destino; en una grupal, uno por cada participante).
 *
 * No hay confirmaciones ni retransmisiones: si un paquete se pierde, se pierden 20 ms
 * de audio y ya. Para voz en tiempo real es mejor eso que esperar (lo que haría TCP).
 */
public class AudioSender implements Runnable {

    private final DatagramSocket socket;

    /** Destinos actuales. La lista la maneja UDPController (CopyOnWriteArrayList, segura entre hilos) */
    private final List<InetSocketAddress> targets;

    private volatile boolean running;
    private volatile boolean muted;
    private volatile TargetDataLine microphone;
    private Thread thread;

    public AudioSender(DatagramSocket socket, List<InetSocketAddress> targets) {
        this.socket = socket;
        this.targets = targets;
    }

    public void start() {
        running = true;
        thread = new Thread(this, "Audio-Sender");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;

        //Cerrar el micrófono desbloquea el read() en el que pueda estar esperando el hilo
        TargetDataLine mic = microphone;

        if (mic != null) {
            mic.stop();
            mic.close();
        }

        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Silencia o activa el micrófono. Con mute se sigue leyendo el micrófono (para que su
     * buffer no se llene) pero no se envía nada.
     */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    @Override
    public void run() {
        try {
            TargetDataLine mic = AudioSystem.getTargetDataLine(AudioConfig.FORMAT);
            mic.open(AudioConfig.FORMAT, AudioConfig.LINE_BUFFER_BYTES);
            mic.start();
            microphone = mic;

            byte[] packet = new byte[AudioConfig.PACKET_BYTES];
            ByteBuffer header = ByteBuffer.wrap(packet);
            int sequence = 0;

            while (running) {
                //Lee 20 ms de audio justo después de la cabecera
                int read = mic.read(packet, AudioConfig.HEADER_BYTES, AudioConfig.FRAME_BYTES);

                if (read <= 0 || muted) {
                    continue;
                }

                header.putInt(0, sequence++);

                for (InetSocketAddress target : targets) {
                    socket.send(new DatagramPacket(packet, AudioConfig.HEADER_BYTES + read, target));
                }
            }

        } catch (LineUnavailableException | IllegalArgumentException e) {
            Console.show("Error: no se pudo abrir el micrófono (" + e.getMessage() + ")");

        } catch (IOException e) {
            //El socket se cerró al colgar: fin normal del hilo
            if (running) {
                Console.show("Error enviando audio: " + e.getMessage());
            }

        } finally {
            TargetDataLine mic = microphone;

            if (mic != null) {
                mic.close();
            }
        }
    }
}
