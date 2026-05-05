package com.example.master2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.master2.models.AppUsage;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 📊 ENHANCED USAGE DATA SYNC SERVICE
 * 
 * Continuously syncs usage data to the DateAwareUsageDataManager
 * Ensures real-time updates while maintaining proper daily data separation
 * 
 * KEY FEATURES:
 * ✅ Real-time usage tracking with UsageStatsManager
 * ✅ Automatic daily data separation at midnight
 * ✅ Syncs data every 10 seconds for real-time parent dashboard updates
 * ✅ Background service that survives app restarts
 * ✅ Proper midnight detection and new day creation
 */
public class EnhancedUsageDataSyncService extends Service {
    private static final String TAG = "EnhancedUsageSync";
    private static final String CHANNEL_ID = "usage_sync_channel";
    private static final int NOTIFICATION_ID = 3001;
    
    // Sync intervals
    private static final long SYNC_INTERVAL_MS = 10000; // 10 seconds for real-time updates
    private static final long MIDNIGHT_CHECK_INTERVAL_MS = 60000; // Check for midnight every minute
    
    // Usage tracking
    private UsageStatsManager usageStatsManager;
    private PackageManager packageManager;
    private DateAwareUsageDataManager dateAwareManager;
    private String deviceId;
    
    // Handlers and runnables
    private Handler syncHandler;
    private Runnable syncRunnable;
    private Handler midnightHandler;
    private Runnable midnightRunnable;
    
    // State tracking
    private String lastProcessedDay = "";
    private final Map<String, Long> appUsageTimes = new HashMap<>();
    private final Map<String, String> appNames = new HashMap<>();
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🚀 Enhanced Usage Data Sync Service created");
        
        // Initialize system services
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        packageManager = getPackageManager();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        
        // Initialize date-aware manager
        dateAwareManager = new DateAwareUsageDataManager(this, deviceId);
        
        // Create notification channel and start foreground
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createServiceNotification());
        
        // Initialize sync handlers
        syncHandler = new Handler(Looper.getMainLooper());
        midnightHandler = new Handler(Looper.getMainLooper());
        
        // Start sync processes
        startUsageDataSync();
        startMidnightMonitoring();
        
        Log.d(TAG, "✅ Enhanced Usage Data Sync Service fully initialized");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "📊 Enhanced Usage Data Sync Service started");
        return START_STICKY; // Keep running even if killed
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
    
    /**
     * 🔄 Start continuous usage data synchronization
     */
    private void startUsageDataSync() {
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    // Collect current usage data
                    collectAndSyncUsageData();
                    
                    // Schedule next sync
                    syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
                    
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error in usage sync: " + e.getMessage());
                    // Continue syncing even after errors
                    syncHandler.postDelayed(this, SYNC_INTERVAL_MS * 2);
                }
            }
        };
        
        // Start immediately
        syncHandler.post(syncRunnable);
        Log.d(TAG, "🔄 Usage data sync started (every " + (SYNC_INTERVAL_MS/1000) + " seconds)");
    }
    
    /**
     * 🌙 Start midnight monitoring for automatic daily separation
     */
    private void startMidnightMonitoring() {
        // Initialize last processed day
        lastProcessedDay = getCurrentDateKey();
        
        midnightRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    checkForMidnightReset();
                    
                    // Schedule next check
                    midnightHandler.postDelayed(this, MIDNIGHT_CHECK_INTERVAL_MS);
                    
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error in midnight check: " + e.getMessage());
                    midnightHandler.postDelayed(this, MIDNIGHT_CHECK_INTERVAL_MS);
                }
            }
        };
        
        // Start midnight monitoring
        midnightHandler.post(midnightRunnable);
        Log.d(TAG, "🌙 Midnight monitoring started (checking every " + (MIDNIGHT_CHECK_INTERVAL_MS/1000) + " seconds)");
    }
    
    /**
     * 📊 Collect and sync current usage data
     */
    private void collectAndSyncUsageData() {
        try {
            // Get today's usage data from UsageStatsManager
            Calendar today = Calendar.getInstance();
            today.set(Calendar.HOUR_OF_DAY, 0);
            today.set(Calendar.MINUTE, 0);
            today.set(Calendar.SECOND, 0);
            today.set(Calendar.MILLISECOND, 0);
            
            long startOfDay = today.getTimeInMillis();
            long now = System.currentTimeMillis();
            
            // Clear previous data
            appUsageTimes.clear();
            
            // Query usage events for today
            UsageEvents usageEvents = usageStatsManager.queryEvents(startOfDay, now);
            Map<String, Long> sessionStartTimes = new HashMap<>();
            
            // Process usage events
            UsageEvents.Event event = new UsageEvents.Event();
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                
                String packageName = event.getPackageName();
                
                // Filter system apps
                if (!shouldTrackApp(packageName)) {
                    continue;
                }
                
                // Track foreground/background events
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    sessionStartTimes.put(packageName, event.getTimeStamp());
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                    Long startTime = sessionStartTimes.get(packageName);
                    if (startTime != null) {
                        long sessionDuration = event.getTimeStamp() - startTime;
                        if (sessionDuration > 0) {
                            appUsageTimes.put(packageName, 
                                appUsageTimes.getOrDefault(packageName, 0L) + sessionDuration);
                        }
                        sessionStartTimes.remove(packageName);
                    }
                }
            }
            
            // Add ongoing sessions
            for (Map.Entry<String, Long> entry : sessionStartTimes.entrySet()) {
                String packageName = entry.getKey();
                long sessionStart = entry.getValue();
                long ongoingTime = now - sessionStart;
                
                if (ongoingTime > 0 && ongoingTime < 300000) { // Max 5 minutes for safety
                    appUsageTimes.put(packageName, 
                        appUsageTimes.getOrDefault(packageName, 0L) + ongoingTime);
                }
            }
            
            // Convert to AppUsage list
            List<AppUsage> appUsageList = new ArrayList<>();
            long totalUsageMs = 0;
            
            for (Map.Entry<String, Long> entry : appUsageTimes.entrySet()) {
                String packageName = entry.getKey();
                long usageTimeMs = entry.getValue();
                
                if (usageTimeMs > 0) {
                    String appName = getAppName(packageName);
                    AppUsage appUsage = new AppUsage(packageName, appName, usageTimeMs, "Unknown", false);
                    appUsageList.add(appUsage);
                    totalUsageMs += usageTimeMs;
                }
            }
            
            // Calculate totals
            float totalMinutes = totalUsageMs / 60000f;
            String totalTimeText = formatUsageTime(totalUsageMs);
            
            // Sync to date-aware manager
            dateAwareManager.updateTodayUsageData(appUsageList, totalMinutes, totalTimeText);
            
            Log.v(TAG, "📊 Synced usage data: " + totalTimeText + " across " + appUsageList.size() + " apps");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error collecting usage data: " + e.getMessage());
        }
    }
    
    /**
     * 🌙 Check for midnight and trigger new day creation
     */
    private void checkForMidnightReset() {
        String currentDay = getCurrentDateKey();
        
        if (!currentDay.equals(lastProcessedDay)) {
            Log.d(TAG, "🌙 MIDNIGHT DETECTED! New day: " + currentDay + " (was: " + lastProcessedDay + ")");
            
            // Trigger midnight reset in date-aware manager
            dateAwareManager.triggerMidnightReset();
            
            // Update last processed day
            lastProcessedDay = currentDay;
            
            // Clear current usage data to start fresh
            appUsageTimes.clear();
            
            Log.d(TAG, "✅ Midnight reset completed - starting fresh data for " + currentDay);
        }
    }
    
    /**
     * 🔍 Check if app should be tracked (filter system apps)
     */
    private boolean shouldTrackApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        
        // Filter common system packages
        if (packageName.equals("android") || 
            packageName.equals("com.android.systemui") ||
            packageName.equals("com.example.master2") ||
            packageName.startsWith("com.android.") ||
            packageName.startsWith("com.google.android.")) {
            return false;
        }
        
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            
            // Only track user-installed apps
            boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean isUpdatedSystemApp = (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            
            return !isSystemApp || isUpdatedSystemApp;
            
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
    
    /**
     * 📱 Get app display name
     */
    private String getAppName(String packageName) {
        if (appNames.containsKey(packageName)) {
            return appNames.get(packageName);
        }
        
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            String appName = packageManager.getApplicationLabel(appInfo).toString();
            appNames.put(packageName, appName);
            return appName;
        } catch (Exception e) {
            appNames.put(packageName, packageName);
            return packageName;
        }
    }
    
    /**
     * ⏰ Format usage time for display
     */
    private String formatUsageTime(long usageTimeMs) {
        long hours = usageTimeMs / (60 * 60 * 1000);
        long minutes = (usageTimeMs % (60 * 60 * 1000)) / (60 * 1000);
        
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }
    
    /**
     * 📅 Get current date key
     */
    private String getCurrentDateKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }
    
    /**
     * 🔔 Create notification channel
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Usage Data Sync",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Syncs usage data with proper daily separation");
            channel.setSound(null, null);
            channel.enableVibration(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * 📢 Create service notification
     */
    private Notification createServiceNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Usage Tracking Active")
            .setContentText("Monitoring app usage with daily separation")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        // Clean up handlers
        if (syncHandler != null && syncRunnable != null) {
            syncHandler.removeCallbacks(syncRunnable);
        }
        
        if (midnightHandler != null && midnightRunnable != null) {
            midnightHandler.removeCallbacks(midnightRunnable);
        }
        
        Log.d(TAG, "🛑 Enhanced Usage Data Sync Service destroyed");
    }
}