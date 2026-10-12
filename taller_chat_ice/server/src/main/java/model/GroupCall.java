package model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Chat.AudioEndpoint;

/**
 * Llamada grupal (conferencia) de una sala (RF-06).
 *
 * Se usa la estrategia de malla P2P (mesh): el servidor solo guarda la lista de
 * participantes con sus endpoints UDP. Cada cliente envía su audio directamente
 * a todos los demás; el audio nunca pasa por el servidor.
 *
 * Igual que Room, los métodos son synchronized porque varios hilos de Ice pueden
 * modificar la misma llamada al tiempo, y los getters devuelven copias.
 */
public class GroupCall {

    private final String roomName;

    /** nickname -> endpoint UDP. LinkedHashMap conserva el orden de llegada */
    private final Map<String, AudioEndpoint> participants;

    public GroupCall(String roomName) {
        if (roomName == null || roomName.trim().isEmpty()) {
            throw new IllegalArgumentException("El nombre de la sala no puede estar vacío");
        }

        this.roomName = roomName;
        this.participants = new LinkedHashMap<>();
    }

    public String getRoomName() {
        return roomName;
    }

    /**
     * Agrega (o actualiza) un participante
     * @param endpoint - dónde recibe audio el participante
     */
    public synchronized void addParticipant(AudioEndpoint endpoint) {
        participants.put(endpoint.nickname, endpoint);
    }

    /**
     * @return - true si el participante estaba en la llamada
     */
    public synchronized boolean removeParticipant(String nickname) {
        return participants.remove(nickname) != null;
    }

    public synchronized boolean hasParticipant(String nickname) {
        return participants.containsKey(nickname);
    }

    public synchronized boolean isEmpty() {
        return participants.isEmpty();
    }

    /**
     * @return - copia de los endpoints de todos los participantes
     */
    public synchronized List<AudioEndpoint> getParticipants() {
        return new ArrayList<>(participants.values());
    }

    /**
     * @return - copia de los endpoints de todos menos el indicado
     */
    public synchronized List<AudioEndpoint> getParticipantsExcept(String nickname) {
        List<AudioEndpoint> others = new ArrayList<>();

        for (AudioEndpoint endpoint : participants.values()) {
            if (!endpoint.nickname.equals(nickname)) {
                others.add(endpoint);
            }
        }

        return others;
    }
}
