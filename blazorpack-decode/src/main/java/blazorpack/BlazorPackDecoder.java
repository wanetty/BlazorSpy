package blazorpack;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.MessageFormat;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Decodes BlazorPack binary frames into Java objects and formats them as JSON.
 *
 * Handles varint32 + MessagePack framing, including detection and graceful
 * reporting of truncated frames (common when Azure SignalR Service splits
 * WebSocket messages into 4096-byte chunks).
 */
public class BlazorPackDecoder {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Maximum nesting depth for MessagePack structures to prevent stack overflow. */
    private static final int MAX_RECURSION_DEPTH = 64;

    /** Maximum number of elements in a MessagePack array or map to prevent OOM. */
    private static final int MAX_CONTAINER_SIZE = 100_000;

    /** SignalR methods whose index-4 argument is a JSON string
     *  (same set as in BlazorPackEncoder, must stay in sync). */
    private static final Set<String> SIGNALR_JSON_ARG_METHODS = new HashSet<>(Arrays.asList(
        "BeginInvokeDotNetFromJS", "DispatchEventAsync", "EndInvokeJSFromDotNet"
    ));

    private BlazorPackDecoder() {} // static utility

    // ---- DecodeResult ----

    /**
     * Result of a buffered decode operation.
     */
    public static class DecodeResult {
        /** Successfully decoded messages (may include truncated-frame markers). */
        public final List<Object> messages;
        /** Unconsumed trailing bytes — typically the start of a truncated frame
         *  that should be prepended to the next WebSocket message. */
        public final byte[] remainder;
        /** True if the input ended with a truncated (incomplete) frame. */
        public final boolean hasTruncatedFrame;

        public DecodeResult(List<Object> messages, byte[] remainder, boolean hasTruncatedFrame) {
            this.messages = messages;
            this.remainder = remainder;
            this.hasTruncatedFrame = hasTruncatedFrame;
        }
    }

    // ---- Main entry points ----

    /**
     * Decodes a raw byte array (possibly with HTTP headers) into a list of
     * decoded MessagePack objects, JSON objects, or error markers.
     *
     * For buffered/reassembly support, prefer {@link #decodeWithBuffer(byte[], byte[])}.
     */
    public static List<Object> decode(byte[] raw) {
        return decodeWithBuffer(raw, null).messages;
    }

    /**
     * Decodes raw bytes with an optional prefix from a previous truncated message.
     *
     * When Azure SignalR Service splits WebSocket messages into 4096-byte chunks,
     * a BlazorPack frame can span multiple messages. The {@code prefix} carries
     * the trailing bytes from the previous message so the frame can be reassembled.
     *
     * @param raw    the current WebSocket message bytes
     * @param prefix trailing bytes from the previous message, or null/empty
     * @return a DecodeResult with decoded messages, any new remainder, and a truncation flag
     */
    public static DecodeResult decodeWithBuffer(byte[] raw, byte[] prefix) {
        // Concatenate prefix + raw
        byte[] combined;
        if (prefix != null && prefix.length > 0) {
            combined = new byte[prefix.length + (raw != null ? raw.length : 0)];
            System.arraycopy(prefix, 0, combined, 0, prefix.length);
            if (raw != null && raw.length > 0) {
                System.arraycopy(raw, 0, combined, prefix.length, raw.length);
            }
        } else {
            combined = (raw != null) ? raw : new byte[0];
        }

        if (combined.length == 0) {
            return new DecodeResult(
                Collections.singletonList(error("empty_frame", 0, null)),
                new byte[0], false);
        }

        // Strip HTTP headers if present
        byte[] body = stripHttp(combined);

        // JSON literal?
        if (BlazorPackFrame.isJsonStart(body)) {
            try {
                String text = new String(body, StandardCharsets.UTF_8).replaceFirst("[\n\r]+$", "");
                Object parsed = GSON.fromJson(text, Object.class);
                return new DecodeResult(Collections.singletonList(parsed), new byte[0], false);
            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("__type", "json_raw");
                err.put("data", new String(body, StandardCharsets.UTF_8));
                return new DecodeResult(Collections.singletonList(err), new byte[0], false);
            }
        }

        // Varint + MessagePack framing
        List<Object> messages = new ArrayList<>();
        int pos = 0;
        int stuck = 0;
        int[] posHolder = null;
        boolean truncated = false;
        byte[] remainder = new byte[0];

        while (pos < body.length) {
            int startPos = pos;
            posHolder = new int[] { pos };

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

            // Validate length — strict boundary check (no +4 fudge)
            if (msgLen <= 0) {
                try {
                    Object obj = unpackSingle(body, startPos);
                    if (obj != null) messages.add(obj);
                } catch (Exception e2) {
                    messages.add(error("binary_undecodable", startPos, body, e2));
                }
                break;
            }

            if (pos + msgLen > body.length) {
                // Truncated frame: varint declares more bytes than available
                int available = body.length - pos;
                messages.add(truncatedFrameError(pos, msgLen, available, body));

                // Save the partial data (varint + available payload) as remainder
                int varintLen = pos - startPos;
                remainder = Arrays.copyOfRange(body, startPos, body.length);

                truncated = true;
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

            // Stuck detection (safety valve)
            if (pos == startPos) {
                stuck++;
                if (stuck > 3) break;
            } else {
                stuck = 0;
            }
        }

        return new DecodeResult(messages, remainder, truncated);
    }

    // ---- MessagePack unpacking ----

    /**
     * Unpacks a single MessagePack value from the byte array starting at offset.
     */
    private static Object unpackSingle(byte[] data, int offset) throws IOException {
        MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data, offset, data.length - offset);
        return unpackValue(unpacker, 0);
    }

    /**
     * Recursively unpacks a MessagePack value from the given unpacker.
     * Converts binary data to UTF-8 strings when possible (matching Blazor behavior).
     *
     * @param depth current recursion depth; throws IOException if it exceeds MAX_RECURSION_DEPTH
     */
    private static Object unpackValue(MessageUnpacker unpacker, int depth) throws IOException {
        if (depth > MAX_RECURSION_DEPTH) {
            throw new IOException("Max recursion depth exceeded (" + MAX_RECURSION_DEPTH + ") — "
                + "deeply nested or malicious MessagePack structure");
        }

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
                if (arrSize > MAX_CONTAINER_SIZE) {
                    throw new IOException("Array size " + arrSize + " exceeds maximum "
                        + MAX_CONTAINER_SIZE + " — possible corrupt or malicious data");
                }
                List<Object> list = new ArrayList<>(arrSize);
                int nextDepth = depth + 1;
                for (int i = 0; i < arrSize; i++) {
                    list.add(unpackValue(unpacker, nextDepth));
                }
                return list;
            case FIXMAP: case MAP16: case MAP32:
                int mapSize = unpacker.unpackMapHeader();
                if (mapSize > MAX_CONTAINER_SIZE) {
                    throw new IOException("Map size " + mapSize + " exceeds maximum "
                        + MAX_CONTAINER_SIZE + " — possible corrupt or malicious data");
                }
                // Use LinkedHashMap to preserve key order
                Map<String, Object> map = new LinkedHashMap<>();
                int mapDepth = depth + 1;
                for (int i = 0; i < mapSize; i++) {
                    Object key = unpackValue(unpacker, mapDepth);
                    Object value = unpackValue(unpacker, mapDepth);
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
     * Formats decoded messages as commented JSON with frame boundaries
     * and truncated-frame annotations visible.
     */
    public static String prettyPrintWithAnnotations(List<Object> messages, boolean hasTruncatedFrame) {
        StringBuilder sb = new StringBuilder();

        if (hasTruncatedFrame) {
            // Find the truncated_frame marker for details
            for (Object msg : messages) {
                if (msg instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> map = (Map<String, Object>) msg;
                    if ("truncated_frame".equals(map.get("__type"))) {
                        appendTruncatedBanner(sb, map);
                        break;
                    }
                }
            }
            sb.append("\n");
        }

        // Separate complete frames from annotation markers
        List<Object> completeFrames = new ArrayList<>();
        List<Map<String, Object>> truncatedMarkers = new ArrayList<>();

        for (Object msg : messages) {
            if (msg instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) msg;
                String type = (String) m.get("__type");
                if ("truncated_frame".equals(type)) {
                    truncatedMarkers.add(m);
                    continue;
                }
            }
            completeFrames.add(msg);
        }

        // Print complete frames
        for (int i = 0; i < completeFrames.size(); i++) {
            sb.append("// --- Frame ").append(i + 1).append(" (complete) ---\n");
            sb.append(GSON.toJson(completeFrames.get(i)));
            sb.append("\n\n");
        }

        // Print truncated frame markers as comments
        for (Map<String, Object> marker : truncatedMarkers) {
            sb.append("// --- Frame ").append(completeFrames.size() + 1)
              .append(" (⚠ INCOMPLETE — not editable, preserved as raw) ---\n");
            Object hexObj = marker.get("hex");
            if (hexObj != null) {
                String hex = hexObj.toString();
                sb.append("// Partial data (first 128 bytes in hex):\n");
                for (int i = 0; i < hex.length(); i += 64) {
                    sb.append("// ").append(hex, i, Math.min(i + 64, hex.length())).append("\n");
                }
            }
            Object missing = marker.get("missing_bytes");
            if (missing instanceof Number) {
                int chunks = ((Number) missing).intValue() / 4096 + 1;
                sb.append("// ⏳ Approximately ").append(chunks)
                  .append(" chunk(s) of 4096 bytes missing.\n");
                sb.append("//    Will be reassembled automatically when the next WebSocket message arrives.\n");
            }
            sb.append("\n");
        }

        if (completeFrames.isEmpty() && truncatedMarkers.isEmpty()) {
            sb.append("// No decodable content\n");
        }

        return sb.toString().trim();
    }

    private static void appendTruncatedBanner(StringBuilder sb, Map<String, Object> marker) {
        sb.append("// ═══════════════════════════════════════════════════════════════\n");
        sb.append("// ⚠ TRUNCATED FRAME — The last frame is incomplete\n");

        Object declared = marker.get("declared_length");
        Object available = marker.get("available_bytes");
        Object missing = marker.get("missing_bytes");

        if (declared instanceof Number) {
            sb.append("// Declared varint length:  ").append(declared).append(" bytes\n");
        }
        if (available instanceof Number) {
            sb.append("// Available bytes in buffer: ").append(available).append(" bytes\n");
        }
        if (missing instanceof Number) {
            int m = ((Number) missing).intValue();
            int chunks = m / 4096 + 1;
            sb.append("// Missing:                         ").append(missing)
              .append(" bytes (~").append(chunks).append(" × 4096 byte chunks)\n");
        }
        sb.append("// ═══════════════════════════════════════════════════════════════\n");
    }

    /**
     * Recursively expands embedded JSON strings into actual objects,
     * ONLY at positions where the encoder's collapseEmbeddedJson will
     * reverse the expansion (index 4 of known SignalR JS-invoke methods).
     * This ensures perfect expand/collapse symmetry and prevents
     * WebSocket disconnection due to structurally mismatched re-encoding.
     */
    @SuppressWarnings("unchecked")
    private static Object expandEmbeddedJson(Object obj) {
        return expand(obj, false, -1);
    }

    private static Object expand(Object obj, boolean parentIsSignalR, int fieldIndex) {
        if (obj instanceof String) {
            // Only expand at index 4 of a SignalR invocation frame.
            // This matches collapseEmbeddedJson's collapse logic exactly.
            if (parentIsSignalR && fieldIndex == 4) {
                String s = ((String) obj).trim();
                if (s.startsWith("{") || s.startsWith("[")) {
                    try {
                        return GSON.fromJson(s, Object.class);
                    } catch (Exception ignored) {
                        // Not valid JSON — leave as string
                    }
                }
            }
            return obj;
        }
        if (obj instanceof List) {
            List<Object> list = (List<Object>) obj;
            // SignalR detection: same logic as collapseEmbeddedJson in the encoder
            boolean isSignalR = list.size() >= 3
                && list.get(1) instanceof String
                && SIGNALR_JSON_ARG_METHODS.contains(list.get(1));

            List<Object> result = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                result.add(expand(list.get(i), isSignalR, i));
            }
            return result;
        }
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            Map<String, Object> result = new LinkedHashMap<>(map.size());
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                // Maps propagate parentIsSignalR and use fieldIndex=-1,
                // matching collapseEmbeddedJson's Map handling.
                result.put(entry.getKey(), expand(entry.getValue(), parentIsSignalR, -1));
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
     * Creates a truncated-frame error marker with diagnostic information.
     */
    private static Map<String, Object> truncatedFrameError(int offset, int declaredLen,
                                                            int available, byte[] data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("__type", "truncated_frame");
        err.put("offset", offset);
        err.put("declared_length", declaredLen);
        err.put("available_bytes", available);
        err.put("missing_bytes", declaredLen - available);

        // Hex dump of the partial payload (up to 128 bytes)
        int hexLen = Math.min(128, available);
        if (hexLen > 0) {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < hexLen; i++) {
                hex.append(String.format("%02x", data[offset + i] & 0xFF));
            }
            err.put("hex", hex.toString());
        }
        return err;
    }

    /**
     * Creates an error marker map for undecodable data.
     */
    private static Map<String, Object> error(String type, int offset, byte[] data) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("__type", type);
        err.put("offset", offset);
        if (data != null && offset < data.length) {
            int len = Math.min(64, data.length - offset);
            if (len > 0) {
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < len; i++) {
                    hex.append(String.format("%02x", data[offset + i] & 0xFF));
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
