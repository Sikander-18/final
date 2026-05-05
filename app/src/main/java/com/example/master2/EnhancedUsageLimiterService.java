package com.example.master2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enhanced Usage Limiter Service with accurate real-time timer tracking
 * Prevents timer freezing and provides accurate foreground app detection
 */
public class EnhancedUsageLimiterService extends Service {
    private static final String TAG = "EnhancedUsageLimiterService";
    private static final String CHANNEL_ID = "enhanced_usage_limiter_channel";
    private static final int NOTIFICATION_ID = 3001;
    private static final int PERMANENT_TIMER_NOTIFICATION_ID = 9999; // For permanent notifications
    
    // Enhanced timing precision
    private static final long TIMER_UPDATE_INTERVAL = 500; // 500ms for smooth updates
    private static final long FIREBASE_SYNC_INTERVAL = 2000; // 2 seconds Firebase sync
    private static final long FOREGROUND_CHECK_INTERVAL = 250; // 250ms for accurate app detection
    
    // Core components
    private String deviceId;
    private DatabaseReference usageLimiterRef;
    private ValueEventListener limiterListener;
    private UsageStatsManager usageStatsManager;
    private PowerManager.WakeLock wakeLock;
    
    // Timer state
    private boolean isTimerActive = false;
    private boolean isTimerRunning = false;
    private long remainingTimeMs = 0;
    private List<String> limitedApps = new ArrayList<>();
    private String currentForegroundApp = "";
    private long lastAppChangeTime = 0;
    
    // Enhanced foreground detection
    private Handler foregroundHandler;
    private Runnable foregroundCheckRunnable;
    private final Map<String, Long> recentAppEvents = new ConcurrentHashMap<>();
    private String lastConfirmedApp = "";
    private int appStabilityCounter = 0;
    private static final int STABILITY_THRESHOLD = 2; // Require 2 consistent detections
    
    // Timer handlers
    private Handler timerHandler;
    private Runnable timerUpdateRunnable;
    private long lastTimerUpdate = 0;
    private long lastFirebaseSync = 0;
    
    // Broadcast receiver for app changes
    private BroadcastReceiver appChangeReceiver;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🚀 Enhanced Usage Limiter Service created");
        
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        
        // Initialize handlers
        foregroundHandler = new Handler(Looper.getMainLooper());
        timerHandler = new Handler(Looper.getMainLooper());
        
        // Setup notification and foreground service
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createServiceNotification("Initializing..."));
        
        // Acquire partial wake lock to prevent doze mode issues
        acquireWakeLock();
        
        // Setup Firebase monitoring
        initializeFirebaseListener();
        
        // Setup app change monitoring
        setupAppChangeReceiver();
        
        Log.d(TAG, "✅ Enhanced Usage Limiter Service initialized for device: " + deviceId);
    }
    
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "EnhancedUsageLimiter::WakeLock"
                );
                wakeLock.acquire(10 * 60 * 1000L); // 10 minutes
                Log.d(TAG, "🔋 Partial wake lock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire wake lock: " + e.getMessage());
        }
    }
    
    private void initializeFirebaseListener() {
        usageLimiterRef = FirebaseDatabase.getInstance()
            .getReference("usage_limiters")
            .child(deviceId);
        
        limiterListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    processLimiterData(dataSnapshot);
                } else {
                    // Timer was cleared by parent - clear permanent notifications
                    clearPermanentLimiterNotifications();
                    stopTimer();
                    updateNotification("No active limiters");
                }
            }
            
            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Firebase listener cancelled: " + databaseError.getMessage());
            }
        };
        
        usageLimiterRef.addValueEventListener(limiterListener);
        Log.d(TAG, "📡 Firebase listener initialized");
    }
    
    private void processLimiterData(DataSnapshot dataSnapshot) {
        try {
            Map<String, Object> limiterData = (Map<String, Object>) dataSnapshot.getValue();
            if (limiterData == null) return;
            
            Boolean isActive = (Boolean) limiterData.get("isActive");
            Long remainingMs = (Long) limiterData.get("remainingTimeMs");
            List<String> selectedApps = (List<String>) limiterData.get("selectedApps");
            
            Log.d(TAG, "📊 Processing limiter data - Active: " + isActive + 
                      ", Remaining: " + (remainingMs != null ? remainingMs/1000 : "null") + "s");
            
            if (Boolean.TRUE.equals(isActive) && remainingMs != null && remainingMs > 0) {
                // Update limiter state
                isTimerActive = true;
                remainingTimeMs = remainingMs;
                limitedApps.clear();
                if (selectedApps != null) {
                    limitedApps.addAll(selectedApps);
                }
                
                // Start enhanced monitoring
                if (!isTimerRunning) {
                    startEnhancedTimer();
                }
                
                updateNotification("Timer active: " + formatTime(remainingTimeMs));
                
            } else {
                stopTimer();
                updateNotification("Timer inactive");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing limiter data: " + e.getMessage());
        }
    }
    
    private void startEnhancedTimer() {
        Log.d(TAG, "⏱️ Starting enhanced timer with accurate foreground detection");
        
        isTimerRunning = true;
        lastTimerUpdate = System.currentTimeMillis();
        lastFirebaseSync = System.currentTimeMillis();
        
        // Start accurate foreground app monitoring
        startForegroundMonitoring();
        
        // Start timer countdown
        startTimerCountdown();
    }
    
    private void startForegroundMonitoring() {
        foregroundCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isTimerRunning) return;
                
                try {
                    String detectedApp = getAccurateForegroundApp();
                    
                    if (detectedApp != null && !detectedApp.equals(lastConfirmedApp)) {
                        // Require stability for app changes
                        if (detectedApp.equals(currentForegroundApp)) {
                            appStabilityCounter++;
                        } else {
                            currentForegroundApp = detectedApp;
                            appStabilityCounter = 1;
                        }
                        
                        // Confirm app change after stability threshold
                        if (appStabilityCounter >= STABILITY_THRESHOLD) {
                            if (!currentForegroundApp.equals(lastConfirmedApp)) {
                                Log.d(TAG, "📱 Confirmed app change: " + lastConfirmedApp + " → " + currentForegroundApp);
                                lastConfirmedApp = currentForegroundApp;
                                lastAppChangeTime = System.currentTimeMillis();
                                appStabilityCounter = 0;
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in foreground monitoring: " + e.getMessage());
                }
                
                // Schedule next check
                if (isTimerRunning) {
                    foregroundHandler.postDelayed(this, FOREGROUND_CHECK_INTERVAL);
                }
            }
        };
        
        foregroundHandler.post(foregroundCheckRunnable);
        Log.d(TAG, "📱 Foreground monitoring started");
    }
    
    private void startTimerCountdown() {
        timerUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isTimerRunning || !isTimerActive) return;
                
                try {
                    long currentTime = System.currentTimeMillis();
                    long elapsed = currentTime - lastTimerUpdate;
                    
                    // Check if current app is limited
                    boolean isAppLimited = isAppInLimitedList(lastConfirmedApp);
                    
                    if (isAppLimited && elapsed > 0) {
                        // Countdown only when limited app is active
                        long timeToSubtract = Math.min(elapsed, remainingTimeMs);
                        remainingTimeMs -= timeToSubtract;
                        
                        Log.d(TAG, "⏳ Timer countdown - App: " + lastConfirmedApp + 
                                  ", Time: " + formatTime(remainingTimeMs) + 
                                  ", Elapsed: " + elapsed + "ms");
                        
                        // Sync to Firebase periodically
                        if (currentTime - lastFirebaseSync >= FIREBASE_SYNC_INTERVAL) {
                            syncToFirebase();
                            lastFirebaseSync = currentTime;
                        }
                        
                        // Check if time is up
                        if (remainingTimeMs <= 0) {
                            handleTimeUp();
                            return;
                        }
                    }
                    
                    lastTimerUpdate = currentTime;
                    
                    // Update notification
                    String status = isAppLimited ? 
                        "Counting: " + formatTime(remainingTimeMs) : 
                        "Paused: " + formatTime(remainingTimeMs);
                    updateNotification(status);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error in timer countdown: " + e.getMessage());
                }
                
                // Schedule next update
                if (isTimerRunning) {
                    timerHandler.postDelayed(this, TIMER_UPDATE_INTERVAL);
                }
            }
        };
        
        timerHandler.post(timerUpdateRunnable);
        Log.d(TAG, "⏱️ Timer countdown started");
    }
    
    private String getAccurateForegroundApp() {
        try {
            if (usageStatsManager == null) return null;
            
            long currentTime = System.currentTimeMillis();
            long startTime = currentTime - 2000; // Last 2 seconds
            
            // Use UsageEvents for most accurate detection
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, currentTime);
            UsageEvents.Event event = new UsageEvents.Event();
            
            String mostRecentApp = null;
            long mostRecentTime = 0;
            
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                
                int eventType = event.getEventType();
                if (eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    
                    if (event.getTimeStamp() > mostRecentTime) {
                        mostRecentApp = event.getPackageName();
                        mostRecentTime = event.getTimeStamp();
                        recentAppEvents.put(mostRecentApp, event.getTimeStamp());
                    }
                }
            }
            
            // Clean old events
            long cleanupTime = currentTime - 5000;
            recentAppEvents.entrySet().removeIf(entry -> entry.getValue() < cleanupTime);
            
            return mostRecentApp;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app: " + e.getMessage());
            return null;
        }
    }
    
    private boolean isAppInLimitedList(String packageName) {
        if (packageName == null || packageName.isEmpty() || limitedApps.isEmpty()) {
            return false;
        }
        
        return limitedApps.contains(packageName);
    }
    
    private void syncToFirebase() {
        try {
            if (usageLimiterRef != null) {
                Map<String, Object> updates = new HashMap<>();
                updates.put("remainingTimeMs", remainingTimeMs);
                updates.put("currentApp", lastConfirmedApp);
                updates.put("lastSync", System.currentTimeMillis());
                updates.put("isRunning", isAppInLimitedList(lastConfirmedApp));
                
                usageLimiterRef.updateChildren(updates)
                    .addOnSuccessListener(aVoid -> {
                        Log.v(TAG, "✅ Firebase sync successful");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Firebase sync failed: " + e.getMessage());
                    });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error syncing to Firebase: " + e.getMessage());
        }
    }
    
    private void handleTimeUp() {
        Log.d(TAG, "⏰ Timer completed!");
        
        remainingTimeMs = 0;
        stopTimer();
        
        // 🔔 SHOW PERMANENT NOTIFICATION instead of blocking apps
        showPermanentTimerExpiredNotification();
        
        // Update Firebase
        Map<String, Object> updates = new HashMap<>();
        updates.put("remainingTimeMs", 0);
        updates.put("isActive", false);
        updates.put("completedAt", System.currentTimeMillis());
        updates.put("showPermanentNotification", true); // New flag for notification
        
        if (usageLimiterRef != null) {
            usageLimiterRef.updateChildren(updates);
        }
        
        updateNotification("Timer expired - Apps still accessible");
        Log.d(TAG, "✅ Timer expired - Showing permanent notification instead of blocking apps");
    }
    
    /**
     * 🔔 Show permanent non-removable notification when timer expires
     * This notification will persist until the timer resets at midnight
     */
    private void showPermanentTimerExpiredNotification() {
        try {
            Log.d(TAG, "🔔 Creating permanent timer expired notification...");
            
            Intent intent = new Intent(this, ChildDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            // Create special notification channel for permanent notifications
            createPermanentNotificationChannel();
            
            if (!NotificationGate.allow(NotificationGate.Type.TIMER_EXPIRED)) return;
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "permanent_timer_channel")
                .setContentTitle("⏰ Daily Time Limit Reached")
                .setContentText("Your daily usage limit has been reached. Apps remain accessible.")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false) // CRITICAL: Cannot be dismissed by user
                .setOngoing(true) // CRITICAL: Persistent notification
                .setColor(Color.parseColor("#FF9800")) // Orange color for warning
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText("Your daily usage limit has been reached.\n\n" +
                           "Apps remain accessible, but please be mindful of your usage.\n\n" +
                           "This notification will remain until your parent clears the timer."))
                .addAction(android.R.drawable.ic_menu_info_details, "View Apps", pendingIntent);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                // Use unique notification ID for permanent timer notifications
                int permanentNotificationId = 9999;
                manager.notify(permanentNotificationId, builder.build());
                
                // Save notification state to SharedPreferences for persistence across reboots
                SharedPreferences notificationPrefs = getSharedPreferences("permanent_notifications", MODE_PRIVATE);
                notificationPrefs.edit()
                    .putBoolean("timer_expired_notification_active", true)
                    .putLong("notification_created_time", System.currentTimeMillis())
                    .putString("device_id", deviceId)
                    .apply();
                
                Log.d(TAG, "✅ Permanent timer expired notification created and saved");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error creating permanent notification: " + e.getMessage());
        }
    }
    
    /**
     * Create special notification channel for permanent timer notifications
     */
    private void createPermanentNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "permanent_timer_channel",
                "Timer Expired Notifications",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Permanent notifications when daily usage limits are reached - Only removable by parent");
            channel.enableLights(true);
            channel.setLightColor(Color.parseColor("#FF9800"));
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(true);
            
            // Make the notification channel non-blockable by user
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                channel.setBlockable(false);  
            }
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "✅ Permanent notification channel created (non-removable)");
            }
        }
    }

    private void blockLimitedApps() {
        // 🚫 DISABLED: No longer blocking apps when timer expires
        // Apps will remain accessible with permanent notification reminder
        Log.d(TAG, "🔔 App blocking DISABLED - Using permanent notification instead");
        
        /* ORIGINAL BLOCKING CODE - DISABLED
        try {
            SharedPreferences blockedAppsPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
            SharedPreferences.Editor editor = blockedAppsPrefs.edit();
            
            for (String packageName : limitedApps) {
                editor.putBoolean(packageName, true);
                Log.d(TAG, "🚫 Blocked app: " + packageName);
            }
            
            editor.apply();
            
            // Broadcast to BlockService
            Intent intent = new Intent("com.example.master2.BLOCKED_APPS_UPDATED");
            intent.putExtra("blocked_count", limitedApps.size());
            sendBroadcast(intent);
            
        } catch (Exception e) {
            Log.e(TAG, "Error blocking apps: " + e.getMessage());
        }
        */
    }
    
    private void setupAppChangeReceiver() {
        appChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.master2.APP_FOREGROUND".equals(intent.getAction())) {
                    String packageName = intent.getStringExtra("package_name");
                    if (packageName != null) {
                        Log.d(TAG, "📱 App change broadcast received: " + packageName);
                        // This provides backup detection in case UsageEvents miss something
                        recentAppEvents.put(packageName, System.currentTimeMillis());
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter("com.example.master2.APP_FOREGROUND");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(appChangeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(appChangeReceiver, filter);
        }
        
        Log.d(TAG, "📡 App change receiver registered");
    }
    
    private void stopTimer() {
        Log.d(TAG, "🛑 Stopping enhanced timer");
        
        isTimerRunning = false;
        isTimerActive = false;
        
        // Stop handlers
        if (foregroundCheckRunnable != null && foregroundHandler != null) {
            foregroundHandler.removeCallbacks(foregroundCheckRunnable);
        }
        
        if (timerUpdateRunnable != null && timerHandler != null) {
            timerHandler.removeCallbacks(timerUpdateRunnable);
        }
        
        Log.d(TAG, "✅ Enhanced timer stopped");
    }
    
    private String formatTime(long timeMs) {
        if (timeMs <= 0) return "0:00";
        
        long totalSeconds = timeMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        return String.format("%d:%02d", minutes, seconds);
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Enhanced Usage Limiter",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Real-time usage limiter with accurate tracking");
            channel.setSound(null, null);
            channel.enableVibration(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createServiceNotification(String status) {
        Intent intent = new Intent(this, ChildDashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        if (!NotificationGate.allow(NotificationGate.Type.LIVE_TIMER)) return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Usage Limiter Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Usage Limiter Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }
    
    private void updateNotification(String status) {
        try {
            Notification notification = createServiceNotification(status);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating notification: " + e.getMessage());
        }
    }
    
    private void showTimeUpNotification() {
        // 🔔 UPDATED: Use permanent notification instead of dismissible one
        Log.d(TAG, "🔔 Showing permanent timer expired notification...");
        showPermanentTimerExpiredNotification();
        
        /* ORIGINAL DISMISSIBLE NOTIFICATION - REPLACED WITH PERMANENT ONE
        try {
            Intent intent = new Intent(this, ChildDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("⏰ Time's Up!")
                .setContentText("Usage limit reached. Selected apps are now blocked.")
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setColor(Color.RED)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID + 1, builder.build());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error showing time up notification: " + e.getMessage());
        }
        */
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Enhanced Usage Limiter Service started");
        return START_STICKY; // Restart if killed
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🛑 Enhanced Usage Limiter Service destroyed");
        
        stopTimer();
        
        // Clean up resources
        if (limiterListener != null && usageLimiterRef != null) {
            usageLimiterRef.removeEventListener(limiterListener);
        }
        
        if (appChangeReceiver != null) {
            try {
                unregisterReceiver(appChangeReceiver);
            } catch (Exception e) {
                Log.w(TAG, "Failed to unregister app change receiver: " + e.getMessage());
            }
        }
        
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
    
    // Public static method to start the service
    public static void startService(Context context) {
        Intent intent = new Intent(context, EnhancedUsageLimiterService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        Log.d("EnhancedUsageLimiterService", "Service start requested");
    }
    
    /**
     * Clear permanent notifications when timer resets (called by DailyTimerResetService)
     */
    public static void clearPermanentTimerNotifications(Context context) {
        try {
            Log.d("EnhancedUsageLimiterService", "🧹 Clearing permanent timer notifications...");
            
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                // Clear the permanent timer notification
                manager.cancel(9999); // Same ID used in showPermanentTimerExpiredNotification
                
                // Clear notification state from SharedPreferences
                SharedPreferences notificationPrefs = context.getSharedPreferences("permanent_notifications", Context.MODE_PRIVATE);
                notificationPrefs.edit()
                    .putBoolean("timer_expired_notification_active", false)
                    .putLong("notification_cleared_time", System.currentTimeMillis())
                    .apply();
                
                Log.d("EnhancedUsageLimiterService", "✅ Permanent timer notifications cleared for daily reset");
            }
        } catch (Exception e) {
            Log.e("EnhancedUsageLimiterService", "❌ Error clearing permanent notifications: " + e.getMessage());
        }
    }
    
    /**
     * Clear permanent limiter notifications when parent clears timer from parent app
     */
    public static void clearPermanentLimiterNotifications(Context context) {
        try {
            Log.d("EnhancedUsageLimiterService", "🧹 Clearing permanent limiter notifications - PARENT CLEARED TIMER...");
            
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                // Clear the permanent limiter notification
                manager.cancel(PERMANENT_TIMER_NOTIFICATION_ID);
                
                // Clear notification state from SharedPreferences
                SharedPreferences notificationPrefs = context.getSharedPreferences("permanent_limiter_notifications", Context.MODE_PRIVATE);
                notificationPrefs.edit()
                    .putBoolean("limiter_expired_notification_active", false)
                    .putLong("notification_cleared_time", System.currentTimeMillis())
                    .putString("cleared_by", "parent")
                    .apply();
                
                Log.d("EnhancedUsageLimiterService", "✅ Permanent limiter notifications cleared - parent cleared timer");
            }
        } catch (Exception e) {
            Log.e("EnhancedUsageLimiterService", "❌ Error clearing permanent limiter notifications: " + e.getMessage());
        }
    }
    
    /**
     * Clear permanent limiter notifications when parent clears timer from parent app (non-static version)
     */
    private void clearPermanentLimiterNotifications() {
        clearPermanentLimiterNotifications(this);
    }
}