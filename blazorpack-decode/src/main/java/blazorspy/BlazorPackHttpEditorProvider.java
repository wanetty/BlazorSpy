package blazorspy;

import burp.api.montoya.ui.editor.extension.*;

/**
 * Provides BlazorPack editor tabs for both HTTP requests and responses.
 */
public class BlazorPackHttpEditorProvider implements HttpRequestEditorProvider,
                                                     HttpResponseEditorProvider {

    @Override
    public ExtensionProvidedHttpRequestEditor provideHttpRequestEditor(EditorCreationContext context) {
        return new BlazorPackHttpEditor();
    }

    @Override
    public ExtensionProvidedHttpResponseEditor provideHttpResponseEditor(EditorCreationContext context) {
        return new BlazorPackHttpEditor();
    }
}
