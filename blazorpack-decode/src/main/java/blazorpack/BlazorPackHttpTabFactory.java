package blazorpack;

/**
 * Legacy IMessageEditorTabFactory — creates BlazorPack editor tabs for HTTP messages.
 */
public class BlazorPackHttpTabFactory implements burp.IMessageEditorTabFactory {

    @Override
    public burp.IMessageEditorTab createNewInstance(burp.IMessageEditorController controller, boolean editable) {
        return new BlazorPackHttpEditorTab(editable);
    }
}
