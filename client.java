import java.io.*;
import java.net.*;
import java.util.*;

class VideoClient {
    public static void main(String[] args) throws IOException {
        Scanner scanner = new Scanner(System.in);

        System.out.print("Enter relay port: ");
        int relayPort = scanner.nextInt();
        scanner.nextLine();

        Socket relaySocket = new Socket("localhost", relayPort);
        System.out.println("Client connected to relay on port " + relayPort);

        DataInputStream in = new DataInputStream(relaySocket.getInputStream());
        PrintWriter out = new PrintWriter(relaySocket.getOutputStream(), true);

        new Thread(() -> {
            try {
                while (true) {
                    out.println("PING");
                    Thread.sleep(5000);
                }
            } catch (Exception e) {
                System.out.println("Heartbeat stopped.");
            }
        }).start();

        System.out.print("Enter number of packets to request: ");
        int numPackets = scanner.nextInt();
        scanner.nextLine();

        out.println(numPackets);

        for (int i = 0; i < numPackets; i++) {
            System.out.println("Client: Requesting packet " + i);
            out.println(i);
            String packet = in.readUTF();

            if (packet.equals("NACK")) {
                System.out.println("Client: NACK Received for packet " + i);
                i--; // Request again
                continue;
            }

            System.out.println("Client: Received " + packet);
        }

        relaySocket.close();
    }
}
