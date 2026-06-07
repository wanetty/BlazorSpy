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
        // Real nested BeginInvokeDotNetFromJS: JSON arg at args[4]
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());               // [1] Headers
        outerFrame.add(null);                                // [2] InvocationId
        outerFrame.add("BeginInvokeDotNetFromJS");           // [3] Target

        List<Object> args = new ArrayList<>();
        args.add("callbackId");
        args.add("asyncHandle");
        args.add("DispatchEventAsync");
        args.add(1);
        args.add("{\"arg1\":\"value1\",\"arg2\":42}");       // args[4] — JSON arg
        outerFrame.add(args);                                // [4] Arguments

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(outerFrame), true);

        // Should have expanded the JSON string at args[4]
        assertTrue(output.contains("\"arg1\""), "arg1 key should be expanded");
        assertTrue(output.contains("\"value1\""));
        assertTrue(output.contains("\"arg2\""));
        assertTrue(output.contains("42"));
        assertFalse(output.contains("{\\\"arg1\\\""), "Should not be JSON-escaped");
    }

    @Test
    void testExpandEmbeddedJsonNonSignalRMethod() {
        // Non-SignalR method: no expansion should occur
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add(null);
        outerFrame.add("MyCustomMethod");  // NOT in SIGNALR_JSON_ARG_METHODS

        List<Object> args = new ArrayList<>();
        args.add("{\"should\":\"not be expanded\"}");
        outerFrame.add(args);

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(outerFrame), true);

        // Should NOT expand — encoder won't collapse it back
        assertFalse(output.contains("\"should\""),
            "Non-SignalR method: should not expand embedded JSON");
        assertTrue(output.contains("{\\\"should\\\""));
    }

    @Test
    void testExpandEmbeddedJsonOnlySignalRArgIndex() {
        // For BeginInvokeDotNetFromJS, only args[4] should be expanded.
        // args[0], args[1], args[2], args[3] should remain strings even if JSON-looking.
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add(null);
        outerFrame.add("BeginInvokeDotNetFromJS");

        List<Object> args = new ArrayList<>();
        args.add("[\"fake-json-at-index-0\"]");  // args[0] — JSON-looking, should NOT expand
        args.add("null");                          // args[1]
        args.add("DispatchEventAsync");            // args[2]
        args.add(1);                               // args[3]
        args.add("{\"real\":\"args\"}");           // args[4] — should expand
        outerFrame.add(args);

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(outerFrame), true);

        // args[0] should NOT be expanded (not at the SignalR-specific index)
        assertTrue(output.contains("\\\"fake-json-at-index-0\\\""),
            "args[0] should remain as JSON-escaped string");
        // args[4] should be expanded
        assertTrue(output.contains("\"real\""),
            "args[4] should be expanded");
        assertTrue(output.contains("\"args\""));
    }

    @Test
    void testExpandEmbeddedJsonDispatchEventAsync() {
        // DispatchEventAsync: JSON arg at args[1]
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add(null);
        outerFrame.add("DispatchEventAsync");

        List<Object> args = new ArrayList<>();
        args.add("targetElementId");
        args.add("{\"eventArgs\":\"data\"}");   // args[1] — JSON arg
        outerFrame.add(args);

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(outerFrame), true);
        assertTrue(output.contains("\"eventArgs\""), "DispatchEventAsync args[1] should expand");
        assertTrue(output.contains("\"data\""));
    }

    @Test
    void testExpandEmbeddedJsonEndInvokeJSFromDotNet() {
        // EndInvokeJSFromDotNet: JSON arg at args[2]
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add("callbackId");
        outerFrame.add("EndInvokeJSFromDotNet");

        List<Object> args = new ArrayList<>();
        args.add(3);
        args.add(true);
        args.add("{\"result\":\"success\"}");  // args[2] — JSON arg
        outerFrame.add(args);

        String output = BlazorPackDecoder.prettyPrint(
            Collections.singletonList(outerFrame), true);
        assertTrue(output.contains("\"result\""));
        assertTrue(output.contains("\"success\""));
    }
}
