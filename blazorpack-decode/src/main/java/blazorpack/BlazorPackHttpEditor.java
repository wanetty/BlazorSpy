package blazorpack;

import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.extension.*;

import javax.swing.*;
import java.awt.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Montoya API HTTP message editor for BlazorPack frames.
 * Implements both request and response editor interfaces.
 *
 * Preserves original HTTP headers and only decodes/encodes the body.
 */
public class BlazorPackHttpEditor implements ExtensionProvidedHttpRequestEditor,
                                             ExtensionProvidedHttpResponseEditor {

    private final JPanel panel;
    private final JTextArea textArea;
    private final JLabel statusLabel;
    private final JLabel infoLabel;
    private final JCheckBox expandCb;

    private byte[] originalBody = new byte[0];
    private byte[] originalFull = new byte[0];
    private String originalDecoded = "";
    private boolean lastDecodeOk = false;

    public BlazorPackHttpEditor() {
        panel = new JPanel(new BorderLayout());

        JPanel topBar = new JPanel();
        topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
        topBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        statusLabel = new JLabel("BlazorPack / MessagePack decoder");
        topBar.add(statusLabel);
        topBar.add(Box.createHorizontalGlue());

        expandCb = new JCheckBox("Expandir JSON embebido", true);
        topBar.add(expandCb);

        panel.add(topBar, BorderLayout.NORTH);

        textArea = new JTextArea();
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setEditable(true);
        textArea.setLineWrap(false);
        textArea.setTabSize(2);

        panel.add(new JScrollPane(textArea), BorderLayout.CENTER);

        JPanel bottomBar = new JPanel();
        bottomBar.setLayout(new BoxLayout(bottomBar, BoxLayout.X_AXIS));
        bottomBar.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        infoLabel = new JLabel("");
        infoLabel.setFont(new Font("Dialog", Font.PLAIN, 11));
        bottomBar.add(infoLabel);

        panel.add(bottomBar, BorderLayout.SOUTH);
    }

    @Override
    public String caption() { return "BlazorPack"; }

    @Override
    public Component uiComponent() { return panel; }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        if (requestResponse == null) return false;
        // Try request body first, then response body
        if (requestResponse.request() != null) {
            byte[] body = extractBody(requestResponse.request().toString());
            if (BlazorPackFrame.isBlazorPackData(body)) return true;
        }
        if (requestResponse.response() != null) {
            byte[] body = extractBody(requestResponse.response().toString());
            return BlazorPackFrame.isBlazorPackData(body);
        }
        return false;
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        if (requestResponse == null) {
            textArea.setText("");
            setStatus("Sin contenido");
            originalBody = new byte[0];
            lastDecodeOk = false;
            return;
        }

        try {
            String fullMsg = requestResponse.request() != null
                ? requestResponse.request().toString()
                : requestResponse.response().toString();
            byte[] fullBytes = fullMsg.getBytes(StandardCharsets.ISO_8859_1);
            originalFull = fullBytes;

            originalBody = extractBody(fullMsg);

            List<Object> messages = BlazorPackDecoder.decode(originalBody);
            lastDecodeOk = true;

            String display = BlazorPackDecoder.prettyPrint(messages, expandCb.isSelected());
            originalDecoded = display;
            textArea.setText(display);
            textArea.setCaretPosition(0);

            int count = messages.size();
            setStatus("BlazorPack ✓ (" + count + " mensaje" + (count != 1 ? "s" : "") + ")", true);
            setInfo(originalBody.length + " bytes -> " + display.length() + " chars JSON");

        } catch (Exception e) {
            lastDecodeOk = false;
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            textArea.setText("// ERROR DE DECODIFICACION\n// " + e.getMessage() + "\n// Stack trace:\n" + sw);
            setStatus("Error: " + e.getMessage(), false);
            setInfo("");
        }
    }

    @Override
    public boolean isModified() {
        if (!lastDecodeOk) return false;
        return !textArea.getText().trim().equals(originalDecoded.trim());
    }

    @Override
    public burp.api.montoya.ui.Selection selectedData() { return null; }

    // ---- HttpRequestEditor ----

    @Override
    public HttpRequest getRequest() {
        if (!isModified()) {
            return HttpRequest.httpRequest(ByteArray.byteArray(originalFull));
        }
        try {
            byte[] reBody = BlazorPackEncoder.encode(textArea.getText());
            byte[] rebuilt = rebuildHttp(originalFull, reBody);
            setStatus("Re-encodificado ✓ (" + reBody.length + " bytes)", true);
            return HttpRequest.httpRequest(ByteArray.byteArray(rebuilt));
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), false);
            return HttpRequest.httpRequest(ByteArray.byteArray(originalFull));
        }
    }

    // ---- HttpResponseEditor ----

    @Override
    public HttpResponse getResponse() {
        if (!isModified()) {
            return HttpResponse.httpResponse(ByteArray.byteArray(originalFull));
        }
        try {
            byte[] reBody = BlazorPackEncoder.encode(textArea.getText());
            byte[] rebuilt = rebuildHttp(originalFull, reBody);
            setStatus("Re-encodificado ✓ (" + reBody.length + " bytes)", true);
            return HttpResponse.httpResponse(ByteArray.byteArray(rebuilt));
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), false);
            return HttpResponse.httpResponse(ByteArray.byteArray(originalFull));
        }
    }

    // ---- Helpers ----

    private byte[] extractBody(String fullMessage) {
        int offset = BlazorPackFrame.findHttpBodyOffset(fullMessage.getBytes(StandardCharsets.ISO_8859_1));
        if (offset > 0) {
            return Arrays.copyOfRange(fullMessage.getBytes(StandardCharsets.ISO_8859_1), offset, fullMessage.length());
        }
        return fullMessage.getBytes(StandardCharsets.ISO_8859_1);
    }

    private byte[] rebuildHttp(byte[] original, byte[] newBody) {
        // Find body offset in original
        int bodyOffset = BlazorPackFrame.findHttpBodyOffset(original);
        if (bodyOffset <= 0) return newBody;

        // Build header part, updating Content-Length
        String headerPart = new String(original, 0, bodyOffset, StandardCharsets.ISO_8859_1);
        headerPart = headerPart.replaceAll("(?i)(Content-Length:\\s*)\\d+", "$1" + newBody.length);

        byte[] newHeaders = headerPart.getBytes(StandardCharsets.ISO_8859_1);
        byte[] result = new byte[newHeaders.length + newBody.length];
        System.arraycopy(newHeaders, 0, result, 0, newHeaders.length);
        System.arraycopy(newBody, 0, result, newHeaders.length, newBody.length);
        return result;
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
