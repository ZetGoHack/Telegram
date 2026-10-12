package org.telegram.rawgram;

import android.util.SparseArray;

import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.List;

/**
 * «Выбрать» in the chat's selection mode: adds every loaded message between the first and the last selected one.
 * The button takes the Reply slot of the bottom actions, as in exteraGram (ChatActivityActionsButtonsLayout).
 */
public final class RawSelectBetween {

    private RawSelectBetween() {
    }

    /**
     * Messages of {@code loaded} that lie between the selected ones and are not selected yet.
     * {@code selected[0]} is the chat itself, {@code selected[1]} the merged (migrated) one, as in ChatActivity.
     */
    public static ArrayList<MessageObject> between(SparseArray<MessageObject>[] selected, List<MessageObject> loaded, long mergeDialogId) {
        ArrayList<MessageObject> result = new ArrayList<>();
        if (selected == null || loaded == null) {
            return result;
        }
        for (int a = 0; a < selected.length; a++) {
            SparseArray<MessageObject> ids = selected[a];
            if (ids == null || ids.size() < 2) {
                continue;
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int i = 0; i < ids.size(); i++) {
                int id = ids.keyAt(i);
                min = Math.min(min, id);
                max = Math.max(max, id);
            }
            for (int i = 0; i < loaded.size(); i++) {
                MessageObject m = loaded.get(i);
                if (m == null || m.contentType != 0 || m.isSponsored()) {
                    continue;
                }
                int side = mergeDialogId != 0 && m.getDialogId() == mergeDialogId ? 1 : 0;
                int id = m.getId();
                if (side == a && id > min && id < max && ids.indexOfKey(id) < 0) {
                    result.add(m);
                }
            }
        }
        return result;
    }

    public static boolean hasBetween(SparseArray<MessageObject>[] selected, List<MessageObject> loaded, long mergeDialogId) {
        return !between(selected, loaded, mergeDialogId).isEmpty();
    }
}
