package com.github.k1rakishou.chan.core.settings.state;

import com.github.k1rakishou.chan.BuildConfig;
import com.github.k1rakishou.chan.core.settings.BooleanSetting;
import com.github.k1rakishou.chan.core.settings.ChanSettings;
import com.github.k1rakishou.chan.core.settings.IntegerSetting;
import com.github.k1rakishou.chan.core.settings.LongSetting;
import com.github.k1rakishou.chan.core.settings.SharedPreferencesSettingProvider;
import com.github.k1rakishou.chan.core.settings.StringSetting;
import com.github.k1rakishou.chan.utils.AndroidUtils;
import com.github.k1rakishou.chan.utils.Logger;

/**
 * This state class acts in a similar manner to {@link ChanSettings}, but everything here is not exported; this data is
 * strictly for use internally to the application and acts as a helper to ensure that data is not lost.
 */

public class PersistableChanState {
    private static final String TAG = "ChanState";

    public static IntegerSetting watchLastCount;
    public static BooleanSetting hasNewApkUpdate;
    public static IntegerSetting previousVersion;
    public static LongSetting updateCheckTime;
    public static StringSetting previousDevHash;
    public static BooleanSetting viewThreadBookmarksGridMode;
    public static final BooleanSetting shittyPhonesBackgroundLimitationsExplanationDialogShown;
    public static BooleanSetting cloudflarePreloadingExplanationShown;

    static {
        try {
            SharedPreferencesSettingProvider p = new SharedPreferencesSettingProvider(AndroidUtils.getAppState());

            watchLastCount = new IntegerSetting(p, "watch_last_count", 0);
            hasNewApkUpdate = new BooleanSetting(p, "has_new_apk_update", false);
            previousVersion = new IntegerSetting(p, "previous_version", BuildConfig.VERSION_CODE);
            updateCheckTime = new LongSetting(p, "update_check_time", 0L);
            previousDevHash = new StringSetting(p, "previous_dev_hash", BuildConfig.COMMIT_HASH);
            viewThreadBookmarksGridMode = new BooleanSetting(p, "view_thread_bookmarks_grid_mode", true);
            shittyPhonesBackgroundLimitationsExplanationDialogShown = new BooleanSetting(p, "shitty_phones_background_limitations_explanation_dialog_shown", false);
            cloudflarePreloadingExplanationShown = new BooleanSetting(p, "cloudflare_preloading_explanation_shown", false);
        } catch (Exception e) {
            Logger.e(TAG, "Error while initializing the state", e);
            throw e;
        }
    }
}