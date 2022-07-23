package org.cloudveil.messenger.api.model.request;

import org.telegram.messenger.BuildConfig;

import java.util.ArrayList;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class SettingsRequest {
    public long userId;
    public String userPhone;
    public String userName;

    public String clientOsType = "Android";
    public String clientVersionName = BuildConfig.VERSION_NAME;
    public int clientVersionCode = BuildConfig.VERSION_CODE;
    public String clientSessionId;

    public ArrayList<GroupRow> groups = new ArrayList<>();
    public ArrayList<GroupChannelRow> channels = new ArrayList<>();
    public ArrayList<Row> bots = new ArrayList<>();
    public ArrayList<Row> stickers = new ArrayList<>();
    public ArrayList<Row> users = new ArrayList<>();

    public boolean isEmpty() {
        return groups.isEmpty() && channels.isEmpty() && bots.isEmpty();
    }

    public static class Row {
        public long id;
        public String title;
        public String userName;
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
