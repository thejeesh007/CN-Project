import java.io.*;
import java.net.*;
import java.util.*;

class VideoRelay {
    private static final int SERVER_PORT = 5000;
    private static final int BUFFER_SIZE = 100;
    private static final int TIMEOUT = 10000; // 10 seconds timeout

    public static final Map<Integer, LinkedHashMap<Integer, String>> relayBuffer = new HashMap<>();

    public static void main(String[] args) throws IOException {
        Socket serverSocket = new Socket("localhost", SERVER_PORT);
        System.out.println("Relay connected to server.");

        ServerSocket relayServerSocket = new ServerSocket(0);
        int relayPort = relayServerSocket.getLocalPort();
        System.out.println("Relay listening on port: " + relayPort);

        relayBuffer.put(relayPort, new LinkedHashMap<Integer, String>(BUFFER_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Integer, String> eldest) {
                return size() > BUFFER_SIZE;
            }
        });

        while (true) {
            Socket clientSocket = relayServerSocket.accept();
            clientSocket.setSoTimeout(TIMEOUT);
            System.out.println("New client connected to relay!");

            Thread clientHandler = new Thread(new ClientHandler(clientSocket, serverSocket, relayPort));
            clientHandler.start();
        }
    }
}

class ClientHandler implements Runnable {
    private Socket clientSocket, serverSocket;
    private DataInputStream inFromServer;
    private DataOutputStream outToClient;
    private BufferedReader inFromClient;
    private PrintWriter outToServer;
    private LinkedHashMap<Integer, String> buffer;

    public ClientHandler(Socket client, Socket server, int relayPort) {
        this.clientSocket = client;
        this.serverSocket = server;
        this.buffer = VideoRelay.relayBuffer.get(relayPort);
    }

    public void run() {
        try {
            inFromServer = new DataInputStream(serverSocket.getInputStream());
            outToClient = new DataOutputStream(clientSocket.getOutputStream());
            inFromClient = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            outToServer = new PrintWriter(serverSocket.getOutputStream(), true);

            while (true) {
                String request = inFromClient.readLine();
                if (request == null) break;

                if (request.equals("PING")) {
                    System.out.println("Client is alive.");
                    continue;
                }

                if (request.startsWith("NACK")) {
                    int packetIndex = Integer.parseInt(request.split(" ")[1]);
                    outToServer.println("NACK " + packetIndex);
                    String packet = inFromServer.readUTF();
                    buffer.put(packetIndex, packet);
                    outToClient.writeUTF(packet);
                    outToClient.flush();
                    continue;
                }

                int packetIndex = Integer.parseInt(request);
                String packet;

                if (buffer.containsKey(packetIndex)) {
                    packet = buffer.get(packetIndex);
                    System.out.println("Relay: Sending cached " + packet);
                } else {
                    outToServer.println(packetIndex);
                    packet = inFromServer.readUTF();
                    if (!packet.equals("NACK")) {
                        buffer.put(packetIndex, packet);
                    }
                    System.out.println("Relay: Fetching from server " + packet);
                }

                outToClient.writeUTF(packet);
                outToClient.flush();
            }

            System.out.println("Relay: Transmission complete.");
            clientSocket.close();
        } catch (SocketTimeoutException e) {
            System.out.println("Client timed out. Closing connection.");
        } catch (IOException e) {
            System.out.println("Relay: Connection lost.");
        }
    }
}
