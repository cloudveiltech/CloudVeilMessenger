package org.cloudveil.messenger.api.model.response;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class SettingsResponse {
    public class AccessList {
        public ArrayList<HashMap<Long, Boolean>> groups;
        public ArrayList<HashMap<Long, Boolean>> bots;
        public ArrayList<HashMap<Long, Boolean>> channels;
        public ArrayList<HashMap<Long, Boolean>> users;
        public ArrayList<HashMap<Long, Boolean>> stickers;

    }
    public AccessList access;

    public boolean secretChat;
    public int secretChatMinimumLength;

    public boolean disableBio;
    public boolean disableBioChange;
    public boolean disableProfilePhoto;
    public boolean disableProfilePhotoChange;
    public boolean disableStickers;
    public boolean manageUsers;
    public boolean inputToggleVoiceVideo;
}
