import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

import controllers.IceController;
import services.ChatServices;
import services.PresenceMonitor;
import com.zeroc.Ice.InitializationData;

public class Server {

    public static void main(String[] args) {
        //configura más hilos para que el servidor no se bloquee
        //cuando atiende una llamada y al mismo tiempo llama a los clientes (callbacks)
        InitializationData initData = new InitializationData();
        initData.properties = Util.createProperties(args);
        initData.properties.setProperty("Ice.ThreadPool.Server.Size", "4");
        initData.properties.setProperty("Ice.ThreadPool.Server.SizeMax", "16");
        //inicializa ice
        try (Communicator communicator = Util.initialize(args, initData)) {

            //crea el servicio
            ChatServices chatServices = new ChatServices();

            //crea el controllador
            IceController controller = new IceController(chatServices);

            //recibe las llamadas de ice y lo vuelve el objeto que corresponda
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints("ChatAdapter", "tcp -h 0.0.0.0 -p 10000");

            //cuando llega una llamada para el objeto add añadirla al controllador de ice
            adapter.add(controller, Util.stringToIdentity("chat"));

            //atiende las solicitudes del cliente
            adapter.activate();

            System.out.println("Servidor de chat iniciado en el puerto 10000");

            
            //Consultar los usuarios conectados y retirar a quienes pierdan la conexión
            PresenceMonitor monitor = new PresenceMonitor(chatServices);
            monitor.startService();

            try {
                //mantiene el servidor funcionando
                communicator.waitForShutdown();
            } finally {
                monitor.stopService();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}