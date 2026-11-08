package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.Components.UpdateLayout;
import org.telegram.ui.IUpdateLayout;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        if (!org.telegram.rawgram.RawUpdates.supported()) { // rawGram: Telegram's update sheet for releases from @rwgrm
            return super.showUpdateAppPopup(context, update, account);
        }
        try {
            (new UpdateAppAlertDialog(context, update, account)).show();
        } catch (Exception e) {
            FileLog.e(e);
        }
        return true;
    }

    @Override
    public IUpdateLayout takeUpdateLayout(Activity activity, ViewGroup sideMenuContainer) {
        if (!org.telegram.rawgram.RawUpdates.supported()) { // rawGram: Telegram's update bar for releases from @rwgrm
            return super.takeUpdateLayout(activity, sideMenuContainer);
        }
        return new UpdateLayout(activity, sideMenuContainer);
    }
}
