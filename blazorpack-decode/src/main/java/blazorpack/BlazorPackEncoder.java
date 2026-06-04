package blazorpack;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageBufferPacker;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Encodes JSON back into BlazorPack binary format (varint32 + MessagePack).
 */
public class BlazorPackEncoder {

    private static final Gson GSON = new Gson();
    private static final Set<String> SIGNALR_JSON_ARG_METHODS = new HashSet<>(Arrays.asList(
        "BeginInvokeDotNetFromJS", "DispatchEventAsync", "EndInvokeJSFromDotNet"
    ));

    private BlazorPackEncoder() {} // static utility

    /**
     * Encodes a JSON string into BlazorPack binary bytes.
     * Accepts a single object, an array of arrays (multi-message), or json_raw pass-through.
     */
    public static byte[] encode(String jsonStr) throws IOException {
        Object parsed = GSON.fromJson(jsonStr, Object.class);

        // json_raw pass-through (un-decoded JSON literal frames)
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            if ("json_raw".equals(map.get("__type"))) {
                String raw = (String) map.getOrDefault("data", "");
                return (raw + "").getBytes(StandardCharsets.UTF_8);
            }
        }

        // Normalize to list of messages
        List<Object> messages;
        if (parsed instanceof Map) {
            // A single dict is a single message — wrap it
            messages = Collections.singletonList(Collections.singletonList(parsed));
        } else if (parsed instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> topList = (List<Object>) parsed;
            // If first element is a list, it's already multi-message format
            if (!topList.isEmpty() && topList.get(0) instanceof List) {
                messages = topList;
            } else {
                // Single message: wrap it
                messages = Collections.singletonList(topList);
            }
        } else {
            messages = Collections.singletonList(Collections.singletonList(parsed));
        }

        // Encode each message with varint prefix
        byte[][] encoded = new byte[messages.size()][];
        int totalLen = 0;
        for (int i = 0; i < messages.size(); i++) {
            Object msg = collapseEmbeddedJson(messages.get(i));
            byte[] packed = packMsgPack(msg);
            byte[] varint = BlazorPackFrame.writeVarint(packed.length);
            encoded[i] = new byte[varint.length + packed.length];
            System.arraycopy(varint, 0, encoded[i], 0, varint.length);
            System.arraycopy(packed, 0, encoded[i], varint.length, packed.length);
            totalLen += encoded[i].length;
        }

        // Concatenate all frames
        byte[] result = new byte[totalLen];
        int offset = 0;
        for (byte[] frame : encoded) {
            System.arraycopy(frame, 0, result, offset, frame.length);
            offset += frame.length;
        }
        return result;
    }

    /**
     * Packs a Java object into MessagePack binary using bin_type (matching Blazor).
     */
    private static byte[] packMsgPack(Object obj) throws IOException {
        MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
        packValue(packer, obj);
        packer.close();
        return packer.toByteArray();
    }

    /**
     * Recursively packs a Java object into the MessagePack buffer.
     */
    @SuppressWarnings("unchecked")
    private static void packValue(MessageBufferPacker packer, Object obj) throws IOException {
        if (obj == null) {
            packer.packNil();
        } else if (obj instanceof Boolean) {
            packer.packBoolean((Boolean) obj);
        } else if (obj instanceof Integer) {
            packer.packInt((Integer) obj);
        } else if (obj instanceof Long) {
            packer.packLong((Long) obj);
        } else if (obj instanceof Double) {
            packer.packDouble((Double) obj);
        } else if (obj instanceof Float) {
            packer.packDouble(((Float) obj).doubleValue());
        } else if (obj instanceof String) {
            // Pack as binary string (use_bin_type=true in Python version)
            byte[] strBytes = ((String) obj).getBytes(StandardCharsets.UTF_8);
            packer.packBinaryHeader(strBytes.length);
            packer.writePayload(strBytes);
        } else if (obj instanceof byte[]) {
            byte[] bytes = (byte[]) obj;
            packer.packBinaryHeader(bytes.length);
            packer.writePayload(bytes);
        } else if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            packer.packArrayHeader(list.size());
            for (Object item : list) {
                packValue(packer, item);
            }
        } else if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            packer.packMapHeader(map.size());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                packValue(packer, entry.getKey());
                packValue(packer, entry.getValue());
            }
        } else if (obj instanceof Object[]) {
            // Array from Gson parsing
            Object[] arr = (Object[]) obj;
            packer.packArrayHeader(arr.length);
            for (Object item : arr) {
                packValue(packer, item);
            }
        } else {
            // Fallback: pack as string representation
            byte[] strBytes = obj.toString().getBytes(StandardCharsets.UTF_8);
            packer.packBinaryHeader(strBytes.length);
            packer.writePayload(strBytes);
        }
    }

    // ---- Embedded JSON collapse ----

    /**
     * Recursively collapses expanded JSON objects back to strings,
     * preserving SignalR method argument boundaries.
     */
    @SuppressWarnings("unchecked")
    static Object collapseEmbeddedJson(Object obj) {
        return collapse(obj, false, -1);
    }

    private static Object collapse(Object obj, boolean parentIsSignalR, int fieldIndex) {
        if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            // Detect SignalR invocation: [type, target, ...] where target is in our whitelist
            boolean isSignalR = list.size() >= 3
                && list.get(1) instanceof String
                && SIGNALR_JSON_ARG_METHODS.contains(list.get(1));

            List<Object> result = new ArrayList<>();
            int i = 0;
            for (Object item : list) {
                // In SignalR frames, the 4th argument (index 4) stays as JSON string
                if (isSignalR && i == 4 && !(item instanceof String)) {
                    result.add(GSON.toJson(item));
                } else {
                    result.add(collapse(item, isSignalR, i));
                }
                i++;
            }
            return result;
        }
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                result.put(entry.getKey(), collapse(entry.getValue(), parentIsSignalR, -1));
            }
            return result;
        }
        return obj;
    }
}
