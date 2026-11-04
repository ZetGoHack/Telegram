package org.telegram.rawgram;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SvgHelper;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ProxyDrawable;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.SlideView;
import org.telegram.ui.TwoStepVerificationActivity;

import java.util.HashMap;

/**
 * Login by QR code on the login screen: this device shows a tg://login token, another logged-in device scans it
 * (Настройки → Устройства → Подключить устройство). The request flow (token refresh, DC migration, 2FA, cancelling
 * requests when the page is left) follows exteraGram / AyuGram; the QR renderer is ported from Nagram (GPLv3).
 */
public final class RawQrLogin {

    private RawQrLogin() {
    }

    /** What the QR page needs from LoginActivity. */
    public interface Host {
        BaseFragment fragment();

        /** The QR page is the current page of the login screen. */
        boolean isQrPageShown();

        boolean testBackend();

        void onQrAuthorized(TLRPC.TL_auth_authorization authorization);

        /** Opens the password page with these params (key "password" = serialized account.Password in hex). */
        void openPasswordPage(Bundle params);

        void openPhonePage();

        void showProgress(boolean show);

        void showAlert(String text);
    }

    private static final int REQUEST_FLAGS = ConnectionsManager.RequestFlagEnableUnauthorized | ConnectionsManager.RequestFlagFailOnServerErrors
            | ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagTryDifferentDc;
    private static final int PASSWORD_FLAGS = ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagWithoutLogin;
    /** The first code appears after the page slide-in. */
    private static final long FIRST_TOKEN_DELAY = 380;

    /** The «⋮» button on the login screen: proxy settings and QR login. */
    public static ImageView createMenuButton(Context context, BaseFragment fragment, Runnable openProxy, Runnable openQr) {
        ImageView button = new ImageView(context);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageResource(R.drawable.ic_ab_other);
        button.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN));
        button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector)));
        button.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        button.setOnClickListener(v -> {
            ProxyDrawable proxyIcon = new ProxyDrawable(context);
            proxyIcon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultSubmenuItemIcon), PorterDuff.Mode.SRC_IN));
            ItemOptions.makeOptions(fragment, button)
                .add(proxyIcon, LocaleController.getString(R.string.ProxySettings), openProxy)
                .add(R.drawable.msg_qrcode, "Вход по QR-коду", openQr)
                .setGravity(Gravity.RIGHT)
                .show();
        });
        return button;
    }

    /** The QR page of the login screen. */
    public static final class QrPage extends SlideView implements NotificationCenter.NotificationCenterDelegate {

        private final Host host;
        private final int account;
        private final TextView titleView;
        private final TextView subtitleView;
        private final QrView qrView;

        private boolean waitingForEvent;
        private boolean firstToken;
        private long shownAt;
        private int exportRequestId, importRequestId, passwordRequestId;
        private boolean exportAgain;
        private Runnable refreshRunnable, applyTokenRunnable;

        public QrPage(Context context, int account, Host host) {
            super(context);
            this.host = host;
            this.account = account;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            titleView.setTypeface(AndroidUtilities.bold());
            titleView.setGravity(Gravity.CENTER);
            titleView.setText("Вход по QR-коду");
            addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 16, 32, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            subtitleView.setGravity(Gravity.CENTER);
            subtitleView.setLineSpacing(AndroidUtilities.dp(2), 1f);
            subtitleView.setText("Открой Telegram на другом устройстве: Настройки → Устройства → Подключить устройство, и отсканируй код.");
            addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 8, 24, 0));

            qrView = new QrView(context);
            addView(qrView, LayoutHelper.createLinear(260, 260, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));
            updateColors();
        }

        @Override
        public void updateColors() {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        }

        @Override
        public String getHeaderName() {
            return "Вход по QR-коду";
        }

        @Override
        public boolean needBackButton() {
            return true;
        }

        @Override
        public boolean onBackPressed(boolean force) {
            host.openPhonePage();
            return false;
        }

        @Override
        public void onShow() {
            super.onShow();
            waitingForEvent = true;
            firstToken = true;
            shownAt = System.currentTimeMillis();
            qrView.clear();
            exportToken(true);
        }

        @Override
        public void onHide() {
            stop();
            super.onHide();
            firstToken = true;
            qrView.clear();
        }

        @Override
        public void onDestroyActivity() {
            stop();
            super.onDestroyActivity();
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (id == NotificationCenter.rawUpdateLoginToken) {
                // the other device accepted the token: exporting again returns the authorization
                exportToken(false);
            }
        }

        private ConnectionsManager connections() {
            return ConnectionsManager.getInstance(account);
        }

        private boolean active() {
            return waitingForEvent && host.isQrPageShown();
        }

        private void stop() {
            waitingForEvent = false;
            exportAgain = false;
            NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.rawUpdateLoginToken);
            if (refreshRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
                refreshRunnable = null;
            }
            if (applyTokenRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(applyTokenRunnable);
                applyTokenRunnable = null;
            }
            if (exportRequestId != 0) {
                connections().cancelRequest(exportRequestId, true);
                exportRequestId = 0;
            }
            if (importRequestId != 0) {
                connections().cancelRequest(importRequestId, true);
                importRequestId = 0;
            }
            if (passwordRequestId != 0) {
                connections().cancelRequest(passwordRequestId, true);
                passwordRequestId = 0;
            }
        }

        private void exportToken(boolean first) {
            if (!waitingForEvent || getContext() == null) {
                return;
            }
            if (exportRequestId != 0) {
                exportAgain = true;
                return;
            }
            exportAgain = false;
            if (first) {
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.rawUpdateLoginToken);
                if (connections().isTestBackend() != host.testBackend()) {
                    connections().switchBackend(false);
                }
                connections().cleanup(false);
            }
            TLRPC.TL_auth_exportLoginToken req = new TLRPC.TL_auth_exportLoginToken();
            req.api_id = BuildVars.APP_ID;
            req.api_hash = BuildVars.APP_HASH;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                UserConfig config = UserConfig.getInstance(a);
                if (config.isClientActivated()) {
                    req.except_ids.add(config.getClientUserId());
                }
            }
            exportRequestId = connections().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> onExported(response, error)), REQUEST_FLAGS);
        }

        private void onExported(TLObject response, TLRPC.TL_error error) {
            exportRequestId = 0;
            boolean again = exportAgain;
            exportAgain = false;
            if (!active()) {
                return;
            }
            if (error != null) {
                qrView.clear();
                stop();
                handleError(error.text);
                return;
            }
            if (response instanceof TLRPC.TL_auth_loginToken) {
                TLRPC.TL_auth_loginToken token = (TLRPC.TL_auth_loginToken) response;
                String link = "tg://login?token=" + Base64.encodeToString(token.token, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                if (firstToken) {
                    if (applyTokenRunnable != null) {
                        AndroidUtilities.cancelRunOnUIThread(applyTokenRunnable);
                    }
                    applyTokenRunnable = () -> {
                        applyTokenRunnable = null;
                        if (active()) {
                            qrView.setData(link);
                            firstToken = false;
                        }
                    };
                    AndroidUtilities.runOnUIThread(applyTokenRunnable, Math.max(0, FIRST_TOKEN_DELAY - (System.currentTimeMillis() - shownAt)));
                } else {
                    qrView.setData(link);
                }
                long expiresIn = token.expires - connections().getCurrentTime();
                if (expiresIn <= 0) {
                    expiresIn = 20;
                }
                if (refreshRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(refreshRunnable);
                }
                AndroidUtilities.runOnUIThread(refreshRunnable = () -> {
                    refreshRunnable = null;
                    exportToken(false);
                }, expiresIn * 1000);
            } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                TLRPC.auth_Authorization auth = ((TLRPC.TL_auth_loginTokenSuccess) response).authorization;
                stop();
                finish(auth);
            } else if (response instanceof TLRPC.TL_auth_loginTokenMigrateTo) {
                TLRPC.TL_auth_loginTokenMigrateTo migrate = (TLRPC.TL_auth_loginTokenMigrateTo) response;
                stop();
                waitingForEvent = true; // keep active() true until the import answers
                host.showProgress(true);
                connections().setDefaultDatacenterId(migrate.dc_id);
                TLRPC.TL_auth_importLoginToken req = new TLRPC.TL_auth_importLoginToken();
                req.token = migrate.token;
                importRequestId = connections().sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> onImported(res, err)), REQUEST_FLAGS);
                return;
            }
            if (again && active() && exportRequestId == 0) {
                exportToken(false);
            }
        }

        private void onImported(TLObject response, TLRPC.TL_error error) {
            importRequestId = 0;
            if (!active()) {
                return;
            }
            host.showProgress(false);
            if (error != null) {
                qrView.clear();
                waitingForEvent = false;
                handleError(error.text);
            } else if (response instanceof TLRPC.TL_auth_loginTokenSuccess) {
                waitingForEvent = false;
                finish(((TLRPC.TL_auth_loginTokenSuccess) response).authorization);
            }
        }

        private void finish(TLRPC.auth_Authorization auth) {
            if (auth instanceof TLRPC.TL_auth_authorization) {
                postDelayed(() -> host.onQrAuthorized((TLRPC.TL_auth_authorization) auth), 150);
            } else {
                host.showAlert(LocaleController.getString(R.string.UnknownError));
            }
        }

        private void handleError(String text) {
            if (TextUtils.isEmpty(text)) {
                host.showAlert(LocaleController.getString(R.string.UnknownError));
            } else if (text.contains("SESSION_PASSWORD_NEEDED")) {
                host.showProgress(true);
                passwordRequestId = connections().sendRequest(new TL_account.getPassword(), (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    passwordRequestId = 0;
                    host.showProgress(false);
                    if (!host.isQrPageShown()) {
                        return;
                    }
                    if (error != null) {
                        host.showAlert(error.text);
                        return;
                    }
                    TL_account.Password password = (TL_account.Password) response;
                    if (!TwoStepVerificationActivity.canHandleCurrentPassword(password, true)) {
                        host.showAlert(LocaleController.getString(R.string.UpdateAppAlert));
                        return;
                    }
                    SerializedData data = new SerializedData(password.getObjectSize());
                    password.serializeToStream(data);
                    Bundle params = new Bundle();
                    params.putString("password", Utilities.bytesToHex(data.toByteArray()));
                    host.openPasswordPage(params);
                }), PASSWORD_FLAGS);
            } else if (text.startsWith("FLOOD_WAIT")) {
                host.showAlert(LocaleController.getString(R.string.FloodWait));
            } else {
                host.showAlert(text);
            }
        }
    }

    /** QR code with Telegram's look: a matrix animation while loading, then the code, crossfading on each new token. Ported from Nagram. */
    static final class QrView extends View {

        private static final int QR_SIZE = 37;
        private static final int CROSSFADE_DP = 140;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final AnimatedFloat contentAlpha = new AnimatedFloat(1f, this, 0, 2000, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final Paint fadeFromPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fadeToPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] radii = new float[8];
        private RLottieDrawable loadingMatrix;
        private Bitmap contentBitmap, oldContentBitmap, qrLogo;
        private String link, preparedLink;
        private int preparedWidth, preparedHeight;
        private boolean firstPrepare = true;

        QrView(Context context) {
            super(context);
            int h = AndroidUtilities.dp(CROSSFADE_DP);
            fadeFromPaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0xffffffff, 0}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            fadeFromPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            fadeToPaint.setShader(new LinearGradient(0, 0, 0, h, new int[]{0, 0xffffffff}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            fadeToPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        }

        void clear() {
            link = null;
            preparedLink = null;
            contentBitmap = null;
            oldContentBitmap = null;
            firstPrepare = true;
            invalidate();
        }

        void setData(String link) {
            this.link = link;
            final int w = getWidth(), h = getHeight();
            Utilities.themeQueue.postRunnable(() -> prepare(w, h, link));
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w != oldw || h != oldh) {
                if (qrLogo != null) {
                    qrLogo.recycle();
                    qrLogo = null;
                }
                String l = link;
                Utilities.themeQueue.postRunnable(() -> prepare(w, h, l));
            }
        }

        private void prepare(int w, int h, String link) {
            if (w == 0 || h == 0 || TextUtils.isEmpty(link)) {
                return;
            }
            if (TextUtils.equals(link, preparedLink) && preparedWidth == w && preparedHeight == h) {
                return;
            }
            HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 0);
            Bitmap bitmap;
            try {
                bitmap = new TelegramQRCodeWriter().encode(link, w, h, hints, null, 0.75f, 0, Color.BLACK);
            } catch (Exception e) {
                FileLog.e(e);
                return;
            }
            if (bitmap == null) {
                return;
            }
            preparedWidth = w;
            preparedHeight = h;
            preparedLink = link;
            AndroidUtilities.runOnUIThread(() -> {
                if (!TextUtils.equals(link, this.link)) {
                    bitmap.recycle();
                    return;
                }
                Bitmap old = contentBitmap;
                contentBitmap = bitmap;
                if (!firstPrepare) {
                    contentAlpha.set(0, true);
                }
                firstPrepare = false;
                if (oldContentBitmap != null && oldContentBitmap != old) {
                    oldContentBitmap.recycle();
                }
                oldContentBitmap = old;
                invalidate();
            });
        }

        private void drawLoading(Canvas canvas, int multiple, int size, float scale) {
            if (loadingMatrix == null) {
                loadingMatrix = new RLottieDrawable(R.raw.qr_matrix, AndroidUtilities.dp(200), AndroidUtilities.dp(200));
                loadingMatrix.setMasterParent(this);
                loadingMatrix.setAutoRepeat(1);
                loadingMatrix.setColorFilter(new PorterDuffColorFilter(Color.BLACK, PorterDuff.Mode.MULTIPLY));
                loadingMatrix.start();
            }
            int width = getWidth();
            loadingMatrix.setBounds(16, 16, width - 16, width - 16);
            loadingMatrix.draw(canvas);
            int imageBlocks = Math.round((size - 32) / 4.65f / multiple);
            if (imageBlocks % 2 != QR_SIZE % 2) {
                imageBlocks++;
            }
            int imageSize = imageBlocks * multiple - 24;
            int imageX = (size - imageSize) / 2;
            canvas.save();
            canvas.scale(scale, scale);
            paint.setColor(Color.BLACK);
            TelegramQRCodeWriter.drawSideQuads(canvas, 0, 0, paint, 7, multiple, 16, size, .75f, radii, true);
            if (qrLogo == null && imageSize > 0) {
                qrLogo = SvgHelper.getBitmap(AndroidUtilities.readRes(R.raw.qr_logo), imageSize, imageSize, false);
            }
            if (qrLogo != null) {
                canvas.drawBitmap(qrLogo, imageX, imageX, null);
            }
            canvas.restore();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), AndroidUtilities.dp(12), AndroidUtilities.dp(12), paint);
            int width = getWidth();
            int multiple = width / QR_SIZE;
            if (multiple <= 0) {
                return;
            }
            int size = multiple * QR_SIZE + 32;
            float scale = width / (float) size;
            float alpha = contentAlpha.set(1f);
            boolean crossfading = alpha > 0 && alpha < 1;
            float h = AndroidUtilities.dp(CROSSFADE_DP);
            if (alpha < 1f) {
                if (crossfading) {
                    AndroidUtilities.rectTmp.set(0, 0, size, size);
                    canvas.saveLayerAlpha(AndroidUtilities.rectTmp, 255, Canvas.ALL_SAVE_FLAG);
                }
                if (oldContentBitmap != null) {
                    canvas.save();
                    canvas.scale(scale, scale);
                    canvas.drawBitmap(oldContentBitmap, 0, 0, null);
                    canvas.restore();
                } else {
                    drawLoading(canvas, multiple, size, scale);
                }
                if (crossfading) {
                    canvas.save();
                    canvas.translate(0, -h + (size + h) * (1f - alpha));
                    canvas.drawRect(0, 0, size, size + h, fadeToPaint);
                    canvas.restore();
                    canvas.restore();
                }
            }
            if (alpha > 0f) {
                if (crossfading) {
                    AndroidUtilities.rectTmp.set(0, 0, getWidth(), getHeight());
                    canvas.saveLayerAlpha(AndroidUtilities.rectTmp, 255, Canvas.ALL_SAVE_FLAG);
                }
                if (contentBitmap != null) {
                    canvas.save();
                    canvas.scale(scale, scale);
                    canvas.drawBitmap(contentBitmap, 0, 0, null);
                    canvas.restore();
                } else {
                    drawLoading(canvas, multiple, size, scale);
                }
                if (crossfading) {
                    canvas.save();
                    canvas.translate(0, -h + (getHeight() + h) * (1f - alpha));
                    canvas.drawRect(0, -h - getHeight(), getWidth(), getHeight() + h, fadeFromPaint);
                    canvas.restore();
                    canvas.restore();
                }
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (loadingMatrix != null) {
                loadingMatrix.stop();
                loadingMatrix.recycle(false);
                loadingMatrix = null;
            }
            if (qrLogo != null) {
                qrLogo.recycle();
                qrLogo = null;
            }
        }
    }
}
