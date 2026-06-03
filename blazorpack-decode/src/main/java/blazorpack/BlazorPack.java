package blazorpack;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

/**
 * BlazorPack Decoder — Burp Suite extension entry point.
 *
 * Decodes Blazor Server WebSocket frames (varint32 + MessagePack) into
 * editable JSON inside Burp Suite's message editor.
 *
 * Installation: ./gradlew jar, then load the JAR in Burp's Extender.
 */
public class BlazorPack implements BurpExtension {

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName("BlazorPack Decoder");

        // Register HTTP editor tabs (Montoya API for request/response editors)
        BlazorPackHttpEditorProvider httpProvider = new BlazorPackHttpEditorProvider();
        api.userInterface().registerHttpRequestEditorProvider(httpProvider);
        api.userInterface().registerHttpResponseEditorProvider(httpProvider);

        // Register WebSocket message editor (THE key feature)
        api.userInterface().registerWebSocketMessageEditorProvider(
            new BlazorPackWsEditorProvider()
        );

        String version = getClass().getPackage().getImplementationVersion();
        if (version == null) version = "dev";
        api.logging().logToOutput("BlazorPack Decoder v" + version + " loaded");
        api.logging().logToOutput("  - msgpack: org.msgpack:msgpack-core:0.9.8");
        api.logging().logToOutput("  - HTTP editor: registered (Montoya)");
        api.logging().logToOutput("  - WebSocket editor: registered (Montoya)");
        api.logging().logToOutput("");
        api.logging().logToOutput("USAGE:");
        api.logging().logToOutput("  1. Open a Blazor WebSocket message in Proxy/Repeater");
        api.logging().logToOutput("  2. Click the 'BlazorPack' tab");
        api.logging().logToOutput("  3. Edit the JSON and resend");
    }
}
