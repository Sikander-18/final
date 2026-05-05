package com.example.master2;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.example.master2.models.DailyUsageLimit;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class DailyUsageLimitService extends Service {
    private static final String TAG = "DailyUsageLimitService";
    private static final String PREF_NAME = "daily_usage_limits";
    private static final long CHECK_INTERVAL = 30000; // Check every 30 seconds

    private DatabaseReference limitsRef;
    private DatabaseReference usageRef;
    private String myDeviceId;
    private DailyUsageLimit currentLimits;
    private Handler limitHandler;
    private Runnable limitRunnable;
    private SharedPreferences blockedAppsPrefs;

    // Track current session usage
    private Map<String, Long> sessionUsage = new HashMap<>();
    private long sessionStartTime;
    private String currentApp = "";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DailyUsageLimitService created");

        myDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        blockedAppsPrefs = getSharedPreferences("blocked_apps", Context.MODE_PRIVATE);

        // Initialize Firebase references
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        limitsRef = database.getReference("daily_usage_limits").child(myDeviceId);
        usageRef = database.getReference("usage_snapshots").child(myDeviceId).child("latest");

        // Load current limits
        loadDailyLimits();

        // Start periodic checking
        startLimitMonitoring();

        Log.d(TAG, "DailyUsageLimitService initialized for device: " + myDeviceId);
        Log.d(TAG, "ℹ️ Note: Automatic midnight resets are handled by DailyTimerResetService");
    }

    private void loadDailyLimits() {
        try {
            limitsRef.addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    try {
                        if (snapshot.exists()) {
                            currentLimits = snapshot.getValue(DailyUsageLimit.class);
                            if (currentLimits != null) {
                                Log.d(TAG, "Loaded daily limits for device: " + currentLimits.deviceName);

                                // Check if limits need to be reset for new day
                                // NOTE: The DailyTimerResetService handles automatic midnight resets,
                                // but we still check here in case the service missed it or app was opened first
                                try {
                                    if (currentLimits.needsReset()) {
                                        Log.d(TAG, "⚠️ New day detected, resetting daily usage (backup check)");
                                        currentLimits.resetDailyUsage();
                                        limitsRef.setValue(currentLimits);
                                    } else {
                                        Log.d(TAG, "✅ Daily usage limits are current (no reset needed)");
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error resetting daily usage: " + e.getMessage());
                                }

                                // Start monitoring current usage
                                try {
                                    monitorCurrentUsage();
                                } catch (Exception e) {
                                    Log.e(TAG, "Error starting usage monitoring: " + e.getMessage());
                                }
                            }
                        } else {
                            Log.d(TAG, "No daily limits found for device: " + myDeviceId);
                            try {
                                // Create default limits
                                currentLimits = new DailyUsageLimit(myDeviceId, ChildAppUtils.getChildDeviceName());
                                limitsRef.setValue(currentLimits);
                            } catch (Exception e) {
                                Log.e(TAG, "Error creating default limits: " + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing daily limits data: " + e.getMessage());
                    }
                }

                @Override
                public void onCancelled(DatabaseError error) {
                    Log.e(TAG, "Failed to load daily limits: " + error.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to set up daily limits listener: " + e.getMessage());
        }
    }

    private void monitorCurrentUsage() {
        usageRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists() && currentLimits != null) {
                    updateUsageFromSnapshot(snapshot);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to monitor usage: " + error.getMessage());
            }
        });
    }

    private void updateUsageFromSnapshot(DataSnapshot snapshot) {
        if (snapshot.child("dailyApps").exists()) {
            DataSnapshot dailyAppsSnapshot = snapshot.child("dailyApps");
            DataSnapshot latestDay = null;

            // Get the latest day (today)
            for (DataSnapshot day : dailyAppsSnapshot.getChildren()) {
                latestDay = day;
            }

            if (latestDay != null) {
                // Update current usage from today's data
                for (DataSnapshot appSnapshot : latestDay.getChildren()) {
                    com.example.master2.models.AppUsage appUsage = appSnapshot
                            .getValue(com.example.master2.models.AppUsage.class);
                    if (appUsage != null) {
                        String packageName = appUsage.getPackageName();
                        long usageTime = appUsage.getUsageTime();

                        // Update app usage
                        currentLimits.addAppUsage(packageName, usageTime);

                        // Update category usage
                        String category = categorizeApp(appUsage.getAppName());
                        currentLimits.addCategoryUsage(category, usageTime);
                    }
                }

                // Save updated usage to Firebase
                limitsRef.setValue(currentLimits);

                // Check limits and block apps if needed
                checkAndEnforceLimits();
            }
        }
    }

    private void startLimitMonitoring() {
        try {
            limitHandler = new Handler(Looper.getMainLooper());
            limitRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        // SAFETY CHECK: Don't run if service is being destroyed
                        if (limitHandler == null) {
                            Log.d(TAG, "Limit handler is null, stopping monitoring");
                            return;
                        }

                        checkAndEnforceLimits();

                        // Schedule next run only if handler is still valid
                        if (limitHandler != null) {
                            limitHandler.postDelayed(this, CHECK_INTERVAL);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in limit monitoring: " + e.getMessage());
                        // Continue monitoring even if one check fails
                        try {
                            if (limitHandler != null) {
                                limitHandler.postDelayed(this, CHECK_INTERVAL * 2); // Wait longer on error
                            }
                        } catch (Exception retryE) {
                            Log.e(TAG, "Failed to schedule retry: " + retryE.getMessage());
                        }
                    }
                }
            };

            if (limitHandler != null) {
                limitHandler.post(limitRunnable);
                Log.d(TAG, "Limit monitoring started");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start limit monitoring: " + e.getMessage());
        }
    }

    private void checkAndEnforceLimits() {
        if (currentLimits == null)
            return;

        Log.d(TAG, "Checking daily usage limits...");

        // Check total screen time limit
        if (currentLimits.isTotalLimitReached()) {
            Log.d(TAG, "Total screen time limit reached!");
            blockAllApps("Daily screen time limit reached");
            return;
        }

        // Check category limits
        for (Map.Entry<String, Long> entry : currentLimits.categoryLimits.entrySet()) {
            String category = entry.getKey();
            if (currentLimits.isCategoryLimitReached(category)) {
                Log.d(TAG, "Category limit reached: " + category);
                blockAppsInCategory(category);
            }
        }

        // Check individual app limits
        for (Map.Entry<String, Long> entry : currentLimits.appLimits.entrySet()) {
            String packageName = entry.getKey();
            if (currentLimits.isAppLimitReached(packageName)) {
                Log.d(TAG, "App limit reached: " + packageName);
                blockApp(packageName);
            }
        }
    }

    private void blockAllApps(String reason) {
        if (!currentLimits.autoBlockWhenLimitReached)
            return;

        Log.d(TAG, "Limit reached: " + reason);

        // 🚫 DISABLED: No longer blocking apps
        /*
         * Log.d(TAG, "Blocking all apps: " + reason);
         * SharedPreferences.Editor editor = blockedAppsPrefs.edit();
         * String[] commonApps = {
         * "com.instagram.android", "com.snapchat.android", "com.tiktok.android",
         * "com.facebook.katana", "com.twitter.android", "com.whatsapp",
         * "com.spotify.music", "com.netflix.mediaclient", "com.youtube.android",
         * "com.reddit.frontpage", "com.discord"
         * };
         * for (String app : commonApps) {
         * editor.putBoolean(app, true);
         * }
         * editor.apply();
         * Intent intent = new Intent("com.example.master2.DAILY_LIMIT_REACHED");
         * intent.putExtra("reason", reason);
         * sendBroadcast(intent);
         */

        // 🔔 SHOW PERMANENT NOTIFICATION instead
        showPermanentLimitReachedNotification("Daily Screen Time Limit Reached",
                "Your daily screen time limit has been reached. Apps remain accessible.");
    }

    private void blockAppsInCategory(String category) {
        if (!currentLimits.autoBlockWhenLimitReached)
            return;

        Log.d(TAG, "Category limit reached: " + category);

        // 🚫 DISABLED: No longer blocking apps
        /*
         * Log.d(TAG, "Blocking apps in category: " + category);
         * Map<String, String[]> categoryApps = new HashMap<>();
         * // ... (omitted for brevity)
         * String[] appsToBlock = categoryApps.get(category);
         * if (appsToBlock != null) {
         * SharedPreferences.Editor editor = blockedAppsPrefs.edit();
         * for (String app : appsToBlock) {
         * editor.putBoolean(app, true);
         * }
         * editor.apply();
         * }
         */

        // 🔔 SHOW PERMANENT NOTIFICATION instead
        showPermanentLimitReachedNotification("Category Limit Reached: " + category,
                "You've reached your limit for " + category + " apps. Apps remain accessible.");
    }

    private void blockApp(String packageName) {
        if (!currentLimits.autoBlockWhenLimitReached)
            return;

        String appName = getAppName(packageName);
        Log.d(TAG, "App limit reached: " + appName);

        // 🚫 DISABLED: No longer blocking apps
        /*
         * Log.d(TAG, "Blocking app: " + packageName);
         * SharedPreferences.Editor editor = blockedAppsPrefs.edit();
         * editor.putBoolean(packageName, true);
         * editor.apply();
         */

        // 🔔 SHOW PERMANENT NOTIFICATION instead
        showPermanentLimitReachedNotification("App Limit Reached: " + appName,
                "You've reached your daily limit for " + appName + ". App remains accessible.");
    }

    /**
     * 🔥 SHOW PERMANENT LIMIT REACHED NOTIFICATION
     * This notification CANNOT be dismissed and stays until midnight
     */
    private void showPermanentLimitReachedNotification(String title, String message) {
        try {
            Log.d(TAG, "🔔 Creating permanent limit notification: " + title);

            // Create notification channel if needed
            createPermanentNotificationChannel();

            Intent intent = new Intent(this, ChildDashboardActivity.class);
            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                    this, 0, intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);

            android.app.Notification.Builder builder;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                builder = new android.app.Notification.Builder(this, "permanent_limit_channel");
            } else {
                builder = new android.app.Notification.Builder(this);
            }

            builder.setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true) // 🔥 PERMANENT
                    .setAutoCancel(false) // 🔥 NOT DISMISSIBLE
                    .setStyle(new android.app.Notification.BigTextStyle()
                            .bigText(message + "\n\nThis notification will remain until midnight."))
                    .setPriority(android.app.Notification.PRIORITY_HIGH);

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                builder.setColor(android.graphics.Color.RED)
                        .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                        .setCategory(android.app.Notification.CATEGORY_ALARM);
            }

            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                // Use a unique ID for this notification
                manager.notify(99999, builder.build());
                Log.d(TAG, "✅ Permanent limit notification displayed");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing permanent notification: " + e.getMessage());
        }
    }

    private void createPermanentNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.NotificationChannel channel = new android.app.NotificationChannel(
                    "permanent_limit_channel",
                    "Daily Limit Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Permanent notifications when daily limits are reached");
            channel.enableLights(true);
            channel.setLightColor(android.graphics.Color.RED);
            channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
            channel.setBypassDnd(true);

            android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private String categorizeApp(String appName) {
        if (appName == null)
            return "Others";

        String lowerName = appName.toLowerCase();

        if (lowerName.contains("instagram") || lowerName.contains("snapchat") ||
                lowerName.contains("facebook") || lowerName.contains("twitter") ||
                lowerName.contains("whatsapp") || lowerName.contains("discord")) {
            return "Social";
        } else if (lowerName.contains("game") || lowerName.contains("fortnite") ||
                lowerName.contains("minecraft") || lowerName.contains("roblox")) {
            return "Games";
        } else if (lowerName.contains("youtube") || lowerName.contains("netflix") ||
                lowerName.contains("spotify") || lowerName.contains("reddit")) {
            return "Entertainment";
        } else {
            return "Others";
        }
    }

    private String getAppName(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    // Method to update current app usage in real-time
    public void updateCurrentAppUsage(String packageName, long usageTime) {
        if (currentLimits != null && packageName != null) {
            currentLimits.addAppUsage(packageName, usageTime);

            String category = categorizeApp(getAppName(packageName));
            currentLimits.addCategoryUsage(category, usageTime);

            // Save to Firebase
            limitsRef.setValue(currentLimits);

            // Check limits immediately
            checkAndEnforceLimits();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Log.d(TAG, "DailyUsageLimitService started");
            return START_NOT_STICKY; // CRITICAL FIX: Changed from START_STICKY to prevent crash loops
        } catch (Exception e) {
            Log.e(TAG, "Critical error in DailyUsageLimitService onStartCommand: " + e.getMessage());
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🛑 DailyUsageLimitService destroyed");

        try {
            // Stop all monitoring
            if (limitHandler != null && limitRunnable != null) {
                limitHandler.removeCallbacks(limitRunnable);
                Log.d(TAG, "📴 Limit monitoring stopped");
            }

            // Remove Firebase listeners
            try {
                if (limitsRef != null) {
                    limitsRef.removeEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot snapshot) {
                        }

                        @Override
                        public void onCancelled(DatabaseError error) {
                        }
                    });
                }

                if (usageRef != null) {
                    usageRef.removeEventListener(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot snapshot) {
                        }

                        @Override
                        public void onCancelled(DatabaseError error) {
                        }
                    });
                }
                Log.d(TAG, "🔥 Firebase listeners removed");
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove Firebase listeners: " + e.getMessage());
            }

            // Clear references
            limitHandler = null;
            limitRunnable = null;
            currentLimits = null;

            Log.d(TAG, "✅ DailyUsageLimitService cleanup completed");
        } catch (Exception e) {
            Log.e(TAG, "Error during service cleanup: " + e.getMessage());
        }
    }
}