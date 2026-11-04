package org.telegram.rawgram.nowplaying;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.PlayPauseDrawable;
import org.telegram.ui.Components.ScaleStateListAnimator;

/**
 * The «Сейчас играет» card in a profile: cover, track / artist / album, a play button for the 30-second preview and a
 * pattern of the service's emoji on a gradient taken from the cover. Ported from exteraGram's {@code NowPlayingCard}
 * (exteraGram, GPLv3), including {@code UIUtil.drawNowPlayingPattern}.
 * <p>
 * Tap opens the track on the service (the saved music: plays it); long press does the same.
 */
@SuppressLint("ViewConstructor")
@OptIn(markerClass = UnstableApi.class)
public abstract class NowPlayingCard extends FrameLayout {

    /** Profile sections' corner radius ({@code RecyclerListView.setSections}); exteraGram uses its section radius. */
    private static final int SECTION_RADIUS_DP = 16;
    private static final int CARD_CONTENT_PADDING_DP = 12;
    private static final int COVER_SIZE_DP = 64;
    private static final int MIN_COVER_RADIUS_DP = 8;
    private static final int PLAY_BUTTON_SIZE_DP = 32;
    private static final int EMOJI_SIZE_DP = 20;
    private static final float PRESS_SCALE = 0.035f;
    private static final float PRESS_TENSION = 1.5f;
    private static final float SECONDARY_TEXT_ALPHA = 0.6f;
    private static final float PATTERN_ALPHA_NO_COVER = 0.4f;

    /** exteraGram's pattern: {x from the right edge, y from the centre, size, alpha} in dp, per emoji. */
    private static final float[] PATTERN = {
            -5.5f, 20.0f, 20.0f, 0.35f,
            -5.5f, -20.0f, 20.0f, 0.35f,
            -36.0f, -42.0f, 22.0f, 0.375f,
            -36.0f, 0.0f, 25.0f, 0.425f,
            -36.0f, 42.0f, 22.0f, 0.375f,
            -70.0f, 22.0f, 23.0f, 0.35f,
            -70.0f, -22.0f, 23.0f, 0.35f,
            -99.0f, 46.0f, 21.0f, 0.275f,
            -99.0f, 0.0f, 22.0f, 0.325f,
            -99.0f, -46.0f, 21.0f, 0.275f,
            -128.0f, -23.0f, 20.0f, 0.225f,
            -128.0f, 23.0f, 20.0f, 0.225f
    };

    private final Theme.ResourcesProvider resourcesProvider;
    private final AudioManager audioManager;
    private final AudioManager.OnAudioFocusChangeListener audioFocusChangeListener;
    private AudioFocusRequest audioFocusRequest;
    private boolean resumeOnFocusGain;

    private final FrameLayout cardLayout;
    private final BackupImageView imageView;
    private final SimpleTextView nameView;
    private final SimpleTextView artistView;
    private final SimpleTextView albumView;
    private final ImageView playPauseButton;
    private final PlayPauseDrawable playPauseDrawable;
    private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emoji;

    private NowPlayingCardData nowPlayingCardData;
    private ExoPlayer player;
    private boolean isPlaying;
    private long currentDocId = -1;
    private String currentPreviewUrl;

    /** Tap on a card that shows the profile's saved music. */
    public abstract void onSavedMusicClick();

    public NowPlayingCard(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.audioFocusChangeListener = this::onAudioFocusChange;
        setClickable(false);
        setWillNotDraw(false);

        cardLayout = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                NowPlayingCardData data = nowPlayingCardData;
                if (data != null) {
                    emoji.setColor(data.accentColor == null ? getThemedColor(Theme.key_windowBackgroundWhiteBlackText) : data.accentColor);
                    drawPattern(canvas, emoji, getWidth(), getHeight(), data.coverBitmap == null ? PATTERN_ALPHA_NO_COVER : 1f);
                }
                super.dispatchDraw(canvas);
            }
        };
        GradientDrawable background = new GradientDrawable() {
            @Override
            protected void onBoundsChange(Rect r) {
                super.onBoundsChange(r);
                setGradientRadius(r.width() * 2f);
            }
        };
        background.setCornerRadius(AndroidUtilities.dpf2(SECTION_RADIUS_DP));
        cardLayout.setBackground(background);
        cardLayout.setClipToOutline(true);
        cardLayout.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                Drawable drawable = view.getBackground();
                if (drawable != null) {
                    drawable.getOutline(outline);
                } else {
                    outline.setRect(0, 0, view.getWidth(), view.getHeight());
                }
            }
        });
        cardLayout.setClickable(true);
        ScaleStateListAnimator.apply(cardLayout, PRESS_SCALE, PRESS_TENSION);
        addView(cardLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        emoji = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(cardLayout, false, AndroidUtilities.dp(EMOJI_SIZE_DP),
                AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        cardLayout.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL,
                CARD_CONTENT_PADDING_DP, CARD_CONTENT_PADDING_DP, CARD_CONTENT_PADDING_DP, CARD_CONTENT_PADDING_DP));

        imageView = new BackupImageView(context);
        imageView.setClipToOutline(true);
        final float coverRadius = getCoverCornerRadius();
        imageView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), coverRadius);
            }
        });
        row.addView(imageView, LayoutHelper.createLinear(COVER_SIZE_DP, COVER_SIZE_DP, Gravity.LEFT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        row.addView(texts, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL));

        nameView = new SimpleTextView(context);
        nameView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        nameView.setTextColor(0xffffffff);
        nameView.setTextSize(16);
        nameView.setTypeface(AndroidUtilities.bold());
        NotificationCenter.listenEmojiLoading(nameView);
        texts.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 22));

        artistView = secondaryText(context);
        texts.addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 19, 0f, 2f, 0f, 0f));

        albumView = secondaryText(context);
        texts.addView(albumView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 19, 0f, 2f, 0f, 0f));

        playPauseDrawable = new PlayPauseDrawable(16);
        playPauseDrawable.setPause(false);
        playPauseDrawable.setColor(0xffffffff);
        playPauseButton = new ImageView(context);
        ScaleStateListAnimator.apply(playPauseButton);
        playPauseButton.setScaleType(ImageView.ScaleType.CENTER);
        playPauseButton.setImageDrawable(playPauseDrawable);
        playPauseButton.setOnClickListener(v -> togglePlayPause());
        row.addView(playPauseButton, LayoutHelper.createLinear(PLAY_BUTTON_SIZE_DP, PLAY_BUTTON_SIZE_DP, Gravity.CENTER_VERTICAL, 8, 0, 8, 0));
    }

    private static SimpleTextView secondaryText(Context context) {
        SimpleTextView view = new SimpleTextView(context);
        view.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        view.setTypeface(android.graphics.Typeface.DEFAULT);
        view.setTextSize(14);
        view.setTextColor(0xffffffff);
        view.setAlpha(SECONDARY_TEXT_ALPHA);
        NotificationCenter.listenEmojiLoading(view);
        return view;
    }

    private static float getCoverCornerRadius() {
        return AndroidUtilities.dpf2(Math.max(SECTION_RADIUS_DP - 12, MIN_COVER_RADIUS_DP));
    }

    /** exteraGram's {@code UIUtil.drawNowPlayingPattern}: the emoji scattered towards the right edge. */
    private static void drawPattern(Canvas canvas, Drawable pattern, float w, float h, float alpha) {
        if (alpha <= 0f) {
            return;
        }
        for (int i = 0; i < PATTERN.length; i += 4) {
            float x = AndroidUtilities.dpf2(PATTERN[i]);
            float y = AndroidUtilities.dpf2(PATTERN[i + 1]);
            float half = AndroidUtilities.dpf2(PATTERN[i + 2]) / 2f;
            float cy = h / 2f;
            pattern.setBounds((int) (x + w - half), (int) (y + cy - half), (int) (x + w + half), (int) (cy + y + half));
            pattern.setAlpha((int) (255f * alpha * PATTERN[i + 3]));
            pattern.draw(canvas);
        }
    }

    private void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (player == null || !player.isPlaying()) {
                return;
            }
            resumeOnFocusGain = true;
            player.pause();
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
            resumeOnFocusGain = false;
            if (player != null) {
                player.pause();
            }
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && resumeOnFocusGain) {
            if (player != null) {
                player.play();
            }
            resumeOnFocusGain = false;
        }
    }

    public void set(NowPlayingCardData cardData) {
        nowPlayingCardData = cardData;
        final NowPlayingTrack track = cardData.track;
        artistView.setText(null);
        nameView.setText(null);
        albumView.setText(null);
        if (track.artists == null || track.artists.isEmpty()) {
            artistView.setText(LocaleController.getString(R.string.AudioUnknownArtist));
        } else {
            artistView.setText(TextUtils.join(", ", track.artists));
        }
        nameView.setText(Emoji.replaceEmoji(track.trackName, nameView.getPaint().getFontMetricsInt(), false));
        boolean showAlbum = !TextUtils.isEmpty(track.albumName) && !TextUtils.equals(track.trackName, track.albumName);
        albumView.setVisibility(showAlbum ? VISIBLE : GONE);
        if (showAlbum) {
            albumView.setText(Emoji.replaceEmoji(track.albumName, albumView.getPaint().getFontMetricsInt(), false));
        }
        setPadding(AndroidUtilities.dp(CARD_CONTENT_PADDING_DP), 0, AndroidUtilities.dp(CARD_CONTENT_PADDING_DP), 0);

        Drawable background = cardLayout.getBackground();
        if (background instanceof GradientDrawable) {
            GradientDrawable gradient = (GradientDrawable) background;
            gradient.mutate();
            gradient.setDither(true);
            gradient.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            gradient.setGradientCenter(1f, 0.5f);
            int color = cardData.backgroundColor != null ? cardData.backgroundColor : getThemedColor(Theme.key_windowBackgroundWhite);
            gradient.setColors(new int[]{color, cardData.backgroundColor != null ? NowPlayingCardData.adjustHsl(color, 1.5f, -1f) : color});
        }

        playPauseButton.setVisibility(!TextUtils.isEmpty(track.previewUrl) && !track.isTelegram() ? VISIBLE : GONE);
        playPauseButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(PLAY_BUTTON_SIZE_DP),
                cardData.accentColor != null ? cardData.accentColor : getThemedColor(Theme.key_featuredStickers_addButton)));

        long documentId = cardData.userEmoji <= 0 || !track.isTelegram()
                ? ServiceEmoji.fromString(track.platform).documentId
                : cardData.userEmoji;
        if (documentId != currentDocId) {
            currentDocId = documentId;
            emoji.set(documentId, true);
        }

        cardLayout.setOnClickListener(v -> {
            if (track.isTelegram()) {
                onSavedMusicClick();
                return;
            }
            // exteraGram offers its supporter subscription here to users without a badge; rawGram just opens the track
            Browser.openUrl(v.getContext(), track.songUrl);
        });
        cardLayout.setOnLongClickListener(v -> {
            if (track.isTelegram()) {
                onSavedMusicClick();
                return true;
            }
            Browser.openUrl(v.getContext(), track.songUrl);
            return true;
        });

        if (cardData.imageLocation != null) {
            Drawable thumb = cardData.coverBitmap != null ? new BitmapDrawable(getContext().getResources(), cardData.coverBitmap) : null;
            imageView.setImage(cardData.imageLocation, null, thumb, 0, null);
            if (cardData.coverBitmap != null) {
                setTextsColor(0xffffffff);
            } else {
                setTextsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
            }
        } else {
            imageView.setImageResource(R.drawable.rawgram_nocover, getThemedColor(Theme.key_player_button));
            setTextsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        }

        String previewUrl = track.previewUrl;
        if (!TextUtils.equals(previewUrl, currentPreviewUrl)) {
            currentPreviewUrl = previewUrl;
            initializePlayer();
        }
        invalidate();
    }

    private void setTextsColor(int color) {
        artistView.setTextColor(color);
        nameView.setTextColor(color);
        albumView.setTextColor(color);
    }

    private void initializePlayer() {
        releasePlayer();
        String previewUrl = nowPlayingCardData == null ? null : nowPlayingCardData.track.previewUrl;
        if (previewUrl == null) {
            return;
        }
        ExoPlayer exoPlayer = new ExoPlayer.Builder(getContext()).build();
        exoPlayer.setMediaSource(new ProgressiveMediaSource.Factory(new DefaultDataSource.Factory(getContext()))
                .createMediaSource(MediaItem.fromUri(Uri.parse(previewUrl))));
        exoPlayer.prepare();
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onIsPlayingChanged(boolean playing) {
                isPlaying = playing;
                updatePlayPauseButton();
                if (!playing) {
                    abandonAudioFocus();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    abandonAudioFocus();
                }
            }
        });
        player = exoPlayer;
    }

    private void togglePlayPause() {
        ExoPlayer exoPlayer = player;
        if (exoPlayer == null) {
            return;
        }
        if (exoPlayer.isPlaying()) {
            exoPlayer.pause();
            abandonAudioFocus();
        } else if (requestAudioFocus()) {
            if (exoPlayer.getPlaybackState() == Player.STATE_ENDED) {
                exoPlayer.seekTo(0L);
            }
            exoPlayer.play();
        }
    }

    private boolean requestAudioFocus() {
        if (Build.VERSION.SDK_INT < 26) {
            return audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build();
        return audioManager.requestAudioFocus(audioFocusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
                audioFocusRequest = null;
            }
            return;
        }
        audioManager.abandonAudioFocus(audioFocusChangeListener);
    }

    private void updatePlayPauseButton() {
        playPauseDrawable.setPause(isPlaying);
    }

    private void releasePlayer() {
        if (player != null) {
            player.release();
        }
        player = null;
        isPlaying = false;
        updatePlayPauseButton();
        abandonAudioFocus();
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        emoji.attach();
        if (player != null || nowPlayingCardData == null) {
            return;
        }
        initializePlayer();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        emoji.detach();
        releasePlayer();
    }
}
