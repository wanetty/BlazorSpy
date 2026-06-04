package blazorpack;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * Abstract base class for BlazorPack editor tabs.
 * Provides shared Swing UI construction, status helpers, comment stripping,
 * and frame-counting logic used by both HTTP and WebSocket editors.
 */
public abstract class BlazorPackEditorBase {

    // ---- UI components ----

    protected JPanel panel;
    protected JTextArea textArea;
    protected JLabel statusLabel;
    protected JLabel infoLabel;
    protected JCheckBox expandCb;

    // ---- Decode state ----

    /** Original decoded JSON text — compared against to detect user edits. */
    protected String originalDecoded = "";
    /** Whether the most recent decode succeeded. */
    protected boolean lastDecodeOk = false;
    /** Raw bytes of truncated frame(s) preserved for re-encode append. */
    protected byte[] truncatedRaw = new byte[0];
    /** Whether the most recent decode ended with a truncated frame. */
    protected boolean lastHadTruncated = false;

    // ---- Construction ----

    /**
     * Builds the shared Swing UI: top bar (status + expand checkbox),
     * monospaced editable JTextArea, and bottom info bar.
     */
    protected void buildUI() {
        panel = new JPanel(new BorderLayout());

        // Top bar
        JPanel topBar = new JPanel();
        topBar.setLayout(new BoxLayout(topBar, BoxLayout.X_AXIS));
        topBar.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        statusLabel = new JLabel("BlazorPack / MessagePack decoder");
        topBar.add(statusLabel);
        topBar.add(Box.createHorizontalGlue());

        expandCb = new JCheckBox("Expand embedded JSON", true);
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

    // ---- Shared helpers ----

    /**
     * Strips comment lines (starting with //) from edited text,
     * returning only actual JSON content for re-encoding.
     */
    protected static String stripComments(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) continue;
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Counts successfully decoded frames (excluding truncated_frame markers).
     */
    protected static int countCompleteFrames(List<Object> messages) {
        int count = 0;
        for (Object msg : messages) {
            if (msg instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> m = (java.util.Map<String, Object>) msg;
                String type = (String) m.get("__type");
                if ("truncated_frame".equals(type)) continue;
            }
            count++;
        }
        return count;
    }

    /**
     * Formats the status bar message for a successful decode.
     */
    protected static String formatStatusMessage(int completeCount, boolean hasTruncatedFrame,
                                                 String editorType) {
        if (hasTruncatedFrame) {
            return "⚠ Frame truncated — " + completeCount + " complete";
        }
        return "BlazorPack ✓ (" + completeCount + " " + editorType
            + (completeCount != 1 ? "s" : "") + ")";
    }

    // ---- UI state helpers ----

    protected void setStatus(String text, boolean ok, boolean truncated) {
        if (truncated) {
            statusLabel.setForeground(new Color(200, 120, 0)); // amber
        } else if (ok) {
            statusLabel.setForeground(new Color(0, 120, 0));
        } else {
            statusLabel.setForeground(new Color(180, 0, 0));
        }
        statusLabel.setText(text);
    }

    protected void setStatus(String text) {
        statusLabel.setForeground(Color.BLACK);
        statusLabel.setText(text);
    }

    protected void setInfo(String text) {
        infoLabel.setText(text);
    }

    // ---- Modified detection ----

    /**
     * Returns true if the editor text differs from the original decoded text.
     */
    public boolean isModified() {
        if (!lastDecodeOk) return false;
        return !textArea.getText().trim().equals(originalDecoded.trim());
    }
}
