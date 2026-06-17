package org.cloudveil.messenger.api.model.request;


import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class SettingsRequest {
    public long userId;
    public String userPhone;
    public String userName;
    public ArrayList<String> userNames = new ArrayList<>();

    public String clientOsType = "Android";
    public String clientVersionName = SettingsRequest.getAppVersionString();
    public int clientVersionCode = SettingsRequest.getAppVersionCode();
    public String clientLocale = SettingsRequest.getClientLocale();
    public String clientSessionId;

    public ArrayList<GroupRow> groups = new ArrayList<>();
    public ArrayList<GroupChannelRow> channels = new ArrayList<>();
    public ArrayList<Row> bots = new ArrayList<>();
    public ArrayList<Row> stickers = new ArrayList<>();
    public ArrayList<Row> users = new ArrayList<>();

    public boolean isEmpty() {
        return groups.isEmpty() && channels.isEmpty() && bots.isEmpty();
    }

    public SettingsRequest() {
        clientVersionName = SettingsRequest.getAppVersionString();
        clientVersionCode = SettingsRequest.getAppVersionCode();
        clientLocale = SettingsRequest.getClientLocale();
    }

    public static String getClientLocale() {
        LocaleController.LocaleInfo localeInfo = LocaleController.getInstance().getCurrentLocaleInfo();
        if (localeInfo != null) {
            return localeInfo.getLangCode();
        }
        return Locale.getDefault().toLanguageTag();
    }

    public static class Row {
        public long id;
        public String title;
        public ArrayList<String> userNames = new ArrayList<>();
        public boolean isCreatorAdmin;
        public boolean isForum;
        public boolean isRestricted;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Row row = (Row) o;
            return id == row.id;
        }
    }

    public static int getAppVersionCode() {
        try {
            PackageInfo pInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return pInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public static String getAppVersionString() {
        try {
            PackageInfo pInfo = ApplicationLoader.applicationContext.getPackageManager().getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return "";
    }

    public static class GroupChannelRow extends Row {
        /** Client is not in this chat; membership and recency data may be stale. */
        public static final long LAST_MESSAGE_DATE_UNTRUSTWORTHY = -1;
        /** In chat, but no last-activity timestamp is available locally. */
        public static final long LAST_MESSAGE_DATE_UNKNOWN = 0;

        public boolean isPublic;
        public long lastMessageDate;
    }

    public static class GroupRow extends GroupChannelRow {
        public boolean isMegagroup;
    }

    public static class SuperGroupRow extends GroupRow {
        public long migratedFromTelegramId;
    }

    public void addChannel(GroupChannelRow channel) {
        addRow(channels, channel);
    }

    public void addGroup(GroupRow group) {
        addRow(groups, group);
    }

    public void addBot(Row bot) {
        addRow(bots, bot);
    }

    public void addUser(Row user) {
        addRow(users, user);
    }

    public void addSticker(Row sticker) {
        addRow(stickers, sticker);
    }

    private<T extends Row> void addRow(ArrayList<T> rows, T data) {
        for(Row row : rows) {
            if(row.id == data.id) {
                return;
            }
        }
        rows.add(data);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SettingsRequest that = (SettingsRequest) o;
        return userId == that.userId &&
                Objects.equals(clientSessionId, that.clientSessionId) &&
                groups.equals(that.groups) &&
                channels.equals(that.channels) &&
                bots.equals(that.bots) &&
                stickers.equals(that.stickers) &&
                users.equals(that.users);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId);
    }
}
