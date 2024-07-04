package org.cloudveil.messenger.jobs;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.gson.Gson;

import org.cloudveil.messenger.CloudVeilSecuritySettings;
import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.api.model.response.SettingsResponse;
import org.cloudveil.messenger.api.service.MessengerHttpInterface;
import org.cloudveil.messenger.api.service.holder.ServiceClientHolders;
import org.cloudveil.messenger.util.CloudVeilDialogHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.protocol.User;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class CloudVeilSyncWorker extends Worker {
    private static final String EXTRA_ADDITION_DIALOG_ID = "extra_dialog_id";
    private static final String EXTRA_ACCOUNT_NUMBER = "extra_account_number";
    private static final long CACHE_TIMEOUT_MS = 30000;

    Handler mainLooperHandler;

    private Disposable subscription;
    private long additionalDialogId = 0;
    private static boolean firstCall = true;
    private int accountNumber = 0;
    private static long lastServerCallTime = 0;
    private static SettingsRequest cachedRequest;

    public CloudVeilSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        this.mainLooperHandler = new Handler(context.getMainLooper());
    }

    public static void startDataChecking(int accountNum, @Nullable Context context) {
        if (context == null) {
            return;
        }
        OneTimeWorkRequest.Builder requestBuilder = WorkerHelper.getOneTimeWorkRequestWithNetwork(CloudVeilSyncWorker.class);
        Data params = new Data.Builder().
                putInt(EXTRA_ACCOUNT_NUMBER, accountNum).
                build();
        requestBuilder = requestBuilder.setInputData(params);

        WorkManager.getInstance(context).pruneWork();
        WorkManager.getInstance(context).enqueueUniqueWork(CloudVeilSyncWorker.class.getName(), ExistingWorkPolicy.REPLACE, requestBuilder.build());
    }

    public static void startDataChecking(int accountNum, long dialogId, @Nullable Context context) {
        if (context == null) {
            return;
        }
        OneTimeWorkRequest.Builder requestBuilder = WorkerHelper.getOneTimeWorkRequestWithNetwork(CloudVeilSyncWorker.class);
        Data params = new Data.Builder().
                putInt(EXTRA_ACCOUNT_NUMBER, accountNum).
                putLong(EXTRA_ADDITION_DIALOG_ID, dialogId).
                build();
        requestBuilder = requestBuilder.setInputData(params);
        WorkManager.getInstance(context).enqueueUniqueWork(CloudVeilSyncWorker.class.getName(), ExistingWorkPolicy.KEEP, requestBuilder.build());
    }


    @NonNull
    @Override
    public Result doWork() {
        Data inputData = getInputData();
        if(inputData == null) {
            return Result.failure();
        }
        long additionalId = inputData.getLong(EXTRA_ADDITION_DIALOG_ID, 0);
        if (additionalId != 0) {
            additionalDialogId = additionalId;
        }

        accountNumber = inputData.getInt(EXTRA_ACCOUNT_NUMBER, 0);
        sendDataCheckRequest();
        return Result.success();
    }

    private void sendDataCheckRequest() {
        UserConfig userConfig = UserConfig.getInstance(accountNumber);
        if (userConfig == null || !userConfig.isConfigLoaded()) {
            postFilterDialogsReady();
            return;
        }

        TLRPC.User currentUser = userConfig.getCurrentUser();
        if (currentUser == null) {
            postFilterDialogsReady();
            return;
        }

        final SettingsRequest request = new SettingsRequest();
        boolean hasAdditionalDialog = additionalDialogId != 0;

        request.userPhone = currentUser.phone;
        request.userId = currentUser.id;
        request.userName = currentUser.username;
        request.clientSessionId = CloudVeilSecuritySettings.getInstallId(accountNumber);

        addDialogsToRequest(request);
        addInlineBotsToRequest(request);
        addStickersToRequest(request);

        if (request.isEmpty()) {
            postFilterDialogsReady();
            return;
        }

        final SettingsResponse cached = loadFromCache();
        long now = System.currentTimeMillis();
        boolean cacheIsFreshEnough = (now-lastServerCallTime) < CACHE_TIMEOUT_MS;
        boolean cachedResponseCalled = false;
        if(cacheIsFreshEnough) {
            Log.d("CloudVeil", "cached response");
        }
        boolean forceCache = firstCall || cacheIsFreshEnough;
        if(request.equals(cachedRequest)) {
            Log.d("CloudVeil", "requests are equal");
            if (cached != null && forceCache && !hasAdditionalDialog) {
                processResponse(cached);
                firstCall = false;
                cachedResponseCalled = true;
            }
        }
        cachedRequest = request;
        if (cachedResponseCalled && cacheIsFreshEnough) {
            postFilterDialogsReady();
            return;
        }

        CloudVeilDialogHelper.getInstance(accountNumber).loadNotificationChannelDialog(request);

        lastServerCallTime = System.currentTimeMillis();
        User user = new User();
        user.setId("" + request.userId);
        user.setUsername(request.userName);
        sendDataAndPingServer(user, request, cached);
        postFilterDialogsReady();
    }

    private void sendDataAndPingServer(@NonNull User user, @NonNull SettingsRequest request, SettingsResponse cached) {
        subscription = ServiceClientHolders.getSettingsService().loadSettings(request).
                subscribeOn(Schedulers.io()).
                subscribe(settingsResponse -> {
                    saveToCache(settingsResponse);
                    processResponse(settingsResponse);
                    freeSubscription();
                }, throwable -> {
                    if (cached != null) {
                        processResponse(cached);
                    }
                    sendSentryEvent(throwable, user, "Settings sync request failed.");
                    throwable.printStackTrace();
                    freeSubscription();
                    subscription = ServiceClientHolders.getSettingsService().ping().
                            subscribeOn(Schedulers.io()).
                            subscribe(response -> {
                                freeSubscription();
                                String responseString = response.string();
                                if(!responseString.equalsIgnoreCase(MessengerHttpInterface.PING_SUCCCESS)) {
                                    sendSentryEvent(new Exception("Ping failed! " + responseString), user, "Ping request failed " + responseString);
                                }
                            }, throwable1 -> {
                                sendSentryEvent(throwable1, user, "Ping request failed.");
                                throwable1.printStackTrace();
                                freeSubscription();
                            });
                });

    }

    private void sendSentryEvent(Throwable exception, User user, String message) {
        Exception wrapped = new RuntimeException("Can't sync with CloudVeil server: " + message, exception);
        Sentry.captureException(wrapped, scope -> {
            scope.setLevel(SentryLevel.FATAL);
            scope.setUser(user);
        });
    }

    private void postFilterDialogsReady() {
        mainLooperHandler.post(() -> NotificationCenter.getInstance(accountNumber).postNotificationName(NotificationCenter.filterDialogsReady));
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
        if (settingsResponse == null || settingsResponse.access == null || !settingsResponse.access.isValid()) {
            return;
        }

        ConcurrentHashMap<Long, Boolean> allowedDialogs = CloudVeilDialogHelper.getInstance(accountNumber).allowedDialogs;
        allowedDialogs.clear();

        appendAllowedDialogs(allowedDialogs, settingsResponse.access.channels);
        appendAllowedDialogs(allowedDialogs, settingsResponse.access.groups);
        appendAllowedDialogs(allowedDialogs, settingsResponse.access.users);

        if (settingsResponse.access.bots != null) {
            ConcurrentHashMap<Long, Boolean> allowedBots = CloudVeilDialogHelper.getInstance(accountNumber).allowedBots;
            allowedBots.clear();
            appendAllowedDialogs(allowedBots, settingsResponse.access.bots);
        }

        if (settingsResponse.access.stickers != null) {
            MediaDataController.getInstance(accountNumber).allowedStickerSets.clear();
            appendAllowedDialogs(MediaDataController.getInstance(accountNumber).allowedStickerSets, settingsResponse.access.stickers);
        }

        CloudVeilSecuritySettings.setDisableSecretChat(!settingsResponse.secretChat);
        CloudVeilSecuritySettings.setMinSecretChatTtl(settingsResponse.secretChatMinimumLength);

        CloudVeilSecuritySettings.setLockDisableOthersBio(settingsResponse.disableBio);
        CloudVeilSecuritySettings.setLockDisableOwnBio(settingsResponse.disableBioChange);
        CloudVeilSecuritySettings.setLockDisableOwnPhoto(settingsResponse.disableProfilePhotoChange);
        CloudVeilSecuritySettings.setLockDisableOthersPhoto(settingsResponse.disableProfilePhoto);
        CloudVeilSecuritySettings.setDisabledVideoInlineRecording(!settingsResponse.inputToggleVoiceVideo);
        CloudVeilSecuritySettings.setLockDisableStickers(settingsResponse.disableStickers);
        CloudVeilSecuritySettings.setManageUsers(settingsResponse.manageUsers);
        CloudVeilSecuritySettings.setBlockedImageUrl(settingsResponse.disableStickersImage);
        CloudVeilSecuritySettings.setProfilePhotoLimit(settingsResponse.profilePhotoLimit);
        CloudVeilSecuritySettings.setIsProfileVideoDisabled(settingsResponse.disableProfileVideo);
        CloudVeilSecuritySettings.setIsProfileVideoChangeDisabled(settingsResponse.disableProfileVideoChange);
        CloudVeilSecuritySettings.setIsEmojiStatusDisabled(settingsResponse.disableEmojiStatus);
        CloudVeilSecuritySettings.setIsDisableStories(settingsResponse.disableStories);

        CloudVeilSecuritySettings.setOrganization(settingsResponse.organization);
        
        if(settingsResponse.googleMapsKeys != null) {
            CloudVeilSecuritySettings.setGoogleMapsKey(settingsResponse.googleMapsKeys.android);
        }

        postFilterDialogsReady();
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
        TLRPC.Chat chat = null;
        TLRPC.User user = null;

        TLObject object = CloudVeilDialogHelper.getInstance(accountNumber).getObjectByDialogId(currentDialogId).first;
        if(object instanceof TLRPC.Chat) {
            chat = (TLRPC.Chat) object;
        } else {
            user = (TLRPC.User) object;
        }

        if (chat != null) {
            boolean isChannel = ChatObject.isChannel(chat) && !chat.megagroup;
            SettingsRequest.GroupChannelRow row = null;
            if(isChannel) {
                row = new SettingsRequest.GroupChannelRow();
            } else {
                row = new SettingsRequest.GroupRow();
            }
            row.title = chat.title;
            row.userName = chat.username;
            row.id = currentDialogId;

            row.isPublic = (chat.flags & TLRPC.CHAT_FLAG_IS_PUBLIC) != 0;
            if (isChannel) {
                request.addChannel(row);
            } else {
                SettingsRequest.GroupRow groupRow = (SettingsRequest.GroupRow)row;
                groupRow.isMegagroup = chat.megagroup;
                request.addGroup(groupRow);
            }
        } else if (user != null) {
            SettingsRequest.Row row = new SettingsRequest.Row();
            if (!user.self) {
                row.id = user.id;
                row.title = "";
                if (user.first_name != null) {
                    row.title = user.first_name;
                }
                if (user.last_name != null) {
                    if (!TextUtils.isEmpty(row.title)) {
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
