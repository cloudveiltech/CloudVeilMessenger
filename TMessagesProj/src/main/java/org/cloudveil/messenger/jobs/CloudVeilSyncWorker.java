package org.cloudveil.messenger.jobs;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.os.Build;
import android.os.PowerManager;

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
import org.cloudveil.messenger.api.model.NetworkHelper;
import org.cloudveil.messenger.api.model.request.SettingsRequest;
import org.cloudveil.messenger.api.model.response.SettingsResponse;
import org.cloudveil.messenger.api.service.holder.ServiceClientHolders;
import org.cloudveil.messenger.util.CloudVeilDialogHelper;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.exceptions.Exceptions;
import io.sentry.Sentry;
import io.sentry.protocol.User;

/**
 * Created by Dmitriy on 05.02.2018.
 */

public class CloudVeilSyncWorker extends Worker {
    private static final String EXTRA_ADDITION_DIALOG_ID = "extra_dialog_id";
    private static final String EXTRA_ACCOUNT_NUMBER = "extra_account_number";
    private static final long CACHE_TIMEOUT_MS = 30000;

    static Handler mainLooperHandler;

    private Disposable subscription;
    private long additionalDialogId = 0;
    private static boolean firstCall = true;
    private int accountNumber = 0;
    private static long lastServerCallTime = 0;
    private static SettingsRequest cachedRequest;

    public CloudVeilSyncWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        mainLooperHandler = new Handler(context.getMainLooper());
    }

    public static void startDataChecking(int accountNum, @Nullable Context context) {
        Sentry.addBreadcrumb("CloudVeilSyncWorker: startDataChecking called for account: " + accountNum + "(no dialogId)");
        if (context == null) {
            Sentry.addBreadcrumb("CloudVeilSyncWorker: startDataChecking cancelled; null context");
            return;
        }
        //  Prevent sync while device is idle
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isDeviceIdleMode()) {
            FileLog.w("CloudVeilSyncWorker: Device is in idle mode. Skipping sync.");
            Sentry.addBreadcrumb("CloudVeil sync skipped due to idle mode");
            return;
        }
        Sentry.addBreadcrumb("CloudVeilSyncWorker: startDataChecking passed idle mode check");
        OneTimeWorkRequest.Builder requestBuilder = WorkerHelper.getOneTimeWorkRequestNoRestrictions(CloudVeilSyncWorker.class);
        Data params = new Data.Builder().
                putInt(EXTRA_ACCOUNT_NUMBER, accountNum).
                build();
        requestBuilder = requestBuilder.setInputData(params);
        Sentry.addBreadcrumb("CloudVeilSyncWorker: Enqueuing work request");
        //WorkManager.getInstance(context).pruneWork();
        WorkManager.getInstance(context).enqueueUniqueWork(CloudVeilSyncWorker.class.getName(), ExistingWorkPolicy.REPLACE, requestBuilder.build());
        Sentry.addBreadcrumb("CloudVeilSyncWorker: startDataChecking done");
    }

    public static void startDataChecking(int accountNum, long dialogId, @Nullable Context context) {
        Sentry.addBreadcrumb("CloudVeilSyncWorker: startDataChecking called for account: " + accountNum + ", dialogId: " + dialogId);
        if (context == null) {
            Sentry.addBreadcrumb("CloudVeilSyncWorker: startDataChecking cancelled; null context");
            return;
        }
        //  Prevent sync while device is idle
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isDeviceIdleMode()) {
            FileLog.w("CloudVeilSyncWorker: Device is in idle mode. Skipping sync.");
            Sentry.addBreadcrumb("CloudVeil sync skipped due to idle mode");
            return;
        }
        Sentry.addBreadcrumb("CloudVeilSyncWorker: startDataChecking passed idle mode check");
        OneTimeWorkRequest.Builder requestBuilder = WorkerHelper.getOneTimeWorkRequestWithNetwork(CloudVeilSyncWorker.class);
        Data params = new Data.Builder().
                putInt(EXTRA_ACCOUNT_NUMBER, accountNum).
                putLong(EXTRA_ADDITION_DIALOG_ID, dialogId).
                build();
        requestBuilder = requestBuilder.setInputData(params);
        Sentry.addBreadcrumb("CloudVeilSyncWorker: Enqueuing work request for dialogId: " + dialogId);
        WorkManager.getInstance(context).enqueueUniqueWork(CloudVeilSyncWorker.class.getName(), ExistingWorkPolicy.KEEP, requestBuilder.build());
        Sentry.addBreadcrumb("CloudVeilSyncWorker: startDataChecking done");
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
            postFilterDialogsReady(accountNumber);
            return;
        }

        TLRPC.User currentUser = userConfig.getCurrentUser();
        if (currentUser == null) {
            postFilterDialogsReady(accountNumber);
            return;
        }

        final SettingsRequest request = new SettingsRequest();
        boolean hasAdditionalDialog = additionalDialogId != 0;

        request.userPhone = currentUser.phone;
        request.userId = currentUser.id;
        request.userNames = collectUserNames(currentUser.username, currentUser.usernames);
        request.clientSessionId = CloudVeilSecuritySettings.getInstallId(accountNumber);

        addDialogsToRequest(request);
        addInlineBotsToRequest(request);
        addStickersToRequest(request);

        if (request.isEmpty()) {
            postFilterDialogsReady(accountNumber);
            return;
        }

        final SettingsResponse cached = loadFromCache(accountNumber);
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
                processResponse(cached, accountNumber);
                firstCall = false;
                cachedResponseCalled = true;
            }
        }

        cachedRequest = request;
        if (cachedResponseCalled && cacheIsFreshEnough) {
            postFilterDialogsReady(accountNumber);
            return;
        }

        lastServerCallTime = System.currentTimeMillis();
        User user = new User();
        user.setId("" + request.userId);
        if (!request.userNames.isEmpty()) {
            user.setUsername(request.userNames.get(0));
        }
        sendDataAndPingServer(user, request, cached);
        postFilterDialogsReady(accountNumber);
    }

    private void sendDataAndPingServer(@NonNull User user, @NonNull SettingsRequest request, SettingsResponse cached) {
        subscription = ServiceClientHolders.getSettingsService().loadSettings(request).
        subscribeOn(Schedulers.io()).
        retryWhen(errors -> errors
            .zipWith(io.reactivex.Observable.range(1, 3), (err, attempt) -> {
                if (err instanceof IOException && attempt < 3) {
                    return attempt;
                } else {
                    throw Exceptions.propagate(err);
                }
            })
            .flatMap(attempt -> {
                long delay = (long) Math.pow(2, attempt); // exponential backoff: 2s, 4s, 8s
                return io.reactivex.Observable.timer(delay, java.util.concurrent.TimeUnit.SECONDS);
            })
        )
        .subscribe(settingsResponse -> {
            saveToCache(settingsResponse);
            processResponse(settingsResponse, accountNumber);
            freeSubscription();
        }, throwable -> {
            if (cached != null) {
                processResponse(cached, accountNumber);
            }
            sendSentryEvent(throwable, user, "Settings sync request failed.");
            freeSubscription();
        });

    }

    private static class CloudVeilSyncException extends RuntimeException {
        public CloudVeilSyncException(String s, Throwable exception) {
            super(s, exception);
        }
    }

    private void sendSentryEvent(Throwable exception, User user, String message) {
        if(!NetworkHelper.hasAnyInternetConnection(getApplicationContext())) {
            return;
        }
/* TODO: Remove, a lot of useless reports here
        Exception wrapped = new CloudVeilSyncException("Can't sync with CloudVeil server: " + message, exception);
        FileLog.e(wrapped);
        Sentry.captureException(wrapped, scope -> {
            scope.setLevel(SentryLevel.FATAL);
            scope.setUser(user);
            NetworkHelper.addNetworkDataToSentry(getApplicationContext(), scope);
        });
        */

    }


    private static void postFilterDialogsReady(int accountNumber) {
        if (mainLooperHandler != null) {
            mainLooperHandler.post(() -> NotificationCenter.getInstance(accountNumber).postNotificationName(NotificationCenter.filterDialogsReady));
        }
    }

    private static ArrayList<String> collectUserNames(String primaryUsername, ArrayList<TLRPC.TL_username> extraUsernames) {
        ArrayList<String> userNames = new ArrayList<>();
        if (extraUsernames != null) {
            for (TLRPC.TL_username un : extraUsernames) {
                if (un != null && !TextUtils.isEmpty(un.username) && !userNames.contains(un.username)) {
                    userNames.add(un.username);
                }
            }
        }
        if (!TextUtils.isEmpty(primaryUsername) && !userNames.contains(primaryUsername)) {
            userNames.add(primaryUsername);
        }
        return userNames;
    }

    private void addInlineBotsToRequest(SettingsRequest request) {
        Collection<TLRPC.User> values = MessagesController.getInstance(accountNumber).getUsers().values();
        for (TLRPC.User user : values) {
            if (user.bot) {
                SettingsRequest.Row row = new SettingsRequest.Row();
                row.id = user.id;

                row.title = user.first_name != null ? user.first_name : user.username;
                row.userNames = collectUserNames(user.username, user.usernames);
                // Inline bots have no dialog; freshness comes only from a prior full-user load.
                row.lastUpdated = getLastUpdateForUser(null, user);

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
        row.isCreatorAdmin = stickerSet.creator;

        ArrayList<String> userNames = new ArrayList<>();
        userNames.add(stickerSet.short_name);
        row.userNames = userNames;

        request.addSticker(row);
    }

    public static void preloadCachedResponse(@NonNull Context context, int accountNumber) {
        SettingsResponse settingsResponse = loadFromCache(accountNumber);
        if(settingsResponse != null) {
            if (mainLooperHandler == null) {
                mainLooperHandler = new Handler(context.getMainLooper());
            }
            processResponse(settingsResponse, accountNumber);
            postFilterDialogsReady(accountNumber);
        }
    }

    private static void processResponse(@NonNull SettingsResponse settingsResponse, int accountNumber) {
        if (settingsResponse == null || settingsResponse.access == null || !settingsResponse.access.isValid()) {
            return;
        }
        CloudVeilDialogHelper.checkDeprecationAlert(accountNumber, settingsResponse.deprecation);

        ConcurrentHashMap<Long, Boolean> allowedDialogs = CloudVeilDialogHelper.getInstance(accountNumber).allowedDialogs;
        // if last response's org is this response's org,
        // keep old peers around even when this response doesn't have them
        // otherwise clear them
        @NonNull
        SettingsResponse.Organization currentOrg = CloudVeilSecuritySettings.getOrganization();
        if (settingsResponse.organization != null
                && currentOrg.id != settingsResponse.organization.id) {
            allowedDialogs.clear();
        }

        appendAllowedDialogs(allowedDialogs, settingsResponse.access.channels);
        appendAllowedDialogs(allowedDialogs, settingsResponse.access.groups);
        appendAllowedDialogs(allowedDialogs, settingsResponse.access.users);

        if (settingsResponse.access.bots != null) {
            // TODO: should this have allowedBots.clear()?
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
        CloudVeilSecuritySettings.setProfilePhotoLimit(settingsResponse.profilePhotoLimit);
        CloudVeilSecuritySettings.setIsProfileVideoDisabled(settingsResponse.disableProfileVideo);
        CloudVeilSecuritySettings.setIsProfileVideoChangeDisabled(settingsResponse.disableProfileVideoChange);
        CloudVeilSecuritySettings.setIsEmojiStatusDisabled(settingsResponse.disableEmojiStatus);
        CloudVeilSecuritySettings.setIsMusicStatusDisabled(settingsResponse.disableMusicStatus);
        CloudVeilSecuritySettings.setIsStarsDisabled(settingsResponse.disableStars);
        CloudVeilSecuritySettings.setIsMiniAppsDisabled(settingsResponse.disableMiniApps);
        CloudVeilSecuritySettings.setIsDisableStories(settingsResponse.disableStories);

        if (settingsResponse.nonblockableBots != null) {
            CloudVeilSecuritySettings.setNonblockableBots(settingsResponse.nonblockableBots);
        }

        CloudVeilSecuritySettings.setOrganization(settingsResponse.organization);

        postFilterDialogsReady(accountNumber);
    }

    private static void appendAllowedDialogs(ConcurrentHashMap<Long, Boolean> allowedDialogs, ArrayList<HashMap<Long, Boolean>> groups) {
        for (HashMap<Long, Boolean> data : groups) {
            Long id = data.keySet().iterator().next();
            Boolean value = data.values().iterator().next();
            allowedDialogs.put(id, value);
        }
    }

    private static SettingsResponse loadFromCache(int accountNumber) {
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences(CloudVeilSyncWorker.class.getCanonicalName(), Activity.MODE_PRIVATE);
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
            TLRPC.Dialog dialog = MessagesController.getInstance(accountNumber).dialogs_dict.get(additionalDialogId);
            addDialogToRequest(additionalDialogId, dialog, request);
            additionalDialogId = 0;
        }
    }

    private void addDialogsToRequest(@NonNull SettingsRequest request, ArrayList<TLRPC.Dialog> dialogs) {
        for (TLRPC.Dialog dlg : dialogs) {
            addDialogToRequest(dlg.id, dlg, request);
        }
    }

    private long getLastUpdateForChat(@Nullable TLRPC.Dialog dialog, @NonNull TLRPC.Chat chat) {
        if (ChatObject.isNotInChat(chat)) {
            return SettingsRequest.Row.LAST_UPDATE_UNTRUSTWORTHY;
        }

        // Telegram-authored, seconds. Drafts are deliberately excluded: a local unsent draft
        // is not evidence that this client has fresh info about the chat itself.
        long lastMessageDateSec = dialog != null ? dialog.last_message_date : 0;

        // loadedFullChats is keyed by positive chat.id and stored in local-clock millis.
        long fullChatLoadedSec = fullLoadSeconds(MessagesController.getInstance(accountNumber).loadedFullChats.get(chat.id, 0));

        long merged = Math.max(lastMessageDateSec, fullChatLoadedSec);
        return merged > 0 ? merged : SettingsRequest.Row.LAST_UPDATE_UNKNOWN;
    }

    private long getLastUpdateForUser(@Nullable TLRPC.Dialog dialog, @NonNull TLRPC.User user) {
        // Users/bots have no "not in chat" concept; there is simply a conversation or not.
        // Inline bots reached via addInlineBotsToRequest pass a null dialog and typically
        // resolve to UNKNOWN unless their full user was loaded at some point.
        long lastMessageDateSec = dialog != null ? dialog.last_message_date : 0;

        long fullUserLoadedSec = fullLoadSeconds(MessagesController.getInstance(accountNumber).loadedFullUsers.get(user.id, 0));

        long merged = Math.max(lastMessageDateSec, fullUserLoadedSec);
        return merged > 0 ? merged : SettingsRequest.Row.LAST_UPDATE_UNKNOWN;
    }

    /**
     * Converts a loadedFull* timestamp (local-clock millis, 0 if never loaded) to Unix seconds,
     * clamped to "now" so a skewed device clock cannot rank this client as falsely authoritative.
     */
    private static long fullLoadSeconds(long loadedAtMs) {
        if (loadedAtMs <= 0) {
            return 0;
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        return Math.min(loadedAtMs / 1000L, nowSec);
    }

    private void addDialogToRequest(long currentDialogId, @Nullable TLRPC.Dialog dialog, @NonNull SettingsRequest request) {
        TLRPC.Chat chat = null;
        TLRPC.ChatFull chatFull = null;
        TLRPC.User user = null;
        boolean isSuperGroup = false;

        TLObject object = CloudVeilDialogHelper.getInstance(accountNumber).getObjectByDialogId(currentDialogId).first;
        if(object instanceof TLRPC.Chat) {
            chat = (TLRPC.Chat) object;
            chatFull = MessagesController.getInstance(accountNumber).getChatFull(chat.id);
            isSuperGroup = chatFull != null && chatFull.migrated_from_chat_id != 0;
        } else {
            user = (TLRPC.User) object;
        }

        if (chat != null) {
            boolean isChannel = ChatObject.isChannel(chat) && !chat.megagroup;
            SettingsRequest.GroupChannelRow row = null;
            if(isChannel) {
                row = new SettingsRequest.GroupChannelRow();
            } else if (isSuperGroup) {
                row = new SettingsRequest.SuperGroupRow();
            } else  {
                row = new SettingsRequest.GroupRow();
            }
            row.title = chat.title;
            row.id = currentDialogId;
            if (chat.creator) { // || chat.adminRights
                row.isCreatorAdmin = true;
            }
            if (chat.forum) {
                row.isForum = true;
            }
            if (chat.restricted || chat.explicit_content) {
                row.isRestricted = true;
            }

            row.userNames = collectUserNames(chat.username, chat.usernames);
            row.isPublic = ChatObject.isPublic(chat);
            row.lastUpdated = getLastUpdateForChat(dialog, chat);
            if (isChannel) {
                request.addChannel(row);
            } else {
                SettingsRequest.GroupRow groupRow = (SettingsRequest.GroupRow)row;
                groupRow.isMegagroup = chat.megagroup;
                if (groupRow instanceof SettingsRequest.SuperGroupRow) {
                    SettingsRequest.SuperGroupRow superGroupRow = (SettingsRequest.SuperGroupRow)groupRow;
                    superGroupRow.migratedFromTelegramId = chatFull.migrated_from_chat_id;
                }
                request.addGroup(groupRow);
            }
        } else if (user != null) {
            SettingsRequest.Row row = new SettingsRequest.Row();
            if (!user.self) {
                row.id = user.id;
                row.title = "";
                if (user.bot_can_edit) {
                    row.isCreatorAdmin = true;
                }
                if (user.bot_forum_view) {
                    row.isForum = true;
                }
                if (user.restricted || user.explicit_content) {
                    row.isRestricted = true;
                }
                if (user.first_name != null) {
                    row.title = user.first_name;
                }
                if (user.last_name != null) {
                    if (!TextUtils.isEmpty(row.title)) {
                        row.title += " ";
                    }
                    row.title += user.last_name;
                }

                row.userNames = collectUserNames(user.username, user.usernames);
                row.lastUpdated = getLastUpdateForUser(dialog, user);

                if (user.bot) {
                    request.addBot(row);
                } else {
                    request.addUser(row);
                }
            }
        }
    }
}
