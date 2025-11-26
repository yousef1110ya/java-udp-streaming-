package server;

/*
 * SserverHanlder 
 * this class will mainly do these tasks over the application 
 * 1- recive datagram packets 
 * 2- saves them as files ( might be more than one packet for a file so notice that ) 
 * 3- NOT A REAL REQUIREMENT : support some gui to show the images on the screen . 
 */
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
public class ServerHandler {
	/*
	 * DECLERATIONS
	 */
    private static final int BUFFER_SIZE = 64_000; // should exceed sender MAX_PACKET_SIZE
    private final DatagramSocket socket;
    private final Path sessionFolder;

    // fileId -> assembly state
    private final Map<Long, FileAssembly> assemblies = new ConcurrentHashMap<>();

	/*
	 * CONSTRUCTORS
	 */
	public ServerHandler() throws Exception {
		this(5000 , Path.of("output"));
	}
	public ServerHandler(int port , Path baseFolder) throws Exception {
		this.socket = new DatagramSocket(port);
        String folderName = LocalDateTime.now().toString().replace(":", "-");
        this.sessionFolder = baseFolder.resolve(folderName);
        Files.createDirectories(sessionFolder);
	}
	/*
	 * METHODES
	 */
	public void start() throws Exception {
        byte[] buffer = new byte[BUFFER_SIZE];
        System.out.printf("Receiver listening on port %d, writing into %s%n", socket.getLocalPort(), sessionFolder);

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            // parse
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(packet.getData(), 0, packet.getLength()));
            long fileId = dis.readLong();
            int totalChunks = dis.readInt();
            int chunkIndex = dis.readInt();
            int nameLen = dis.readShort();
            byte[] nameBytes = new byte[nameLen];
            dis.readFully(nameBytes);
            String filename = new String(nameBytes, "UTF-8");

            byte[] chunkData = dis.readAllBytes();

            // sanity checks
            if (chunkData.length == 0 && totalChunks > 0) {
                System.out.printf("Received empty chunk for file %d chunk %d%n", fileId, chunkIndex);
            }

            FileAssembly fa = assemblies.computeIfAbsent(fileId, id -> new FileAssembly(fileId, filename, totalChunks));
            boolean inserted = fa.insertChunk(chunkIndex, chunkData);

            // send ACK if inserted (or even if duplicate)
            sendAck(packet.getAddress(), packet.getPort(), fileId, chunkIndex);

            if (fa.isComplete()) {
                Path outFile = sessionFolder.resolve(fa.filename);
                Files.write(outFile, fa.assemble());
                assemblies.remove(fileId);
                System.out.printf("Completed write: %s (fileId=%d) -> %s%n", fa.filename, fileId, outFile);
            }
        }
    }

    private void sendAck(InetAddress addr, int port, long fileId, int chunkIndex) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(baos);
            dos.writeLong(fileId);
            dos.writeInt(chunkIndex);
            dos.writeByte(0); // status ok
            dos.flush();
            byte[] ack = baos.toByteArray();
            DatagramPacket ackPacket = new DatagramPacket(ack, ack.length, addr, port);
            socket.send(ackPacket);
        } catch (IOException e) {
            System.err.println("Failed to send ACK: " + e.getMessage());
        }
    }

    public void close() {
        socket.close();
    }
	/*
	 * SUB_CLASS
	 */
	private static class FileAssembly {
		/*
		 * DECLERATIONS
		 */
        final long fileId;
        final String filename;
        final int totalChunks;
        final byte[][] parts;
        final BitSet received;
        /*
    	 * CONSTRUCTORS
    	 */
        FileAssembly(long fileId, String filename, int totalChunks) {
            this.fileId = fileId;
            this.filename = sanitizeFilename(filename);
            this.totalChunks = totalChunks;
            this.parts = new byte[totalChunks][];
            this.received = new BitSet(totalChunks);
        }
    	/*
    	 * METHODES
    	 */
        synchronized boolean insertChunk(int idx, byte[] data) {
            if (idx < 0 || idx >= totalChunks) return false;
            if (parts[idx] == null) {
                parts[idx] = data;
                received.set(idx);
                return true;
            } else {
                // duplicate chunk
                return false;
            }
        }

        synchronized boolean isComplete() {
            return received.cardinality() == totalChunks;
        }

        synchronized byte[] assemble() {
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                for (int i = 0; i < totalChunks; i++) {
                    baos.write(parts[i]);
                }
                return baos.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        // prevent path traversal and invalid names
        private static String sanitizeFilename(String name) {
            String sanitized = name.replace("..", "").replace("/", "_").replace("\\", "_");
            return sanitized;
        }
    }

	/*
	 * APP_START 
	 */
	public static void main(String[] args) throws Exception  {
			ServerHandler receiver = new ServerHandler(); 
		try {
			System.out.println("started the receiver");
			receiver.start();
		}catch (Exception e) {
			System.out.println("catch in server handler");
		} finally {
			receiver.close();
		}
	}
}
