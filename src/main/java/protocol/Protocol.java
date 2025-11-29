package protocol;

public class Protocol {
    public static final int PACKET_SIZE = 1400;

    public static final int HEADER_SIZE = 8;

    public static final int PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE;
}
