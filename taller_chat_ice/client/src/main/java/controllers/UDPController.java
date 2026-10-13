package controllers;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import audio.AudioReceiver;
import audio.AudioSender;

/**
 * Maneja el canal de voz UDP del cliente (RF-05 / RF-06).
 *
 * Es el dueño del DatagramSocket y de los hilos de audio:
 *  - open():       crea el socket en un puerto libre. Ese puerto se informa al servidor
 *                  por Ice (startCall / acceptCall / joinGroupCall) para que los demás
 *                  sepan a dónde enviar el audio.
 *  - addTarget():  agrega un destino (IP:puerto de otro participante).
 *  - startAudio(): empieza a capturar/enviar y a recibir/reproducir.
 *  - setMuted():   silencia o activa el micrófono.
 *  - close():      cuelga: detiene los hilos, cierra el socket y libera micrófono y parlantes.
 *
 * El mismo socket se usa para enviar y recibir. Así, el puerto de origen de los paquetes
 * que envías es el mismo en el que recibes, y los demás pueden identificarte por él.
 *
 * Los métodos son synchronized porque los llaman hilos distintos: el hilo de la consola
 * (comandos /call, /hangup...) y los hilos de Ice (callbacks callAccepted, callEnded...).
 */
public class UDPController {

    /** Destinos del audio. CopyOnWriteArrayList: el hilo emisor la recorre mientras otros la modifican */
    private final List<InetSocketAddress> targets = new CopyOnWriteArrayList<>();

    private DatagramSocket socket;
    private AudioSender sender;
    private AudioReceiver receiver;
    private boolean muted;

    /**
     * Abre el socket UDP (si no estaba abierto) en un puerto libre que elige el sistema
     * @return - el puerto local donde se recibirá el audio
     */
    public synchronized int open() throws SocketException {
        if (socket == null) {
            socket = new DatagramSocket(0);
            //Buffer de recepción más grande para no perder paquetes si hay varias personas
            socket.setReceiveBufferSize(64 * 1024);
        }

        return socket.getLocalPort();
    }

    public synchronized boolean isOpen() {
        return socket != null;
    }

    /**
     * Empieza a enviar y recibir audio. Si ya estaba iniciado, no hace nada.
     */
    public synchronized void startAudio() {
        if (socket == null) {
            throw new IllegalStateException("El canal de voz no está abierto");
        }

        if (receiver == null) {
            receiver = new AudioReceiver(socket);
            receiver.start();
        }

        if (sender == null) {
            sender = new AudioSender(socket, targets);
            sender.setMuted(muted);
            sender.start();
        }
    }

    /**
     * Agrega un destino al que se enviará el audio
     */
    public synchronized void addTarget(String host, int port) {
        InetSocketAddress address = new InetSocketAddress(host, port);

        if (!targets.contains(address)) {
            targets.add(address);
        }
    }

    /**
     * Deja de enviar audio a un destino (alguien salió de la llamada grupal)
     */
    public synchronized void removeTarget(String host, int port) {
        targets.remove(new InetSocketAddress(host, port));
    }

    public synchronized void setMuted(boolean muted) {
        this.muted = muted;

        if (sender != null) {
            sender.setMuted(muted);
        }
    }

    public synchronized boolean isMuted() {
        return muted;
    }

    /**
     * Termina la llamada en este cliente: detiene el audio, cierra el socket y
     * libera el micrófono y los parlantes. Se puede volver a llamar a open() después.
     */
    public synchronized void close() {
        if (sender != null) {
            sender.stop();
            sender = null;
        }

        if (receiver != null) {
            receiver.stop();
            receiver = null;
        }

        if (socket != null) {
            //Cerrar el socket desbloquea el receive() del hilo receptor
            socket.close();
            socket = null;
        }

        targets.clear();
        muted = false;
    }
}
