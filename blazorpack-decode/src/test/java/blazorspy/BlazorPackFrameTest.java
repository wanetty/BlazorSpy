package blazorspy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for varint32 encoding/decoding and frame detection.
 */
class BlazorPackFrameTest {

    // ---- readVarint ----

    @Test
    void testReadVarintSingleByte() {
        // 1 (single byte, no continuation)
        byte[] data = { (byte) 0x01 };
        int[] pos = { 0 };
        int result = BlazorPackFrame.readVarint(data, pos);
        assertEquals(1, result);
        assertEquals(1, pos[0]);
    }

    @Test
    void testReadVarintMultiByte() {
        // 300 = 0xAC 0x02 (varint: 10101100 00000010)
        byte[] data = { (byte) 0xAC, 0x02 };
        int[] pos = { 0 };
        int result = BlazorPackFrame.readVarint(data, pos);
        assertEquals(300, result);
        assertEquals(2, pos[0]);
    }

    @Test
    void testReadVarintMaxValue() {
        // Max 32-bit unsigned fits in 5 bytes: 0b11111111_11111111_11111111_11111111
        // Varint: 0xFF 0xFF 0xFF 0xFF 0x0F
        byte[] data = { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F };
        int[] pos = { 0 };
        int result = BlazorPackFrame.readVarint(data, pos);
        assertEquals(-1, result); // unsigned 0xFFFFFFFF = -1 in signed
        assertEquals(5, pos[0]);
    }

    @Test
    void testReadVarintZeroValue() {
        byte[] data = { 0x00 };
        int[] pos = { 0 };
        int result = BlazorPackFrame.readVarint(data, pos);
        assertEquals(0, result);
        assertEquals(1, pos[0]);
    }

    @Test
    void testReadVarintOverflow() {
        // 6 continuation bytes would overflow — but the method caps at 5 bytes
        byte[] data = { (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x01 };
        int[] pos = { 0 };
        assertThrows(IllegalArgumentException.class, () -> {
            BlazorPackFrame.readVarint(data, pos);
        });
    }

    @Test
    void testReadVarintTruncatedBuffer() {
        // High bit set on last available byte = truncated
        byte[] data = { (byte) 0x80 };
        int[] pos = { 0 };
        assertThrows(IllegalArgumentException.class, () -> {
            BlazorPackFrame.readVarint(data, pos);
        });
    }

    // ---- writeVarint ----

    @Test
    void testWriteVarintSingleByte() {
        byte[] result = BlazorPackFrame.writeVarint(1);
        assertArrayEquals(new byte[]{ 0x01 }, result);
    }

    @Test
    void testWriteVarintMultiByte() {
        byte[] result = BlazorPackFrame.writeVarint(300);
        assertArrayEquals(new byte[]{ (byte) 0xAC, 0x02 }, result);
    }

    @Test
    void testVarintRoundTrip() {
        int[] testValues = { 0, 1, 127, 128, 255, 300, 16383, 16384, 1000000, Integer.MAX_VALUE };
        for (int value : testValues) {
            byte[] encoded = BlazorPackFrame.writeVarint(value);
            int[] pos = { 0 };
            int decoded = BlazorPackFrame.readVarint(encoded, pos);
            assertEquals(value, decoded, "Round-trip failed for value: " + value);
        }
    }

    // ---- isJsonStart ----

    @Test
    void testIsJsonStartObject() {
        byte[] data = "{ \"key\": \"value\" }".getBytes();
        assertTrue(BlazorPackFrame.isJsonStart(data));
    }

    @Test
    void testIsJsonStartArray() {
        byte[] data = "[1, 2, 3]".getBytes();
        assertTrue(BlazorPackFrame.isJsonStart(data));
    }

    @Test
    void testIsJsonStartBinary() {
        byte[] data = { (byte) 0x90, 0x01, 0x02 }; // fixarray
        assertFalse(BlazorPackFrame.isJsonStart(data));
    }

    // ---- isBlazorPackData heuristic ----

    @Test
    void testIsBlazorPackDataValidVarintMsgPack() {
        // Encoded as: varint(4) + fixarray(3 items) + ints
        byte[] packed = new byte[]{ 0x04, (byte) 0x93, 0x01, 0x02, 0x03 };
        assertTrue(BlazorPackFrame.isBlazorPackData(packed));
    }

    @Test
    void testIsBlazorPackDataJsonLiteral() {
        byte[] data = "{ \"type\": 1 }".getBytes();
        assertTrue(BlazorPackFrame.isBlazorPackData(data));
    }

    @Test
    void testIsBlazorPackDataTooShort() {
        byte[] data = { 0x00 };
        assertFalse(BlazorPackFrame.isBlazorPackData(data));
    }

    @Test
    void testIsBlazorPackDataNullSafe() {
        assertFalse(BlazorPackFrame.isBlazorPackData(null));
    }

    // ---- isTruncatedFrame ----

    @Test
    void testIsTruncatedFrameComplete() {
        // [varint=3][3 bytes payload] — complete
        byte[] data = { 0x03, (byte) 0x93, 0x01, 0x02 };
        assertFalse(BlazorPackFrame.isTruncatedFrame(data));
    }

    @Test
    void testIsTruncatedFrameTruncated() {
        // [varint=10][only 2 bytes available] — truncated
        byte[] data = { 0x0A, 0x01, 0x02 };
        assertTrue(BlazorPackFrame.isTruncatedFrame(data));
    }

    @Test
    void testIsTruncatedFrameVarintTruncated() {
        byte[] data = { (byte) 0x80, 0x01 }; // continuation bit set + extra byte (still truncated)
        assertTrue(BlazorPackFrame.isTruncatedFrame(data));
    }

    // ---- isMsgPackContainer helpers (via isBlazorPackData) ----

    @Test
    void testIsBlazorPackDataBareFixMap() {
        byte[] data = { (byte) 0x81, 0x01, 0x02 }; // fixmap with 1 entry
        assertTrue(BlazorPackFrame.isBlazorPackData(data));
    }

    @Test
    void testIsBlazorPackDataBareFixArray() {
        byte[] data = { (byte) 0x90, 0x00 }; // empty fixarray + extra byte
        assertTrue(BlazorPackFrame.isBlazorPackData(data));
    }
}
