package org.cloudveil.messenger.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;

import org.cloudveil.messenger.GlobalSecuritySettings;
import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.service.ChannelCheckingService;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public class CloudVeilDialogHelper {
    private int accountNumber;

    private static volatile CloudVeilDialogHelper[] Instance = new CloudVeilDialogHelper[UserConfig.MAX_ACCOUNT_COUNT];

    private CloudVeilDialogHelper(int num) {
        accountNumber = num;
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
        if (isCloudVeilChannelLoaded(request)) {
            return;
        }

        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = "CloudVeilMessenger";
        MessagesController messagesController = MessagesController.getInstance(accountNumber);
        final int reqId = messagesController.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {

            if (error == null) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                if (res.chats.size() == 0) {
                    return;
                }
                TLRPC.Chat chat = res.chats.get(0);
                messagesController.putChat(chat, false);
                messagesController.addUserToChat(chat.id, UserConfig.getInstance(accountNumber).getCurrentUser(), 0, null, null, null);
            }
        }));
    }

    private boolean isCloudVeilChannelLoaded(SettingsRequest request) {
        if (request == null) {
            return false;
        }
        for (int i = 0; i < request.channels.size(); i++) {
            String userName = request.channels.get(i).userName;
            if (userName != null && userName.equalsIgnoreCase("CloudVeilMessenger")) {
                return true;
            }
        }
        return false;
    }


    public boolean isUserAllowed(TLRPC.User user) {
        if (user == null) {
            return true;
        }
        long id = user.id;
        if (user.bot) {
            return isBotIdAllowed(id);
        } else if (GlobalSecuritySettings.getManageUsers()) {
            return !allowedDialogs.containsKey(id) || allowedDialogs.get(id);
        }
        return true;
    }

    public boolean isBotAllowed(TLRPC.BotInfo bot) {
        if (bot == null) {
            return true;
        }

        return isBotIdAllowed(bot.user_id);
    }

    private boolean isBotIdAllowed(long id) {
        if (GlobalSecuritySettings.LOCK_DISABLE_BOTS) {
            return false;
        }
        return !allowedBots.containsKey(id) || allowedBots.get(id);
    }

    public TLObject getObjectByDialogId(long currentDialogId) {
        TLRPC.Chat chat = null;
        TLRPC.User user = null;
        TLRPC.EncryptedChat encryptedChat = null;
        if (DialogObject.isEncryptedDialog(currentDialogId)) {
            encryptedChat = MessagesController.getInstance(accountNumber).getEncryptedChat(DialogObject.getEncryptedChatId(currentDialogId));
            if (encryptedChat != null) {
                user = MessagesController.getInstance(accountNumber).getUser(encryptedChat.user_id);
            }
        } else if (DialogObject.isUserDialog(currentDialogId)) {
            user = MessagesController.getInstance(accountNumber).getUser(currentDialogId);
        } else {
            chat = MessagesController.getInstance(accountNumber).getChat(currentDialogId);
            if (chat == null) {
                chat = MessagesController.getInstance(accountNumber).getChat(-currentDialogId);
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
        if (DialogObject.isEncryptedDialog(currentDialogId)) {
            if(GlobalSecuritySettings.isDisabledSecretChat()) {
                return false;
            }
            return true;
        } else if (DialogObject.isUserDialog(currentDialogId)) {
            return isUserAllowed(MessagesController.getInstance(accountNumber).getUser(currentDialogId));
        } else {
            return isChatIdAllowed(currentDialogId);
        }
    }

    private boolean isChatIdAllowed(long currentDialogId) {
        return !allowedDialogs.containsKey(currentDialogId) || allowedDialogs.get(currentDialogId);
    }

    public boolean isDialogCheckedOnServer(long currentDialogId) {
        TLRPC.Chat chat = null;
        TLRPC.User user = null;

        TLObject object = CloudVeilDialogHelper.getInstance(accountNumber).getObjectByDialogId(currentDialogId);
        if (object instanceof TLRPC.Chat) {
            chat = (TLRPC.Chat) object;
        } else {
            user = (TLRPC.User) object;
        }

        if (chat != null) {
            return allowedDialogs.containsKey(currentDialogId);
        } else if (user != null) {
            if (user.bot) {
                return allowedBots.containsKey(user.id);
            } else if (GlobalSecuritySettings.getManageUsers()) {
                return allowedDialogs.containsKey(currentDialogId);
            }
            return true;
        }
        return false;
    }


    public static void dismissProgress() {
        delegateInstance = null;
        if (progressDialog != null) {
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
            if (progressDialog != null) {
                progressDialog.dismiss();
            }
            progressDialog = null;
        }
    }

    public static void openUncheckedDialog(long dialogId, TLRPC.User user, TLRPC.Chat chat, BaseFragment fragment, int type, boolean closeLast) {
        if (progressDialog != null) {
            progressDialog.dismiss();
        }
        if (fragment.getParentActivity() == null) {
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
                && !MediaDataController.getInstance(accountNumber).isStickerAllowed(messageObject.messageOwner.media.document)) {
            if (TextUtils.isEmpty(GlobalSecuritySettings.getBlockedImageUrl())) {
                return false;
            }
        }

        if (messageObject.messageOwner.via_bot_id > 0) {
            TLRPC.User botUser = MessagesController.getInstance(accountNumber).getUser(messageObject.messageOwner.via_bot_id);
            if (botUser != null && botUser.username != null && botUser.username.length() > 0) {
                return isUserAllowed(botUser);
            }
        }

        TLRPC.Peer fromId = messageObject.messageOwner.from_id;
        if (fromId != null) {
            if (fromId.user_id > 0) {
                TLRPC.User user = MessagesController.getInstance(accountNumber).getUser(fromId.user_id);
                if (user != null && user.username != null && user.username.length() > 0) {
                    return isUserAllowed(user);
                }
            }
            if (fromId.chat_id > 0 || fromId.channel_id > 0) {
                TLRPC.Chat chat = MessagesController.getInstance(accountNumber).getChat(fromId.chat_id > 0 ? fromId.chat_id : fromId.channel_id);
                if (chat != null) {
                    return isChatIdAllowed(-chat.id);
                }
            }
        }

        return true;
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

    public static boolean isBatteryOptimized(final Context context) {
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        String name = context.getPackageName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return !powerManager.isIgnoringBatteryOptimizations(name);
        }
        return false;
    }

    public static void showBatteryWarning(BaseFragment fragment, int currentAccount, final Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {//not used
            return;
        }
        if (!isBatteryOptimized(context)) {
            return;
        }

        SharedPreferences preferences = MessagesController.getNotificationsSettings(currentAccount);
        boolean alertEnabled = preferences.getBoolean("checkPowerSavingOnStart", true);
        if(!alertEnabled) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(context.getString(R.string.warning))
                .setMessage(context.getString(R.string.cloudveil_battery_warning))
                .setPositiveButton(context.getString(R.string.resolve), (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    context.startActivity(intent);

                    dialog.dismiss();
                    fragment.finishFragment();
                })
                .setNegativeButton(context.getString(R.string.cancel), (dialog, which) -> fragment.finishFragment());
        fragment.showDialog(builder.create());
    }

    public static void showWarning(BaseFragment fragment, TLObject tlObject, Runnable onOkRunnable, Runnable onDismissRunnable) {
        if (tlObject == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(fragment.getParentActivity().getString(R.string.warning))
                .setMessage(fragment.getParentActivity().getString(R.string.cloudveil_message_dialog_forbidden))
                .setPositiveButton(fragment.getParentActivity().getString(R.string.contact), (dialog, which) -> {
                    dialog.dismiss();
                    if (onOkRunnable != null) {
                        onOkRunnable.run();
                    }

                    sendUnlockRequest(tlObject, fragment.getCurrentAccount());
                })
                .setNegativeButton(fragment.getParentActivity().getString(R.string.cancel), (dialog, i) -> {
                    dialog.dismiss();
                    if (onDismissRunnable != null) {
                        onDismissRunnable.run();
                    }
                })
                .setOnDismissListener(dialog -> {
                    if (onDismissRunnable != null) {
                        onDismissRunnable.run();
                    }
                })
                .setOnBackButtonListener((dialog, which) -> {
                    if (onDismissRunnable != null) {
                        onDismissRunnable.run();
                    }
                });
        fragment.showDialog(builder.create(), dialog -> {
            if (onDismissRunnable != null) {
                onDismissRunnable.run();
            }
        });
    }

    private static void sendUnlockRequest(TLObject tlObject, int currentAccount) {
        long currentUserId = UserConfig.getInstance(currentAccount).getCurrentUser().id;
        long itemId = 0;


        if (tlObject instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) tlObject;
            itemId = user.id;
        } else if (tlObject instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) tlObject;
            itemId = chat.id;
        } else if (tlObject instanceof TLRPC.TL_dialog) {
            TLRPC.TL_dialog dlg = (TLRPC.TL_dialog) tlObject;
            TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dlg.id);
            if (chat != null && chat.migrated_to != null) {
                TLRPC.Chat chat2 = MessagesController.getInstance(currentAccount).getChat(chat.migrated_to.channel_id);
                if (chat2 != null) {
                    chat = chat2;
                }
            }
            if (chat != null) {
                itemId = chat.id;
            }
        }

        Browser.openUrl(ApplicationLoader.applicationContext, "https://messenger.cloudveil.org/unblock/" + currentUserId + "/" + itemId);
    }
}
