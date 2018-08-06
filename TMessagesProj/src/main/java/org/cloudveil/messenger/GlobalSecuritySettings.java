package org.cloudveil.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

/**
 * Created by darren on 2017-03-25.
 */

public class GlobalSecuritySettings {
    public static final boolean LOCK_DISABLE_DELETE_CHAT = false;
    public static final boolean LOCK_DISABLE_FORWARD_CHAT = false;
    public static final boolean LOCK_DISABLE_BOTS = false;
    public static final boolean LOCK_DISABLE_YOUTUBE_VIDEO = true;
    public static final boolean LOCK_DISABLE_IN_APP_BROWSER = true;
    public static final boolean LOCK_DISABLE_AUTOPLAY_GIFS = true;
    public static final boolean LOCK_DISABLE_GLOBAL_SEARCH = true;

    private static final boolean DEFAULT_LOCK_DISABLE_STICKERS = false;
    private static final boolean DEFAULT_LOCK_DISABLE_GIFS = true;
    private static boolean DEFAULT_LOCK_DISABLE_SECRET_CHAT = false;
    private static int DEFAULT_MIN_SECRET_CHAT_TTL = 0;
    private static final boolean DEFAULT_MANAGE_USERS = false;
    private static final boolean DEFAULT_LOCK_DISABLE_OWN_BIO = true;
    private static final boolean DEFAULT_LOCK_DISABLE_OWN_PHOTO = true;
    private static final boolean DEFAULT_LOCK_DISABLE_OTHERS_BIO = true;
    private static final boolean DEFAULT_LOCK_DISABLE_OTHERS_PHOTO = true;
    private static final boolean DEFAULT_LOCK_DISABLE_INLINE_VIDEO = true;

    public static boolean isDisabledSecretChat() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getBoolean("disabledSecretChat", DEFAULT_LOCK_DISABLE_SECRET_CHAT);
    }

    public static void setDisableSecretChat(boolean isDisabled) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putBoolean("disabledSecretChat", isDisabled).apply();
    }

    public static int getMinSecretChatTtl() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getInt("minChatTtl", DEFAULT_MIN_SECRET_CHAT_TTL);
    }

    public static void setMinSecretChatTtl(int ttl) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putInt("minChatTtl", ttl).apply();
    }

    public static boolean getLockDisableOwnBio() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getBoolean("disabledOwnBio", DEFAULT_LOCK_DISABLE_OWN_BIO);
    }

    public static void setLockDisableOwnBio(boolean lockDisableOwnBio) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putBoolean("disabledOwnBio", lockDisableOwnBio).apply();
    }

    public static boolean getLockDisableOwnPhoto() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getBoolean("disabledOwnPhoto", DEFAULT_LOCK_DISABLE_OWN_PHOTO);
    }

    public static void setLockDisableOwnPhoto(boolean lockDisableOwnPhoto) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putBoolean("disabledOwnPhoto", lockDisableOwnPhoto).apply();
    }

    public static boolean getLockDisableOthersBio() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getBoolean("disabledOthersBio", DEFAULT_LOCK_DISABLE_OTHERS_BIO);
    }

    public static void setLockDisableOthersBio(boolean lockDisableOthersBio) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putBoolean("disabledOthersBio", lockDisableOthersBio).apply();
    }

    public static boolean getLockDisableOthersPhoto() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getBoolean("disabledOthersPhoto", DEFAULT_LOCK_DISABLE_OTHERS_PHOTO);
    }

    public static void setLockDisableOthersPhoto(boolean lockDisableOthersPhoto) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putBoolean("disabledOthersPhoto", lockDisableOthersPhoto).apply();
    }

    public static boolean getDisabledVideoInlineRecording() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getBoolean("inputToggleVoiceVideo", DEFAULT_LOCK_DISABLE_INLINE_VIDEO);
    }

    public static void setDisabledVideoInlineRecording(boolean lockDisableInlineVideo) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putBoolean("inputToggleVoiceVideo", lockDisableInlineVideo).apply();
    }

    public static boolean isLockDisableGifs() {
        return DEFAULT_LOCK_DISABLE_GIFS;
    }

    public static void setLockDisableStickers(boolean lockDisableStickers) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putBoolean("isLockDisableStickers", lockDisableStickers).apply();
    }

    public static boolean isLockDisableStickers() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getBoolean("isLockDisableStickers", DEFAULT_LOCK_DISABLE_STICKERS);
    }

    public static void setManageUsers(boolean isManagingUsers) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putBoolean("isManagingUsers", isManagingUsers).apply();
    }

    public static boolean getManageUsers() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getBoolean("isManagingUsers", DEFAULT_MANAGE_USERS);
    }

    public static void setBlockedImageUrl(String blockedImageUrl) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        preferences.edit().putString("blockedImageUrl", blockedImageUrl).apply();
    }

    public static String getBlockedImageUrl() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(GlobalSecuritySettings.class.getCanonicalName(), Activity.MODE_PRIVATE);
        return preferences.getString("blockedImageUrl", "");
    }
}
