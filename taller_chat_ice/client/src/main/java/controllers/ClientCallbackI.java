package controllers;

import com.zeroc.Ice.Current;

import Chat.ClientCallback;
import ui.Console;

/**
 *
 * El servidor tiene un control remoto (ClientCallbackPrx) que apunta a este objeto.
 * Cuando el servidor llama, por ejemplo, privateMessage(...) sobre ese control remoto,
 * el método se ejecuta AQUÍ, en el computador del cliente.
 *
 * Estos métodos los ejecutan hilos de Ice, no el hilo principal,
 * por eso se imprime con Console (que está sincronizada).
 */
public class ClientCallbackI implements ClientCallback {

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
     * El PresenceMonitor del servidor llama esto cada 5 segundos.
     * Está vacío a propósito: si la llamada llega, el servidor ya sabe que sigues conectado.
     */
    @Override
    public void ping(Current current) {
    }
}