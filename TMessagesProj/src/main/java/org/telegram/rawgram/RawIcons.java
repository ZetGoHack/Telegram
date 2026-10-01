/*
 * This file is part of rawGram, a fork of Telegram for Android (GPLv3).
 *
 * Icon pack replacement ported from Nagram (tw.nekomimi.nekogram.ui.icons.IconsResources
 * and SolarIcons, GPLv3, https://github.com/NextAlone/Nagram), which in turn took the idea
 * and the icon set from exteraGram (https://github.com/exteraSquad/exteraGram, GPL-2.0-or-later).
 *
 * Solar Icon Set by 480 Design (https://www.figma.com/community/file/1166831539721848736),
 * licensed under CC BY 4.0. The *_solar / ayu_* vector drawables were taken from Nagram.
 */
package org.telegram.rawgram;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

/**
 * Alternative icon pack. {@link #wrap(Resources)} is called from LaunchActivity.getResources():
 * when a non-default pack is selected it returns a Resources wrapper that substitutes drawable ids
 * (Telegram drawable id -> pack drawable id) on every getDrawable* call. The pack is read once per
 * process, so changing it requires a restart.
 */
@SuppressLint("UseCompatLoadingForDrawables")
public final class RawIcons {

    public static final int PACK_TELEGRAM = 0;
    public static final int PACK_SOLAR = 1;

    private static int activePack = -1;
    private static volatile SparseIntArray map;

    private static Resources lastBase;
    private static IconsResources wrapper;

    private RawIcons() {
    }

    private static int activePack() {
        if (activePack == -1) {
            if (ApplicationLoader.applicationContext == null) {
                return PACK_TELEGRAM;
            }
            activePack = RawUiConfig.getIconPack();
        }
        return activePack;
    }

    /** Returns the base resources, or a drawable-substituting wrapper when an icon pack is active. */
    public static Resources wrap(Resources base) {
        if (base == null || activePack() != PACK_SOLAR) {
            return base;
        }
        synchronized (RawIcons.class) {
            if (wrapper == null || lastBase != base || wrapper.getAssets() != base.getAssets()) {
                wrapper = new IconsResources(base);
                lastBase = base;
            } else if (!wrapper.getConfiguration().equals(base.getConfiguration())) {
                // the wrapper owns its own ResourcesImpl: keep it in sync on rotation / night mode / locale
                wrapper.updateConfiguration(base.getConfiguration(), base.getDisplayMetrics());
            }
            return wrapper;
        }
    }

    /** Maps a Telegram drawable id to the active pack's drawable id (identity when none). */
    public static int convert(int id) {
        if (activePack() != PACK_SOLAR) {
            return id;
        }
        SparseIntArray m = map;
        if (m == null) {
            m = buildSolar();
            map = m;
        }
        return m.get(id, id);
    }

    private static final class IconsResources extends Resources {

        @SuppressWarnings("deprecation")
        IconsResources(Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
        }

        @Override
        public Drawable getDrawable(int id) throws NotFoundException {
            return super.getDrawable(convert(id), null);
        }

        @Override
        public Drawable getDrawable(int id, @Nullable Theme theme) throws NotFoundException {
            return super.getDrawable(convert(id), theme);
        }

        @Nullable
        @Override
        public Drawable getDrawableForDensity(int id, int density, @Nullable Theme theme) {
            return super.getDrawableForDensity(convert(id), density, theme);
        }

        @Nullable
        @Override
        public Drawable getDrawableForDensity(int id, int density) throws NotFoundException {
            return super.getDrawableForDensity(convert(id), density, null);
        }
    }

    private static void put(SparseIntArray m, int from, int to) {
        if (m.indexOfKey(from) < 0) {
            m.put(from, to);
        }
    }

    // Telegram drawable -> Solar drawable (from Nagram's SolarIcons; entries whose source drawable
    // does not exist in rawGram are omitted).
    private static SparseIntArray buildSolar() {
        SparseIntArray m = new SparseIntArray(410);
        put(m, R.drawable.arrow_more, R.drawable.arrow_more_solar);
        put(m, R.drawable.attach_send, R.drawable.attach_send_solar);
        put(m, R.drawable.bot_file, R.drawable.msg_round_file_solar);
        put(m, R.drawable.bot_location, R.drawable.bot_location_solar);
        put(m, R.drawable.filled_fire, R.drawable.burn_solar);
        put(m, R.drawable.calls_bluetooth, R.drawable.calls_menu_bluetooth_solar);
        put(m, R.drawable.calls_camera_mini, R.drawable.calls_camera_mini_solar);
        put(m, R.drawable.calls_decline, R.drawable.calls_decline_solar);
        put(m, R.drawable.calls_headphones, R.drawable.calls_menu_headset_solar);
        put(m, R.drawable.calls_menu_headset, R.drawable.calls_menu_headset_solar);
        put(m, R.drawable.calls_menu_phone, R.drawable.calls_menu_phone_solar);
        put(m, R.drawable.calls_mute_mini, R.drawable.calls_mute_solar);
        put(m, R.drawable.calls_speaker, R.drawable.calls_menu_speaker_solar);
        put(m, R.drawable.calls_unmute, R.drawable.input_mic_pressed_solar);
        put(m, R.drawable.calls_video, R.drawable.profile_video_solar);
        put(m, R.drawable.camera_revert1, R.drawable.camera_revert1_solar);
        put(m, R.drawable.camera_revert2, R.drawable.camera_revert2_solar);
        put(m, R.drawable.chat_calls_video, R.drawable.profile_video_solar);
        put(m, R.drawable.chat_calls_voice, R.drawable.profile_phone_solar);
        put(m, R.drawable.chats_archive, R.drawable.chats_archive_solar);
        put(m, R.drawable.chats_pin, R.drawable.msg_pin_solar);
        put(m, R.drawable.chats_replies, R.drawable.chats_replies_solar);
        put(m, R.drawable.chats_saved, R.drawable.chats_saved_solar);
        put(m, R.drawable.chats_unpin, R.drawable.msg_unpin_solar);
        put(m, R.drawable.emoji_tabs_faves, R.drawable.emoji_tabs_faves_solar);
        put(m, R.drawable.emoji_tabs_new1, R.drawable.emoji_tabs_new1_solar);
        put(m, R.drawable.emoji_tabs_new2, R.drawable.emoji_tabs_new2_solar);
        put(m, R.drawable.emoji_tabs_new3, R.drawable.emoji_tabs_new3_solar);
        put(m, R.drawable.filled_button_reply, R.drawable.filled_button_reply_solar);
        put(m, R.drawable.files_folder, R.drawable.files_folder_solar);
        put(m, R.drawable.files_gallery, R.drawable.files_gallery_solar);
        put(m, R.drawable.files_internal, R.drawable.files_internal_solar);
        put(m, R.drawable.files_storage, R.drawable.files_storage_solar);
        put(m, R.drawable.fingerprint, R.drawable.fingerprint_solar);
        put(m, R.drawable.flash_auto, R.drawable.flash_auto_solar);
        put(m, R.drawable.flash_off, R.drawable.flash_off_solar);
        put(m, R.drawable.flash_on, R.drawable.flash_on_solar);
        put(m, R.drawable.ghost, R.drawable.ghost_solar);
        put(m, R.drawable.group_edit, R.drawable.group_edit_profile_solar);
        put(m, R.drawable.group_edit_profile, R.drawable.group_edit_profile_solar);
        put(m, R.drawable.ic_arrow_drop_down, R.drawable.ic_arrow_drop_down_solar);
        put(m, R.drawable.ic_chatlist_add_2, R.drawable.ic_chatlist_add_2_solar);
        put(m, R.drawable.ic_gallery_background, R.drawable.ic_gallery_background_solar);
        put(m, R.drawable.ic_goinline, R.drawable.ic_goinline_solar);
        put(m, R.drawable.ic_lock_header, R.drawable.list_secret_solar);
        put(m, R.drawable.ic_masks_msk1, R.drawable.ic_masks_msk1_solar);
        put(m, R.drawable.ic_outinline, R.drawable.ic_outinline_solar);
        put(m, R.drawable.ic_send, R.drawable.ic_send_solar);
        put(m, R.drawable.input_attach, R.drawable.ayu_input_attach);
        put(m, R.drawable.msg_input_attach2, R.drawable.ayu_input_attach);
        put(m, R.drawable.input_bot1, R.drawable.input_bot1_solar);
        put(m, R.drawable.input_bot2, R.drawable.input_bot2_solar);
        put(m, R.drawable.input_calendar1, R.drawable.input_calendar1_solar);
        put(m, R.drawable.input_calendar2, R.drawable.input_calendar2_solar);
        put(m, R.drawable.input_forward, R.drawable.input_forward_solar);
        put(m, R.drawable.input_keyboard, R.drawable.input_keyboard_solar);
        put(m, R.drawable.input_mic, R.drawable.input_mic_solar);
        put(m, R.drawable.input_mic_pressed, R.drawable.input_mic_pressed_solar);
        put(m, R.drawable.input_notify_off, R.drawable.msg_bell_mute_solar);
        put(m, R.drawable.input_notify_on, R.drawable.msg_notifications_solar);
        put(m, R.drawable.input_reply, R.drawable.input_reply_solar);
        put(m, R.drawable.input_schedule, R.drawable.input_schedule_solar);
        put(m, R.drawable.input_smile, R.drawable.input_smile_solar);
        put(m, R.drawable.input_video, R.drawable.input_video_solar);
        put(m, R.drawable.input_video_pressed, R.drawable.input_video_pressed_solar);
        put(m, R.drawable.list_mute, R.drawable.list_mute_solar);
        put(m, R.drawable.list_pin, R.drawable.list_pin_solar);
        put(m, R.drawable.list_secret, R.drawable.list_secret_solar);
        put(m, R.drawable.menu_devices, R.drawable.msg_devices_solar);
        put(m, R.drawable.msg2_animations, R.drawable.msg_played_solar);
        put(m, R.drawable.msg2_archived_stickers, R.drawable.msg_archive_solar);
        put(m, R.drawable.msg2_ask_question, R.drawable.msg_ask_question_solar);
        put(m, R.drawable.msg2_autodelete, R.drawable.msg_autodelete_solar);
        put(m, R.drawable.msg_autodelete, R.drawable.msg_autodelete_solar);
        put(m, R.drawable.msg2_battery, R.drawable.msg2_battery_solar);
        put(m, R.drawable.msg2_block2, R.drawable.msg_block2_solar);
        put(m, R.drawable.msg2_call_earpiece, R.drawable.msg_calls_solar);
        put(m, R.drawable.msg2_data, R.drawable.msg_data_solar);
        put(m, R.drawable.msg2_devices, R.drawable.msg_devices_solar);
        put(m, R.drawable.msg2_discussion, R.drawable.msg_discussion_solar);
        put(m, R.drawable.msg2_email, R.drawable.msg_email_solar);
        put(m, R.drawable.msg2_folder, R.drawable.msg_folder_solar);
        put(m, R.drawable.msg2_gif, R.drawable.msg_gif_solar);
        put(m, R.drawable.msg2_help, R.drawable.msg_psa_solar);
        put(m, R.drawable.msg2_language, R.drawable.msg_language_solar);
        put(m, R.drawable.msg2_notifications, R.drawable.msg_notifications_solar);
        put(m, R.drawable.msg2_permissions, R.drawable.msg_permissions_solar);
        put(m, R.drawable.msg2_policy, R.drawable.msg_policy_solar);
        put(m, R.drawable.msg2_reactions2, R.drawable.msg_reactions_solar);
        put(m, R.drawable.msg2_secret, R.drawable.msg_secret_solar);
        put(m, R.drawable.msg2_smile_status, R.drawable.input_smile_solar);
        put(m, R.drawable.msg2_sticker, R.drawable.msg_sticker_solar);
        put(m, R.drawable.msg2_trending, R.drawable.msg_trending_solar);
        put(m, R.drawable.msg2_videocall, R.drawable.msg_videocall_solar);
        put(m, R.drawable.msg_addbio, R.drawable.msg_addbio_solar);
        put(m, R.drawable.msg_addcontact, R.drawable.msg_contact_add_solar);
        put(m, R.drawable.msg_addfolder, R.drawable.msg_addfolder_solar);
        put(m, R.drawable.msg_addphoto, R.drawable.msg_addphoto_solar);
        put(m, R.drawable.msg_admin_add, R.drawable.msg_admin_add_solar);
        put(m, R.drawable.msg_admins, R.drawable.msg_admins_solar);
        put(m, R.drawable.msg_allowspeak, R.drawable.msg_allowspeak_solar);
        put(m, R.drawable.msg_archive, R.drawable.msg_archive_solar);
        put(m, R.drawable.msg_autodelete_1d, R.drawable.msg_autodelete_1d_solar);
        put(m, R.drawable.msg_autodelete_1m, R.drawable.msg_autodelete_1m_solar);
        put(m, R.drawable.msg_autodelete_1w, R.drawable.msg_autodelete_1w_solar);
        put(m, R.drawable.msg_autodelete_badge2, R.drawable.msg_autodelete_badge2_solar);
        put(m, R.drawable.msg_background, R.drawable.msg_background_solar);
        put(m, R.drawable.msg_block, R.drawable.msg_block_solar);
        put(m, R.drawable.msg_block2, R.drawable.msg_block2_solar);
        put(m, R.drawable.msg_calendar, R.drawable.msg_calendar_solar);
        put(m, R.drawable.msg_calendar2, R.drawable.msg_calendar2_solar);
        put(m, R.drawable.msg_callback, R.drawable.msg_calls_solar);
        put(m, R.drawable.msg_calls, R.drawable.msg_calls_solar);
        put(m, R.drawable.msg_calls_regular, R.drawable.msg_calls_regular_solar);
        put(m, R.drawable.msg_camera, R.drawable.msg_camera_solar);
        put(m, R.drawable.msg_cancel, R.drawable.msg_cancel_solar);
        put(m, R.drawable.msg_channel, R.drawable.msg_channel_solar);
        put(m, R.drawable.msg_chats_remove, R.drawable.msg_chats_remove_solar);
        put(m, R.drawable.msg_clear, R.drawable.msg_clear_solar);
        put(m, R.drawable.msg_clear_input, R.drawable.smiles_tab_clear_solar);
        put(m, R.drawable.msg_clear_recent, R.drawable.msg_clear_recent_solar);
        put(m, R.drawable.msg_clearcache, R.drawable.msg_delete_24_solar);
        put(m, R.drawable.msg_colors, R.drawable.msg_colors_solar);
        put(m, R.drawable.msg_contact_add, R.drawable.msg_contact_add_solar);
        put(m, R.drawable.msg_contacts, R.drawable.msg_contacts_solar);
        put(m, R.drawable.msg_contacts_name, R.drawable.msg_contacts_name_solar);
        put(m, R.drawable.msg_contacts_time, R.drawable.msg_contacts_time_solar);
        put(m, R.drawable.msg_copy, R.drawable.msg_copy_solar);
        put(m, R.drawable.msg_copy_filled, R.drawable.msg_copy_filled_solar);
        put(m, R.drawable.msg_current_location, R.drawable.msg_current_location_solar);
        put(m, R.drawable.msg_customize, R.drawable.msg_photo_settings_solar);
        put(m, R.drawable.msg_delete, R.drawable.msg_delete_24_solar);
        put(m, R.drawable.msg_delete_auto, R.drawable.msg_delete_auto_solar);
        put(m, R.drawable.msg_discuss, R.drawable.msg_ask_question_solar);
        put(m, R.drawable.msg_discussion, R.drawable.msg_discussion_solar);
        put(m, R.drawable.msg_download, R.drawable.msg_download_solar);
        put(m, R.drawable.msg_edit, R.drawable.msg_edit_24_solar);
        put(m, R.drawable.msg_emoji_activities, R.drawable.msg_emoji_activities_solar);
        put(m, R.drawable.msg_emoji_cat, R.drawable.msg_emoji_cat_solar);
        put(m, R.drawable.msg_emoji_flags, R.drawable.msg_emoji_flags_solar);
        put(m, R.drawable.msg_emoji_food, R.drawable.msg_emoji_food_solar);
        put(m, R.drawable.msg_emoji_objects, R.drawable.msg_emoji_objects_solar);
        put(m, R.drawable.msg_emoji_other, R.drawable.msg_emoji_other_solar);
        put(m, R.drawable.msg_emoji_question, R.drawable.msg_psa_solar);
        put(m, R.drawable.msg_emoji_recent, R.drawable.msg_emoji_recent_solar);
        put(m, R.drawable.msg_emoji_smiles, R.drawable.input_smile_solar);
        put(m, R.drawable.msg_emoji_stickers, R.drawable.msg_sticker_solar);
        put(m, R.drawable.msg_emoji_travel, R.drawable.msg_emoji_travel_solar);
        put(m, R.drawable.msg_endcall, R.drawable.msg_endcall_solar);
        put(m, R.drawable.msg_fave, R.drawable.msg_fave_solar);
        put(m, R.drawable.msg_filehq, R.drawable.msg_filehq_solar);
        put(m, R.drawable.msg_filled_blocked, R.drawable.msg_filled_blocked_solar);
        put(m, R.drawable.msg_filled_data_calls, R.drawable.msg_filled_data_calls_solar);
        put(m, R.drawable.msg_filled_data_files, R.drawable.msg_filled_data_files_solar);
        put(m, R.drawable.msg_filled_data_messages, R.drawable.msg_filled_data_messages_solar);
        put(m, R.drawable.msg_filled_data_music, R.drawable.msg_filled_data_music_solar);
        put(m, R.drawable.msg_filled_data_photos, R.drawable.msg_filled_data_photos_solar);
        put(m, R.drawable.msg_filled_data_received, R.drawable.msg_filled_data_received_solar);
        put(m, R.drawable.msg_filled_data_sent, R.drawable.msg_filled_data_sent_solar);
        put(m, R.drawable.msg_filled_data_videos, R.drawable.msg_filled_data_videos_solar);
        put(m, R.drawable.msg_filled_data_voice, R.drawable.msg_filled_data_voice_solar);
        put(m, R.drawable.msg_filled_datausage, R.drawable.msg_filled_datausage_solar);
        put(m, R.drawable.msg_filled_menu_channels, R.drawable.msg_filled_menu_channels_solar);
        put(m, R.drawable.msg_filled_menu_groups, R.drawable.msg_filled_menu_groups_solar);
        put(m, R.drawable.msg_filled_menu_users, R.drawable.msg_filled_menu_users_solar);
        put(m, R.drawable.msg_filled_sdcard, R.drawable.msg_filled_sdcard_solar);
        put(m, R.drawable.msg_filled_shareout, R.drawable.msg_filled_shareout_solar);
        put(m, R.drawable.msg_filled_storageusage, R.drawable.msg_filled_storageusage_solar);
        put(m, R.drawable.msg_folders, R.drawable.msg_folder_solar);
        put(m, R.drawable.msg_folders_archive, R.drawable.msg_folders_archive_solar);
        put(m, R.drawable.msg_folders_bots, R.drawable.msg_folders_bots_solar);
        put(m, R.drawable.msg_folders_channels, R.drawable.msg_folders_channels_solar);
        put(m, R.drawable.msg_folders_groups, R.drawable.msg_folders_groups_solar);
        put(m, R.drawable.msg_folders_muted, R.drawable.msg_folders_muted_solar);
        put(m, R.drawable.msg_folders_private, R.drawable.msg_folders_private_solar);
        put(m, R.drawable.msg_folders_read, R.drawable.msg_folders_read_solar);
        put(m, R.drawable.msg_folders_requests, R.drawable.msg_folders_requests_solar);
        put(m, R.drawable.msg_forward, R.drawable.msg_share_quote_solar);
        put(m, R.drawable.msg_forward_replace, R.drawable.msg_forward_replace_solar);
        put(m, R.drawable.msg_gallery, R.drawable.msg_gallery_solar);
        put(m, R.drawable.msg_gif, R.drawable.msg_gif_solar);
        put(m, R.drawable.msg_gif_add, R.drawable.msg_gif_add_solar);
        put(m, R.drawable.msg_gift_premium, R.drawable.msg_gift_premium_solar);
        put(m, R.drawable.msg_groups, R.drawable.msg_groups_solar);
        put(m, R.drawable.msg_groups_create, R.drawable.groups_create_solar);
        put(m, R.drawable.msg_help, R.drawable.msg_psa_solar);
        put(m, R.drawable.msg_home, R.drawable.msg_home_solar);
        put(m, R.drawable.msg_hybrid, R.drawable.msg_hybrid_solar);
        put(m, R.drawable.msg_info, R.drawable.msg_info_solar);
        put(m, R.drawable.msg_instant, R.drawable.msg_instant_solar);
        put(m, R.drawable.msg_instant_link, R.drawable.msg_instant_link_solar);
        put(m, R.drawable.msg_invited, R.drawable.msg_invited_solar);
        put(m, R.drawable.msg_jobtitle, R.drawable.msg_jobtitle_solar);
        put(m, R.drawable.msg_language, R.drawable.msg_language_solar);
        put(m, R.drawable.msg_retry, R.drawable.msg_retry_solar);
        put(m, R.drawable.msg_leave, R.drawable.msg_leave_solar);
        put(m, R.drawable.msg_link, R.drawable.msg_link2_solar);
        put(m, R.drawable.msg_link2, R.drawable.msg_link2_solar);
        put(m, R.drawable.msg_link_1, R.drawable.msg_link_1_solar);
        put(m, R.drawable.msg_link_2, R.drawable.msg_link_2_solar);
        put(m, R.drawable.msg_location, R.drawable.msg_location_solar);
        put(m, R.drawable.msg_location_alert, R.drawable.msg_location_alert_solar);
        put(m, R.drawable.msg_location_alert2, R.drawable.msg_bell_mute_solar);
        put(m, R.drawable.msg_log, R.drawable.msg_log_solar);
        put(m, R.drawable.msg_map, R.drawable.msg_map_solar);
        put(m, R.drawable.msg_map_type, R.drawable.msg_map_type_solar);
        put(m, R.drawable.msg_markread, R.drawable.msg_markread_solar);
        put(m, R.drawable.msg_markunread, R.drawable.msg_markunread_solar);
        put(m, R.drawable.msg_mask, R.drawable.msg_mask_solar);
        put(m, R.drawable.msg_media, R.drawable.msg_media_solar);
        put(m, R.drawable.msg_message, R.drawable.msg_message_solar);
        put(m, R.drawable.msg_mini_autodelete, R.drawable.msg_mini_autodelete_solar);
        put(m, R.drawable.msg_mini_autodelete_empty, R.drawable.msg_mini_autodelete_empty_solar);
        put(m, R.drawable.msg_mini_customize, R.drawable.msg_mini_customize_solar);
        put(m, R.drawable.msg_mini_qr, R.drawable.msg_mini_qr_solar);
        put(m, R.drawable.msg_msgbubble, R.drawable.msg_msgbubble3_solar);
        put(m, R.drawable.msg_msgbubble2, R.drawable.msg_msgbubble2_solar);
        put(m, R.drawable.msg_msgbubble3, R.drawable.msg_msgbubble3_solar);
        put(m, R.drawable.msg_mute, R.drawable.msg_mute_solar);
        put(m, R.drawable.msg_mute_1h, R.drawable.msg_mute_1h_solar);
        put(m, R.drawable.msg_mute_period, R.drawable.msg_mute_period_solar);
        put(m, R.drawable.msg_newphone, R.drawable.msg_newphone_solar);
        put(m, R.drawable.msg_noise_off, R.drawable.msg_noise_off_solar);
        put(m, R.drawable.msg_noise_on, R.drawable.msg_noise_on_solar);
        put(m, R.drawable.msg_notifications, R.drawable.msg_notifications_solar);
        put(m, R.drawable.msg_notspam, R.drawable.msg_notspam_solar);
        put(m, R.drawable.msg_openin, R.drawable.msg_instant_link_solar);
        put(m, R.drawable.msg_list, R.drawable.msg_list_solar);
        put(m, R.drawable.msg_openprofile, R.drawable.msg_openprofile_solar);
        put(m, R.drawable.msg_palette, R.drawable.msg_theme_solar);
        put(m, R.drawable.msg_payment_address, R.drawable.msg_location_solar);
        put(m, R.drawable.msg_payment_card, R.drawable.msg_payment_card_solar);
        put(m, R.drawable.msg_payment_delivery, R.drawable.msg_payment_delivery_solar);
        put(m, R.drawable.msg_payment_provider, R.drawable.msg_payment_provider_solar);
        put(m, R.drawable.msg_permissions, R.drawable.msg_permissions_solar);
        put(m, R.drawable.msg_photo_blur, R.drawable.msg_photo_blur_solar);
        put(m, R.drawable.media_crop, R.drawable.media_crop_solar);
        put(m, R.drawable.msg_photo_curve, R.drawable.msg_photo_curve_solar);
        put(m, R.drawable.media_draw, R.drawable.media_draw_solar);
        put(m, R.drawable.msg_photo_flip, R.drawable.msg_photo_flip_solar);
        put(m, R.drawable.msg_photo_rotate, R.drawable.msg_photo_rotate_solar);
        put(m, R.drawable.msg_photo_settings, R.drawable.msg_photo_settings_solar);
        put(m, R.drawable.msg_photo_text2, R.drawable.msg_photo_text_solar);
        put(m, R.drawable.msg_photoeditor, R.drawable.media_draw_solar);
        put(m, R.drawable.msg_photos, R.drawable.msg_photos_solar);
        put(m, R.drawable.msg_pin, R.drawable.msg_pin_solar);
        put(m, R.drawable.msg_pin_code, R.drawable.msg_pin_code_solar);
        put(m, R.drawable.msg_pin_mini, R.drawable.msg_pin_mini_solar);
        put(m, R.drawable.msg_pinnedlist, R.drawable.msg_pinnedlist_solar);
        put(m, R.drawable.msg_played, R.drawable.msg_played_solar);
        put(m, R.drawable.msg_policy, R.drawable.msg_policy_solar);
        put(m, R.drawable.msg_pollstop, R.drawable.msg_pollstop_solar);
        put(m, R.drawable.msg_psa, R.drawable.msg_psa_solar);
        put(m, R.drawable.msg_rate_down, R.drawable.msg_rate_down_solar);
        put(m, R.drawable.msg_rate_up, R.drawable.msg_rate_up_solar);
        put(m, R.drawable.msg_qr_mini, R.drawable.msg_qrcode_mini_solar);
        put(m, R.drawable.msg_qrcode, R.drawable.msg_qrcode_solar);
        put(m, R.drawable.msg_reactions, R.drawable.msg_reactions_solar);
        put(m, R.drawable.msg_reactions2, R.drawable.msg_reactions_solar);
        put(m, R.drawable.msg_reactions_filled, R.drawable.msg_reactions_filled_solar);
        put(m, R.drawable.msg_recent, R.drawable.msg_recent_solar);
        put(m, R.drawable.msg_remove, R.drawable.msg_remove_solar);
        put(m, R.drawable.msg_removefolder, R.drawable.msg_removefolder_solar);
        put(m, R.drawable.msg_replace, R.drawable.msg_replace_solar);
        put(m, R.drawable.menu_reply, R.drawable.msg_reply_solar);
        put(m, R.drawable.msg_reply_small, R.drawable.msg_reply_small_solar);
        put(m, R.drawable.msg_report, R.drawable.msg_report_other_solar);
        put(m, R.drawable.msg_report_drugs, R.drawable.msg_report_drugs_solar);
        put(m, R.drawable.msg_report_fake, R.drawable.msg_report_fake_solar);
        put(m, R.drawable.msg_report_other, R.drawable.msg_report_other_solar);
        put(m, R.drawable.msg_report_personal, R.drawable.msg_report_personal_solar);
        put(m, R.drawable.msg_report_violence, R.drawable.msg_report_violence_solar);
        put(m, R.drawable.msg_report_xxx, R.drawable.msg_report_xxx_solar);
        put(m, R.drawable.msg_requests, R.drawable.msg_contact_add_solar);
        put(m, R.drawable.msg_reset, R.drawable.msg_reset_solar);
        put(m, R.drawable.msg_round_file_s, R.drawable.msg_round_file_solar);
        put(m, R.drawable.msg_satellite, R.drawable.msg_satellite_solar);
        put(m, R.drawable.msg_saved, R.drawable.msg_saved_solar);
        put(m, R.drawable.msg_screencast, R.drawable.msg_screencast_solar);
        put(m, R.drawable.msg_screencast_off, R.drawable.msg_screencast_off_solar);
        put(m, R.drawable.msg_search, R.drawable.msg_search_solar);
        put(m, R.drawable.msg_secret, R.drawable.msg_secret_solar);
        put(m, R.drawable.msg_select, R.drawable.msg_select_solar);
        put(m, R.drawable.msg_send, R.drawable.msg_send_solar);
        put(m, R.drawable.msg_sendfile, R.drawable.msg_sendfile_solar);
        put(m, R.drawable.msg_settings, R.drawable.msg_settings_solar);
        put(m, R.drawable.msg_settings_old, R.drawable.msg_settings_solar);
        put(m, R.drawable.msg_share, R.drawable.msg_share_solar);
        put(m, R.drawable.msg_share_filled, R.drawable.msg_share_filled_solar);
        put(m, R.drawable.msg_shareout, R.drawable.msg_shareout_solar);
        put(m, R.drawable.msg_silent, R.drawable.msg_silent_solar);
        put(m, R.drawable.msg_smile_status, R.drawable.input_smile_solar);
        put(m, R.drawable.msg_speed, R.drawable.msg_speed_solar);
        put(m, R.drawable.msg_stats, R.drawable.msg_stats_solar);
        put(m, R.drawable.msg_sticker, R.drawable.msg_sticker_solar);
        put(m, R.drawable.msg_photo_sticker, R.drawable.msg_sticker_solar);
        put(m, R.drawable.msg_theme, R.drawable.msg_theme_solar);
        put(m, R.drawable.msg_tone_add, R.drawable.msg_tone_add_solar);
        put(m, R.drawable.msg_tone_off, R.drawable.msg_tone_off_solar);
        put(m, R.drawable.msg_tone_on, R.drawable.msg_tone_on_solar);
        put(m, R.drawable.msg_topic_close, R.drawable.msg_remove_solar);
        put(m, R.drawable.msg_topic_create, R.drawable.msg_topic_create_solar);
        put(m, R.drawable.msg_topics, R.drawable.msg_topics_solar);
        put(m, R.drawable.msg_translate, R.drawable.msg_translate_solar);
        put(m, R.drawable.msg_unarchive, R.drawable.msg_unarchive_solar);
        put(m, R.drawable.msg_unfave, R.drawable.msg_unfave_solar);
        put(m, R.drawable.msg_ungroup, R.drawable.msg_ungroup_solar);
        put(m, R.drawable.msg_unmute, R.drawable.notifications_on_solar);
        put(m, R.drawable.msg_unpin, R.drawable.msg_unpin_solar);
        put(m, R.drawable.msg_unvote, R.drawable.msg_unvote_solar);
        put(m, R.drawable.msg_user_remove, R.drawable.msg_user_remove_solar);
        put(m, R.drawable.msg_usersearch, R.drawable.msg_user_search_solar);
        put(m, R.drawable.msg_videocall, R.drawable.msg_videocall_solar);
        put(m, R.drawable.msg_view_file, R.drawable.msg_message_solar);
        put(m, R.drawable.msg_viewchats, R.drawable.msg_discuss_solar);
        put(m, R.drawable.msg_viewintopic, R.drawable.msg_viewintopic_solar);
        put(m, R.drawable.msg_viewreplies, R.drawable.msg_viewreplies_solar);
        put(m, R.drawable.msg_views, R.drawable.msg_views_solar);
        put(m, R.drawable.msg_voice_bluetooth, R.drawable.msg_voice_bluetooth_solar);
        put(m, R.drawable.msg_voice_headphones, R.drawable.msg_voice_headphones_solar);
        put(m, R.drawable.msg_voice_phone, R.drawable.msg_voice_phone_solar);
        put(m, R.drawable.msg_voice_pip, R.drawable.msg_voice_pip_solar);
        put(m, R.drawable.msg_voice_speaker, R.drawable.notifications_on_solar);
        put(m, R.drawable.msg_voicechat, R.drawable.msg_voicechat_solar);
        put(m, R.drawable.msg_voicechat2, R.drawable.msg_voicechat2_solar);
        put(m, R.drawable.msg_work, R.drawable.msg_work_solar);
        put(m, R.drawable.msg_zoomin, R.drawable.msg_zoomin_solar);
        put(m, R.drawable.msg_zoomout, R.drawable.msg_zoomout_solar);
        put(m, R.drawable.notifications_mute1h, R.drawable.notifications_mute1h_solar);
        put(m, R.drawable.notifications_mute2d, R.drawable.notifications_mute2d_solar);
        put(m, R.drawable.notifications_on, R.drawable.notifications_on_solar);
        put(m, R.drawable.permissions_camera1, R.drawable.permissions_camera1_solar);
        put(m, R.drawable.permissions_camera2, R.drawable.permissions_camera2_solar);
        put(m, R.drawable.permissions_gallery1, R.drawable.permissions_gallery1_solar);
        put(m, R.drawable.permissions_gallery2, R.drawable.permissions_gallery2_solar);
        put(m, R.drawable.photo_paint_brush, R.drawable.photo_paint_brush_solar);
        put(m, R.drawable.photo_undo, R.drawable.photo_undo_solar);
        put(m, R.drawable.picker, R.drawable.ic_colorpicker_solar);
        put(m, R.drawable.pin, R.drawable.bot_location_solar);
        put(m, R.drawable.profile_discuss, R.drawable.profile_discuss_solar);
        put(m, R.drawable.profile_newmsg, R.drawable.profile_newmsg_filled_solar);
        put(m, R.drawable.profile_phone, R.drawable.profile_phone_solar);
        put(m, R.drawable.profile_video, R.drawable.profile_video_solar);
        put(m, R.drawable.qr_gallery, R.drawable.qr_gallery_solar);
        put(m, R.drawable.reactionbutton, R.drawable.msg_reactions_solar);
        put(m, R.drawable.screencast_big, R.drawable.screencast_big_solar);
        put(m, R.drawable.search_files_filled, R.drawable.msg_round_file_solar);
        put(m, R.drawable.share, R.drawable.msg_filled_shareout_solar);
        put(m, R.drawable.share_arrow, R.drawable.share_arrow_solar);
        put(m, R.drawable.smallanimationpin, R.drawable.smallanimationpin_solar);
        put(m, R.drawable.smiles_inputsearch, R.drawable.smiles_inputsearch_solar);
        put(m, R.drawable.smiles_tab_clear, R.drawable.smiles_tab_clear_solar);
        put(m, R.drawable.smiles_tab_gif, R.drawable.msg_gif_solar);
        put(m, R.drawable.smiles_tab_settings, R.drawable.smiles_tab_settings_solar);
        put(m, R.drawable.smiles_tab_smiles, R.drawable.input_smile_solar);
        put(m, R.drawable.smiles_tab_stickers, R.drawable.msg_sticker_solar);
        put(m, R.drawable.stickers_empty, R.drawable.stickers_empty_solar);
        put(m, R.drawable.stickers_favorites, R.drawable.stickers_favorites_solar);
        put(m, R.drawable.stickers_gifs_trending, R.drawable.stickers_gifs_trending_solar);
        put(m, R.drawable.stickers_recent, R.drawable.msg_emoji_recent_solar);
        put(m, R.drawable.tabs_reorder, R.drawable.tabs_reorder_solar);
        put(m, R.drawable.theme_picker, R.drawable.theme_picker_solar);
        put(m, R.drawable.verified_profile, R.drawable.verified_profile_solar);
        put(m, R.drawable.msg2_chats_add, R.drawable.msg_chats_add_solar);
        put(m, R.drawable.msg2_link2, R.drawable.msg_link2_solar);
        put(m, R.drawable.msg_online, R.drawable.msg_online_solar);
        put(m, R.drawable.msg_link_folder, R.drawable.msg_link2_solar);
        put(m, R.drawable.msg_bot, R.drawable.msg_bots_solar);
        put(m, R.drawable.msg_brightness_high, R.drawable.msg_brightness_high_solar);
        put(m, R.drawable.msg_brightness_low, R.drawable.msg_brightness_low_solar);
        put(m, R.drawable.msg_header_share, R.drawable.msg_share_filled_solar);
        put(m, R.drawable.msg_header_draw, R.drawable.msg_header_draw_solar);
        put(m, R.drawable.filter_cat, R.drawable.filter_cat_solar);
        put(m, R.drawable.filter_book, R.drawable.filter_book_solar);
        put(m, R.drawable.filter_money, R.drawable.filter_money_solar);
        put(m, R.drawable.filter_game, R.drawable.filter_game_solar);
        put(m, R.drawable.filter_light, R.drawable.filter_light_solar);
        put(m, R.drawable.filter_like, R.drawable.filter_like_solar);
        put(m, R.drawable.filter_note, R.drawable.filter_note_solar);
        put(m, R.drawable.filter_palette, R.drawable.filter_palette_solar);
        put(m, R.drawable.filter_travel, R.drawable.filter_travel_solar);
        put(m, R.drawable.filter_sport, R.drawable.filter_sport_solar);
        put(m, R.drawable.filter_favorite, R.drawable.filter_favorite_solar);
        put(m, R.drawable.filter_study, R.drawable.filter_study_solar);
        put(m, R.drawable.filter_airplane, R.drawable.filter_airplane_solar);
        put(m, R.drawable.filter_private, R.drawable.filter_private_solar);
        put(m, R.drawable.filter_group, R.drawable.ayu_filter_groups);
        put(m, R.drawable.filter_all, R.drawable.ayu_filter_all);
        put(m, R.drawable.filter_unread, R.drawable.filter_unread_solar);
        put(m, R.drawable.filter_bots, R.drawable.filter_bots_solar);
        put(m, R.drawable.filter_crown, R.drawable.filter_crown_solar);
        put(m, R.drawable.filter_flower, R.drawable.filter_flower_solar);
        put(m, R.drawable.filter_home, R.drawable.filter_home_solar);
        put(m, R.drawable.filter_love, R.drawable.filter_love_solar);
        put(m, R.drawable.filter_mask, R.drawable.filter_mask_solar);
        put(m, R.drawable.filter_party, R.drawable.filter_party_solar);
        put(m, R.drawable.filter_trade, R.drawable.filter_trade_solar);
        put(m, R.drawable.filter_work, R.drawable.filter_work_solar);
        put(m, R.drawable.filter_unmuted, R.drawable.filter_unmuted_solar);
        put(m, R.drawable.filter_channels, R.drawable.ayu_filter_channels);
        put(m, R.drawable.filter_custom, R.drawable.filter_custom_solar);
        put(m, R.drawable.filter_setup, R.drawable.filter_setup_solar);
        return m;
    }
}
