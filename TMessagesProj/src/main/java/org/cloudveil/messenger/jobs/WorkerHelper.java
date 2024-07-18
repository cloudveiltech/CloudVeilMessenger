package org.cloudveil.messenger.jobs;

import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;

import java.util.concurrent.TimeUnit;

public class WorkerHelper {
    public static OneTimeWorkRequest.Builder getOneTimeWorkRequestWithNetwork(Class c) {
        return new OneTimeWorkRequest.Builder(c)
                .setConstraints(
                        new Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                );
    }
}
