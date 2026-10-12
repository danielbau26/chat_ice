package ui;

/**
 * Imprime en consola de forma ordenada.
 *
 * En el cliente hay varios hilos imprimiendo al mismo tiempo:
 *  - el hilo principal, que lee tus comandos y muestra sus respuestas
 *  - los hilos de Ice, que ejecutan los callbacks (mensajes que llegan, presencia)
 *
 * Los métodos son synchronized para que dos hilos no impriman a la vez
 * y las líneas no salgan mezcladas.
 */
public class Console {

    private static final String PROMPT = "> ";

    /**
     * Muestra un mensaje en su propia línea y vuelve a dejar el prompt "> "
     * @param message - texto a mostrar
     */
    public static synchronized void show(String message) {
        // \r regresa al inicio de la línea para no quedar pegado al "> "
        System.out.print("\r" + message + "\n" + PROMPT);
        System.out.flush();
    }

    /**
     * Solo muestra el prompt "> "
     */
    public static synchronized void prompt() {
        System.out.print(PROMPT);
        System.out.flush();
    }
}