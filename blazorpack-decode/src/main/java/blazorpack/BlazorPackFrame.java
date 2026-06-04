package blazorpack;

/**
 * Low-level BlazorPack / SignalR framing utilities.
 * Handles varint32 encoding, MessagePack detection, and frame boundary detection.
 */
public class BlazorPackFrame {

    private BlazorPackFrame() {} // static utility class

    // ---- Varint32 ----

    /** Maximum bytes a valid varint32 can occupy. */
    private static final int VARINT_MAX_BYTES = 5;

    /**
     * Reads a varint32 from the byte array starting at pos.
     * Returns the decoded integer value. Advances pos[0] past the varint.
     *
     * @throws IllegalArgumentException if varint is truncated, exceeds 5 bytes,
     *         or overflows 32 bits (corrupt data).
     */
    public static int readVarint(byte[] data, int[] pos) {
        int result = 0;
        int shift = 0;
        int p = pos[0];
        int bytesRead = 0;

        while (p < data.length) {
            bytesRead++;
            if (bytesRead > VARINT_MAX_BYTES) {
                throw new IllegalArgumentException(
                    "Varint exceeds " + VARINT_MAX_BYTES + " bytes — corrupt or invalid data");
            }

            int b = data[p] & 0xFF;
            p++;

            // Overflow protection: if shift >= 32, the value won't fit in a signed 32-bit int
            if (shift >= 32) {
                throw new IllegalArgumentException("Varint overflow — value exceeds 32-bit range");
            }

            result |= (b & 0x7F) << shift;
            shift += 7;

            if ((b & 0x80) == 0) {
                pos[0] = p;
                return result;
            }
        }
        throw new IllegalArgumentException("Varint truncated at end of buffer");
    }

    /**
     * Encodes an integer as a varint32 byte array.
     */
    public static byte[] writeVarint(int value) {
        // Max 5 bytes for 32-bit varint
        byte[] buf = new byte[5];
        int i = 0;
        while (true) {
            int b = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                buf[i++] = (byte) (b | 0x80);
            } else {
                buf[i++] = (byte) b;
                break;
            }
        }
        byte[] result = new byte[i];
        System.arraycopy(buf, 0, result, 0, i);
        return result;
    }

    // ---- Frame detection ----

    /**
     * Returns true if the first byte looks like JSON literal start.
     */
    public static boolean isJsonStart(byte[] data) {
        if (data == null || data.length == 0) return false;
        int first = data[0] & 0xFF;
        return first == '{' || first == '[';
    }

    /**
     * Quick heuristic: does the data look like a BlazorPack / MessagePack frame?
     * Checks varint+msgpack framing, bare msgpack, JSON literal, or valid msgpack unpack.
     */
    public static boolean isBlazorPackData(byte[] data) {
        if (data == null || data.length < 2) return false;

        byte[] sample = data.length > 512 ? java.util.Arrays.copyOf(data, 512) : data;

        // JSON literal?
        if (isJsonStart(sample)) return true;

        // varint + msgpack framing? (most common for Blazor/SignalR)
        try {
            int[] pos = {0};
            int msgLen = readVarint(sample, pos);
            if (pos[0] > 0 && pos[0] < sample.length) {
                int mb = sample[pos[0]] & 0xFF;
                if (isMsgPackContainer(mb)) return true;
            }
        } catch (Exception ignored) { }

        // Bare MessagePack?
        try {
            int mb = sample[0] & 0xFF;
            if (isMsgPackContainer(mb)) return true;
        } catch (Exception ignored) { }

        // Try direct msgpack unpack as last resort
        try {
            org.msgpack.core.MessagePack.newDefaultUnpacker(sample).unpackValue();
            return true;
        } catch (Exception ignored) { }

        return false;
    }

    /**
     * Checks if a byte value represents a MessagePack container type header
     * (array or map — the likely first byte of a SignalR hub message).
     */
    private static boolean isMsgPackContainer(int b) {
        // fixmap: 0x80-0x8F, fixarray: 0x90-0x9F
        // array16: 0xDC, array32: 0xDD, map16: 0xDE, map32: 0xDF
        return (b >= 0x80 && b <= 0x9F) || b == 0xDC || b == 0xDD || b == 0xDE || b == 0xDF;
    }

    /**
     * Checks whether the last BlazorPack frame in the data is truncated
     * (the varint-declared length extends beyond the available bytes).
     *
     * Walks through all complete frames; if the final frame's message
     * length exceeds the remaining data, returns true.
     *
     * @return true if the buffer ends with an incomplete frame
     */
    public static boolean isTruncatedFrame(byte[] data) {
        if (data == null || data.length < 2) return false;
        int[] pos = {0};

        try {
            while (pos[0] < data.length) {
                int start = pos[0];
                int msgLen = readVarint(data, pos);
                if (pos[0] + msgLen > data.length) {
                    // Last frame extends beyond available data
                    return true;
                }
                pos[0] += msgLen;
                if (pos[0] == start) break; // stuck
            }
        } catch (Exception e) {
            // Varint parse error at end of buffer — consider it truncated
            return true;
        }
        return false;
    }

    // ---- HTTP header detection ----

    /**
     * Checks if data starts with HTTP request/response line.
     */
    public static boolean looksLikeHttp(byte[] data) {
        if (data == null || data.length < 10) return false;
        // Check first few bytes for HTTP method or HTTP/ prefix
        String head = new String(data, 0, Math.min(10, data.length), java.nio.charset.StandardCharsets.ISO_8859_1);
        return head.startsWith("HTTP/") || head.startsWith("GET ") || head.startsWith("POST ")
            || head.startsWith("PUT ") || head.startsWith("DELETE ") || head.startsWith("OPTIONS ")
            || head.startsWith("HEAD ") || head.startsWith("PATCH ");
    }

    /**
     * Finds the byte offset where HTTP headers end (double CRLF or double LF).
     * Returns the body starting position, or -1 if no header terminator found.
     */
    public static int findHttpBodyOffset(byte[] data) {
        // Search for \r\n\r\n
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i+1] == '\n' && data[i+2] == '\r' && data[i+3] == '\n') {
                return i + 4;
            }
        }
        // Search for \n\n
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == '\n' && data[i+1] == '\n') {
                return i + 2;
            }
        }
        return -1;
    }
}
