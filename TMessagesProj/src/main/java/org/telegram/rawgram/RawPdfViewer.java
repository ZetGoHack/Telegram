package org.telegram.rawgram;

import android.app.Activity;
import android.os.Build;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.util.Locale;

/**
 * Entry point of the built-in PDF viewer (exteraGram's pdf/** idea, rewritten on android.graphics.pdf.PdfRenderer).
 * AndroidUtilities.openForView calls {@link #tryOpen}; with the switch off nothing changes.
 */
public class RawPdfViewer {

    /** Set while the viewer hands a file over to the external app, so openForView does not come back here. */
    static boolean external;

    public static boolean tryOpen(File file, String fileName, String mimeType, Activity activity) {
        if (external || Build.VERSION.SDK_INT < 21 || !RawChatUiConfig.pdfViewer.get() || file == null || !isPdf(file, fileName, mimeType)) {
            return false;
        }
        BaseFragment last = LaunchActivity.getSafeLastFragment();
        if (last == null || last.getParentActivity() == null) {
            return false;
        }
        String title = fileName != null && fileName.length() > 0 ? fileName : file.getName();
        last.presentFragment(new RawPdfViewerActivity(file, title));
        return true;
    }

    /**
     * A link preview that carries a PDF: opens it in the viewer, or starts the download (the preview shows its progress,
     * a second tap opens it). False when the viewer is off or the document isn't a PDF.
     *
     * <p>From exteraGram (PdfViewerActivity.openWebPageDocument).
     */
    public static boolean openWebPageDocument(BaseFragment fragment, MessageObject messageObject) {
        if (fragment == null || fragment.getParentActivity() == null || Build.VERSION.SDK_INT < 21 || !RawChatUiConfig.pdfViewer.get()
                || messageObject == null || messageObject.messageOwner == null || messageObject.getDocument() == null) {
            return false;
        }
        String mimeType = messageObject.getMimeType();
        boolean pdf = mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("application/pdf") || "PDF".equals(messageObject.getExtension());
        if (!pdf) {
            return false;
        }
        File file = null;
        String attachPath = messageObject.messageOwner.attachPath;
        if (attachPath != null && attachPath.length() > 0) {
            file = new File(attachPath);
        }
        if (file == null || !file.exists()) {
            file = FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
        }
        if (file != null && file.exists()) {
            String name = messageObject.getDocumentName();
            fragment.presentFragment(new RawPdfViewerActivity(file, name != null && name.length() > 0 ? name : file.getName()));
        } else {
            FileLoader.getInstance(messageObject.currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_NORMAL, 0);
        }
        return true;
    }

    private static boolean isPdf(File file, String fileName, String mimeType) {
        if ("application/pdf".equals(mimeType)) {
            return true;
        }
        String name = (fileName != null ? fileName : file.getName()).toLowerCase(Locale.US);
        return name.endsWith(".pdf");
    }

    /** Opens the file in an external app (the stock path). */
    static void openExternal(File file, String title, Activity activity) {
        external = true;
        try {
            org.telegram.messenger.AndroidUtilities.openForView(file, title, "application/pdf", activity, null, false);
        } finally {
            external = false;
        }
    }
}
