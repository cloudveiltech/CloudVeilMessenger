package org.cloudveil.messenger.api.model.request;


import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.Set;

import io.sentry.protocol.App;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class SettingsRequest {
    public long userId;
    public String userPhone;
    public String userName;

    public String clientOsType = "Android";
    public String clientVersionName = SettingsRequest.getAppVersionString();
    public int clientVersionCode = SettingsRequest.getAppVersionCode();
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
    }

    public static class Row {
        public long id;
        public String title;
        public String userName;
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
        public boolean isPublic;
    }

    public static class GroupRow extends GroupChannelRow {
        public boolean isMegagroup;
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
}
