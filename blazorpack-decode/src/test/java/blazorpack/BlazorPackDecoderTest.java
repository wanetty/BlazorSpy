package blazorpack;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MessagePack decoding, JSON literal pass-through,
 * truncated frame detection, and pretty-printing.
 */
class BlazorPackDecoderTest {

    // ---- JSON literal pass-through ----

    @Test
    void testDecodeJsonLiteral() {
        String json = "{\"hello\":\"world\"}";
        List<Object> result = BlazorPackDecoder.decode(json.getBytes());
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result.get(0);
        assertEquals("world", map.get("hello"));
    }

    @Test
    void testDecodeJsonLiteralArray() {
        String json = "[1,2,3]";
        List<Object> result = BlazorPackDecoder.decode(json.getBytes());
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof List);
    }

    // ---- Varint + MessagePack framing ----

    @Test
    void testDecodeVarintFramedSimpleArray() throws Exception {
        // Build a BlazorPack frame: varint + msgpack fixarray[3 ints]
        byte[] msgpack = new byte[]{
            (byte) 0x93, 0x01, 0x02, 0x03  // fixarray(3): 1, 2, 3
        };
        byte[] varint = BlazorPackFrame.writeVarint(msgpack.length);
        byte[] frame = new byte[varint.length + msgpack.length];
        System.arraycopy(varint, 0, frame, 0, varint.length);
        System.arraycopy(msgpack, 0, frame, varint.length, msgpack.length);

        List<Object> result = BlazorPackDecoder.decode(frame);
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> arr = (List<Object>) result.get(0);
        assertEquals(3, arr.size());
    }

    @Test
    void testDecodeVarintFramedMap() throws Exception {
        // Build a MessagePack map: { "a": 1, "b": 2 }
        org.msgpack.core.MessageBufferPacker packer =
            org.msgpack.core.MessagePack.newDefaultBufferPacker();
        packer.packMapHeader(2);
        packer.packString("a");
        packer.packInt(1);
        packer.packString("b");
        packer.packInt(2);
        packer.close();
        byte[] msgpack = packer.toByteArray();

        byte[] varint = BlazorPackFrame.writeVarint(msgpack.length);
        byte[] frame = new byte[varint.length + msgpack.length];
        System.arraycopy(varint, 0, frame, 0, varint.length);
        System.arraycopy(msgpack, 0, frame, varint.length, msgpack.length);

        List<Object> result = BlazorPackDecoder.decode(frame);
        assertEquals(1, result.size());
        assertTrue(result.get(0) instanceof Map);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result.get(0);
        assertEquals(1L, map.get("a"));
        assertEquals(2L, map.get("b"));
    }

    // ---- decodeWithBuffer ----

    @Test
    void testDecodeWithBufferNoPrefix() {
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(new byte[]{ 0x00 }, null);
        assertEquals(0, result.remainder.length);
        assertFalse(result.hasTruncatedFrame);
    }

    @Test
    void testDecodeWithBufferEmptyInput() {
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(new byte[0], null);
        assertEquals(0, result.remainder.length);
        assertFalse(result.hasTruncatedFrame);
        // Should produce an error marker
        assertEquals(1, result.messages.size());
    }

    @Test
    void testDecodeWithBufferPrefixIsPrepended() throws Exception {
        // First half: [varint=7]
        // Second half: [msgpack with 3 bytes payload]
        // Together they form a complete frame
        byte[] fullMsgpack = new byte[]{ (byte) 0x93, 0x01, 0x02, 0x03 };
        byte[] fullVarint = BlazorPackFrame.writeVarint(fullMsgpack.length);
        byte[] prefix = new byte[]{ fullVarint[0] }; // first varint byte
        byte[] suffix = new byte[fullVarint.length - 1 + fullMsgpack.length];
        System.arraycopy(fullVarint, 1, suffix, 0, fullVarint.length - 1);
        System.arraycopy(fullMsgpack, 0, suffix, fullVarint.length - 1, fullMsgpack.length);

        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(suffix, prefix);
        assertFalse(result.hasTruncatedFrame);
        assertEquals(0, result.remainder.length);
        assertEquals(1, result.messages.size());
    }

    // ---- Truncated frame detection ----

    @Test
    void testDecodeTruncatedFrame() {
        // Varint says 100 bytes, but only 3 bytes available
        byte[] frame = new byte[]{ 100, 0x01, 0x02, 0x03 };
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(frame, null);
        assertTrue(result.hasTruncatedFrame);
        assertTrue(result.remainder.length > 0);
    }

    // ---- prettyPrint ----

    @Test
    void testPrettyPrintSingleMessage() {
        List<Object> messages = new ArrayList<>();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", "value");
        messages.add(map);

        String output = BlazorPackDecoder.prettyPrint(messages, false);
        assertTrue(output.contains("\"key\""));
        assertTrue(output.contains("\"value\""));
    }

    @Test
    void testPrettyPrintMultiMessage() {
        List<Object> messages = new ArrayList<>();
        messages.add(Collections.singletonMap("a", 1L));
        messages.add(Collections.singletonMap("b", 2L));

        String output = BlazorPackDecoder.prettyPrint(messages, false);
        assertTrue(output.contains("\"a\""));
        assertTrue(output.contains("\"b\""));
    }

    @Test
    void testPrettyPrintWithAnnotationsNoTruncation() {
        List<Object> messages = new ArrayList<>();
        messages.add(Collections.singletonMap("ok", "yes"));

        String output = BlazorPackDecoder.prettyPrintWithAnnotations(messages, false);
        assertTrue(output.contains("(complete)"));
    }

    @Test
    void testPrettyPrintWithAnnotationsTruncated() {
        List<Object> messages = new ArrayList<>();
        Map<String, Object> truncated = new LinkedHashMap<>();
        truncated.put("__type", "truncated_frame");
        truncated.put("declared_length", 100);
        truncated.put("available_bytes", 3);
        truncated.put("missing_bytes", 97);
        truncated.put("offset", 0);
        truncated.put("hex", "010203");
        messages.add(truncated);

        String output = BlazorPackDecoder.prettyPrintWithAnnotations(messages, true);
        assertTrue(output.contains("TRUNCATED FRAME"));
        assertTrue(output.contains("INCOMPLETE"));
    }

    // ---- expandEmbeddedJson (tested via prettyPrint) ----

    @Test
    void testExpandEmbeddedJson() {
        List<Object> messages = new ArrayList<>();
        Map<String, Object> map = new LinkedHashMap<>();
        // String value that looks like JSON
        map.put("nested", "{\"inner\": 42}");
        messages.add(map);

        String output = BlazorPackDecoder.prettyPrint(messages, true);
        // Should have expanded the nested JSON string into an actual object
        assertTrue(output.contains("inner"));
        assertTrue(output.contains("42"));
        assertFalse(output.contains("{\\\"inner\\\""));
    }
}
