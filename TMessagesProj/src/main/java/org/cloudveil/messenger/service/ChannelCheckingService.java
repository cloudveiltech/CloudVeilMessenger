package org.cloudveil.messenger.service;

import android.app.Activity;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.gson.Gson;

import org.cloudveil.messenger.GlobalSecuritySettings;
import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.api.model.response.SettingsResponse;
import org.cloudveil.messenger.api.service.holder.ServiceClientHolders;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.query.StickersQuery;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class ChannelCheckingService extends Service {
    private static final String ACTION_CHECK_CHANNELS = "org.cloudveil.messenger.service.check.channels";
    private static final String EXTRA_ADDITION_DIALOG_ID = "extra_dialog_id";
    private static final long DEBOUNCE_TIME_MS = 200;

    private Disposable subscription;
    Handler handler = new Handler();
    private long additionalDialogId = 0;
    private boolean firstCall = true;


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void startDataChecking(@NonNull Context context) {
        Intent intent = new Intent(ACTION_CHECK_CHANNELS);
        intent.setClass(context, ChannelCheckingService.class);
        context.startService(intent);
    }

    public static void startDataChecking(long dialogId, @NonNull Context context) {
        Intent intent = new Intent(ACTION_CHECK_CHANNELS);
        intent.setClass(context, ChannelCheckingService.class);
        intent.putExtra(EXTRA_ADDITION_DIALOG_ID, dialogId);
        context.startService(intent);
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null && intent.getAction().equals(ACTION_CHECK_CHANNELS)) {
            handler.removeCallbacks(checkDataRunnable);
            long additionalId = intent.getLongExtra(EXTRA_ADDITION_DIALOG_ID, 0);
            if (additionalId != 0) {
                additionalDialogId = additionalId;
            }

            handler.postDelayed(checkDataRunnable, DEBOUNCE_TIME_MS);
        }
        return super.onStartCommand(intent, flags, startId);
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

        request.userPhone = UserConfig.getCurrentUser().phone;
        request.userId = UserConfig.getCurrentUser().id;
        request.userName = UserConfig.getCurrentUser().username;

        if (request.isEmpty()) {
            NotificationCenter.getInstance().postNotificationName(NotificationCenter.filterDialogsReady);
            return;
        }

        final SettingsResponse cached = loadFromCache();
        if (cached != null && (firstCall || !ConnectionsManager.isNetworkOnline())) {
            processResponse(cached);
            firstCall = false;
        }
        if (!ConnectionsManager.isNetworkOnline()) {
            return;
        }

        NotificationCenter.getInstance().postNotificationName(NotificationCenter.filterDialogsReady);
        subscription = ServiceClientHolders.getSettingsService().loadSettings(request).
                subscribeOn(Schedulers.io()).
                observeOn(AndroidSchedulers.mainThread()).
                subscribe(new Consumer<SettingsResponse>() {

                    @Override
                    public void accept(SettingsResponse settingsResponse) throws Exception {
                        processResponse(settingsResponse);
                        freeSubscription();

                        saveToCache(settingsResponse);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) throws Exception {
                        freeSubscription();
                    }
                });
    }

    private void addInlineBotsToRequest(SettingsRequest request) {
        Collection<TLRPC.User> values = MessagesController.getInstance().getUsers().values();
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
        for (int i = 0; i < StickersQuery.getStickersSetTypesCount(); i++) {
            addStickerSetToRequest(StickersQuery.getStickerSets(i), request);
        }

        addStickerSetToRequest(StickersQuery.newStickerSets, request);

        ArrayList<TLRPC.StickerSetCovered> featuredStickerSets = StickersQuery.getFeaturedStickerSetsUnfiltered();
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
        if(settingsResponse.access == null || !settingsResponse.access.isValid()) {
            return;
        }

        ConcurrentHashMap<Long, Boolean> allowedDialogs = MessagesController.getInstance().allowedDialogs;
        allowedDialogs.clear();

        appendAllowedDialogs(allowedDialogs, settingsResponse.access.channels);
        appendAllowedDialogs(allowedDialogs, settingsResponse.access.groups);
        appendAllowedDialogs(allowedDialogs, settingsResponse.access.users);

        if(settingsResponse.access.bots != null) {
            ConcurrentHashMap<Long, Boolean> allowedBots = MessagesController.getInstance().allowedBots;
            allowedBots.clear();
            appendAllowedDialogs(allowedBots, settingsResponse.access.bots);
        }

        if(settingsResponse.access.stickers != null) {
            StickersQuery.allowedStickerSets.clear();
            appendAllowedDialogs(StickersQuery.allowedStickerSets, settingsResponse.access.stickers);
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

        NotificationCenter.getInstance().postNotificationName(NotificationCenter.filterDialogsReady);
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
        String json = preferences.getString("settings", null);
        if (json == null) {
            return null;
        }
        return new Gson().fromJson(json, SettingsResponse.class);
    }

    private void saveToCache(@NonNull SettingsResponse settings) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(this.getClass().getCanonicalName(), Activity.MODE_PRIVATE);
        String json = new Gson().toJson(settings);
        preferences.edit().putString("settings", json).apply();
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
        addDialogsToRequest(request, MessagesController.getInstance().dialogs);
        addDialogsToRequest(request, MessagesController.getInstance().dialogsForward);
        addDialogsToRequest(request, MessagesController.getInstance().dialogsGroupsOnly);
        addDialogsToRequest(request, MessagesController.getInstance().dialogsServerOnly);

        if (additionalDialogId != 0) {
            addDialogToRequest(additionalDialogId, request);
            additionalDialogId = 0;
        }
    }

    private void addDialogsToRequest(@NonNull SettingsRequest request, ArrayList<TLRPC.TL_dialog> dialogs) {
        for (TLRPC.TL_dialog dlg : dialogs) {
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
                chat = MessagesController.getInstance().getChat(lower_id);
            } else {
                if (lower_id < 0) {
                    chat = MessagesController.getInstance().getChat(-lower_id);
                    if (chat != null && chat.migrated_to != null) {
                        TLRPC.Chat chat2 = MessagesController.getInstance().getChat(chat.migrated_to.channel_id);
                        if (chat2 != null) {
                            chat = chat2;
                        }
                    }
                } else {
                    user = MessagesController.getInstance().getUser(lower_id);
                }
            }
        } else {
            TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance().getEncryptedChat(high_id);
            if (encryptedChat != null) {
                user = MessagesController.getInstance().getUser(encryptedChat.user_id);
            }
        }

        SettingsRequest.Row row = new SettingsRequest.Row();
        row.id = currentDialogId;
        if (chat != null) {
            row.title = chat.title;
            row.userName = chat.username;

            if (chat instanceof TLRPC.TL_channel) {
                request.addChannel(row);
            } else {
                request.addGroup(row);
            }
        } else if (user != null) {
            if (!user.self) {
                row.id = user.id;
                row.title = user.first_name + " " + user.last_name;
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
