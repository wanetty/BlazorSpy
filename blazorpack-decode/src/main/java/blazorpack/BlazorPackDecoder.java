package blazorpack;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.MessageFormat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Decodes BlazorPack binary frames into Java objects and formats them as JSON.
 */
public class BlazorPackDecoder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private BlazorPackDecoder() {} // static utility

    // ---- Main entry ----

    /**
     * Decodes a raw byte array (possibly with HTTP headers) into a list of
     * decoded MessagePack objects, JSON objects, or error markers.
     */
    public static List<Object> decode(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return Collections.singletonList(error("Frame vacio", 0, null));
        }

        // Strip HTTP headers if present
        byte[] body = stripHttp(raw);

        // JSON literal?
        if (BlazorPackFrame.isJsonStart(body)) {
            try {
                String text = new String(body, StandardCharsets.UTF_8).replaceFirst("[\n\r]+$", "");
                Object parsed = new Gson().fromJson(text, Object.class);
                return Collections.singletonList(parsed);
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("__type", "json_raw");
                err.put("data", new String(body, StandardCharsets.UTF_8));
                return Collections.singletonList(err);
            }
        }

        // Varint + MessagePack framing
        List<Object> messages = new ArrayList<>();
        int pos = 0;
        int stuck = 0;
        int[] posHolder = new int[1];

        while (pos < body.length) {
            int startPos = pos;
            posHolder[0] = pos;

            // Try to read a varint-framed message
            int msgLen;
            try {
                msgLen = BlazorPackFrame.readVarint(body, posHolder);
            } catch (Exception e) {
                // Varint failed — try direct msgpack on remaining bytes
                try {
                    Object obj = unpackSingle(body, pos);
                    if (obj != null) messages.add(obj);
                } catch (Exception e2) {
                    messages.add(error("binary_undecodable", pos, body, e2));
                }
                break;
            }

            pos = posHolder[0];

            // Validate length
            if (msgLen <= 0 || pos + msgLen > body.length + 4) {
                try {
                    Object obj = unpackSingle(body, startPos);
                    if (obj != null) messages.add(obj);
                } catch (Exception e2) {
                    messages.add(error("binary_undecodable", startPos, body, e2));
                }
                break;
            }

            // Extract and decode the MessagePack payload
            byte[] msgData = Arrays.copyOfRange(body, pos, pos + msgLen);
            pos += msgLen;

            try {
                Object obj = unpackSingle(msgData, 0);
                messages.add(obj);
            } catch (Exception e) {
                messages.add(error("msgpack_error", startPos, msgData, e));
            }

            if (pos == startPos) {
                stuck++;
                if (stuck > 3) break;
            } else {
                stuck = 0;
            }
        }

        return messages;
    }

    // ---- MessagePack unpacking ----

    /**
     * Unpacks a single MessagePack value from the byte array starting at offset.
     * Handles bin type specially — tries UTF-8 decode, falls back to hex string.
     */
    private static Object unpackSingle(byte[] data, int offset) throws IOException {
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data, offset, data.length - offset);
        return unpackValue(unpacker);
    }

    /**
     * Recursively unpacks a MessagePack value from the given unpacker.
     * Converts binary data to UTF-8 strings when possible (matching Blazor behavior).
     */
    private static Object unpackValue(MessageUnpacker unpacker) throws IOException {
        MessageFormat mf = unpacker.getNextFormat();
        switch (mf) {
            case NIL:
                unpacker.unpackNil();
                return null;
            case BOOLEAN:
                return unpacker.unpackBoolean();
            case POSFIXINT: case NEGFIXINT:
            case INT8: case INT16: case INT32: case INT64:
            case UINT8: case UINT16: case UINT32: case UINT64:
                return unpacker.unpackLong();
            case FLOAT32: case FLOAT64:
                return unpacker.unpackDouble();
            case FIXSTR: case STR8: case STR16: case STR32:
                return unpacker.unpackString();
            case BIN8: case BIN16: case BIN32:
                int binLen = unpacker.unpackBinaryHeader();
                byte[] raw = new byte[binLen];
                unpacker.readPayload(raw, 0, binLen);
                try {
                    return new String(raw, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    return raw; // return as byte[] if not valid UTF-8
                }
            case FIXARRAY: case ARRAY16: case ARRAY32:
                int arrSize = unpacker.unpackArrayHeader();
                List<Object> list = new ArrayList<>(arrSize);
                for (int i = 0; i < arrSize; i++) {
                    list.add(unpackValue(unpacker));
                }
                return list;
            case FIXMAP: case MAP16: case MAP32:
                int mapSize = unpacker.unpackMapHeader();
                // Use LinkedHashMap to preserve key order
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < mapSize; i++) {
                    Object key = unpackValue(unpacker);
                    Object value = unpackValue(unpacker);
                    map.put(key != null ? key.toString() : null, value);
                }
                return map;
            default:
                // Extension types, never-used formats, etc.
                unpacker.skipValue();
                return null;
        }
    }

    // ---- JSON pretty-printing ----

    /**
     * Formats the decoded messages as indented JSON, optionally expanding
     * embedded JSON strings into actual JSON objects.
     */
    public static String prettyPrint(List<Object> messages, boolean expand) {
        if (expand) {
            List<Object> expanded = new ArrayList<>();
            for (Object msg : messages) {
                expanded.add(expandEmbeddedJson(msg));
            }
            messages = expanded;
        }
        if (messages.size() == 1) {
            return GSON.toJson(messages.get(0));
        }
        return GSON.toJson(messages);
    }

    /**
     * Recursively expands string fields that look like JSON into actual objects.
     */
    @SuppressWarnings("unchecked")
    private static Object expandEmbeddedJson(Object obj) {
        if (obj instanceof String) {
            String s = ((String) obj).trim();
            if (s.startsWith("{") || s.startsWith("[")) {
                try {
                    return new Gson().fromJson(s, Object.class);
                } catch (Exception ignored) { }
            }
            return obj;
        }
        if (obj instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<Object>) obj) {
                result.add(expandEmbeddedJson(item));
            }
            return result;
        }
        if (obj instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) obj).entrySet()) {
                result.put(entry.getKey(), expandEmbeddedJson(entry.getValue()));
            }
            return result;
        }
        return obj;
    }

    // ---- Helpers ----

    /**
     * Strip HTTP headers from raw content. Returns body bytes, or original if not HTTP.
     */
    private static byte[] stripHttp(byte[] data) {
        if (!BlazorPackFrame.looksLikeHttp(data)) return data;
        int offset = BlazorPackFrame.findHttpBodyOffset(data);
        if (offset > 0) {
            return Arrays.copyOfRange(data, offset, data.length);
        }
        return data;
    }

    /**
     * Creates an error marker map for undecodable data.
     */
    private static Map<String, Object> error(String type, int offset, byte[] data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("__type", type);
        err.put("offset", offset);
        if (data != null) {
            int len = Math.min(64, data.length - Math.min(offset, data.length));
            int start = Math.min(offset, data.length);
            if (len > 0) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    hex.append(String.format("%02x", data[start + i] & 0xFF));
                }
                err.put("hex", hex.toString());
            }
        }
        return err;
    }

    private static Map<String, Object> error(String type, int offset, byte[] data, Exception e) {
        Map<String, Object> err = error(type, offset, data);
        err.put("error", e.getMessage());
        return err;
    }
}
