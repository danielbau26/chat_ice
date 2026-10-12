package controllers;

import com.zeroc.Ice.Current;

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


    /**
     * El PresenceMonitor del servidor llama esto cada 5 segundos.
     * Está vacío a propósito: si la llamada llega, el servidor ya sabe que sigues conectado.
     */
    @Override
    public void ping(Current current) {
    }
}