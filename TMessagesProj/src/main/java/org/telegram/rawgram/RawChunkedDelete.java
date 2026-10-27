package org.telegram.rawgram;

import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;

/**
 * Deletes all rows of one dialog from a big table in chunks of 2000 rows with secure_delete off
 * (one huge DELETE with secure_delete on overwrites every page and blocks the storage queue).
 * Ported from exteraGram's MessagesStorage.deleteDialogRowsChunked.
 */
public final class RawChunkedDelete {

    private static final int CHUNK = 2000;

    private RawChunkedDelete() {
    }

    /** {@code table} must be a trusted constant: it is concatenated into the statement. */
    public static void deleteByUid(SQLiteDatabase database, String table, long uid) throws SQLiteException {
        database.executeFast("PRAGMA secure_delete = OFF").stepThis().dispose();
        SQLitePreparedStatement state = null;
        try {
            state = database.executeFast("DELETE FROM " + table + " WHERE rowid IN (SELECT rowid FROM " + table + " WHERE uid = ? LIMIT " + CHUNK + ")");
            while (true) {
                state.requery();
                state.bindLong(1, uid);
                if (state.step() != 1) {
                    break;
                }
                Integer changes = database.executeInt("SELECT changes()");
                if (changes == null || changes == 0) {
                    break;
                }
            }
        } finally {
            if (state != null) {
                state.dispose();
            }
            database.executeFast("PRAGMA secure_delete = ON").stepThis().dispose();
        }
    }
}
