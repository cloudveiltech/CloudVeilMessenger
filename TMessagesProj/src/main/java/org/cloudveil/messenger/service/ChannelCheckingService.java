package org.cloudveil.messenger.service;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;

import org.cloudveil.messenger.GlobalSecuritySettings;
import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.api.model.response.SettingsResponse;
import org.cloudveil.messenger.api.service.holder.ServiceClientHolders;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class ChannelCheckingService extends Service {
    private static final int NOTIFICATION_ID = ChannelCheckingService.class.getName().hashCode();

    private static final String ACTION_CHECK_CHANNELS = "org.cloudveil.messenger.service.check.channels";
    private static final String EXTRA_ADDITION_DIALOG_ID = "extra_dialog_id";
    private static final String EXTRA_ACCOUNT_NUMBER = "extra_account_number";
    private static final long DEBOUNCE_TIME_MS = 200;

    private Disposable subscription;
    Handler handler = new Handler();
    private long additionalDialogId = 0;
    private boolean firstCall = true;
    private int accountNumber = 0;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void startDataChecking(int accountNum, @NonNull Context context) {
        Intent intent = new Intent(ACTION_CHECK_CHANNELS);
        intent.putExtra(EXTRA_ACCOUNT_NUMBER, accountNum);
        intent.setClass(context, ChannelCheckingService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void startDataChecking(int accountNum, long dialogId, @NonNull Context context) {
        Intent intent = new Intent(ACTION_CHECK_CHANNELS);
        intent.setClass(context, ChannelCheckingService.class);
        intent.putExtra(EXTRA_ADDITION_DIALOG_ID, dialogId);
        intent.putExtra(EXTRA_ACCOUNT_NUMBER, accountNum);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null && intent.getAction().equals(ACTION_CHECK_CHANNELS)) {
            handler.removeCallbacks(checkDataRunnable);
            long additionalId = intent.getLongExtra(EXTRA_ADDITION_DIALOG_ID, 0);
            if (additionalId != 0) {
                additionalDialogId = additionalId;
            }

            accountNumber = intent.getIntExtra(EXTRA_ACCOUNT_NUMBER, 0);

            handler.postDelayed(checkDataRunnable, DEBOUNCE_TIME_MS);
        }

        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String CHANNEL_ID = "CVM channel 1";
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "CVM channel",
                    NotificationManager.IMPORTANCE_NONE);

            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("")
                    .setContentText("").build();

            startForeground(NOTIFICATION_ID, notification);
        }
    }

    Runnable checkDataRunnable = new Runnable() {
        @Override
        public void run() {
            sendDataCheckRequest();
        }
    };

    private void sendDataCheckRequest() {
        final SettingsRequest request = new SettingsRequest();
        addDialogsToRequest(request);
        addInlineBotsToRequest(request);
        addStickersToRequest(request);

        request.userPhone = UserConfig.getInstance(accountNumber).getCurrentUser().phone;
        request.userId = UserConfig.getInstance(accountNumber).getCurrentUser().id;
        request.userName = UserConfig.getInstance(accountNumber).getCurrentUser().username;

        if (request.isEmpty()) {
            NotificationCenter.getInstance(accountNumber).postNotificationName(NotificationCenter.filterDialogsReady);
            return;
        }

        final SettingsResponse cached = loadFromCache();
        if (cached != null && (firstCall || !ApplicationLoader.isNetworkOnline())) {
            processResponse(cached);
            firstCall = false;
        }
        if (!ApplicationLoader.isNetworkOnline()) {
            return;
        }

        NotificationCenter.getInstance(accountNumber).postNotificationName(NotificationCenter.filterDialogsReady);
        subscription = ServiceClientHolders.getSettingsService().loadSettings(request).
                subscribeOn(Schedulers.io()).
                observeOn(AndroidSchedulers.mainThread()).
                subscribe(settingsResponse -> {
                    processResponse(settingsResponse);
                    freeSubscription();

                    saveToCache(settingsResponse);
                    stopForeground(true);
                    stopSelf();
                }, throwable -> freeSubscription());
    }

    private void addInlineBotsToRequest(SettingsRequest request) {
        Collection<TLRPC.User> values = MessagesController.getInstance(accountNumber).getUsers().values();
        for (TLRPC.User user : values) {
            if (user.bot) {
                SettingsRequest.Row row = new SettingsRequest.Row();
                row.id = user.id;

                row.title = user.username;
                row.userName = user.username;
                request.addBot(row);
            }
        }
    }

    private void addStickersToRequest(SettingsRequest request) {
        for (int i = 0; i < MediaDataController.getInstance(accountNumber).getStickersSetTypesCount(); i++) {
            addStickerSetToRequest(MediaDataController.getInstance(accountNumber).getStickerSets(i), request);
        }

        addStickerSetToRequest(MediaDataController.getInstance(accountNumber).newStickerSets, request);

        ArrayList<TLRPC.StickerSetCovered> featuredStickerSets = MediaDataController.getInstance(accountNumber).getFeaturedStickerSetsUnfiltered();
        for (TLRPC.StickerSetCovered stickerSetCovered : featuredStickerSets) {
            addStickerSetToRequest(stickerSetCovered.set, request);
        }
    }

    private void addStickerSetToRequest(ArrayList<TLRPC.TL_messages_stickerSet> stickerSets, SettingsRequest request) {
        for (TLRPC.TL_messages_stickerSet set : stickerSets) {
            addStickerSetToRequest(set.set, request);
        }
    }

    private void addStickerSetToRequest(TLRPC.StickerSet stickerSet, SettingsRequest request) {
        SettingsRequest.Row row = new SettingsRequest.Row();
        row.id = stickerSet.id;
        row.title = stickerSet.title;
        row.userName = stickerSet.short_name;

        request.addSticker(row);
    }

    private void processResponse(@NonNull SettingsResponse settingsResponse) {
        if (settingsResponse.access == null || !settingsResponse.access.isValid()) {
            return;
        }

        ConcurrentHashMap<Long, Boolean> allowedDialogs = MessagesController.getInstance(accountNumber).allowedDialogs;
        allowedDialogs.clear();

        appendAllowedDialogs(allowedDialogs, settingsResponse.access.channels);
        appendAllowedDialogs(allowedDialogs, settingsResponse.access.groups);
        appendAllowedDialogs(allowedDialogs, settingsResponse.access.users);

        if (settingsResponse.access.bots != null) {
            ConcurrentHashMap<Long, Boolean> allowedBots = MessagesController.getInstance(accountNumber).allowedBots;
            allowedBots.clear();
            appendAllowedDialogs(allowedBots, settingsResponse.access.bots);
        }

        if (settingsResponse.access.stickers != null) {
            MediaDataController.getInstance(accountNumber).allowedStickerSets.clear();
            appendAllowedDialogs(MediaDataController.getInstance(accountNumber).allowedStickerSets, settingsResponse.access.stickers);
        }

        GlobalSecuritySettings.setDisableSecretChat(!settingsResponse.secretChat);
        GlobalSecuritySettings.setMinSecretChatTtl(settingsResponse.secretChatMinimumLength);

        GlobalSecuritySettings.setLockDisableOthersBio(settingsResponse.disableBio);
        GlobalSecuritySettings.setLockDisableOwnBio(settingsResponse.disableBioChange);
        GlobalSecuritySettings.setLockDisableOwnPhoto(settingsResponse.disableProfilePhotoChange);
        GlobalSecuritySettings.setLockDisableOthersPhoto(settingsResponse.disableProfilePhoto);
        GlobalSecuritySettings.setDisabledVideoInlineRecording(!settingsResponse.inputToggleVoiceVideo);
        GlobalSecuritySettings.setLockDisableStickers(settingsResponse.disableStickers);
        GlobalSecuritySettings.setManageUsers(settingsResponse.manageUsers);
        GlobalSecuritySettings.setBlockedImageUrl(settingsResponse.disableStickersImage);

        NotificationCenter.getInstance(accountNumber).postNotificationName(NotificationCenter.filterDialogsReady);
    }

    private void appendAllowedDialogs(ConcurrentHashMap<Long, Boolean> allowedDialogs, ArrayList<HashMap<Long, Boolean>> groups) {
        for (HashMap<Long, Boolean> data : groups) {
            Long id = data.keySet().iterator().next();
            Boolean value = data.values().iterator().next();
            allowedDialogs.put(id, value);
        }
    }

    private SettingsResponse loadFromCache() {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(this.getClass().getCanonicalName(), Activity.MODE_PRIVATE);
        String json = preferences.getString("settings." + accountNumber, null);
        if (json == null) {
            return null;
        }
        return new Gson().fromJson(json, SettingsResponse.class);
    }

    private void saveToCache(@NonNull SettingsResponse settings) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(this.getClass().getCanonicalName(), Activity.MODE_PRIVATE);
        String json = new Gson().toJson(settings);
        preferences.edit().putString("settings." + accountNumber, json).apply();
    }

    private void freeSubscription() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
        subscription = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        freeSubscription();
    }

    private void addDialogsToRequest(@NonNull SettingsRequest request) {
        addDialogsToRequest(request, MessagesController.getInstance(accountNumber).allDialogs);
        addDialogsToRequest(request, MessagesController.getInstance(accountNumber).dialogsForward);
        addDialogsToRequest(request, MessagesController.getInstance(accountNumber).dialogsGroupsOnly);
        addDialogsToRequest(request, MessagesController.getInstance(accountNumber).dialogsServerOnly);

        if (additionalDialogId != 0) {
            addDialogToRequest(additionalDialogId, request);
            additionalDialogId = 0;
        }
    }

    private void addDialogsToRequest(@NonNull SettingsRequest request, ArrayList<TLRPC.Dialog> dialogs) {
        for (TLRPC.Dialog dlg : dialogs) {
            long currentDialogId = dlg.id;
            addDialogToRequest(currentDialogId, request);
        }
    }

    private void addDialogToRequest(long currentDialogId, @NonNull SettingsRequest request) {
        //this is very complicated code from Telegram core to separate dialogs to users, groups and channels
        int lower_id = (int) currentDialogId;
        int high_id = (int) (currentDialogId >> 32);
        TLRPC.Chat chat = null;
        TLRPC.User user = null;
        if (lower_id != 0) {
            if (high_id == 1) {
                chat = MessagesController.getInstance(accountNumber).getChat(lower_id);
            } else {
                if (lower_id < 0) {
                    chat = MessagesController.getInstance(accountNumber).getChat(-lower_id);
                    if (chat != null && chat.migrated_to != null) {
                        TLRPC.Chat chat2 = MessagesController.getInstance(accountNumber).getChat(chat.migrated_to.channel_id);
                        if (chat2 != null) {
                            chat = chat2;
                        }
                    }
                } else {
                    user = MessagesController.getInstance(accountNumber).getUser(lower_id);
                }
            }
        } else {
            TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance(accountNumber).getEncryptedChat(high_id);
            if (encryptedChat != null) {
                user = MessagesController.getInstance(accountNumber).getUser(encryptedChat.user_id);
            }
        }

        SettingsRequest.Row row = new SettingsRequest.Row();
        row.id = currentDialogId;
        if (chat != null) {
            row.title = chat.title;
            row.userName = chat.username;

            boolean isChannel = ChatObject.isChannel(chat) && !chat.megagroup;
            if (isChannel) {
                request.addChannel(row);
            } else {
                request.addGroup(row);
            }
        } else if (user != null) {
            if (!user.self) {
                row.id = user.id;
                row.title = "";
                if(user.first_name != null) {
                    row.title = user.first_name;
                }
                if(user.last_name != null) {
                    if(!TextUtils.isEmpty(row.title)) {
                        row.title += " ";
                    }
                    row.title += user.last_name;
                }
                row.userName = user.username;
                if (user.bot) {
                    request.addBot(row);
                } else {
                    request.addUser(row);
                }
            }
        }
    }
}
