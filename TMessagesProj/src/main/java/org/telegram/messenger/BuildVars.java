/*
 * This is the source code of Telegram for Android v. 4.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2017.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

public class BuildVars {

    public static boolean DEBUG_VERSION = false;
    public static boolean DEBUG_PRIVATE_VERSION = false;
    public static boolean LOGS_ENABLED = false;
    public static boolean CHECK_UPDATES = false;
    public static int BUILD_VERSION = 1341;
    public static String BUILD_VERSION_STRING = "4.9.1";
    public static int APP_ID = 66408;
    public static String APP_HASH = "2db8d372e3978a884a38ee57d04d4ee1"; //obtain your own APP_HASH at https://core.telegram.org/api/obtaining_api_id
    public static String HOCKEY_APP_HASH = "e683f2bc25474272a9d987ffc3c73763";
    public static String HOCKEY_APP_HASH_DEBUG = "e683f2bc25474272a9d987ffc3c73763";
    public static String PLAYSTORE_APP_URL = "";

    static {
        if (ApplicationLoader.applicationContext != null) {
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            LOGS_ENABLED = sharedPreferences.getBoolean("logsEnabled", DEBUG_VERSION);
        }
    }
}
