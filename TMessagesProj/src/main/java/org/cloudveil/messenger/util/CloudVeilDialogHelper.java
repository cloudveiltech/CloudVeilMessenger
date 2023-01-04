package org.cloudveil.messenger.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import org.cloudveil.messenger.GlobalSecuritySettings;
import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.service.ChannelCheckingService;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
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
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CloudVeilDialogHelper {
    private static final long ONE_DAY_MS = 24 * 60 * 60 * 1000;
    private final int accountNumber;

    public enum DialogType {
        channel, group, user, bot, chat
    }

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
        if(!allowedBots.containsKey(id)) {
            return false;
        }
        return allowedBots.get(id);
    }

    public Pair<TLObject, DialogType> getObjectByDialogId(long currentDialogId) {
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
            return new Pair<>(encryptedChat, DialogType.group);
        } else if (chat != null) {
            return new Pair<>(chat,  ChatObject.isChannel(chat) ? DialogType.channel : DialogType.group);
        } else if (user != null) {
            return new Pair<>(user, user.bot ? DialogType.bot : DialogType.user);
        }
        return new Pair<>(null, DialogType.group);
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

        TLObject object = CloudVeilDialogHelper.getInstance(accountNumber).getObjectByDialogId(currentDialogId).first;
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

    public static void showWarning(BaseFragment fragment, DialogType type, long dialogId, Runnable onOkRunnable, Runnable onDismissRunnable) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(fragment.getParentActivity().getString(R.string.warning))
                .setMessage(fragment.getParentActivity().getString(R.string.cloudveil_message_dialog_forbidden, type.toString()))
                .setPositiveButton(fragment.getParentActivity().getString(R.string.continue_label), (dialog, which) -> {
                    dialog.dismiss();
                    if (onOkRunnable != null) {
                        onOkRunnable.run();
                    }

                    sendUnlockRequest(dialogId, fragment.getCurrentAccount(), fragment);
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

    private static void sendUnlockRequest(long itemId, int currentAccount, BaseFragment fragment) {
        long currentUserId = UserConfig.getInstance(currentAccount).getCurrentUser().id;
        Browser.openUrl(ApplicationLoader.applicationContext, "https://messenger.cloudveil.org/unblock/" + currentUserId + "/" + itemId, fragment);
    }

    private static final Pattern youtubeIdRegex = Pattern.compile("(?:youtube(?:-nocookie)?\\.com/(?:[^/\\n\\s]+/\\S+/|(?:v|e(?:mbed)?)/|\\S*?[?&]v=)|youtu\\.be/)([a-zA-Z0-9_-]{11})");
    public static boolean isYoutubeUrl(String url) {
        if(TextUtils.isEmpty(url)) {
            return false;
        }
        Matcher matcher = youtubeIdRegex.matcher(url);
        return matcher.find();
    }

    public void checkOrganizationChangeRequired(BaseFragment fragment, Context context) {
        if(!GlobalSecuritySettings.getIsOrganisationChangeRequired()) {
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences preferences = MessagesController.getMainSettings(accountNumber);
        long lastCheckTime = preferences.getLong("checkOrganizationChangeRequiredTime", 0);
        if(now - lastCheckTime < ONE_DAY_MS) {
            return;
        }
        preferences.edit().putLong("checkOrganizationChangeRequiredTime", now).apply();

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(context.getString(R.string.warning))
                .setMessage(context.getString(R.string.cloudveil_organisation_change))
                .setPositiveButton(context.getString(R.string.change), (dialog, which) -> {
                    long currentUserId = UserConfig.getInstance(accountNumber).getCurrentUser().id;
                    Browser.openUrl(context, "https://messenger.cloudveil.org/unblock_status/" + currentUserId, fragment);
                    dialog.dismiss();
                })
                .setNegativeButton(context.getString(R.string.cancel), (dialog, which) -> {});
        fragment.showDialog(builder.create());
    }

    public void showPopup(BaseFragment fragment, final Context context) {
        SharedPreferences defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (defaultSharedPreferences.getBoolean("popupShown", false)) {
            CloudVeilDialogHelper.getInstance(accountNumber).checkOrganizationChangeRequired(fragment, ApplicationLoader.applicationContext);
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(context.getString(R.string.warning))
                .setMessage(context.getString(R.string.cloudveil_message_warning))
                .setPositiveButton(context.getString(R.string.OK), (dialog, which) -> {
                    dialog.dismiss();
                    setPopupShown();
                })
                .setOnDismissListener(dialog -> setPopupShown())
                .setOnBackButtonListener((dialog, which) -> setPopupShown());
        fragment.showDialog(builder.create(), dialog -> setPopupShown());
    }

    private void setPopupShown() {
        SharedPreferences defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(ApplicationLoader.applicationContext);
        defaultSharedPreferences.edit().putBoolean("popupShown", true).apply();
    }
}
