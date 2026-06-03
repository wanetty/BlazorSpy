package blazorpack;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Legacy IMessageEditorTab for HTTP request/response messages.
 * Provides inline BlazorPack decoding/encoding within Burp's HTTP message editor.
 */
public class BlazorPackHttpEditorTab implements burp.IMessageEditorTab {

    private JPanel panel;
    private JTextArea textArea;
    private JLabel statusLabel;
    private JLabel infoLabel;
    private JCheckBox expandCb;

    private byte[] currentContent = new byte[0];
    private String originalDecoded = "";
    private boolean lastDecodeOk = false;
    private boolean editable;

    public BlazorPackHttpEditorTab(boolean editable) {
        this.editable = editable;
        buildUI();
    }

    private void buildUI() {
        panel = new JPanel(new BorderLayout());

        // Top bar
        JPanel topBar = new JPanel();
        topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
        topBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        statusLabel = new JLabel("BlazorPack / MessagePack decoder");
        topBar.add(statusLabel);
        topBar.add(Box.createHorizontalGlue());

        expandCb = new JCheckBox("Expandir JSON embebido", true);
        topBar.add(expandCb);

        panel.add(topBar, BorderLayout.NORTH);

        // Text area
        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setEditable(editable);
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

    // ---- IMessageEditorTab interface ----

    @Override
    public String getTabCaption() {
        return "BlazorPack";
    }

    @Override
    public Component getUiComponent() {
        return panel;
    }

    @Override
    public boolean isEnabled(byte[] content, boolean isRequest) {
        // Strip HTTP headers if present
        byte[] body = content;
        if (BlazorPackFrame.looksLikeHttp(content)) {
            int offset = BlazorPackFrame.findHttpBodyOffset(content);
            if (offset > 0) body = Arrays.copyOfRange(content, offset, content.length);
        }
        return BlazorPackFrame.isBlazorPackData(body);
    }

    @Override
    public void setMessage(byte[] content, boolean isRequest) {
        if (content == null || content.length == 0) {
            textArea.setText("");
            setStatus("Sin contenido");
            currentContent = new byte[0];
            lastDecodeOk = false;
            return;
        }

        try {
            // Store the original
            currentContent = content;

            // Strip HTTP headers
            byte[] body = content;
            if (BlazorPackFrame.looksLikeHttp(content)) {
                int offset = BlazorPackFrame.findHttpBodyOffset(content);
                if (offset > 0) body = Arrays.copyOfRange(content, offset, content.length);
            }

            // Decode
            List<Object> messages = BlazorPackDecoder.decode(body);
            lastDecodeOk = true;

            String display = BlazorPackDecoder.prettyPrint(messages, expandCb.isSelected());

            originalDecoded = display;
            textArea.setText(display);
            textArea.setCaretPosition(0);

            int count = messages.size();
            setStatus("BlazorPack ✓ (" + count + " mensaje" + (count != 1 ? "s" : "") + ")", true);
            setInfo(body.length + " bytes -> " + display.length() + " chars JSON");

        } catch (Exception e) {
            lastDecodeOk = false;
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));

            StringBuilder hex = new StringBuilder();
            int hexLen = Math.min(128, body(content).length);
            for (int i = 0; i < hexLen; i++) {
                hex.append(String.format("%02x", body(content)[i] & 0xFF));
            }

            textArea.setText(
                "// ERROR DE DECODIFICACION\n" +
                "// " + e.getMessage() + "\n" +
                "//\n" +
                "// Hex (primeros " + hexLen + " bytes): " + hex + "\n" +
                "//\n" +
                "// Stack trace:\n" +
                sw.toString()
            );
            setStatus("Error al decodificar: " + e.getMessage(), false);
            setInfo("");
        }
    }

    @Override
    public byte[] getMessage() {
        if (!isModified()) {
            return currentContent;
        }

        String edited = textArea.getText();
        try {
            byte[] reEncoded = BlazorPackEncoder.encode(edited);

            // If original had HTTP headers, prepend with updated Content-Length
            if (BlazorPackFrame.looksLikeHttp(currentContent)) {
                int offset = BlazorPackFrame.findHttpBodyOffset(currentContent);
                if (offset > 0) {
                    byte[] headers = Arrays.copyOfRange(currentContent, 0, offset);
                    String headerStr = new String(headers, StandardCharsets.ISO_8859_1);
                    headerStr = headerStr.replaceAll("(?i)(Content-Length:\\s*)\\d+",
                                                     "$1" + reEncoded.length);
                    byte[] newHeaders = headerStr.getBytes(StandardCharsets.ISO_8859_1);
                    byte[] result = new byte[newHeaders.length + reEncoded.length];
                    System.arraycopy(newHeaders, 0, result, 0, newHeaders.length);
                    System.arraycopy(reEncoded, 0, result, newHeaders.length, reEncoded.length);
                    setStatus("Re-encodificado ✓ (" + reEncoded.length + " bytes)", true);
                    setInfo(edited.length() + " chars JSON -> " + reEncoded.length + " bytes BlazorPack");
                    return result;
                }
            }

            setStatus("Re-encodificado ✓ (" + reEncoded.length + " bytes)", true);
            setInfo(edited.length() + " chars JSON -> " + reEncoded.length + " bytes BlazorPack");
            return reEncoded;

        } catch (Exception e) {
            setStatus("Error al re-encodificar: " + e.getMessage(), false);
            return currentContent;
        }
    }

    @Override
    public boolean isModified() {
        if (!lastDecodeOk) return false;
        return !textArea.getText().trim().equals(originalDecoded.trim());
    }

    @Override
    public byte[] getSelectedData() {
        String selected = textArea.getSelectedText();
        return selected != null ? selected.getBytes(StandardCharsets.UTF_8) : null;
    }

    // ---- Helpers ----

    private byte[] body(byte[] content) {
        if (BlazorPackFrame.looksLikeHttp(content)) {
            int offset = BlazorPackFrame.findHttpBodyOffset(content);
            if (offset > 0) return Arrays.copyOfRange(content, offset, content.length);
        }
        return content;
    }

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
