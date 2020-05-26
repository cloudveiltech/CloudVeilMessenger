package org.cloudveil.messenger.util;

import android.text.TextUtils;
import android.widget.Toast;

import com.google.android.exoplayer2.extractor.mkv.MatroskaExtractor;

import org.cloudveil.messenger.GlobalSecuritySettings;
import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.service.ChannelCheckingService;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class CloudVeilDialogHelper {
    private int currentAccount;

    private static volatile CloudVeilDialogHelper[] Instance = new CloudVeilDialogHelper[UserConfig.MAX_ACCOUNT_COUNT];

    private CloudVeilDialogHelper(int num) {
        currentAccount = num;
    }

    public static CloudVeilDialogHelper getInstance(int num) {
        CloudVeilDialogHelper localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (CloudVeilDialogHelper.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new CloudVeilDialogHelper(num);
                }
            }
        }
        return localInstance;
    }

    public ConcurrentHashMap<Long, Boolean> allowedDialogs = new ConcurrentHashMap<>();
    public ConcurrentHashMap<Long, Boolean> allowedBots = new ConcurrentHashMap<>();


    /*
    {
        "is_public": true,d
        "id": -1244601995,
        "title": "CloudVeil Messenger Announcements",
        "user_name": "CloudVeilMessenger"
    }
     */
    public void loadNotificationChannelDialog(SettingsRequest request) {
        if(isCloudVeilChannelLoaded(request)) {
            return;
        }

        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = "CloudVeilMessenger";
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        final int reqId = messagesController.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {

            if (error == null) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                if(res.chats.size() == 0) {
                    return;
                }
                TLRPC.Chat chat = res.chats.get(0);
                messagesController.putChat(chat, false);
                messagesController.addUserToChat(chat.id, UserConfig.getInstance(currentAccount).getCurrentUser(), null, 0, null, null, null);
            }
        }));
    }

    private boolean isCloudVeilChannelLoaded(SettingsRequest request) {
        if(request == null) {
            return false;
        }
        for(int i=0; i<request.channels.size(); i++) {
            String userName = request.channels.get(i).userName;
            if(userName != null && userName.equalsIgnoreCase("CloudVeilMessenger")) {
                return true;
            }
        }
        return false;
    }


    public boolean isUserAllowed(TLRPC.User user) {
        if (user == null) {
            return true;
        }
        if (user.bot) {
            if (GlobalSecuritySettings.LOCK_DISABLE_BOTS) {
                return false;
            }
            long id = (long) user.id;
            return !allowedBots.containsKey(id) || allowedBots.get(id);
        } else if (GlobalSecuritySettings.getManageUsers()) {
            long id = (long) user.id;
            return !allowedDialogs.containsKey(id) || allowedDialogs.get(id);
        }
        return true;
    }

    public TLObject getObjectByDialogId(long currentDialogId) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);

        int lower_id = (int) currentDialogId;
        int high_id = (int) (currentDialogId >> 32);
        TLRPC.Chat chat = null;
        TLRPC.User user = null;
        TLRPC.EncryptedChat encryptedChat = null;
        if (lower_id != 0) {
            if (high_id == 1) {
                chat = messagesController.getChat(lower_id);
            } else {
                if (lower_id < 0) {
                    chat = messagesController.getChat(-lower_id);
                    if (chat != null && chat.migrated_to != null) {
                        TLRPC.Chat chat2 = messagesController.getChat(chat.migrated_to.channel_id);
                        if (chat2 != null) {
                            chat = chat2;
                        }
                    }
                } else {
                    user = messagesController.getUser(lower_id);
                }
            }
        } else {
            encryptedChat = messagesController.getEncryptedChat(high_id);
            if (encryptedChat != null) {
                user = messagesController.getUser(encryptedChat.user_id);
            }
        }

        if (encryptedChat != null && GlobalSecuritySettings.isDisabledSecretChat()) {
            return encryptedChat;
        } else if (chat != null) {
            return chat;
        } else if (user != null) {
            return user;
        }
        return null;
    }

    public boolean isDialogIdAllowed(long currentDialogId) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        int lower_id = (int) currentDialogId;
        int high_id = (int) (currentDialogId >> 32);
        TLRPC.Chat chat = null;
        TLRPC.User user = null;
        TLRPC.EncryptedChat encryptedChat = null;
        if (lower_id != 0) {
            if (high_id == 1) {
                chat = messagesController.getChat(lower_id);
            } else {
                if (lower_id < 0) {
                    chat = messagesController.getChat(-lower_id);
                    if (chat != null && chat.migrated_to != null) {
                        TLRPC.Chat chat2 = messagesController.getChat(chat.migrated_to.channel_id);
                        if (chat2 != null) {
                            chat = chat2;
                        }
                    }
                } else {
                    user = messagesController.getUser(lower_id);
                }
            }
        } else {
            encryptedChat = messagesController.getEncryptedChat(high_id);
            if (encryptedChat != null) {
                user = messagesController.getUser(encryptedChat.user_id);
            }
        }

        if (encryptedChat != null && GlobalSecuritySettings.isDisabledSecretChat()) {
            return false;
        } else if (chat != null) {
            return !allowedDialogs.containsKey(currentDialogId) || allowedDialogs.get(currentDialogId);
        } else if (user != null) {
            return isUserAllowed(user);
        }
        return false;
    }

    public boolean isDialogCheckedOnServer(long currentDialogId) {
        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        int lower_id = (int) currentDialogId;
        int high_id = (int) (currentDialogId >> 32);
        TLRPC.Chat chat = null;
        TLRPC.User user = null;
        TLRPC.EncryptedChat encryptedChat = null;
        if (lower_id != 0) {
            if (high_id == 1) {
                chat = messagesController.getChat(lower_id);
            } else {
                if (lower_id < 0) {
                    chat = messagesController.getChat(-lower_id);
                    if (chat != null && chat.migrated_to != null) {
                        TLRPC.Chat chat2 = messagesController.getChat(chat.migrated_to.channel_id);
                        if (chat2 != null) {
                            chat = chat2;
                        }
                    }
                } else {
                    user = messagesController.getUser(lower_id);
                }
            }
        } else {
            encryptedChat = messagesController.getEncryptedChat(high_id);
            if (encryptedChat != null) {
                user = messagesController.getUser(encryptedChat.user_id);
            }
        }

        if (chat != null) {
            return allowedDialogs.containsKey(currentDialogId);
        } else if (user != null) {
            if (user.bot) {
                long id = (long) user.id;
                return allowedBots.containsKey(id);
            } else if (GlobalSecuritySettings.getManageUsers()) {
                return allowedDialogs.containsKey(currentDialogId);
            }
            return true;
        }
        return false;
    }


    public static void dismissProgress() {
        delegateInstance = null;
        if(progressDialog != null) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }

    private static ReopenDialogAfterCheckDelegate delegateInstance;
    private static AlertDialog progressDialog;

    private static class ReopenDialogAfterCheckDelegate implements NotificationCenter.NotificationCenterDelegate {
        private final TLRPC.User user;
        private final TLRPC.Chat chat;
        private final BaseFragment fragment;
        private final int type;
        private final boolean closeLast;

        ReopenDialogAfterCheckDelegate(TLRPC.User user, TLRPC.Chat chat, BaseFragment fragment, int type, boolean closeLast) {
            this.user = user;
            this.chat = chat;
            this.fragment = fragment;
            this.type = type;
            this.closeLast = closeLast;
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            MessagesController.openChatOrProfileWith(user, chat, fragment, type, closeLast);

            NotificationCenter.getInstance(fragment.getCurrentAccount()).removeObserver(this, NotificationCenter.filterDialogsReady);
            delegateInstance = null;
            if(progressDialog != null) {
                progressDialog.dismiss();
            }
            progressDialog = null;
        }
    }

    public static void openUncheckedDialog(long dialogId, TLRPC.User user, TLRPC.Chat chat, BaseFragment fragment, int type, boolean closeLast) {
        if (progressDialog != null) {
            progressDialog.dismiss();
        }
        if(fragment.getParentActivity() == null) {
            return;
        }
        delegateInstance = new ReopenDialogAfterCheckDelegate(user, chat, fragment, type, closeLast);
        progressDialog = new AlertDialog(fragment.getParentActivity(), 3);
        NotificationCenter.getInstance(fragment.getCurrentAccount()).addObserver(delegateInstance, NotificationCenter.filterDialogsReady);
        ChannelCheckingService.startDataChecking(fragment.getCurrentAccount(), dialogId, fragment.getParentActivity());
        progressDialog.show();
    }

    public boolean isMessageAllowed(MessageObject messageObject) {
        if (messageObject.messageOwner.media != null && messageObject.messageOwner.media.document != null
                && !MediaDataController.getInstance(currentAccount).isStickerAllowed(messageObject.messageOwner.media.document)) {
            if (TextUtils.isEmpty(GlobalSecuritySettings.getBlockedImageUrl())) {
                return false;
            }
        }

        if (messageObject.messageOwner.via_bot_id <= 0) {
            return true;
        }

        TLRPC.User botUser = MessagesController.getInstance(currentAccount).getUser(messageObject.messageOwner.via_bot_id);
        if (botUser != null && botUser.username != null && botUser.username.length() > 0) {
            return isUserAllowed(botUser);
        }
        return false;
    }


    public ArrayList<MessageObject> filterMessages(ArrayList<MessageObject> messages) {
        ArrayList<MessageObject> filtered = new ArrayList<>();
        if (messages == null) {
            return filtered;
        }

        for (MessageObject messageObject : messages) {
            if (isMessageAllowed(messageObject)) {
                filtered.add(messageObject);
            }
        }
        return filtered;
    }
}