import java.util.Scanner;
import java.util.UUID;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.LocalException;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;

import Chat.ChatError;
import Chat.ChatServerPrx;
import Chat.ClientCallbackPrx;
import Chat.NicknameInUse;
import controllers.ClientCallbackI;
import ui.Console;
import files.FileReceiver;
import files.FileSender;
public class Client {
    /**
     * el cliente cumple dos papeles al tiempo:
     * 1 el cliente llama operaciones remotas del servidor, login, sendprivatemessage,
     * createRoom, a traves de un proxy de tipo ChatServerPrx
     *
     * 2- servidor de ice: expone un objeto propio clientCallbackI para que el servidor
     * pueda invocar metodos de forma asincronica (mensajes entrantesm cambios de presencia
     * ping. para eso el cliente crea su propio object adapter
     *
     * ambas direcciones viajan por una sola conexion tcp, bidireccional, la que cliente abre
     * hacia el servidor, asi el servidor nunca tiene que abrir una nueva hacia el cliente, lo
     * que evita problemas con bloqueos
     */

    // puerto donde el servidor publica su object adapter
    private static final int port= 10000;
    // identidad con la que el servidor registro su objeto que ejecuta
    private static final String SERVER_IDENTITY = "chat";
    public static void main(String[]args){

        Scanner scanner= new Scanner(System.in);
        // por si es conexion con otra maquina
        System.out.println("IP del servidor (Enter = localhost): ");
        String host= scanner.nextLine().trim();

        if(host.isEmpty()){
            host= "localhost";
        }
        // communicator, punto de entrada, gestion de conexiones
        try (Communicator communicator= Util.initialize(args)){
            // obtener el proxy del servidor, referencia local a un objeto remoto
            // al invocar un metodo sobre el proxy, se serializan los parametros, los envia por red,
            // el servidor ejecuta el metodo real y devuelve el resultado+

            ChatServerPrx chat= ChatServerPrx.checkedCast(communicator.stringToProxy(SERVER_IDENTITY + ":tcp -h " + host + " -p " + port));

            if(chat==null){
                System.out.println("El objeto '" + SERVER_IDENTITY + "' del servidor no implementa ChatServer");
                return;
            }
            // publicar el objeto de callback del cliente; para que un servidor pueda invocar metodos del
            // cliente, el cliente necesita tambien su object adapter, en este caso sin ednpoint: no abre ningun
            // puerto ni escucha conexiones nuevas. solo recibira invocaciones que lleguen por conexiones ya existente

            ObjectAdapter adapter= communicator.createObjectAdapter("");

            FileReceiver receiver= new FileReceiver();
            //registrar el servant en el adapater, en este caso el ClientCallbackI es la implementacion real
            // adapter.add() lo registrara bajo una identidad
            // devuelve el proxy que apunta a el
            ObjectPrx prx= adapter.add(new ClientCallbackI(receiver), Util.stringToIdentity(UUID.randomUUID().toString()));
            // activar el adapter para que despache invocaciones
            adapter.activate();
            //asociar el adapter a la conexion con el servidor (bidireccional).
            chat.ice_getConnection().setAdapter(adapter);
            // convertir el proxy generico al tipo ClientCallbackPrx que pide login()
            // unchecked porque es seguro q es ClientCallBackI, checkedcast intentaria contactar el objeto
            // lo cual no tiene sentido sobre un adapter sin endpoints
            ClientCallbackPrx callback= ClientCallbackPrx.uncheckedCast(prx);

            //iniciar sesion: se envia el nickname junto con el proxy del callback
            String nickname = login(scanner, chat,callback);

            if(nickname == null){
                return;
            }
            receiver.setOwner(nickname);
            //Console.show en vez de System.out: justo después del login el servidor manda
            //presenceChanged desde otro hilo y así las líneas no se mezclan
            Console.show("Bienvenido, " + nickname + ". Escribe /help para ver los comandos.");

            // ciclo de comandos
            commandLoop(scanner, chat,nickname);
        }catch (LocalException e){
            System.out.println("No se pudo conectar con el servidor en " + host + ":" + port
                    + " (" + e.getClass().getSimpleName() + ")");
        }
    }
    // pide un nickname y llama login() en el servidor hasta que sea aceptado
    // login puede lanzar dos excepciones definidas en chat..ice, nicknameInUse o chatErrror, cualquier otro error controlado
    /**
     * @param scanner - entrada de consola
     * @param chat - proxy del servidor
     * @param callback - proxy hacia el ClientCallbackI de este cliente
     * @return - el nickname aceptado por el servidor, o null si se cerró la entrada
     */
    private static String login(Scanner scanner, ChatServerPrx chat, ClientCallbackPrx callback){
        while(true){
            System.out.println("Nickname: ");
            if (!scanner.hasNext()){
                return null;
            }
            String nickname= scanner.nextLine().trim();
            try {
                chat.login(nickname,callback);
                return nickname;
            }catch (NicknameInUse e){
                System.out.println("el nickname: " + nickname + " está en uso");
            }catch (ChatError e){
                System.out.println("Error: " + e.reason);
            }
        }
    }
    // lee comandos del usuaro y ejecuta la operacion remota correspondiente
    // modelo de hilos: este metodo corre en el hilo principal y se bloque en scanner.nexline esperando q
    // escriba el uuario. las notificaciones del servidor (mennsajes entrantes, presencia, ping) las ejecutan
    // los hilos internos de ice al despachar ClientCallbackI. como son hilos distintos el usuario puede
    // recibir mensajes mientras escribe, sin que la lectura bloquee la recepcion ni viceversa
    // ambos imprimen console, cuyos metodos estan sincronizados para que la salida no se mezcle.
    // las invocaciones al servidor son sincronicas: el metodo retorna cuando el servidor dejo de procesar
    // por eso si no se lanza excepcion se puede cofirmar al usaurio que la operacion se completo+

    /**
     *
     * @param scanner - entada de consola
     * @param chat - proxy del servidor
     * @param nickname - nickname con el que se inciio la sesion
     */
    private static void commandLoop(Scanner scanner, ChatServerPrx chat, String nickname) {

        while (scanner.hasNextLine()) {

            String line = scanner.nextLine().trim();

            if (line.isEmpty()) {
                Console.prompt();
                continue;
            }

            /*
             * Se divide en máximo 3 partes: comando, destino y texto.
             * El límite de 3 permite que el texto del mensaje conserve sus espacios:
             * "/msg pedro hola que tal" -> ["/msg", "pedro", "hola que tal"]
             */
            String[] parts = line.split("\\s+", 3);
            String command = parts[0].toLowerCase();

            try {
                switch (command) {

                    //Mensaje privado 1 a 1 (RF-02)
                    case "/msg":
                        if (parts.length < 3) {
                            Console.show("Uso: /msg <usuario> <texto>");
                            break;
                        }
                        chat.sendPrivateMessage(nickname, parts[1], parts[2]);
                        Console.show("[OK] Mensaje entregado a " + parts[1]);
                        break;

                    //Mensaje a una sala; el servidor lo reenvía a todos los miembros menos al emisor (RF-03)
                    case "/room":
                        if (parts.length < 3) {
                            Console.show("Uso: /room <sala> <texto>");
                            break;
                        }
                        chat.sendRoomMessage(nickname, parts[1], parts[2]);
                        Console.show("[OK] Mensaje enviado a #" + parts[1]);
                        break;

                    //Crear sala; el servidor agrega automáticamente al creador como miembro
                    case "/create":
                        if (parts.length < 2) {
                            Console.show("Uso: /create <sala>");
                            break;
                        }
                        chat.createRoom(nickname, parts[1]);
                        Console.show("[OK] Sala #" + parts[1] + " creada (ya estás dentro)");
                        break;

                    case "/join":
                        if (parts.length < 2) {
                            Console.show("Uso: /join <sala>");
                            break;
                        }
                        chat.joinRoom(nickname, parts[1]);
                        Console.show("[OK] Te uniste a #" + parts[1]);
                        break;

                    case "/leave":
                        if (parts.length < 2) {
                            Console.show("Uso: /leave <sala>");
                            break;
                        }
                        chat.leaveRoom(nickname, parts[1]);
                        Console.show("[OK] Saliste de #" + parts[1]);
                        break;

                    //Catálogo de salas disponibles
                    case "/rooms":
                        String[] rooms = chat.getRooms();
                        Console.show(rooms.length == 0
                                ? "No hay salas creadas"
                                : "Salas: " + String.join(", ", rooms));
                        break;

                    //Consulta manual de conectados (la lista también llega sola por presenceChanged)
                    case "/users":
                        Console.show("Conectados: " + String.join(", ", chat.getUsers()));
                        break;
                    /*
                     * Enviar archivo (RF-04). Destino "usuario" = privado, "#sala" = sala.
                     * Se envía en un hilo aparte (FileSender.sendAsync) para que la consola
                     * siga disponible durante la transferencia.
                     */
                    case "/sendfile":
                        if (parts.length < 3) {
                            Console.show("Uso: /sendfile <usuario|#sala> <ruta>");
                            break;
                        }
                        FileSender.sendAsync(chat, nickname, parts[1], parts[2]);
                        break;
                    case "/help":
                        showHelp();
                        break;

                    /*
                     * Cierre de sesión ordenado (RF-01): el servidor elimina al usuario,
                     * lo saca de sus salas y notifica la nueva lista de presencia.
                     * Al retornar, se sale del try-with-resources del main y se cierra
                     * el Communicator.
                     */
                    case "/logout":
                        chat.logout(nickname);
                        Console.showFinal("Sesión cerrada. ¡Hasta luego!");
                        return;

                    default:
                        Console.show("Comando desconocido. Escribe /help");
                }

            } catch (ChatError e) {
                /*
                 * Error controlado definido en Chat.ice. El servidor lo lanza cuando la
                 * operación no se puede realizar (usuario no conectado, sala inexistente,
                 * no pertenece a la sala, mensaje vacío...). reason trae el motivo.
                 */
                Console.show("Error: " + e.reason);

            } catch (LocalException e) {
                /*
                 * Error de comunicación: el servidor se cayó o la conexión se perdió.
                 * No tiene sentido seguir leyendo comandos.
                 */
                Console.showFinal("Se perdió la conexión con el servidor.");
                return;
            }
        }
    }

    /**
     * Muestra los comandos disponibles
     */
    private static void showHelp() {
        Console.show(String.join("\n",
                "Comandos:",
                "  /msg <usuario> <texto>   mensaje privado",
                "  /room <sala> <texto>     mensaje a una sala",
                "  /create <sala>           crear sala",
                "  /join <sala>             unirse a una sala",
                "  /leave <sala>            salir de una sala",
                "  /sendfile <usuario> <ruta>   enviar archivo en privado",
                "  /sendfile #<sala> <ruta>     enviar archivo a una sala",
                "  /rooms                   ver salas",
                "  /users                   ver conectados",
                "  /logout                  cerrar sesión y salir"
        ));
    }
}

