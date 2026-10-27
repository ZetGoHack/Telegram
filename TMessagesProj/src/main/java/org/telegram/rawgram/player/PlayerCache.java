package org.telegram.rawgram.player;

import java.io.File;
import org.telegram.messenger.FileLoader;

/**
 * Ported from exteraGram (exteraSquad, GPL-2.0-or-later): PlayerCache.
 */
public abstract class PlayerCache {
    public static File directory(String str) {
        File fileCheckDirectory = FileLoader.checkDirectory(4);
        if (fileCheckDirectory == null) {
            return null;
        }
        File file = new File(fileCheckDirectory, str);
        if (file.exists() || file.mkdirs()) {
            return file;
        }
        return null;
    }
}
