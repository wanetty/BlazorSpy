package blazorpack;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;
import burp.api.montoya.ui.contextmenu.WebSocketMessage;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

/**
 * Montoya API WebSocket message editor for BlazorPack frames.
 * Provides inline decoding/encoding within Burp's WebSocket message editor.
 */
public class BlazorPackWsEditor implements ExtensionProvidedWebSocketMessageEditor {

    private JPanel panel;
    private JTextArea textArea;
    private JLabel statusLabel;
    private JLabel infoLabel;
    private JCheckBox expandCb;

    private byte[] originalRaw = new byte[0];
    private String originalDecoded = "";
    private boolean isModified = false;
    private boolean lastDecodeOk = false;

    public BlazorPackWsEditor() {
        buildUI();
    }

    private void buildUI() {
        panel = new JPanel(new BorderLayout());

        // Top bar
        JPanel topBar = new JPanel();
        topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
        topBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        statusLabel = new JLabel("BlazorPack / MessagePack (WebSocket)");
        topBar.add(statusLabel);
        topBar.add(Box.createHorizontalGlue());

        expandCb = new JCheckBox("Expandir JSON embebido", true);
        topBar.add(expandCb);

        panel.add(topBar, BorderLayout.NORTH);

        // Text area
        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setEditable(true);
        textArea.setLineWrap(false);
        textArea.setTabSize(2);

        panel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        // Bottom bar
        JPanel bottomBar = new JPanel();
        bottomBar.setLayout(new BoxLayout(bottomBar, BoxLayout.X_AXIS));
        bottomBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        infoLabel = new JLabel("");
        infoLabel.setFont(new Font("Dialog", Font.PLAIN, 11));
        bottomBar.add(infoLabel);

        panel.add(bottomBar, BorderLayout.SOUTH);
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
        if (message == null) {
            textArea.setText("");
            setStatus("Sin contenido");
            originalRaw = new byte[0];
            originalDecoded = "";
            isModified = false;
            lastDecodeOk = false;
            return;
        }

        try {
            // Extract raw bytes from WebSocket message
            byte[] rawBytes = message.payload().getBytes();
            if (rawBytes == null || rawBytes.length == 0) {
                textArea.setText("");
                setStatus("Sin contenido (mensaje vacio)");
                originalRaw = new byte[0];
                isModified = false;
                lastDecodeOk = false;
                return;
            }

            originalRaw = rawBytes;
            isModified = false;

            // Decode
            List<Object> messages = BlazorPackDecoder.decode(rawBytes);
            lastDecodeOk = true;

            String display = BlazorPackDecoder.prettyPrint(messages, expandCb.isSelected());

            originalDecoded = display;
            textArea.setText(display);
            textArea.setCaretPosition(0);

            int count = messages.size();
            setStatus("BlazorPack ✓ (" + count + " mensaje" + (count != 1 ? "s" : "") + ")", true);
            setInfo(rawBytes.length + " bytes -> " + display.length() + " chars JSON");

        } catch (Exception e) {
            lastDecodeOk = false;
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
                "// ERROR DE DECODIFICACION\n" +
                "// " + e.getMessage() + "\n" +
                "//\n" +
                "// Primeros 32 bytes (hex): " + hex + "\n" +
                "// Stack trace:\n" +
                sw.toString()
            );
            setStatus("Error al decodificar: " + e.getMessage(), false);
            setInfo("");
        }
    }

    @Override
    public ByteArray getMessage() {
        if (!isModified) {
            return ByteArray.byteArray(originalRaw);
        }

        String edited = textArea.getText();
        try {
            byte[] reEncoded = BlazorPackEncoder.encode(edited);
            setStatus("Re-encodificado ✓ (" + reEncoded.length + " bytes)", true);
            setInfo(edited.length() + " chars JSON -> " + reEncoded.length + " bytes BlazorPack");
            return ByteArray.byteArray(reEncoded);
        } catch (Exception e) {
            setStatus("Error al re-encodificar: " + e.getMessage(), false);
            return ByteArray.byteArray(originalRaw);
        }
    }

    @Override
    public boolean isModified() {
        if (!lastDecodeOk) return false;
        return !textArea.getText().trim().equals(originalDecoded.trim());
    }

    @Override
    public burp.api.montoya.ui.Selection selectedData() {
        // Note: Selection is a Montoya class. Return null for simplicity
        // — the Montoya API will handle copy/paste natively.
        return null;
    }

    // ---- Helpers ----

    private void setStatus(String text, boolean ok) {
        statusLabel.setForeground(ok ? new Color(0, 120, 0) : new Color(180, 0, 0));
        statusLabel.setText(text);
    }

    private void setStatus(String text) {
        statusLabel.setForeground(Color.BLACK);
        statusLabel.setText(text);
    }

    private void setInfo(String text) {
        infoLabel.setText(text);
    }
}
