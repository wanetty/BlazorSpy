package blazorspy;

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
    void testExpandEmbeddedJsonNonSignalRContext() {
        // Non-SignalR context: JSON strings should NOT be expanded
        // to prevent WebSocket disconnection on re-encode (the expand/collapse
        // functions must be symmetric — collapse only handles SignalR index 4).
        List<Object> messages = new ArrayList<>();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nested", "{\"inner\": 42}");
        messages.add(map);

        String output = BlazorPackDecoder.prettyPrint(messages, true);
        // Should NOT expand outside SignalR index 4 context
        assertTrue(output.contains("{\\\"inner\\\""));
        assertFalse(output.contains("\"inner\""));
    }

    @Test
    void testExpandEmbeddedJsonSignalRArgs() {
        // SignalR BeginInvokeDotNetFromJS: index 4 contains JSON args string
        List<Object> signalrFrame = new ArrayList<>();
        signalrFrame.add(1L);                              // type
        signalrFrame.add("BeginInvokeDotNetFromJS");        // method (index 1)
        signalrFrame.add("callbackId");                     // target
        signalrFrame.add("dotNetMethod");                   // method name
        signalrFrame.add("{\"arg1\":\"value1\",\"arg2\":42}"); // index 4 — JSON args

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(signalrFrame), true);

        // Should have expanded the JSON string at index 4
        assertTrue(output.contains("\"arg1\""));
        assertTrue(output.contains("\"value1\""));
        assertTrue(output.contains("\"arg2\""));
        assertTrue(output.contains("42"));
        // The JSON string should appear as unquoted object, not escaped
        assertFalse(output.contains("{\\\"arg1\\\""));
    }

    @Test
    void testExpandEmbeddedJsonNonSignalRMethod() {
        // A non-SignalR method with JSON-looking string at index 4
        List<Object> frame = new ArrayList<>();
        frame.add(1L);
        frame.add("MyCustomMethod");  // NOT in SIGNALR_JSON_ARG_METHODS
        frame.add("target");
        frame.add(0L);
        frame.add("{\"should\":\"not be expanded\"}");

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(frame), true);

        // Should NOT expand — encoder won't collapse it back
        assertFalse(output.contains("\"should\""));
        assertTrue(output.contains("{\\\"should\\\""));
    }

    @Test
    void testExpandEmbeddedJsonNonIndex4() {
        // Even for SignalR methods, only index 4 should be expanded
        List<Object> frame = new ArrayList<>();
        frame.add(1L);
        frame.add("BeginInvokeDotNetFromJS");
        frame.add("{\"fake\":\"target\"}");  // index 2 — NOT index 4
        frame.add("methodName");
        frame.add("{\"real\":\"args\"}");     // index 4 — should be expanded

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(frame), true);

        // Index 2 should NOT be expanded (not index 4)
        assertTrue(output.contains("{\\\"fake\\\""));
        assertFalse(output.contains("\"fake\""));
        // Index 4 should be expanded
        assertTrue(output.contains("\"real\""));
        assertTrue(output.contains("\"args\""));
    }

    @Test
    void testExpandEmbeddedJsonDispatchEventAsync() {
        List<Object> frame = new ArrayList<>();
        frame.add(1L);
        frame.add("DispatchEventAsync");
        frame.add("target");
        frame.add("eventName");
        frame.add("{\"eventArgs\":\"data\"}");

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(frame), true);
        assertTrue(output.contains("\"eventArgs\""));
        assertTrue(output.contains("\"data\""));
    }

    @Test
    void testExpandEmbeddedJsonEndInvokeJSFromDotNet() {
        List<Object> frame = new ArrayList<>();
        frame.add(1L);
        frame.add("EndInvokeJSFromDotNet");
        frame.add("callbackId");
        frame.add(0L);
        frame.add("{\"result\":\"success\"}");

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(frame), true);
        assertTrue(output.contains("\"result\""));
        assertTrue(output.contains("\"success\""));
    }
}
