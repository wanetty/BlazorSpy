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
    void testEncodeSimpleMapAsJsonLiteral() throws Exception {
        String json = "{\"a\":1,\"b\":\"hello\"}";
        byte[] encoded = BlazorPackEncoder.encode(json);

        // Should be a JSON literal ending with 0x1e (record separator)
        assertTrue(encoded.length > 2);
        assertEquals('{', encoded[0]);
        assertEquals(0x1e, encoded[encoded.length - 1]);
        
        // Decode it back and verify
        String decodedStr = new String(encoded, 0, encoded.length - 1, StandardCharsets.UTF_8);
        assertTrue(decodedStr.contains("\"a\":1"));
        assertTrue(decodedStr.contains("\"b\":\"hello\""));
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
    void testStringPackedAsString() throws Exception {
        // Use a List containing a Map so it encodes as a MessagePack array containing a Map
        String json = "[{\"key\":\"value\"}]";
        byte[] encoded = BlazorPackEncoder.encode(json);

        // Find the MessagePack payload after varint
        int[] pos = { 0 };
        int msgLen = BlazorPackFrame.readVarint(encoded, pos);
        byte[] msgpackPayload = Arrays.copyOfRange(encoded, pos[0], pos[0] + msgLen);

        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(msgpackPayload);

        // Outer array
        org.msgpack.core.MessageFormat outerFormat = unpacker.getNextFormat();
        assertTrue(outerFormat == org.msgpack.core.MessageFormat.FIXARRAY
            || outerFormat == org.msgpack.core.MessageFormat.ARRAY16
            || outerFormat == org.msgpack.core.MessageFormat.ARRAY32,
            "Expected outer array, got: " + outerFormat);
        int outerSize = unpacker.unpackArrayHeader();
        assertEquals(1, outerSize);

        // Map inside array
        org.msgpack.core.MessageFormat mapFormat = unpacker.getNextFormat();
        boolean isMap = mapFormat == org.msgpack.core.MessageFormat.FIXMAP
            || mapFormat == org.msgpack.core.MessageFormat.MAP16
            || mapFormat == org.msgpack.core.MessageFormat.MAP32;
        assertTrue(isMap, "Expected map, got: " + mapFormat);

        int mapSize = unpacker.unpackMapHeader();
        // Key should be a string-encoded string (STR type), NOT binary (BIN)
        org.msgpack.core.MessageFormat keyFormat = unpacker.getNextFormat();
        boolean isStr = keyFormat == org.msgpack.core.MessageFormat.FIXSTR
            || keyFormat == org.msgpack.core.MessageFormat.STR8
            || keyFormat == org.msgpack.core.MessageFormat.STR16
            || keyFormat == org.msgpack.core.MessageFormat.STR32;
        assertTrue(isStr,
            "Expected STR header for string key, got: " + keyFormat
            + " — BIN type causes SignalR deserialization to throw InvalidOperationException");
    }

    // ---- SignalR argument preservation ----

    @Test
    void testCollapseEmbeddedJsonPreservesSignalRArgs() throws Exception {
        // Real nested SignalR invocation frame:
        // [1, {}, invocationId, "BeginInvokeDotNetFromJS", ["2","null","DispatchEventAsync",1,{"arg":"value"}]]
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);                              // [0] MessageType
        outerFrame.add(new LinkedHashMap<>());           // [1] Headers
        outerFrame.add(null);                            // [2] InvocationId
        outerFrame.add("BeginInvokeDotNetFromJS");       // [3] Target

        List<Object> args = new ArrayList<>();
        args.add("2");                                   // args[0]
        args.add("null");                                // args[1]
        args.add("DispatchEventAsync");                  // args[2]
        args.add(1);                                     // args[3]
        args.add(new LinkedHashMap<>(                    // args[4] — JSON arg
            Collections.singletonMap("arg", "value")));
        outerFrame.add(args);                            // [4] Arguments

        Object collapsed = BlazorPackEncoder.collapseEmbeddedJson(outerFrame);
        assertTrue(collapsed instanceof List);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) collapsed;
        @SuppressWarnings("unchecked")
        List<Object> resultArgs = (List<Object>) result.get(4);
        // args[4] should be collapsed to a JSON string
        assertTrue(resultArgs.get(4) instanceof String,
            "BeginInvokeDotNetFromJS args[4] should be a JSON string");
        assertTrue(((String) resultArgs.get(4)).contains("\"arg\""));
    }

    @Test
    void testCollapseEmbeddedJsonNonSignalRPreservesStructure() {
        // Non-SignalR method — no collapsing should occur
        List<Object> frame = new ArrayList<>();
        frame.add(1L);
        frame.add(new LinkedHashMap<>());
        frame.add(null);
        frame.add("MyCustomMethod");  // NOT in SIGNALR_JSON_ARG_METHODS

        List<Object> args = new ArrayList<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("key", "value");
        args.add(nested);
        frame.add(args);

        Object collapsed = BlazorPackEncoder.collapseEmbeddedJson(frame);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) collapsed;
        @SuppressWarnings("unchecked")
        List<Object> resultArgs = (List<Object>) result.get(4);
        // For non-SignalR methods, args should retain their Map structure
        assertTrue(resultArgs.get(0) instanceof Map);
    }

    // ---- Round-trip with embedded JSON expansion ----

    @Test
    void testRoundTripSignalRIndex4WithExpansion() throws Exception {
        // Real nested BeginInvokeDotNetFromJS frame
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add("cb123");
        outerFrame.add("BeginInvokeDotNetFromJS");

        List<Object> args = new ArrayList<>();
        args.add("2");
        args.add("null");
        args.add("DispatchEventAsync");
        args.add(1);
        args.add("{\"arg1\":42,\"arg2\":\"hello\"}");
        outerFrame.add(args);

        // Encode to binary as single-frame message
        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(Collections.singletonList(outerFrame));
        byte[] originalBinary = BlazorPackEncoder.encode(json);

        // Decode with expansion
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(originalBinary, null);
        assertFalse(result.hasTruncatedFrame);

        String display = BlazorPackDecoder.prettyPrint(result.messages, true);

        // Re-encode
        String cleanJson = stripCommentLines(display);
        byte[] reEncodedBinary = BlazorPackEncoder.encode(cleanJson);

        // Verify the critical property: args[4] must be a JSON string
        BlazorPackDecoder.DecodeResult reResult =
            BlazorPackDecoder.decodeWithBuffer(reEncodedBinary, null);
        assertFalse(reResult.hasTruncatedFrame);
        assertEquals(1, reResult.messages.size());

        @SuppressWarnings("unchecked")
        List<Object> reFrame = (List<Object>) reResult.messages.get(0);
        assertEquals("BeginInvokeDotNetFromJS", reFrame.get(3),
            "SignalR method name must be preserved");

        @SuppressWarnings("unchecked")
        List<Object> reArgs = (List<Object>) reFrame.get(4);
        assertTrue(reArgs.get(4) instanceof String,
            "BeginInvokeDotNetFromJS args[4] must be a JSON string, not a Map — "
            + "otherwise the server drops the WebSocket connection");
        String argsJson = (String) reArgs.get(4);
        assertTrue(argsJson.contains("\"arg1\""));
        assertTrue(argsJson.contains("\"hello\""));
    }

    @Test
    void testRoundTripNonSignalRWithExpansion() throws Exception {
        // Non-SignalR method: JSON-looking string at index 3 of Arguments
        // should NOT be expanded or collapsed
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add(null);
        outerFrame.add("MyHubMethod");

        List<Object> args = new ArrayList<>();
        args.add("target");                    // args[0]
        args.add(0L);                           // args[1]
        args.add("{\"payload\":\"data\"}");    // args[2] — JSON-looking string
        outerFrame.add(args);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(Collections.singletonList(outerFrame));
        byte[] originalBinary = BlazorPackEncoder.encode(json);

        // Decode with expansion
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(originalBinary, null);
        String display = BlazorPackDecoder.prettyPrint(result.messages, true);

        // Re-encode
        String cleanJson = stripCommentLines(display);
        byte[] reEncodedBinary = BlazorPackEncoder.encode(cleanJson);

        // Verify the string at args[2] is still a string
        BlazorPackDecoder.DecodeResult reResult =
            BlazorPackDecoder.decodeWithBuffer(reEncodedBinary, null);
        assertFalse(reResult.hasTruncatedFrame);
        assertEquals(1, reResult.messages.size());

        @SuppressWarnings("unchecked")
        List<Object> reFrame = (List<Object>) reResult.messages.get(0);
        @SuppressWarnings("unchecked")
        List<Object> reArgs = (List<Object>) reFrame.get(4);
        assertTrue(reArgs.get(2) instanceof String,
            "Non-SignalR method: args[2] must remain a string");
        String payload = (String) reArgs.get(2);
        assertTrue(payload.contains("\"payload\""));
        assertTrue(payload.contains("\"data\""));
    }

    @Test
    void testRoundTripWithoutExpansion() throws Exception {
        // Round-trip WITHOUT expansion should always preserve structure
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add("cb123");
        outerFrame.add("BeginInvokeDotNetFromJS");

        List<Object> args = new ArrayList<>();
        args.add("2");
        args.add("null");
        args.add("DispatchEventAsync");
        args.add(1);
        args.add("{\"arg1\":42,\"arg2\":\"hello\"}");
        outerFrame.add(args);

        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(Collections.singletonList(outerFrame));
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
        assertEquals("BeginInvokeDotNetFromJS", reFrame.get(3));

        @SuppressWarnings("unchecked")
        List<Object> reArgs = (List<Object>) reFrame.get(4);
        // Without expansion, args[4] should still be a string
        assertTrue(reArgs.get(4) instanceof String,
            "args[4] should remain a string when expand is OFF");
    }

    @Test
    void testCollapseEmbeddedJsonDispatchEventAsync() {
        // DispatchEventAsync: JSON arg at args[1]
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add(null);
        outerFrame.add("DispatchEventAsync");

        List<Object> args = new ArrayList<>();
        args.add("targetElementId");
        Map<String, Object> jsonArg = new LinkedHashMap<>();
        jsonArg.put("eventArgs", "data");
        args.add(jsonArg);  // args[1] — JSON arg
        outerFrame.add(args);

        Object collapsed = BlazorPackEncoder.collapseEmbeddedJson(outerFrame);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) collapsed;
        @SuppressWarnings("unchecked")
        List<Object> resultArgs = (List<Object>) result.get(4);
        assertTrue(resultArgs.get(1) instanceof String,
            "DispatchEventAsync args[1] should be a JSON string");
        assertTrue(((String) resultArgs.get(1)).contains("\"eventArgs\""));
    }

    @Test
    void testCollapseEmbeddedJsonEndInvokeJSFromDotNet() {
        // EndInvokeJSFromDotNet: JSON arg at args[2]
        List<Object> outerFrame = new ArrayList<>();
        outerFrame.add(1L);
        outerFrame.add(new LinkedHashMap<>());
        outerFrame.add("callbackId");
        outerFrame.add("EndInvokeJSFromDotNet");

        List<Object> args = new ArrayList<>();
        args.add(3);
        args.add(true);
        Map<String, Object> jsonArg = new LinkedHashMap<>();
        jsonArg.put("result", "success");
        args.add(jsonArg);  // args[2] — JSON arg
        outerFrame.add(args);

        Object collapsed = BlazorPackEncoder.collapseEmbeddedJson(outerFrame);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) collapsed;
        @SuppressWarnings("unchecked")
        List<Object> resultArgs = (List<Object>) result.get(4);
        assertTrue(resultArgs.get(2) instanceof String,
            "EndInvokeJSFromDotNet args[2] should be a JSON string");
        assertTrue(((String) resultArgs.get(2)).contains("\"result\""));
    }

    // ---- normalizeNumbers ----

    @Test
    void testNormalizeNumbersConvertsIntegers() {
        // Gson returns these as Double — normalizeNumbers must fix them
        assertEquals(Integer.valueOf(42),  BlazorPackEncoder.normalizeNumbers(42.0));
        assertEquals(Integer.valueOf(1),   BlazorPackEncoder.normalizeNumbers(1.0));
        assertEquals(Integer.valueOf(-1),  BlazorPackEncoder.normalizeNumbers(-1.0));
        assertEquals(Integer.valueOf(0),   BlazorPackEncoder.normalizeNumbers(0.0));
        assertEquals(Long.valueOf(Integer.MAX_VALUE + 1L),
            BlazorPackEncoder.normalizeNumbers((double) (Integer.MAX_VALUE + 1L)));
    }

    @Test
    void testNormalizeNumbersPreservesDoubles() {
        // Fractional values must remain as Double
        assertEquals(Double.valueOf(3.14), BlazorPackEncoder.normalizeNumbers(3.14));
        assertEquals(Double.valueOf(-0.5), BlazorPackEncoder.normalizeNumbers(-0.5));
    }

    @Test
    void testNormalizeNumbersRecursesLists() {
        java.util.List<Object> list = new java.util.ArrayList<>();
        list.add(1.0);
        list.add("hello");
        list.add(3.14);
        java.util.List<Object> result = (java.util.List<Object>) BlazorPackEncoder.normalizeNumbers(list);

        assertEquals(Integer.valueOf(1), result.get(0), "Integer value at index 0 must be restored");
        assertEquals("hello", result.get(1), "String must be left unchanged");
        assertEquals(Double.valueOf(3.14), result.get(2), "Fractional double must be preserved");
    }

    @Test
    void testNormalizeNumbersRecursesMaps() {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("MessageType", 1.0);
        map.put("payload", "{\"val\":42}");
        map.put("ratio", 0.75);
        java.util.Map<String, Object> result = (java.util.Map<String, Object>)
            BlazorPackEncoder.normalizeNumbers(map);

        assertEquals(Integer.valueOf(1), result.get("MessageType"),
            "MessageType must be restored to Integer");
        assertEquals("{\"val\":42}", result.get("payload"),
            "String payload must be unchanged");
        assertEquals(Double.valueOf(0.75), result.get("ratio"),
            "Fractional ratio must be preserved as Double");
    }

    @Test
    void testNormalizeNumbersHandlesNullAndNonNumeric() {
        assertNull(BlazorPackEncoder.normalizeNumbers(null));
        assertEquals(Boolean.TRUE, BlazorPackEncoder.normalizeNumbers(Boolean.TRUE));
        assertEquals("abc", BlazorPackEncoder.normalizeNumbers("abc"));
    }

    // ---- Integer type preservation in full round-trip ----

    @Test
    void testMessageTypeIsIntegerInRoundTrip() throws Exception {
        // Simulate a real SignalR frame — MessageType=1 must stay as int
        String json = "[1,\"BeginInvokeDotNetFromJS\",\"2\",\"null\",\"DispatchEventAsync\",1,{}]";
        byte[] encoded = BlazorPackEncoder.encode(json);

        // Decode and verify MessageType is still an integer, not a Double
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(encoded, null);
        assertFalse(result.hasTruncatedFrame);
        assertEquals(1, result.messages.size());

        @SuppressWarnings("unchecked")
        java.util.List<Object> frame = (java.util.List<Object>) result.messages.get(0);
        assertTrue(frame.get(0) instanceof Number, "MessageType must be a Number");
        assertFalse(frame.get(0) instanceof Double,
            "MessageType must NOT be Double — would cause server to drop WS connection");
        assertEquals(1L, ((Number) frame.get(0)).longValue(), "MessageType must equal 1");
    }

    @Test
    void testIntegerFieldsSurviveRoundTrip() throws Exception {
        // Multiple integer values throughout the message
        String json = "{\"MessageType\":1,\"Headers\":0,\"InvocationId\":\"abc\",\"Arguments\":[42,-1,0]}";
        byte[] encoded = BlazorPackEncoder.encode(json);

        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(encoded, null);
        assertFalse(result.hasTruncatedFrame);

        String decoded = BlazorPackDecoder.prettyPrint(result.messages, false);
        assertTrue(decoded.contains("\"MessageType\""));
        // The value should be a raw integer, not a float
        assertFalse(decoded.contains(": 1.0"), "MessageType should not appear as 1.0");
        assertFalse(decoded.contains(": 42.0"), "Argument 42 should not appear as 42.0");
    }

    @Test
    void testFloatingPointValuesRoundTripCorrectly() throws Exception {
        // Messages with actual float values should still work
        String json = "{\"value\":3.14,\"MessageType\":1}";
        byte[] encoded = BlazorPackEncoder.encode(json);

        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(encoded, null);
        assertFalse(result.hasTruncatedFrame);

        String decoded = BlazorPackDecoder.prettyPrint(result.messages, false);
        assertTrue(decoded.contains("3.14"), "Float value must be preserved");
    }

    @Test
    void testRoundTripNestedSignalRArgumentsWithExpansion() throws Exception {
        // Nested SignalR Invocation Frame: [1, {}, null, "BeginInvokeDotNetFromJS", ["callbackId", "assemblyName", "methodIdentifier", 0, "{\"payload\":\"data\"}"]]
        List<Object> arguments = new ArrayList<>();
        arguments.add("callbackId");
        arguments.add("assemblyName");
        arguments.add("methodIdentifier");
        arguments.add(0);
        arguments.add("{\"payload\":\"data\"}");

        List<Object> frame = new ArrayList<>();
        frame.add(1);                        // MessageType = Invocation
        frame.add(new LinkedHashMap<>());    // Headers
        frame.add(null);                     // InvocationId
        frame.add("BeginInvokeDotNetFromJS"); // Target
        frame.add(arguments);                // Arguments

        com.google.gson.Gson gson = new com.google.gson.Gson();
        String json = gson.toJson(Collections.singletonList(frame));
        byte[] originalBinary = BlazorPackEncoder.encode(json);

        // Decode with expansion
        BlazorPackDecoder.DecodeResult result =
            BlazorPackDecoder.decodeWithBuffer(originalBinary, null);
        assertFalse(result.hasTruncatedFrame);

        String display = BlazorPackDecoder.prettyPrint(result.messages, true);
        // The display string should show the arguments JSON string as a nested JSON object (expanded)
        assertTrue(display.contains("\"payload\""));
        assertTrue(display.contains("\"data\""));

        // Re-encode
        String cleanJson = stripCommentLines(display);
        byte[] reEncodedBinary = BlazorPackEncoder.encode(cleanJson);

        // Decode again and verify it collapsed back to a JSON string in arguments[4]
        BlazorPackDecoder.DecodeResult reResult =
            BlazorPackDecoder.decodeWithBuffer(reEncodedBinary, null);
        assertFalse(reResult.hasTruncatedFrame);
        assertEquals(1, reResult.messages.size());

        @SuppressWarnings("unchecked")
        List<Object> reFrame = (List<Object>) reResult.messages.get(0);
        @SuppressWarnings("unchecked")
        List<Object> reArgs = (List<Object>) reFrame.get(4);
        assertTrue(reArgs.get(4) instanceof String, "Arguments[4] must collapse back to a JSON String");
        assertTrue(((String) reArgs.get(4)).contains("\"payload\""));
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
