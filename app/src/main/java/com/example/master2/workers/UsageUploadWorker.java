package com.example.master2.workers;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.example.master2.SessionManager;
import com.example.master2.utils.SUsageDataManager;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WorkManager Worker for reliable background usage data uploads.
 * This worker is guaranteed to run even if the app is killed by Android.
 * 
 * Features:
 * - Uploads all 7 days of usage data
 * - Updates heartbeat timestamp
 * - Survives app restarts
 * - Battery optimized by WorkManager
 */
public class UsageUploadWorker extends Worker {

    private static final String TAG = "UsageUploadWorker";

    public UsageUploadWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Log.d(TAG, "🚀 UsageUploadWorker starting...");

        try {
            // Get device ID from session
            SessionManager sessionManager = new SessionManager(getApplicationContext());
            String deviceId = sessionManager.getChildDeviceId();

            if (deviceId == null || deviceId.isEmpty()) {
                Log.w(TAG, "⚠️ No device ID found - skipping upload");
                return Result.success(); // Don't retry, just skip
            }

            Log.d(TAG, "📱 Uploading usage data for device: " + deviceId);

            // Update heartbeat first (even if upload fails, heartbeat shows we're alive)
            updateHeartbeat(deviceId);

            // Upload usage data
            boolean uploadSuccess = uploadUsageData(deviceId);

            if (uploadSuccess) {
                Log.d(TAG, "✅ UsageUploadWorker completed successfully");
                return Result.success();
            } else {
                Log.w(TAG, "⚠️ Upload failed - will retry");
                return Result.retry(); // WorkManager will retry with exponential backoff
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in UsageUploadWorker: " + e.getMessage(), e);
            return Result.retry();
        }
    }

    /**
     * Update heartbeat timestamp in Firebase
     * This proves the child device is alive and running
     */
    private void updateHeartbeat(String deviceId) {
        try {
            DatabaseReference heartbeatRef = FirebaseDatabase.getInstance()
                    .getReference("susage_data")
                    .child(deviceId)
                    .child("heartbeat");

            heartbeatRef.child("lastHeartbeat").setValue(System.currentTimeMillis());
            heartbeatRef.child("deviceAlive").setValue(true);

            Log.d(TAG, "💓 Heartbeat updated");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to update heartbeat: " + e.getMessage());
        }
    }

    /**
     * Upload usage data to Firebase using SUsageDataManager
     * Returns true if successful, false otherwise
     */
    private boolean uploadUsageData(String deviceId) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);

        try {
            SUsageDataManager usageManager = SUsageDataManager.getInstance(getApplicationContext());

            usageManager.uploadToFirebase(deviceId, new SUsageDataManager.OnUploadCompleteListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "✅ Usage data uploaded successfully");
                    success.set(true);
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "❌ Upload error: " + error);
                    success.set(false);
                    latch.countDown();
                }
            });

            // Wait for upload to complete (max 30 seconds)
            boolean completed = latch.await(30, TimeUnit.SECONDS);

            if (!completed) {
                Log.w(TAG, "⏰ Upload timed out after 30 seconds");
                return false;
            }

            return success.get();

        } catch (Exception e) {
            Log.e(TAG, "❌ Exception during upload: " + e.getMessage());
            return false;
        }
    }
}
