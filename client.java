import java.io.*;
import java.net.*;
import java.util.*;

class VideoClient {
    private static final int HEARTBEAT_INTERVAL = 5000;
    private static final int TIMEOUT = 10000;

    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter relay port: ");
        int relayPort = scanner.nextInt();
        scanner.nextLine(); // Consume newline

        Socket relaySocket = new Socket("localhost", relayPort);
        relaySocket.setSoTimeout(TIMEOUT);
        System.out.println("Client connected to relay on port " + relayPort);

        DataInputStream in = new DataInputStream(relaySocket.getInputStream());
        PrintWriter out = new PrintWriter(relaySocket.getOutputStream(), true);

        new Thread(() -> {
            try {
                while (true) {
                    out.println("PING");
                    Thread.sleep(HEARTBEAT_INTERVAL);
                }
            } catch (Exception e) {
                System.out.println("Heartbeat stopped.");
            }
        }).start();

        System.out.print("Enter number of packets to request: ");
        int numPackets = scanner.nextInt();
        scanner.nextLine();

        out.println(numPackets);
        Set<Integer> missingPackets = new HashSet<>();

        for (int i = 0; i < numPackets; i++) {
            out.println(i);
            try {
                String packet = in.readUTF();
                if (packet.equals("NACK")) {
                    missingPackets.add(i);
                } else {
                    System.out.println("Client: Received " + packet);
                }
            } catch (IOException e) {
                missingPackets.add(i);
            }
        }

        for (int index : missingPackets) {
            out.println("NACK " + index);
            String packet = in.readUTF();
            System.out.println("Client: Retransmitted " + packet);
        }

        relaySocket.close();
    }
}
