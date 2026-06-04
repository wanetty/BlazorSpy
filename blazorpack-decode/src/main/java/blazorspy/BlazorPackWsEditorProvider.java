package blazorspy;

import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedWebSocketMessageEditor;
import burp.api.montoya.ui.editor.extension.WebSocketMessageEditorProvider;

/**
 * Montoya API provider that creates BlazorPack WebSocket message editors.
 */
public class BlazorPackWsEditorProvider implements WebSocketMessageEditorProvider {

    @Override
    public ExtensionProvidedWebSocketMessageEditor provideMessageEditor(EditorCreationContext creationContext) {
        return new BlazorPackWsEditor();
    }
}
