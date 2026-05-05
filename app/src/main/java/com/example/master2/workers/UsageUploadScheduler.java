package com.example.master2.workers;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Scheduler for reliable usage data uploads using WorkManager.
 * 
 * WorkManager advantages:
 * - Survives app kills
 * - Survives device reboots
 * - Battery optimized
 * - Guaranteed execution
 * - Automatic retry on failure
 */
public class UsageUploadScheduler {

    private static final String TAG = "UsageUploadScheduler";
    private static final String WORK_NAME = "usage_upload_periodic";

    // Upload interval in minutes - Child uploads every 2 minutes for accuracy
    private static final int UPLOAD_INTERVAL_MINUTES = 2;

    /**
     * Schedule periodic usage uploads.
     * Call this once in ChildDashboardActivity.onCreate()
     */
    public static void schedulePeriodicUpload(Context context) {
        Log.d(TAG, "📅 Scheduling periodic usage upload every " + UPLOAD_INTERVAL_MINUTES + " minutes");

        try {
            // Constraints: require network connection
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();

            // Create periodic work request
            PeriodicWorkRequest uploadRequest = new PeriodicWorkRequest.Builder(
                    UsageUploadWorker.class,
                    UPLOAD_INTERVAL_MINUTES,
                    TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .addTag("usage_upload")
                    .build();

            // Enqueue the work - KEEP existing if already scheduled
            WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                            WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP, // Don't restart if already scheduled
                            uploadRequest);

            Log.d(TAG, "✅ Periodic upload scheduled successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to schedule periodic upload: " + e.getMessage(), e);
        }
    }

    /**
     * Trigger an immediate one-time upload (for manual refresh)
     */
    public static void triggerImmediateUpload(Context context) {
        Log.d(TAG, "⚡ Triggering immediate upload...");

        try {
            androidx.work.OneTimeWorkRequest immediateRequest = new androidx.work.OneTimeWorkRequest.Builder(
                    UsageUploadWorker.class)
                    .addTag("usage_upload_immediate")
                    .build();

            WorkManager.getInstance(context).enqueue(immediateRequest);

            Log.d(TAG, "✅ Immediate upload triggered");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to trigger immediate upload: " + e.getMessage());
        }
    }

    /**
     * Cancel all scheduled uploads (for cleanup/logout)
     */
    public static void cancelAllUploads(Context context) {
        Log.d(TAG, "🛑 Cancelling all scheduled uploads");

        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
            WorkManager.getInstance(context).cancelAllWorkByTag("usage_upload");
            Log.d(TAG, "✅ All uploads cancelled");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to cancel uploads: " + e.getMessage());
        }
    }
}
