import java.io.*;
import java.net.*;
import java.util.*;

class VideoServer {
    private static final int SERVER_PORT = 5000;
    public static final int TOTAL_PACKETS = 100;

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
        System.out.println("Server waiting for relay connections...");

        while (true) {
            Socket relaySocket = serverSocket.accept();
            System.out.println("Relay connected!");
            Thread relayHandler = new Thread(new RelayHandler(relaySocket));
            relayHandler.start();
        }
    }
}

class RelayHandler implements Runnable {
    private Socket relaySocket;
    private DataOutputStream outToRelay;
    private BufferedReader inFromRelay;
    private String[] videoPackets;

    public RelayHandler(Socket relaySocket) {
        this.relaySocket = relaySocket;
        videoPackets = new String[VideoServer.TOTAL_PACKETS];
        for (int i = 0; i < VideoServer.TOTAL_PACKETS; i++) {
            videoPackets[i] = "Video_Packet_" + i;
        }
    }

    public void run() {
        try {
            outToRelay = new DataOutputStream(relaySocket.getOutputStream());
            inFromRelay = new BufferedReader(new InputStreamReader(relaySocket.getInputStream()));

            while (true) {
                String request = inFromRelay.readLine();
                if (request == null) break;

                if (request.startsWith("NACK")) {
                    int packetIndex = Integer.parseInt(request.split(" ")[1]);
                    sendPacket(packetIndex);
                    continue;
                }

                int packetIndex = Integer.parseInt(request);
                sendPacket(packetIndex);
            }
        } catch (IOException e) {
            System.out.println("Server: Relay connection closed.");
        }
    }

    private void sendPacket(int packetIndex) throws IOException {
        if (packetIndex >= 0 && packetIndex < videoPackets.length) {
            System.out.println("Server: Sending Packet " + packetIndex);
            outToRelay.writeUTF(videoPackets[packetIndex]);
        } else {
            outToRelay.writeUTF("NACK");
        }
        outToRelay.flush();
    }
}
