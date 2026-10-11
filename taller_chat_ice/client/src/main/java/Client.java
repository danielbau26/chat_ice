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

            ChatServerPrx chat= ChatServerPrx.checkedCast(communicator.stringToProxy(SERVER_IDENTITY + ":tcp-h"
            + host +" -p" + port));

            if(chat==null){
                System.out.println("El objeto '" + SERVER_IDENTITY + "' del servidor no implementa ChatServer");
                return;
            }
            // publicar el objeto de callback del cliente; para que un servidor pueda invocar metodos del
            // cliente, el cliente necesita tambien su object adapter, en este caso sin ednpoint: no abre ningun
            // puerto ni escucha conexiones nuevas. solo recibira invocaciones que lleguen por conexiones ya existente

            ObjectAdapter adapter= communicator.createObjectAdapter("");

            //registrar el servant en el adapater, en este caso el ClientCallbackI es la implementacion real
            // adapter.add() lo registrara bajo una identidad
            // devuelve el proxy que apunta a el
            ObjectPrx prx= adapter.add(new ClientCallbackI(), Util.stringToIdentity(UUID.randomUUID().toString()));
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
            System.out.println("Bienvenido, " + nickname + ". Escribe /help para ver los comandos.");
            Console.prompt();

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
                System.out.println("el nickname: " + nickname + "esta en uso");
            }catch (ChatError e){
                System.out.println("Error: " + e.reason);
            }
        }
    }

    private static void commandLoop(Scanner scanner, ChatServerPrx chat, String nickname) {

    }
}
