package org.cloudveil.messenger.api.model.response;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class SettingsResponse {
    public static class AccessList {
        public ArrayList<HashMap<Long, Boolean>> groups;
        public ArrayList<HashMap<Long, Boolean>> bots;
        public ArrayList<HashMap<Long, Boolean>> channels;
        public ArrayList<HashMap<Long, Boolean>> users;
        public ArrayList<HashMap<Long, Boolean>> stickers;

        public boolean isValid() {
            return groups != null &&
                    bots != null &&
                    channels != null;
        }
    }

    public static class GoogleMapsKeys {
        public String ios;
        public String android;
        public String desktop;
    }

    public static class Organization {
        public int id;
        public String name;
        public boolean needChange;
        public String aboutUrl;
    }

    public AccessList access;

    public boolean secretChat;
    public int secretChatMinimumLength;

    public boolean disableBio;
    public boolean disableBioChange;
    public boolean disableProfilePhoto;
    public boolean disableProfilePhotoChange;
    public boolean disableStickers;
    public boolean disableEmojiStatus;
    public boolean disableStories;
    public boolean manageUsers;
    public boolean inputToggleVoiceVideo;
    public boolean disableProfileVideo;
    public boolean disableProfileVideoChange;
    public String disableStickersImage;
    public int profilePhotoLimit;
    public GoogleMapsKeys googleMapsKeys;
    public Organization organization;
}
