package org.cloudveil.messenger.util;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.cloudveil.messenger.CloudVeilSecuritySettings;
import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.jobs.CloudVeilSyncWorker;
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
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CloudVeilDialogHelper {
    private static final long SUPPORT_BOT_ID = 689684671;
    private static final long ONE_DAY_MS = 24 * 60 * 60 * 1000;
    private final int accountNumber;

    public enum DialogType {
        channel, group, user, bot, chat,
        // The type's name fills the "%1$s" in the CloudVeil alert texts. Before this existed a
        // secret chat was labeled "group" or "user", depending on which screen showed the alert.
        secretChat {
            @NonNull
            @Override
            public String toString() {
                return "secret chat";
            }
        }
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
    @Deprecated
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

    @Deprecated
    private boolean isCloudVeilChannelLoaded(SettingsRequest request) {
        if (request == null) {
            return false;
        }
        for (int i = 0; i < request.channels.size(); i++) {
            SettingsRequest.GroupChannelRow channel = request.channels.get(i);
            //loop in channel.userNames
            for (String userName : channel.userNames) {
                if (userName != null && userName.equalsIgnoreCase("CloudVeilMessenger")) {
                    return true;
                }
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
        } else if (CloudVeilSecuritySettings.getManageUsers()) {
            return allowedDialogs.containsKey(id) && Boolean.TRUE.equals(allowedDialogs.get(id));
        }
        return true;
    }

    public boolean isBotAllowed(TL_bots.BotInfo bot) {
        if (bot == null) {
            return true;
        }

        return isBotIdAllowed(bot.user_id);
    }

    private boolean isBotIdAllowed(long id) {
        if (CloudVeilSecuritySettings.LOCK_DISABLE_BOTS) {
            return false;
        }
        if(!allowedBots.containsKey(id)) {
            return false;
        }
        return Boolean.TRUE.equals(allowedBots.get(id));
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

        // A secret chat is labeled "secret chat" in alerts, whichever object is returned for it.
        // Only the label changes: the returned object is the same as before.
        boolean isSecretChat = DialogObject.isEncryptedDialog(currentDialogId);
        if (encryptedChat != null && CloudVeilSecuritySettings.isDisabledSecretChat()) {
            return new Pair<>(encryptedChat, DialogType.secretChat);
        } else if (chat != null) {
            if (ChatObject.isChannel(chat)) {
                return new Pair<>(chat, chat.megagroup ? DialogType.group : DialogType.channel);
            } else {
                return new Pair<>(chat, DialogType.group);
            }
        } else if (user != null) {
            return new Pair<>(user, isSecretChat ? DialogType.secretChat : user.bot ? DialogType.bot : DialogType.user);
        }
        return new Pair<>(null, isSecretChat ? DialogType.secretChat : DialogType.group);
    }


    public boolean isDialogIdAllowed(long currentDialogId) {
        if(currentDialogId == SUPPORT_BOT_ID) {
            return true;
        }
        if (DialogObject.isEncryptedDialog(currentDialogId)) {
            // Secret chats turned off by the server: every secret chat is blocked by that one setting.
            if (CloudVeilSecuritySettings.isDisabledSecretChat()) {
                return false;
            }
            // Otherwise a secret chat follows the server's answer for the other participant, so
            // blocking a person also blocks secret chats with them. Before, only the on/off setting
            // was checked, and a secret chat with a blocked person still opened.
            // Participant not loaded yet: blocked until they can be checked (the "checking" alert).
            TLRPC.User partner = getSecretChatPartner(currentDialogId);
            return partner != null && isUserAllowed(partner);
        } else if (DialogObject.isUserDialog(currentDialogId)) {
            return isUserAllowed(MessagesController.getInstance(accountNumber).getUser(currentDialogId));
        } else {
            return isChatIdAllowed(currentDialogId);
        }
    }

    private boolean isChatIdAllowed(long currentDialogId) {
        return allowedDialogs.containsKey(currentDialogId) && Boolean.TRUE.equals(allowedDialogs.get(currentDialogId));
    }

    public boolean isDialogCheckedOnServer(long currentDialogId) {
        if(currentDialogId == SUPPORT_BOT_ID) {
            return true;
        }
        if (DialogObject.isEncryptedDialog(currentDialogId)) {
            // Secret chats turned off: that one setting decides, there is no server answer to wait for.
            if (CloudVeilSecuritySettings.isDisabledSecretChat()) {
                return true;
            }
            // Otherwise the answer that matters is the other participant's, and the server's answers
            // are stored by user id, not by the secret chat's own id (looking up the secret chat's id
            // never matched, so with "manage users" on a secret chat counted as never checked).
            // Participant not loaded yet: not checked, so the chat shows "checking" instead of opening.
            TLRPC.User partner = getSecretChatPartner(currentDialogId);
            return partner != null && isDialogCheckedOnServer(partner.id);
        }

        TLRPC.Chat chat = null;
        TLRPC.User user = null;

        TLObject object = CloudVeilDialogHelper.getInstance(accountNumber).getObjectByDialogId(currentDialogId).first;
        // instanceof instead of a blind cast: getObjectByDialogId can also return an EncryptedChat,
        // and casting that to User crashed (the ClassCastException this fix is about).
        if (object instanceof TLRPC.Chat) {
            chat = (TLRPC.Chat) object;
        } else if (object instanceof TLRPC.User) {
            user = (TLRPC.User) object;
        }

        if (chat != null) {
            return allowedDialogs.containsKey(currentDialogId);
        } else if (user != null) {
            if (user.bot) {
                return allowedBots.containsKey(user.id);
            } else if (CloudVeilSecuritySettings.getManageUsers()) {
                return allowedDialogs.containsKey(currentDialogId);
            }
            return true;
        }
        return false;
    }

    /**
     * Finds the other participant of a secret chat, straight from the in-memory caches.
     * Deliberately not getObjectByDialogId: that returns the EncryptedChat itself while secret
     * chats are turned off, and it reads that setting again, so the caller's own read of the
     * setting and this lookup could disagree if a sync flipped it in between.
     * Returns null when the secret chat or its participant is not loaded.
     */
    @Nullable
    private TLRPC.User getSecretChatPartner(long encryptedDialogId) {
        MessagesController messagesController = MessagesController.getInstance(accountNumber);
        TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(encryptedDialogId));
        return encryptedChat != null ? messagesController.getUser(encryptedChat.user_id) : null;
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
            MessagesController.getInstance(account).openChatOrProfileWith(user, chat, fragment, type, closeLast);

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
        CloudVeilSyncWorker.startDataChecking(fragment.getCurrentAccount(), dialogId, fragment.getParentActivity());
        progressDialog.show();
    }

    public boolean isMessageAllowed(@NonNull MessageObject messageObject) {
        if(messageObject.messageOwner == null) {
            return false;
        }
        if(messageObject.messageOwner.action instanceof TLRPC.TL_messageActionChatEditPhoto) {
            if(CloudVeilSecuritySettings.getLockDisableOthersPhoto()) {
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

    public static void showWarning(BaseFragment fragment, DialogType type, long dialogId, Runnable onOkRunnable, Runnable onDismissRunnable) {
        String message = fragment.getParentActivity().getString(R.string.cloudveil_message_dialog_forbidden, type.toString());
        long unlockItemId = dialogId;
        boolean canRequestUnlock = true;
        if (DialogObject.isEncryptedDialog(dialogId)) {
            // Secret chat alerts get their own texts. The label is set here instead of trusting the
            // caller's type, because the chat list works out its own type and calls a secret chat "group".
            String secretChatLabel = DialogType.secretChat.toString();
            TLRPC.User partner = getInstance(fragment.getCurrentAccount()).getSecretChatPartner(dialogId);
            if (CloudVeilSecuritySettings.isDisabledSecretChat()) {
                // Secret chats turned off for the whole account. No unblock form: asking to unblock one
                // chat can't turn the feature back on.
                message = fragment.getParentActivity().getString(R.string.cloudveil_secret_chats_disabled);
                canRequestUnlock = false;
            } else if (partner == null) {
                // Participant not loaded yet, so they couldn't be checked: say "checking" rather than
                // "blocked", and offer no form, since there is no user id to send.
                message = fragment.getParentActivity().getString(R.string.cloudveil_checking_server_policy, secretChatLabel);
                canRequestUnlock = false;
            } else {
                // The other participant is blocked (or, with "manage users" on, not approved yet).
                // The unblock form gets their user id, like a normal chat with them does; the server
                // can't look up a secret chat's own id. Keeping "Continue" here is provisional until
                // the website form is confirmed to handle a person's id.
                message = fragment.getParentActivity().getString(R.string.cloudveil_message_dialog_forbidden, secretChatLabel);
                unlockItemId = partner.id;
            }
        }
        final long finalUnlockItemId = unlockItemId;

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(fragment.getParentActivity().getString(R.string.warning))
                .setMessage(message);
        if (canRequestUnlock) {
            builder.setPositiveButton(fragment.getParentActivity().getString(R.string.continue_label), (dialog, which) -> {
                        sendUnlockRequest(finalUnlockItemId, fragment.getCurrentAccount(), fragment);
                        if (onOkRunnable != null) {
                            onOkRunnable.run();
                        }
                        dialog.dismiss();
                    })
                    .setNegativeButton(fragment.getParentActivity().getString(R.string.cancel), (dialog, i) -> {
                        dialog.dismiss();
                        if (onDismissRunnable != null) {
                            onDismissRunnable.run();
                        }
                    });
        } else {
            // Only dismiss: the dismiss listener installed by fragment.showDialog below already runs
            // onDismissRunnable. Running it here too made the chat screen close itself twice.
            builder.setPositiveButton(fragment.getParentActivity().getString(R.string.OK), (dialog, which) -> dialog.dismiss());
        }
        builder.setOnDismissListener(dialog -> {
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

    public static void showCheckingServerPolicy(BaseFragment fragment, DialogType type, Runnable onOkRunnable) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(fragment.getParentActivity().getString(R.string.cloudveil));
        builder.setMessage(fragment.getParentActivity().getString(R.string.cloudveil_checking_server_policy, type.toString()));
        builder.setPositiveButton(fragment.getParentActivity().getString(R.string.OK), (dialog2, which) -> {
                    dialog2.dismiss();
                    if (onOkRunnable != null) {
                        onOkRunnable.run();
                    }
                });
        fragment.showDialog(builder.create(), dialog -> {
        });
    }

    public static void showWarningAboutContentDisable(BaseFragment fragment) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(fragment.getParentActivity().getString(R.string.warning))
                .setMessage(fragment.getParentActivity().getString(R.string.cloudveil_hidden_for_protection))
                .setPositiveButton(fragment.getParentActivity().getString(R.string.continue_label), (dialog, which) -> {
                    //dialog.dismiss();
                });

        fragment.showDialog(builder.create(), dialog -> {
        });
    }

    public static void showCheckingServerPolicy(BaseFragment fragment) {
        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setTitle(fragment.getParentActivity().getString(R.string.one_moment))
                .setMessage(fragment.getParentActivity().getString(R.string.cloudveil_checking_server_policy))
                .setPositiveButton(fragment.getParentActivity().getString(R.string.continue_label), (dialog, which) -> {
                    //dialog.dismiss();
                });

        fragment.showDialog(builder.create(), dialog -> {
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
        if(!CloudVeilSecuritySettings.getOrganization().needChange) {
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
