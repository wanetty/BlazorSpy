package blazorpack;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;
import burp.api.montoya.websocket.Direction;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Montoya API WebSocket message editor for BlazorPack frames.
 * Provides inline decoding/encoding within Burp's WebSocket message editor.
 *
 * Supports reassembly of frames split across 4096-byte WebSocket chunks
 * (common with Azure SignalR Service).
 */
public class BlazorPackWsEditor extends BlazorPackEditorBase
        implements ExtensionProvidedWebSocketMessageEditor {

    /** Maximum idle time (ms) before resetting the reassembly buffer.
     *  If no new message arrives within this window, we assume the user
     *  switched to a different connection and discard stale state. */
    private static final long REASSEMBLY_TIMEOUT_MS = 30_000;

    /** Maximum size of the pending reassembly buffer (256 KB).
     *  If exceeded, the buffer is discarded to prevent OOM from
     *  a stream of never-completing truncated frames. */
    private static final int MAX_PENDING_BUFFER = 256 * 1024;

    private byte[] originalRaw = new byte[0];

    /** Buffered trailing bytes from the previous message for frame reassembly. */
    private byte[] pendingBuffer = new byte[0];

    /** Direction of the last processed message (detect connection switch). */
    private Direction lastDirection = null;
    /** Timestamp of the last setMessage() call (detect stale buffer). */
    private long lastSetMessageTime = 0;

    public BlazorPackWsEditor() {
        buildUI();
    }

    // ---- ExtensionProvidedWebSocketMessageEditor interface ----

    @Override
    public String caption() {
        return "BlazorPack";
    }

    @Override
    public Component uiComponent() {
        return panel;
    }

    @Override
    public boolean isEnabledFor(WebSocketMessage message) {
        if (message == null || message.payload() == null) return false;
        try {
            byte[] raw = message.payload().getBytes();
            return BlazorPackFrame.isBlazorPackData(raw);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void setMessage(WebSocketMessage message) {
        // Reset per-message state
        truncatedRaw = new byte[0];
        lastHadTruncated = false;

        if (message == null) {
            clearState("No content");
            return;
        }

        try {
            // Extract raw bytes from WebSocket message
            byte[] rawBytes = message.payload().getBytes();
            if (rawBytes == null || rawBytes.length == 0) {
                clearState("No content (empty message)");
                return;
            }

            originalRaw = rawBytes;

            // Reset stale reassembly buffer when switching connections
            // (Montoya does not expose connection IDs, so we use direction + time heuristics)
            Direction dir = message.direction();
            long now = System.currentTimeMillis();
            if (pendingBuffer.length > 0) {
                boolean directionChanged = (lastDirection != null && dir != lastDirection);
                boolean timedOut = (lastSetMessageTime > 0
                    && (now - lastSetMessageTime) > REASSEMBLY_TIMEOUT_MS);
                if (directionChanged || timedOut) {
                    pendingBuffer = new byte[0];
                }
            }
            lastDirection = dir;
            lastSetMessageTime = now;

            // Decode with buffered prefix from previous truncated message
            byte[] prefix = pendingBuffer;
            BlazorPackDecoder.DecodeResult result = BlazorPackDecoder.decodeWithBuffer(rawBytes, prefix);

            lastDecodeOk = true;
            lastHadTruncated = result.hasTruncatedFrame;

            // Update pending buffer for next message
            if (result.hasTruncatedFrame && result.remainder.length > 0) {
                if (result.remainder.length > MAX_PENDING_BUFFER) {
                    // Buffer exceeds safety limit — discard to prevent OOM
                    pendingBuffer = new byte[0];
                    truncatedRaw = new byte[0];
                } else {
                    pendingBuffer = result.remainder;
                    truncatedRaw = result.remainder;
                }
            } else {
                pendingBuffer = new byte[0];
            }

            List<Object> messages = result.messages;

            // Choose display format
            String display;
            if (result.hasTruncatedFrame) {
                display = BlazorPackDecoder.prettyPrintWithAnnotations(messages, true);
            } else if (prefix.length > 0) {
                // Successfully reassembled — show with annotation
                StringBuilder sb = new StringBuilder();
                sb.append("// ✓ Frame successfully reassembled (from 2 WebSocket fragments)\n");
                sb.append("// Pending bytes from previous message: ").append(prefix.length).append("\n");
                sb.append("// Bytes in current message: ").append(rawBytes.length).append("\n\n");
                sb.append(BlazorPackDecoder.prettyPrint(messages, expandCb.isSelected()));
                display = sb.toString();
            } else {
                display = BlazorPackDecoder.prettyPrint(messages, expandCb.isSelected());
            }

            originalDecoded = display;
            textArea.setText(display);
            textArea.setCaretPosition(0);

            // Status bar
            int completeCount = countCompleteFrames(messages);
            StringBuilder statusText = new StringBuilder();
            if (result.hasTruncatedFrame) {
                statusText.append("⚠ Frame ").append(completeCount + 1).append(" truncated");
                // Find missing bytes
                for (Object msg : messages) {
                    if (msg instanceof java.util.Map) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> m = (java.util.Map<String, Object>) msg;
                        if ("truncated_frame".equals(m.get("__type"))) {
                            Object missing = m.get("missing_bytes");
                            if (missing instanceof Number) {
                                statusText.append(" (missing ").append(missing).append(" bytes)");
                            }
                            break;
                        }
                    }
                }
                setStatus(statusText.toString(), false, true);
            } else if (prefix.length > 0) {
                setStatus("✓ Reassembled correctly (" + completeCount + " frames)", true, false);
            } else {
                setStatus(formatStatusMessage(completeCount, false, "message"), true, false);
            }

            // Info bar
            StringBuilder infoText = new StringBuilder();
            infoText.append(rawBytes.length).append(" bytes → ").append(display.length()).append(" chars JSON");
            if (prefix.length > 0) {
                infoText.append(" | ⊕ reassembled (+").append(prefix.length).append(" pending bytes)");
            }
            if (pendingBuffer.length > 0) {
                infoText.append(" | ⏳ ").append(pendingBuffer.length).append(" bytes pending for next message");
            }
            setInfo(infoText.toString());

        } catch (Exception e) {
            lastDecodeOk = false;
            lastHadTruncated = false;
            pendingBuffer = new byte[0];

            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));

            StringBuilder hex = new StringBuilder();
            try {
                byte[] raw = message.payload().getBytes();
                int hexLen = Math.min(32, raw.length);
                for (int i = 0; i < hexLen; i++) {
                    hex.append(String.format("%02x", raw[i] & 0xFF));
                }
            } catch (Exception ignored) { }

            textArea.setText(
                "// DECODING ERROR\n" +
                "// " + e.getMessage() + "\n" +
                "//\n" +
                "// First 32 bytes (hex): " + hex + "\n" +
                "// Stack trace:\n" +
                sw.toString()
            );
            setStatus("Decoding error: " + e.getMessage(), false, false);
            setInfo("");
        }
    }

    @Override
    public ByteArray getMessage() {
        if (!isModified()) {
            return ByteArray.byteArray(originalRaw);
        }

        String edited = textArea.getText();
        try {
            // Extract non-comment lines for re-encoding
            String cleanJson = stripComments(edited);
            byte[] reEncoded = BlazorPackEncoder.encode(cleanJson);

            // Append truncated raw bytes that were preserved
            byte[] output;
            if (truncatedRaw.length > 0) {
                output = new byte[reEncoded.length + truncatedRaw.length];
                System.arraycopy(reEncoded, 0, output, 0, reEncoded.length);
                System.arraycopy(truncatedRaw, 0, output, reEncoded.length, truncatedRaw.length);
            } else {
                output = reEncoded;
            }

            setStatus("Re-encoded ✓ (" + output.length + " bytes)", true, false);
            setInfo(edited.length() + " chars JSON → " + output.length + " bytes BlazorPack"
                + (truncatedRaw.length > 0 ? " (+" + truncatedRaw.length + " truncated bytes preserved)" : ""));
            return ByteArray.byteArray(output);
        } catch (Exception e) {
            setStatus("Re-encoding error: " + e.getMessage(), false, false);
            return ByteArray.byteArray(originalRaw);
        }
    }

    @Override
    public burp.api.montoya.ui.Selection selectedData() {
        return null;
    }

    // ---- Helpers ----

    private void clearState(String status) {
        textArea.setText("");
        setStatus(status);
        originalRaw = new byte[0];
        originalDecoded = "";
        lastDecodeOk = false;
        lastHadTruncated = false;
        truncatedRaw = new byte[0];
        lastDirection = null;
        lastSetMessageTime = 0;
        setInfo("");
    }
}
