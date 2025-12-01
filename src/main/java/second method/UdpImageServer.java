import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UdpImageServer {

    private static final int PORT = 9876;
    private static final int MAX_PACKET_SIZE = 65507; // Maximum safe UDP payload
    private static final String SAVE_DIRECTORY = "received_images";

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get(SAVE_DIRECTORY));

        DatagramSocket socket = new DatagramSocket(PORT);
        System.out.println("UDP Image Server started on port " + PORT + "...");

        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        byte[] buffer = new byte[MAX_PACKET_SIZE + 8]; // 4 bytes index + 4 bytes size + payload

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);                     // Blocking receive
            executor.submit(() -> processPacket(packet)); // Offload to thread pool
        }
    }

    private static void processPacket(DatagramPacket packet) {
        byte[] data = packet.getData();
        int offset = packet.getOffset();

        // First 4 bytes: image index, next 4 bytes: total image size
        int imageIndex = Integer.reverseBytes(java.nio.ByteBuffer.wrap(data, offset, 4).getInt()) >>> 16;
        int totalSize  = Integer.reverseBytes(java.nio.ByteBuffer.wrap(data, offset + 4, 4).getInt()) >>> 16;

        // Extract image bytes (all images fit in one packet because they are < 64KB)
        byte[] imageBytes = new byte[totalSize];
        System.arraycopy(data, offset + 8, imageBytes, 0, totalSize);

        saveImage(imageIndex, imageBytes);
    }

    private static synchronized void saveImage(int index, byte[] imageData) {
        String filename = String.format("%s/image_%04d.png", SAVE_DIRECTORY, index + 1);
        try {
            Files.write(Path.of(filename), imageData);
            System.out.println("Received & saved: " + filename + " (" + imageData.length + " bytes)");
        } catch (IOException e) {
            System.err.println("Error saving image " + index + ": " + e.getMessage());
        }
    }
}