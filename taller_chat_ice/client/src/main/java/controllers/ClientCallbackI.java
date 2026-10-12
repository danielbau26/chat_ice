package controllers;

import com.zeroc.Ice.Current;

import Chat.AudioEndpoint;
import Chat.ClientCallback;
import Chat.FileChunk;
import files.FileReceiver;
import ui.Console;

/**
 * Implementación (servant) de la interfaz ClientCallback definida en Chat.ice.
 *
 * Este objeto vive en el cliente, pero quien invoca sus métodos es el servidor.
 * En el login, el cliente le entrega al servidor un proxy (ClientCallbackPrx) que apunta
 * a este objeto; el servidor lo guarda en el User y lo usa cada vez que necesita
 * notificar algo:
 *
 *  - ChatServices.sendPrivateMessage  -> privateMessage(...)
 *  - ChatServices.sendRoomMessage     -> roomMessage(...)
 *  - ChatServices.notifyPresence      -> presenceChanged(...)
 *  - ChatServices.sendFileChunkTo...  -> fileChunk(...)
 *  - PresenceMonitor                  -> ping()
 *
 * Estos métodos los ejecutan los hilos de Ice (no el hilo principal que lee comandos),
 * por eso se imprime con Console, que está sincronizada.
 */
public class ClientCallbackI implements ClientCallback {

    private final FileReceiver fileReceiver;

    public ClientCallbackI(FileReceiver fileReceiver) {
        this.fileReceiver = fileReceiver;
    }
    /**
     * El servidor avisa que alguien te mandó un mensaje privado
     */
    @Override
    public void privateMessage(String sender, String text, Current current) {
        Console.show("[Privado] " + sender + ": " + text);
    }

    /**
     * El servidor avisa que alguien escribió en una sala a la que perteneces
     */
    @Override
    public void roomMessage(String sender, String roomName, String text, Current current) {
        Console.show("[#" + roomName + "] " + sender + ": " + text);
    }

    /**
     * El servidor avisa que alguien entró o salió; llega la lista completa de conectados
     */
    @Override
    public void presenceChanged(String[] nicknames, Current current) {
        Console.show("Conectados (" + nicknames.length + "): " + String.join(", ", nicknames));
    }
    /**
     * El servidor reenvía un bloque de un archivo; roomName es "" si el envío es privado.
     * FileReceiver junta los bloques y guarda el archivo cuando llegan todos.
     */
    @Override
    public void fileChunk(String sender, String roomName, FileChunk chunk, Current current) {
        fileReceiver.receive(sender, roomName, chunk);
    }


    // ===================== LLAMADAS DE VOZ (RF-05 / RF-06) =====================
    // Por ahora solo muestran el aviso. El manejo del audio UDP se conecta en los
    // siguientes pasos (UDPController).

    /**
     * Alguien te está llamando
     */
    @Override
    public void incomingCall(String caller, Current current) {
        Console.show("[Llamada] " + caller + " te está llamando. Usa /accept " + caller + " o /reject " + caller);
    }

    /**
     * La persona a la que llamaste aceptó; peer trae su IP y puerto UDP
     */
    @Override
    public void callAccepted(AudioEndpoint peer, Current current) {
        Console.show("[Llamada] " + peer.nickname + " aceptó la llamada");
    }

    /**
     * La persona a la que llamaste rechazó
     */
    @Override
    public void callRejected(String callee, Current current) {
        Console.show("[Llamada] " + callee + " rechazó la llamada");
    }

    /**
     * La otra persona colgó, canceló o se desconectó
     */
    @Override
    public void callEnded(String peer, Current current) {
        Console.show("[Llamada] La llamada con " + peer + " terminó");
    }

    /**
     * Alguien entró a la llamada grupal de una sala
     */
    @Override
    public void groupMemberJoined(String roomName, AudioEndpoint member, Current current) {
        Console.show("[Llamada #" + roomName + "] " + member.nickname + " entró a la llamada");
    }

    /**
     * Alguien salió de la llamada grupal de una sala
     */
    @Override
    public void groupMemberLeft(String roomName, String nickname, Current current) {
        Console.show("[Llamada #" + roomName + "] " + nickname + " salió de la llamada");
    }

    /**
     * El PresenceMonitor del servidor llama esto cada 5 segundos.
     * Está vacío a propósito: si la llamada llega, el servidor ya sabe que sigues conectado.
     */
    @Override
    public void ping(Current current) {
    }
}