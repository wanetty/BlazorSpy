package blazorpack;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JSON-to-BlazorPack encoding, round-trip integrity,
 * and SignalR argument preservation.
 */
class BlazorPackEncoderTest {

    // ---- Basic encode ----

    @Test
    void testEncodeSimpleMap() throws Exception {
        String json = "{\"a\":1,\"b\":\"hello\"}";
        byte[] encoded = BlazorPackEncoder.encode(json);

        // Should start with a varint
        assertTrue(encoded.length > 2);

        // Decode the varint
        int[] pos = { 0 };
        int msgLen = BlazorPackFrame.readVarint(encoded, pos);
        assertTrue(msgLen > 0);
        assertEquals(encoded.length, pos[0] + msgLen);
    }

    @Test
    void testEncodeSimpleArray() throws Exception {
        String json = "[1,2,3]";
        byte[] encoded = BlazorPackEncoder.encode(json);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
    }

    // ---- Round-trip ----

    @Test
    void testRoundTripMapValues() throws Exception {
        Map<String, Object> original = new LinkedHashMap<>();
        original.put("string", "hello");
        original.put("int", 42);
        original.put("bool", true);

        // We can't directly encode a Map, but we can go through JSON
        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(Collections.singletonList(original));

        // Encode
        byte[] encoded = BlazorPackEncoder.encode(json);

        // Decode via decodeWithBuffer
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(encoded, null);
        assertFalse(result.hasTruncatedFrame);
        assertFalse(result.messages.isEmpty());

        // The outer wrapper may differ, but values should match
        String decoded = BlazorPackDecoder.prettyPrint(result.messages, false);
        assertTrue(decoded.contains("hello"));
        assertTrue(decoded.contains("42"));
    }

    @Test
    void testRoundTripMultipleMessages() throws Exception {
        // Two messages in multi-message format: [["msg1",1],["msg2",2]]
        // Outer list: first element is a List -> encoder treats as multi-message
        String json = "[[\"msg1\",1],[\"msg2\",2]]";
        byte[] encoded = BlazorPackEncoder.encode(json);

        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(encoded, null);
        assertFalse(result.hasTruncatedFrame);
        assertEquals(2, result.messages.size());
    }

    @Test
    void testRoundTripNestedStructures() throws Exception {
        String json = "{\"arr\":[1,2,3],\"obj\":{\"x\":\"y\"}}";
        byte[] encoded = BlazorPackEncoder.encode(json);

        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(encoded, null);
        assertFalse(result.hasTruncatedFrame);
        String decoded = BlazorPackDecoder.prettyPrint(result.messages, false);
        assertTrue(decoded.contains("\"arr\""));
        assertTrue(decoded.contains("\"obj\""));
        assertTrue(decoded.contains("\"x\""));
    }

    // ---- json_raw pass-through ----

    @Test
    void testJsonRawPassThrough() throws Exception {
        String json = "{\"__type\":\"json_raw\",\"data\":\"raw text\"}";
        byte[] encoded = BlazorPackEncoder.encode(json);
        assertNotNull(encoded);
        // Should return the raw data as bytes (with record separator)
        String encodedStr = new String(encoded, StandardCharsets.UTF_8);
        assertTrue(encodedStr.startsWith("raw text"));
    }

    // ---- String encoding uses binary type (bin_type) ----

    @Test
    void testStringPackedAsBinary() throws Exception {
        String json = "{\"key\":\"value\"}";
        byte[] encoded = BlazorPackEncoder.encode(json);

        // Find the MessagePack payload after varint
        int[] pos = { 0 };
        int msgLen = BlazorPackFrame.readVarint(encoded, pos);
        byte[] msgpackPayload = Arrays.copyOfRange(encoded, pos[0], pos[0] + msgLen);

        // Encode wraps single map as [[map]], so outer is array
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(msgpackPayload);

        // Outer: FIXARRAY with 1 element (the singletonList wrapping)
        org.msgpack.core.MessageFormat outerFormat = unpacker.getNextFormat();
        assertTrue(outerFormat == org.msgpack.core.MessageFormat.FIXARRAY
            || outerFormat == org.msgpack.core.MessageFormat.ARRAY16
            || outerFormat == org.msgpack.core.MessageFormat.ARRAY32,
            "Expected outer array, got: " + outerFormat);
        int outerSize = unpacker.unpackArrayHeader();
        assertEquals(1, outerSize);

        // Map (wrapped directly inside the array)
        org.msgpack.core.MessageFormat mapFormat = unpacker.getNextFormat();
        boolean isMap = mapFormat == org.msgpack.core.MessageFormat.FIXMAP
            || mapFormat == org.msgpack.core.MessageFormat.MAP16
            || mapFormat == org.msgpack.core.MessageFormat.MAP32;
        assertTrue(isMap, "Expected map, got: " + mapFormat);

        int mapSize = unpacker.unpackMapHeader();
        // Key should be a binary-encoded string
        org.msgpack.core.MessageFormat keyFormat = unpacker.getNextFormat();
        boolean isBin = keyFormat == org.msgpack.core.MessageFormat.BIN8
            || keyFormat == org.msgpack.core.MessageFormat.BIN16
            || keyFormat == org.msgpack.core.MessageFormat.BIN32;
        assertTrue(isBin, "Expected binary header for string key, got: " + keyFormat);
    }

    // ---- SignalR argument preservation ----

    @Test
    void testCollapseEmbeddedJsonPreservesSignalRArgs() throws Exception {
        // Simulate a SignalR BeginInvokeDotNetFromJS frame structure:
        // [1, "BeginInvokeDotNetFromJS", ..., ..., ..., {"json":"arg"}]
        List<Object> signalrFrame = new ArrayList<>();
        signalrFrame.add(1L);                        // type
        signalrFrame.add("BeginInvokeDotNetFromJS"); // method (index 1)
        signalrFrame.add("target");                   // target
        signalrFrame.add(0L);                         // index 3
        signalrFrame.add(new LinkedHashMap<>(        // index 4 — should stay as JSON string
            Collections.singletonMap("arg", "value")));

        Object collapsed = BlazorPackEncoder.collapseEmbeddedJson(signalrFrame);
        assertTrue(collapsed instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) collapsed;
        // Index 4 should have been collapsed to a JSON string
        assertTrue(result.get(4) instanceof String, "SignalR arg at index 4 should be a JSON string");
        assertTrue(((String) result.get(4)).contains("\"arg\""));
    }

    @Test
    void testCollapseEmbeddedJsonNonSignalRPreservesStructure() {
        List<Object> frame = new ArrayList<>();
        frame.add(1L);
        frame.add("NotASignalRMethod");
        frame.add("target");
        frame.add(0L);
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("key", "value");
        frame.add(nested);

        Object collapsed = BlazorPackEncoder.collapseEmbeddedJson(frame);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) collapsed;
        // For non-SignalR methods, index 4 stays as a Map
        assertTrue(result.get(4) instanceof Map);
    }
}
