import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

public class UdpImageClient {

    private final String serverIp;
    private final int serverPort;
    private final String imagesFolderPath;


        public static void main(String[] args) {
            String serverIp = "127.0.0.1";      // Change if server is on another machine
            int serverPort = 9876;
            String imagesFolder = "images";     // Folder containing the 1000 images
    
            UdpImageClient client = new UdpImageClient(serverIp, serverPort, imagesFolder);
    
            try {
                client.sendAllImages();
            } catch (Exception e) {
                System.err.println("Failed to send images: " + e.getMessage());
                e.printStackTrace();
            }
        }


    public UdpImageClient(String serverIp, int serverPort, String imagesFolderPath) {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.imagesFolderPath = imagesFolderPath;
    }

    public void sendAllImages() throws Exception {
        File folder = new File(imagesFolderPath);
        if (!folder.isDirectory()) {
            throw new IllegalArgumentException("Folder not found: " + imagesFolderPath);
        }

        File[] imageFiles = folder.listFiles((dir, name) ->
                name.matches(".*\\.(?i)(png|jpg|jpeg|gif|bmp)$"));

        if (imageFiles == null || imageFiles.length == 0) {
            System.out.println("No images found in folder: " + imagesFolderPath);
            return;
        }

        // Sort files to ensure correct sequential order
        Arrays.sort(imageFiles, Comparator.comparing(File::getName));

        DatagramSocket socket = new DatagramSocket();
        InetAddress address = InetAddress.getByName(serverIp);

        System.out.println("Starting to send " + imageFiles.length + " images to " + serverIp + ":" + serverPort);
        System.out.println("Using maximum-speed UDP (fire-and-forget)\n");

        long startNanos = System.nanoTime();

        for (int i = 0; i < imageFiles.length; i++) {
            File file = imageFiles[i];
            byte[] imageBytes = Files.readAllBytes(file.toPath());

            byte[] packetData = buildPacket(i, imageBytes);

            DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, serverPort);
            socket.send(packet); // Instant send – no acknowledgment

            if ((i + 1) % 100 == 0 || i == imageFiles.length - 1) {
                System.out.printf("Sent %04d/%d - %s (%,d bytes)%n",
                        i + 1, imageFiles.length, file.getName(), imageBytes.length);
            }
        }

        long endNanos = System.nanoTime();
        double elapsedMs = (endNanos - startNanos) / 1_000_000.0;

        System.out.println("\nAll images sent successfully!");
        System.out.printf("Total time: %.2f ms (%.3f seconds)%n", elapsedMs, elapsedMs / 1000);
        System.out.printf("Average speed: %.2f images/second%n",
                imageFiles.length / (elapsedMs / 1000));

        socket.close();
    }

    private byte[] buildPacket(int imageIndex, byte[] imageData) {
        byte[] packet = new byte[8 + imageData.length];

        // 4 bytes: image index (0-based)
        packet[0] = (byte) (imageIndex >> 24);
        packet[1] = (byte) (imageIndex >> 16);
        packet[2] = (byte) (imageIndex >> 8);
        packet[3] = (byte) imageIndex;

        // 4 bytes: total image size
        int size = imageData.length;
        packet[4] = (byte) (size >> 24);
        packet[5] = (byte) (size >> 16);
        packet[6] = (byte) (size >> 8);
        packet[7] = (byte) size;

        // Copy actual image bytes
        System.arraycopy(imageData, 0, packet, 8, imageData.length);
        return packet;
    }
}