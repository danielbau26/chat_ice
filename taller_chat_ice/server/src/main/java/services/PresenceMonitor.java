package services;

import java.util.List;

import model.User;

//detectar si un usuario cerro la aplicacion sin hacer logout
public class PresenceMonitor implements Runnable {

    private final ChatServices chatServices;
    private volatile boolean running;
    private Thread thread;

    public PresenceMonitor(ChatServices chatServices) {
        this.chatServices = chatServices;
    }

    public void startService() {
        running = true;
        thread = new Thread(this, "Presence-Monitor");
        thread.setDaemon(true);
        thread.start();
    }

    public void stopService() {
        running = false;

        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public void run() {
        while (running) {
            try {
                List<User> users = chatServices.getUsers();

                for (int i = 0; i < users.size(); i++) {
                    if (!running) {
                        break;
                    }

                    User user = users.get(i);

                    try {
                        //Comprueba que el usuario esta respondiendo, si no responde en 2 segundos cierra la sesión
                        //También puede cerrarlo si la conexión es muy lenta
                        user.getCallback().ice_invocationTimeout(2000).ping();

                    } catch (com.zeroc.Ice.LocalException e) {
                        chatServices.removeDisconnectedUser(user);
                    }
                }

                Thread.sleep(5000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}