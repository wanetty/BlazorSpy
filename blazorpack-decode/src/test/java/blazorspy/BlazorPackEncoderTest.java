package blazorspy;

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

    // ---- Round-trip with embedded JSON expansion ----

    @Test
    void testRoundTripSignalRIndex4WithExpansion() throws Exception {
        // Build a SignalR BeginInvokeDotNetFromJS frame with JSON args at index 4
        List<Object> signalrFrame = new ArrayList<>();
        signalrFrame.add(1L);
        signalrFrame.add("BeginInvokeDotNetFromJS");
        signalrFrame.add("cb123");
        signalrFrame.add("dotNetMethod");
        signalrFrame.add("{\"arg1\":42,\"arg2\":\"hello\"}");

        // Encode to binary as a single-frame message
        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(Collections.singletonList(signalrFrame));
        byte[] originalBinary = BlazorPackEncoder.encode(json);

        // Decode with expansion (simulating "Expand embedded JSON" checkbox ON)
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(originalBinary, null);
        assertFalse(result.hasTruncatedFrame);

        String display = BlazorPackDecoder.prettyPrint(result.messages, true);

        // Simulate stripComments() from BlazorPackEditorBase
        String cleanJson = stripCommentLines(display);
        byte[] reEncodedBinary = BlazorPackEncoder.encode(cleanJson);

        // Decode the re-encoded binary and verify the critical property:
        // the SignalR arg at index 4 must be a JSON string (not a Map/List),
        // otherwise the server will reject the message and drop the connection.
        BlazorPackDecoder.DecodeResult reResult =
            BlazorPackDecoder.decodeWithBuffer(reEncodedBinary, null);
        assertFalse(reResult.hasTruncatedFrame);
        assertEquals(1, reResult.messages.size());

        @SuppressWarnings("unchecked")
        List<Object> reFrame = (List<Object>) reResult.messages.get(0);
        assertEquals("BeginInvokeDotNetFromJS", reFrame.get(1),
            "SignalR method name must be preserved");
        assertTrue(reFrame.get(4) instanceof String,
            "Index 4 arg must be a JSON string, not a Map — "
            + "otherwise the server drops the WebSocket connection");
        String argsJson = (String) reFrame.get(4);
        assertTrue(argsJson.contains("\"arg1\""));
        assertTrue(argsJson.contains("\"hello\""));
    }

    @Test
    void testRoundTripNonSignalRWithExpansion() throws Exception {
        // Non-SignalR message with JSON-looking string NOT at index 4.
        // This is the exact bug case: expand shouldn't touch it, and round-trip must work.
        List<Object> frame = new ArrayList<>();
        frame.add(1L);
        frame.add("MyHubMethod");
        frame.add("target");
        frame.add("{\"payload\":\"data\"}");  // index 3 — NOT expanded (not index 4)

        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(Collections.singletonList(frame));
        byte[] originalBinary = BlazorPackEncoder.encode(json);

        // Decode with expansion
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(originalBinary, null);
        String display = BlazorPackDecoder.prettyPrint(result.messages, true);

        // Re-encode
        String cleanJson = stripCommentLines(display);
        byte[] reEncodedBinary = BlazorPackEncoder.encode(cleanJson);

        // Verify the string at index 3 is still a string (wasn't accidentally expanded)
        BlazorPackDecoder.DecodeResult reResult =
            BlazorPackDecoder.decodeWithBuffer(reEncodedBinary, null);
        assertFalse(reResult.hasTruncatedFrame);
        assertEquals(1, reResult.messages.size());

        @SuppressWarnings("unchecked")
        List<Object> reFrame = (List<Object>) reResult.messages.get(0);
        assertTrue(reFrame.get(3) instanceof String,
            "Non-SignalR index 3 must remain a string, not be expanded to a Map");
        String payload = (String) reFrame.get(3);
        assertTrue(payload.contains("\"payload\""));
        assertTrue(payload.contains("\"data\""));
    }

    @Test
    void testRoundTripWithoutExpansion() throws Exception {
        // Decode WITHOUT expansion — round-trip should always work
        List<Object> signalrFrame = new ArrayList<>();
        signalrFrame.add(1L);
        signalrFrame.add("BeginInvokeDotNetFromJS");
        signalrFrame.add("cb123");
        signalrFrame.add("dotNetMethod");
        signalrFrame.add("{\"arg1\":42,\"arg2\":\"hello\"}");

        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(Collections.singletonList(signalrFrame));
        byte[] originalBinary = BlazorPackEncoder.encode(json);

        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(originalBinary, null);
        String display = BlazorPackDecoder.prettyPrint(result.messages, false);

        String cleanJson = stripCommentLines(display);
        byte[] reEncodedBinary = BlazorPackEncoder.encode(cleanJson);

        // Verify the message structure is intact
        BlazorPackDecoder.DecodeResult reResult =
            BlazorPackDecoder.decodeWithBuffer(reEncodedBinary, null);
        assertFalse(reResult.hasTruncatedFrame);
        assertEquals(1, reResult.messages.size());

        @SuppressWarnings("unchecked")
        List<Object> reFrame = (List<Object>) reResult.messages.get(0);
        assertEquals("BeginInvokeDotNetFromJS", reFrame.get(1));
        // Without expansion, index 4 should still be a string
        assertTrue(reFrame.get(4) instanceof String,
            "Index 4 should remain a string when expand is OFF");
    }

    /**
     * Strips comment lines (starting with //) from text, matching the logic
     * in BlazorPackEditorBase.stripComments().
     */
    private static String stripCommentLines(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }
}
