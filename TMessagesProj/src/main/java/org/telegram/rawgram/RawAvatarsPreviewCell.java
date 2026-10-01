package org.telegram.rawgram;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

/** Avatar corners preview: the account's own avatar and two letter avatars, re-rounded live by the slider. */
@SuppressLint("ViewConstructor")
public class RawAvatarsPreviewCell extends LinearLayout {

    private static final int SIZE_DP = 56;

    private final int account;
    private final BackupImageView[] avatars = new BackupImageView[3];
    private final TextView[] names = new TextView[3];
    private final AvatarDrawable[] drawables = new AvatarDrawable[3];

    public RawAvatarsPreviewCell(Context context, int account) {
        super(context);
        this.account = account;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(12), dp(16), dp(12), dp(6));

        for (int i = 0; i < avatars.length; i++) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);

            avatars[i] = new BackupImageView(context);
            drawables[i] = new AvatarDrawable();
            column.addView(avatars[i], LayoutHelper.createLinear(SIZE_DP, SIZE_DP, Gravity.CENTER_HORIZONTAL));

            names[i] = new TextView(context);
            names[i].setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            names[i].setSingleLine(true);
            names[i].setEllipsize(TextUtils.TruncateAt.END);
            names[i].setGravity(Gravity.CENTER);
            column.addView(names[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

            addView(column, LayoutHelper.createLinear(84, LayoutHelper.WRAP_CONTENT, 4, 0, 4, 0));
        }

        TLRPC.User self = UserConfig.getInstance(account).getCurrentUser();
        avatars[0].getImageReceiver().setCurrentAccount(account);
        if (self != null) {
            drawables[0].setInfo(account, self);
            avatars[0].setForUserOrChat(self, drawables[0]);
        } else {
            drawables[0].setInfo(1, "Вы", null);
            avatars[0].setImageDrawable(drawables[0]);
        }
        String selfName = self != null ? UserObject.getFirstName(self) : null;
        names[0].setText(TextUtils.isEmpty(selfName) ? "Вы" : selfName);

        drawables[1].setInfo(5, "Анна", "Смирнова");
        avatars[1].setImageDrawable(drawables[1]);
        names[1].setText("Анна");

        drawables[2].setInfo(2, "Рабочий", "чат");
        avatars[2].setImageDrawable(drawables[2]);
        names[2].setText("Рабочий чат");
    }

    /** Applies the current corner option. */
    public void bind() {
        int radius = RawUi.avatarR(dp(SIZE_DP / 2f));
        for (int i = 0; i < avatars.length; i++) {
            avatars[i].setRoundRadius(radius);
            names[i].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        }
    }
}
