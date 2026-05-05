package com.example.master2.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.master2.R;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Service that monitors foreground apps and decrements per-app timers.
 * Timer only runs when the specific app is in foreground.
 * Syncs remaining time to Firebase for parent to see (both app_timers +
 * usage_limiters).
 * Handles timer expiry with notifications (once per 5 active minutes, not
 * spam).
 * Midnight reset restores timers AND updates usage_limiters for parent UI.
 */
public class AppTimerService extends Service {
    private static final String TAG = "AppTimerService";
    private static final String CHANNEL_ID = "app_timer_channel";
    private static final String EXPIRY_CHANNEL_ID = "timer_expiry_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final int EXPIRY_NOTIFICATION_ID_BASE = 3000;
    private static final long CHECK_INTERVAL_MS = 1000; // Check every second

    // 🔔 Expiry notification fires once per this many active-use milliseconds (5
    // minutes)
    private static final long EXPIRY_NOTIF_INTERVAL_MS = 5 * 60 * 1000L;

    // Key to store last reset date in SharedPreferences
    private static final String PREF_LAST_RESET_DATE = "last_timer_reset_date";

    private String deviceId;
    private Handler handler;
    private Runnable timerRunnable;
    private UsageStatsManager usageStatsManager;
    private DatabaseReference timersRef; // app_timers/{deviceId}
    private DatabaseReference limiterRef; // usage_limiters/{deviceId} (parent UI path)
    private ValueEventListener timersListener;
    private Map<String, AppTimer> activeTimers = new HashMap<>();
    private String currentForegroundApp = "";
    private long lastCheckTime = 0;
    private NotificationManager notificationManager;
    private String lastResetDate = "";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "⏱️ AppTimerService created");

        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        handler = new Handler(Looper.getMainLooper());

        createNotificationChannel();
        createExpiryNotificationChannel();

        // Initialize last reset date
        lastResetDate = getSharedPreferences("timer_prefs", MODE_PRIVATE)
                .getString(PREF_LAST_RESET_DATE, "");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "⏱️ AppTimerService started");

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification());

        if (intent != null) {
            deviceId = intent.getStringExtra("deviceId");
        }

        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "No device ID provided");
            stopSelf();
            return START_NOT_STICKY;
        }

        // Firebase references
        timersRef = FirebaseDatabase.getInstance()
                .getReference("app_timers")
                .child(deviceId);
        limiterRef = FirebaseDatabase.getInstance()
                .getReference("usage_limiters")
                .child(deviceId);

        // Listen for timer updates from Firebase
        setupTimerListener();

        // Start monitoring foreground app
        startMonitoring();

        return START_STICKY;
    }

    private void setupTimerListener() {
        timersListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                // ✅ FIX (Bug 2): Clear-and-rebuild so deleted timers are removed from memory
                // Build the set of package names still present in Firebase
                Set<String> firebasePackages = new HashSet<>();

                for (DataSnapshot timerSnap : snapshot.getChildren()) {
                    try {
                        String packageName = timerSnap.child("packageName").getValue(String.class);
                        Long remainingMs = timerSnap.child("remainingTimeMillis").getValue(Long.class);
                        Long dailyLimitMs = timerSnap.child("dailyLimitMillis").getValue(Long.class);
                        Boolean active = timerSnap.child("active").getValue(Boolean.class);

                        // Default daily limit to remaining if not set (legacy support)
                        if (dailyLimitMs == null && remainingMs != null) {
                            dailyLimitMs = remainingMs;
                        }

                        if (packageName != null) {
                            firebasePackages.add(packageName);

                            AppTimer timer = activeTimers.get(packageName);
                            if (timer == null) {
                                timer = new AppTimer();
                                activeTimers.put(packageName, timer);
                            }

                            timer.packageName = packageName;
                            // ⚠️ Only sync remainingTime from server if we don't have a local
                            // in-progress value (prevent Firebase overwriting ticking timer)
                            if (timer.lastSyncTime == 0) {
                                timer.remainingTimeMillis = remainingMs != null ? remainingMs : 0;
                            }
                            timer.dailyLimitMillis = dailyLimitMs != null ? dailyLimitMs : 0;
                            timer.active = active != null && active;
                            timer.key = timerSnap.getKey();
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Error parsing timer: " + e.getMessage());
                    }
                }

                // ✅ FIX (Bug 2): Remove timers that no longer exist in Firebase
                // This stops notifications for timers the parent deleted
                Set<String> toRemove = new HashSet<>();
                for (String pkg : activeTimers.keySet()) {
                    if (!firebasePackages.contains(pkg)) {
                        toRemove.add(pkg);
                    }
                }
                for (String pkg : toRemove) {
                    AppTimer orphan = activeTimers.remove(pkg);
                    if (orphan != null) {
                        cancelExpiryNotification(orphan);
                        Log.d(TAG, "🗑️ Removed deleted timer for: " + pkg + " — notification cancelled");
                    }
                }

                Log.d(TAG, "📥 Active timers in memory: " + activeTimers.size());
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Timer listener cancelled: " + error.getMessage());
            }
        };

        timersRef.addValueEventListener(timersListener);
    }

    private void startMonitoring() {
        lastCheckTime = System.currentTimeMillis();

        timerRunnable = new Runnable() {
            @Override
            public void run() {
                checkAndUpdateTimers();
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };

        handler.post(timerRunnable);
    }

    private void checkAndUpdateTimers() {
        long currentTime = System.currentTimeMillis();
        long elapsedMs = currentTime - lastCheckTime;
        lastCheckTime = currentTime;

        // Check for midnight reset FIRST
        checkMidnightReset();

        // Get current foreground app
        String foregroundApp = getForegroundApp();
        if (foregroundApp == null || foregroundApp.isEmpty()) {
            return;
        }

        // Check if this app has a timer
        AppTimer timer = activeTimers.get(foregroundApp);
        if (timer != null && timer.active) {

            if (timer.remainingTimeMillis > 0) {
                // Decrement timer only if app is in foreground
                timer.remainingTimeMillis -= elapsedMs;

                // Prevent negative time
                if (timer.remainingTimeMillis < 0)
                    timer.remainingTimeMillis = 0;

                Log.d(TAG, "⏱️ Timer for " + foregroundApp + ": " + (timer.remainingTimeMillis / 1000) + "s left");

                if (timer.remainingTimeMillis <= 0) {
                    // ✅ FIX (Bug 1): EXPIRED JUST NOW — mark isActive=false in Firebase
                    timer.remainingTimeMillis = 0;
                    Log.d(TAG, "⏰ Timer expired for: " + foregroundApp);

                    // Show first expiry notification immediately
                    showExpiryNotification(timer);
                    timer.accumulatedActiveMs = 0; // start counting from 0 for re-notify

                    // ✅ FIX (Bug 1): Set active=false so parent UI shows "Expired" not blank
                    syncTimerExpiredToFirebase(timer);
                } else {
                    // Sync to Firebase every 5 seconds
                    if (timer.lastSyncTime == 0 || currentTime - timer.lastSyncTime >= 5000) {
                        timer.lastSyncTime = currentTime;
                        updateTimerInFirebase(timer);
                    }
                }

            } else {
                // ✅ FIX (Bug 3): Already expired — accumulate active-use time
                // Only re-notify every 5 active minutes, NOT every 10 seconds
                timer.accumulatedActiveMs += elapsedMs;

                long intervalsPassed = timer.accumulatedActiveMs / EXPIRY_NOTIF_INTERVAL_MS;
                if (intervalsPassed > timer.lastNotifIntervalCount) {
                    timer.lastNotifIntervalCount = intervalsPassed;
                    showExpiryNotification(timer);
                    Log.d(TAG, "🔔 Re-notifying expiry for " + foregroundApp
                            + " after " + (intervalsPassed * 5) + " active minutes");
                }
            }
        }

        // Track foreground app change for logging
        if (!foregroundApp.equals(currentForegroundApp)) {
            currentForegroundApp = foregroundApp;
        }
    }

    private void checkMidnightReset() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String currentDate = sdf.format(new Date());

        if (!currentDate.equals(lastResetDate)) {
            Log.d(TAG, "🕛 Midnight detected! Resetting timers from " + lastResetDate + " to " + currentDate);

            // It's a new day! Reset all timers
            for (AppTimer timer : activeTimers.values()) {
                if (timer.dailyLimitMillis > 0) {
                    timer.remainingTimeMillis = timer.dailyLimitMillis;
                    timer.active = true; // Re-enable
                    timer.lastSyncTime = 0; // Force sync
                    timer.accumulatedActiveMs = 0; // Reset notification accumulator
                    timer.lastNotifIntervalCount = 0;

                    // Sync to app_timers (child usage) path
                    updateTimerInFirebase(timer);

                    // ✅ FIX: Also reset usage_limiters path so parent UI auto-refreshes
                    syncMidnightResetToLimiter(timer.dailyLimitMillis, currentDate);

                    // Clear expiry notification
                    cancelExpiryNotification(timer);
                }
            }

            // Save new date
            lastResetDate = currentDate;
            getSharedPreferences("timer_prefs", MODE_PRIVATE)
                    .edit()
                    .putString(PREF_LAST_RESET_DATE, lastResetDate)
                    .apply();

            Log.d(TAG, "✅ All timers reset for new day");
        }
    }

    /**
     * Update app_timers/{deviceId}/{key} — the child-side running timer data
     */
    private void updateTimerInFirebase(AppTimer timer) {
        if (timersRef == null || timer.key == null)
            return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("remainingTimeMillis", timer.remainingTimeMillis);
        updates.put("active", timer.active);
        updates.put("expired", timer.remainingTimeMillis <= 0);
        updates.put("lastUpdated", System.currentTimeMillis());

        timersRef.child(timer.key).updateChildren(updates)
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to sync timer: " + e.getMessage()));
    }

    /**
     * ✅ FIX (Bug 1): On expiry, set active=false in both app_timers AND
     * usage_limiters.
     * This makes the parent page show "00:00 – Expired" instead of going blank.
     */
    private void syncTimerExpiredToFirebase(AppTimer timer) {
        if (timersRef == null || timer.key == null)
            return;

        // Update app_timers (child side)
        Map<String, Object> appTimerUpdates = new HashMap<>();
        appTimerUpdates.put("remainingTimeMillis", 0L);
        appTimerUpdates.put("active", false); // ← key fix: parent reads this
        appTimerUpdates.put("expired", true);
        appTimerUpdates.put("lastUpdated", System.currentTimeMillis());
        timersRef.child(timer.key).updateChildren(appTimerUpdates)
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to sync expiry to app_timers: " + e.getMessage()));

        // Update usage_limiters (parent UI reads this)
        if (limiterRef != null) {
            Map<String, Object> limiterUpdates = new HashMap<>();
            limiterUpdates.put("remainingTimeMs", 0L);
            limiterUpdates.put("isActive", false); // ← parent TimerManagementActivity reads isActive
            limiterUpdates.put("expired", true);
            limiterUpdates.put("lastUpdated", System.currentTimeMillis());
            limiterRef.updateChildren(limiterUpdates)
                    .addOnFailureListener(
                            e -> Log.e(TAG, "❌ Failed to sync expiry to usage_limiters: " + e.getMessage()));
        }
    }

    /**
     * ✅ FIX: On midnight reset, update usage_limiters so parent UI auto-shows the
     * fresh timer.
     */
    private void syncMidnightResetToLimiter(long dailyLimitMs, String newDate) {
        if (limiterRef == null)
            return;

        Map<String, Object> updates = new HashMap<>();
        updates.put("remainingTimeMs", dailyLimitMs);
        updates.put("isActive", true);
        updates.put("expired", false);
        updates.put("lastResetDate", newDate);
        updates.put("lastUpdated", System.currentTimeMillis());

        limiterRef.updateChildren(updates)
                .addOnSuccessListener(v -> Log.d(TAG, "✅ usage_limiters reset for new day"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to reset usage_limiters: " + e.getMessage()));
    }

    // ========== NOTIFICATIONS ==========

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "App Timer Service",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Monitors app usage timers");

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void createExpiryNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ✅ FIX (Bug 3): Use DEFAULT_IMPORTANCE (not HIGH) so re-posting doesn't
            // re-vibrate/re-sound
            NotificationChannel channel = new NotificationChannel(
                    EXPIRY_CHANNEL_ID,
                    "Timer Expired Alerts",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Alerts when app time limits are reached");
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[] { 0, 500, 200, 500 });

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("App Timer Active")
                .setContentText("Monitoring app usage")
                .setSmallIcon(R.drawable.ic_timer_status)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    /**
     * Show expiry notification for a timer.
     * Called ONCE on initial expiry, then once per EXPIRY_NOTIF_INTERVAL_MS of
     * active foreground use — not every 10 seconds.
     */
    private void showExpiryNotification(AppTimer timer) {
        String appName = getAppName(timer.packageName);

        Notification notification = new NotificationCompat.Builder(this, EXPIRY_CHANNEL_ID)
                .setContentTitle("⏳ Time's Up: " + appName)
                .setContentText("Daily usage limit reached for " + appName)
                .setSmallIcon(R.drawable.bg_circle_red)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(false) // ✅ FIX: dismissible — stops "stuck" notification
                .setAutoCancel(true)
                .build();

        // Unique ID per package so multiple expired timers each get their own
        // notification
        int notifId = EXPIRY_NOTIFICATION_ID_BASE + Math.abs(timer.packageName.hashCode());

        if (notificationManager != null) {
            notificationManager.notify(notifId, notification);
            Log.d(TAG, "🔔 Expiry notification shown for " + appName);
        }
    }

    private void cancelExpiryNotification(AppTimer timer) {
        int notifId = EXPIRY_NOTIFICATION_ID_BASE + Math.abs(timer.packageName.hashCode());
        if (notificationManager != null) {
            notificationManager.cancel(notifId);
            Log.d(TAG, "🔕 Expiry notification cancelled for " + timer.packageName);
        }
    }

    private String getAppName(String packageName) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Service task removed - attempting restart");
        Intent restartServiceIntent = new Intent(getApplicationContext(), this.getClass());
        restartServiceIntent.setPackage(getPackageName());

        android.app.PendingIntent restartServicePendingIntent = android.app.PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent,
                android.app.PendingIntent.FLAG_ONE_SHOT | android.app.PendingIntent.FLAG_IMMUTABLE);

        android.app.AlarmManager alarmService = (android.app.AlarmManager) getApplicationContext()
                .getSystemService(Context.ALARM_SERVICE);
        alarmService.set(android.app.AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000, restartServicePendingIntent);

        super.onTaskRemoved(rootIntent);
    }

    private String getForegroundApp() {
        if (usageStatsManager == null)
            return "";

        long endTime = System.currentTimeMillis();
        long startTime = endTime - 10000; // 10 seconds lookback

        // Hybrid approach: Check events first for precise transitions
        String eventApp = getForegroundAppFromEvents(startTime, endTime);
        if (!eventApp.isEmpty()) {
            return eventApp;
        }

        // Fallback: Check UsageStats for "most recently used"
        List<android.app.usage.UsageStats> stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY, startTime, endTime);

        if (stats != null) {
            String topPackageName = "";
            long lastTime = 0;
            for (android.app.usage.UsageStats stat : stats) {
                if (stat.getLastTimeUsed() > lastTime) {
                    lastTime = stat.getLastTimeUsed();
                    topPackageName = stat.getPackageName();
                }
            }

            // If used within last 2 seconds, assume it's foreground
            if (lastTime > endTime - 2000 && !topPackageName.isEmpty()) {
                return topPackageName;
            }
        }

        return currentForegroundApp; // Stick to last known if detection fails
    }

    private String getForegroundAppFromEvents(long startTime, long endTime) {
        try {
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();
            String foregroundApp = "";
            long lastEventTime = 0;

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (event.getTimeStamp() > lastEventTime) {
                        foregroundApp = event.getPackageName();
                        lastEventTime = event.getTimeStamp();
                    }
                }
            }
            return foregroundApp;
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "⏱️ AppTimerService destroyed");

        if (handler != null && timerRunnable != null) {
            handler.removeCallbacks(timerRunnable);
        }

        if (timersListener != null && timersRef != null) {
            timersRef.removeEventListener(timersListener);
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static void start(Context context, String deviceId) {
        Intent intent = new Intent(context, AppTimerService.class);
        intent.putExtra("deviceId", deviceId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, AppTimerService.class);
        context.stopService(intent);
    }

    // Timer data class
    static class AppTimer {
        String packageName;
        String key;
        long remainingTimeMillis;
        long dailyLimitMillis; // Original daily limit for midnight reset
        boolean active;
        long lastSyncTime = 0; // Track last Firebase sync time

        // ✅ NEW: For 5-minute notification cadence tracking
        long accumulatedActiveMs = 0; // Total foreground ms since expiry
        long lastNotifIntervalCount = 0; // How many 5-min intervals have triggered a notif
    }
}
