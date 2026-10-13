package audio;

import java.util.Scanner;

import controllers.UDPController;

/**
 * Prueba del canal de voz UDP SIN servidor ni Ice: el cliente se envía audio a sí mismo
 * (a 127.0.0.1) y lo reproduce. Sirve para comprobar micrófono, parlantes y el envío UDP.
 *
 * Ejecutar con:  .\gradlew :client:audioTest --console=plain
 *
 * IMPORTANTE: usar audífonos; con parlantes el micrófono capta su propio sonido (eco/pitido).
 */
public class AudioTest {

    public static void main(String[] args) throws Exception {
        UDPController voice = new UDPController();

        int port = voice.open();
        voice.addTarget("127.0.0.1", port);
        voice.startAudio();

        System.out.println("Prueba de audio por UDP en el puerto " + port);
        System.out.println("Habla: deberías escucharte con un pequeño retraso.");
        System.out.println("Escribe 'm' + Enter para mute/unmute, o solo Enter para terminar.");

        Scanner scanner = new Scanner(System.in);

        while (scanner.hasNextLine()) {
            String line = scanner.nextLine().trim();

            if (!line.equalsIgnoreCase("m")) {
                break;
            }

            voice.setMuted(!voice.isMuted());
            System.out.println(voice.isMuted() ? "Micrófono silenciado" : "Micrófono activo");
        }

        voice.close();
        System.out.println("Prueba terminada");
    }
}
