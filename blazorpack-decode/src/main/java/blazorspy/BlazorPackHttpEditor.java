package blazorspy;

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
public class BlazorPackHttpEditor extends BlazorPackEditorBase
        implements ExtensionProvidedHttpRequestEditor,
                   ExtensionProvidedHttpResponseEditor {

    private byte[] originalBody = new byte[0];
    private byte[] originalFull = new byte[0];

    public BlazorPackHttpEditor() {
        buildUI();
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
        truncatedRaw = new byte[0];
        lastHadTruncated = false;

        if (requestResponse == null) {
            textArea.setText("");
            setStatus("No content");
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

            // Decode with buffer support for truncated frame detection
            BlazorPackDecoder.DecodeResult result =
                BlazorPackDecoder.decodeWithBuffer(originalBody, null);
            List<Object> messages = result.messages;

            lastDecodeOk = true;
            lastHadTruncated = result.hasTruncatedFrame;
            if (result.hasTruncatedFrame && result.remainder.length > 0) {
                truncatedRaw = result.remainder;
            }

            // Choose display format
            String display;
            if (result.hasTruncatedFrame) {
                display = BlazorPackDecoder.prettyPrintWithAnnotations(messages, true);
            } else {
                display = BlazorPackDecoder.prettyPrint(messages, expandCb.isSelected());
            }

            originalDecoded = display;
            textArea.setText(display);
            textArea.setCaretPosition(0);

            int completeCount = countCompleteFrames(messages);
            setStatus(formatStatusMessage(completeCount, result.hasTruncatedFrame, "message"),
                true, result.hasTruncatedFrame);
            setInfo(originalBody.length + " bytes -> " + display.length() + " chars JSON");

        } catch (Exception e) {
            lastDecodeOk = false;
            lastHadTruncated = false;
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            textArea.setText("// DECODING ERROR\n// " + e.getMessage() + "\n// Stack trace:\n" + sw);
            setStatus("Error: " + e.getMessage(), false, false);
            setInfo("");
        }
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
            byte[] reBody = buildReEncodedBody();
            byte[] rebuilt = rebuildHttp(originalFull, reBody);
            setStatus("Re-encoded ✓ (" + reBody.length + " bytes)", true, false);
            return HttpRequest.httpRequest(ByteArray.byteArray(rebuilt));
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), false, false);
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
            byte[] reBody = buildReEncodedBody();
            byte[] rebuilt = rebuildHttp(originalFull, reBody);
            setStatus("Re-encoded ✓ (" + reBody.length + " bytes)", true, false);
            return HttpResponse.httpResponse(ByteArray.byteArray(rebuilt));
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage(), false, false);
            return HttpResponse.httpResponse(ByteArray.byteArray(originalFull));
        }
    }

    // ---- Helpers ----

    private byte[] extractBody(String fullMessage) {
        byte[] fullBytes = fullMessage.getBytes(StandardCharsets.ISO_8859_1);
        int offset = BlazorPackFrame.findHttpBodyOffset(fullBytes);
        if (offset > 0) {
            return Arrays.copyOfRange(fullBytes, offset, fullBytes.length);
        }
        return fullBytes;
    }

    /**
     * Builds the re-encoded body from the edited text, stripping comments
     * and appending preserved truncated raw bytes.
     */
    private byte[] buildReEncodedBody() throws Exception {
        String edited = textArea.getText();
        String cleanJson = stripComments(edited);
        byte[] reEncoded = BlazorPackEncoder.encode(cleanJson);

        if (truncatedRaw.length > 0) {
            byte[] output = new byte[reEncoded.length + truncatedRaw.length];
            System.arraycopy(reEncoded, 0, output, 0, reEncoded.length);
            System.arraycopy(truncatedRaw, 0, output, reEncoded.length, truncatedRaw.length);
            return output;
        }
        return reEncoded;
    }

    private byte[] rebuildHttp(byte[] original, byte[] newBody) {
        int bodyOffset = BlazorPackFrame.findHttpBodyOffset(original);
        if (bodyOffset <= 0) return newBody;

        String headerPart = new String(original, 0, bodyOffset, StandardCharsets.ISO_8859_1);
        headerPart = headerPart.replaceAll("(?i)(Content-Length:\\s*)\\d+", "$1" + newBody.length);

        byte[] newHeaders = headerPart.getBytes(StandardCharsets.ISO_8859_1);
        byte[] result = new byte[newHeaders.length + newBody.length];
        System.arraycopy(newHeaders, 0, result, 0, newHeaders.length);
        System.arraycopy(newBody, 0, result, newHeaders.length, newBody.length);
        return result;
    }
}
