package com.example.master2;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.example.master2.models.AppUsage;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 🎯 BULLETPROOF 7-DAY ROLLING USAGE DATA MANAGER
 * 
 * Features:
 * - Starts collecting immediately on child device connection
 * - Creates new day data at midnight automatically
 * - Maintains exactly 7 days of data (removes day n-8 when adding day n+1)
 * - Real-time updates to parent dashboard
 * - Preserves past day data (no modifications to previous days)
 */
public class RollingUsageDataManager {
    private static final String TAG = "RollingUsageData";
    
    // Constants
    private static final int MAX_DAYS = 7;
    private static final long UPDATE_INTERVAL = 5 * 60 * 1000L; // 5 minutes
    private static final long MIDNIGHT_CHECK_INTERVAL = 60 * 1000L; // 1 minute
    
    private Context context;
    private UsageStatsManager usageStatsManager;
    private PackageManager packageManager;
    private DatabaseReference firebaseRef;
    private String deviceId;
    
    // Handlers for periodic tasks
    private Handler updateHandler;
    private Handler midnightHandler;
    private Runnable updateRunnable;
    private Runnable midnightRunnable;
    
    // Date tracking
    private SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private String currentDateKey;
    private boolean isCollecting = false;
    
    // System apps to exclude
    private static final Set<String> SYSTEM_APPS = new HashSet<>(Arrays.asList(
        "android", "com.android.systemui", "com.android.launcher3",
        "com.android.settings", "com.android.permissioncontroller",
        "com.google.android.gms", "com.google.android.gsf",
        "com.example.master2" // Exclude self
    ));
    
    public RollingUsageDataManager(Context context) {
        this.context = context;
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = context.getPackageManager();
        this.deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        
        // Firebase reference for 7-day usage data
        this.firebaseRef = FirebaseDatabase.getInstance()
                .getReference("usage_7day")
                .child(deviceId);
        
        this.updateHandler = new Handler(Looper.getMainLooper());
        this.midnightHandler = new Handler(Looper.getMainLooper());
        
        this.currentDateKey = dateFormat.format(new Date());
        
        Log.d(TAG, "🚀 RollingUsageDataManager initialized for device: " + deviceId);
    }
    
    /**
     * 🟢 START - Begin collecting usage data immediately
     */
    public void startCollecting() {
        if (isCollecting) {
            Log.d(TAG, "⚠️ Already collecting data");
            return;
        }
        
        isCollecting = true;
        Log.d(TAG, "✅ Starting usage data collection");
        
        // Collect initial data immediately
        collectAndStoreCurrentDayData();
        
        // Setup periodic updates
        setupPeriodicUpdate();
        
        // Setup midnight detection for new day
        setupMidnightDetection();
        
        // Clean old data on startup
        cleanOldData();
    }
    
    /**
     * 🔴 STOP - Stop collecting usage data
     */
    public void stopCollecting() {
        if (!isCollecting) {
            return;
        }
        
        isCollecting = false;
        Log.d(TAG, "🛑 Stopping usage data collection");
        
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
        
        if (midnightHandler != null && midnightRunnable != null) {
            midnightHandler.removeCallbacks(midnightRunnable);
        }
    }
    
    /**
     * 📊 Collect and store current day's usage data
     * FIXED: Only updates current day, never touches frozen days
     */
    private void collectAndStoreCurrentDayData() {
        try {
            String todayKey = dateFormat.format(new Date());
            
            // SAFETY CHECK: Make sure we're collecting for TODAY only
            if (!todayKey.equals(currentDateKey)) {
                Log.w(TAG, "⚠️ Date mismatch detected! Expected: " + currentDateKey + ", Got: " + todayKey);
                currentDateKey = todayKey; // Sync to correct date
            }
            
            Log.d(TAG, "📊 Collecting usage data for: " + todayKey);
            
            // Get today's start and end times
            Calendar startCal = Calendar.getInstance();
            startCal.set(Calendar.HOUR_OF_DAY, 0);
            startCal.set(Calendar.MINUTE, 0);
            startCal.set(Calendar.SECOND, 0);
            startCal.set(Calendar.MILLISECOND, 0);
            long startTime = startCal.getTimeInMillis();
            
            long endTime = System.currentTimeMillis();
            
            // Collect usage data
            Map<String, AppUsage> appUsageMap = collectUsageData(startTime, endTime);
            
            // Calculate total usage
            long totalUsageMs = 0;
            for (AppUsage app : appUsageMap.values()) {
                totalUsageMs += app.getUsageTime();
            }
            
            // Prepare data for Firebase with day label for X-axis
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", todayKey);
            dayData.put("dayLabel", getDayLabelFromDate(todayKey)); // For X-axis display (Mon, Tue, etc.)
            dayData.put("totalUsageMs", totalUsageMs);
            dayData.put("totalUsageText", formatDuration(totalUsageMs));
            dayData.put("appCount", appUsageMap.size());
            dayData.put("apps", appUsageMap);
            dayData.put("lastUpdated", System.currentTimeMillis());
            dayData.put("status", "collecting");
            dayData.put("frozen", false);
            
            // Store in Firebase under date key
            firebaseRef.child(todayKey).setValue(dayData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Usage data stored for: " + todayKey);
                        
                        // Also update "latest" reference for quick access
                        firebaseRef.child("latest").setValue(dayData);
                        
                        // Update summary
                        updateSevenDaySummary();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to store usage data: " + e.getMessage());
                    });
                    
        } catch (Exception e) {
            Log.e(TAG, "❌ Error collecting usage data: " + e.getMessage());
        }
    }
    
    /**
     * 📱 Collect usage data using UsageEvents API
     */
    private Map<String, AppUsage> collectUsageData(long startTime, long endTime) {
        Map<String, AppUsage> appUsageMap = new HashMap<>();
        
        if (usageStatsManager == null) {
            Log.e(TAG, "❌ UsageStatsManager is null");
            return appUsageMap;
        }
        
        try {
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
            UsageEvents.Event event = new UsageEvents.Event();
            
            Map<String, Long> appStartTimes = new HashMap<>();
            Map<String, Long> appTotalTimes = new HashMap<>();
            
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                String packageName = event.getPackageName();
                
                // Skip system apps
                if (shouldExcludeApp(packageName)) {
                    continue;
                }
                
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    
                    appStartTimes.put(packageName, event.getTimeStamp());
                    
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED ||
                           event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    
                    Long startTimeStamp = appStartTimes.get(packageName);
                    if (startTimeStamp != null && startTimeStamp < event.getTimeStamp()) {
                        long sessionTime = event.getTimeStamp() - startTimeStamp;
                        long currentTotal = appTotalTimes.getOrDefault(packageName, 0L);
                        appTotalTimes.put(packageName, currentTotal + sessionTime);
                        appStartTimes.remove(packageName);
                    }
                }
            }
            
            // Handle apps still in foreground
            for (Map.Entry<String, Long> entry : appStartTimes.entrySet()) {
                String packageName = entry.getKey();
                Long startTimeStamp = entry.getValue();
                if (startTimeStamp != null && startTimeStamp < endTime) {
                    long sessionTime = endTime - startTimeStamp;
                    long currentTotal = appTotalTimes.getOrDefault(packageName, 0L);
                    appTotalTimes.put(packageName, currentTotal + sessionTime);
                }
            }
            
            // Convert to AppUsage objects
            for (Map.Entry<String, Long> entry : appTotalTimes.entrySet()) {
                String packageName = entry.getKey();
                Long usageMs = entry.getValue();
                
                if (usageMs > 0) {
                    AppUsage appUsage = new AppUsage();
                    appUsage.setPackageName(packageName);
                    appUsage.setUsageTime(usageMs);
                    appUsage.setAppName(getAppDisplayName(packageName));
                    appUsage.setCategory(getAppCategory(packageName));
                    appUsage.setBlocked(false);
                    
                    appUsageMap.put(packageName, appUsage);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error querying usage events: " + e.getMessage());
        }
        
        return appUsageMap;
    }
    
    /**
     * 🔄 Setup periodic update every 5 minutes
     */
    private void setupPeriodicUpdate() {
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isCollecting) {
                    collectAndStoreCurrentDayData();
                    updateHandler.postDelayed(this, UPDATE_INTERVAL);
                }
            }
        };
        
        // Start periodic updates
        updateHandler.postDelayed(updateRunnable, UPDATE_INTERVAL);
    }
    
    /**
     * 🌙 Setup midnight detection for new day data
     * FIXED: Properly freezes old day data and creates fresh new day bar
     */
    private void setupMidnightDetection() {
        midnightRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isCollecting) {
                    return;
                }
                
                String newDateKey = dateFormat.format(new Date());
                
                // Check if date has changed (MIDNIGHT CROSSED)
                if (!newDateKey.equals(currentDateKey)) {
                    Log.d(TAG, "🌙 ═══════════════════════════════════════");
                    Log.d(TAG, "🌙 MIDNIGHT DETECTED! Day changed!");
                    Log.d(TAG, "🌙 Old day: " + currentDateKey + " → New day: " + newDateKey);
                    Log.d(TAG, "🌙 ═══════════════════════════════════════");
                    
                    // STEP 1: Mark old day as FROZEN (prevent any further updates)
                    freezePreviousDayData(currentDateKey);
                    
                    // STEP 2: Update to new day
                    String previousDay = currentDateKey;
                    currentDateKey = newDateKey;
                    
                    // STEP 3: Create FRESH empty data structure for new day
                    createFreshDayData(newDateKey);
                    
                    // STEP 4: Clean old data (remove day 8 when adding day 1)
                    cleanOldData();
                    
                    Log.d(TAG, "🌙 Midnight transition complete: " + previousDay + " → " + newDateKey);
                    Log.d(TAG, "🌙 Previous day data FROZEN, new day started FRESH");
                }
                
                // Schedule next check
                midnightHandler.postDelayed(this, MIDNIGHT_CHECK_INTERVAL);
            }
        };
        
        // Calculate time until next midnight
        Calendar midnight = Calendar.getInstance();
        midnight.add(Calendar.DAY_OF_MONTH, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 1);
        
        long delayUntilMidnight = midnight.getTimeInMillis() - System.currentTimeMillis();
        
        Log.d(TAG, "🌙 Scheduling midnight check in " + (delayUntilMidnight / 1000) + " seconds");
        
        // Start checking
        midnightHandler.postDelayed(midnightRunnable, Math.min(delayUntilMidnight, MIDNIGHT_CHECK_INTERVAL));
    }
    
    /**
     * 🔒 Freeze previous day's data - mark as complete, no more updates
     */
    private void freezePreviousDayData(String dateKey) {
        try {
            Log.d(TAG, "🔒 Freezing data for day: " + dateKey);
            
            Map<String, Object> freezeUpdate = new HashMap<>();
            freezeUpdate.put("frozen", true);
            freezeUpdate.put("frozenAt", System.currentTimeMillis());
            freezeUpdate.put("status", "day_complete");
            
            firebaseRef.child(dateKey).updateChildren(freezeUpdate)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "🔒 Day " + dateKey + " FROZEN successfully - no more updates allowed");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to freeze day: " + e.getMessage());
                });
                
        } catch (Exception e) {
            Log.e(TAG, "❌ Error freezing day data: " + e.getMessage());
        }
    }
    
    /**
     * 🆕 Create fresh, empty data structure for a new day (ZERO data)
     */
    private void createFreshDayData(String dateKey) {
        try {
            Log.d(TAG, "🆕 Creating FRESH empty data for new day: " + dateKey);
            
            Map<String, Object> freshData = new HashMap<>();
            freshData.put("date", dateKey);
            freshData.put("dayLabel", getDayLabelFromDate(dateKey));
            freshData.put("totalUsageMs", 0L);
            freshData.put("totalUsageText", "0 min");
            freshData.put("appCount", 0);
            freshData.put("apps", new HashMap<>()); // Empty apps map
            freshData.put("createdAt", System.currentTimeMillis());
            freshData.put("lastUpdated", System.currentTimeMillis());
            freshData.put("status", "collecting");
            freshData.put("frozen", false);
            
            firebaseRef.child(dateKey).setValue(freshData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "🆕 Fresh day " + dateKey + " created with ZERO data - ready for collection");
                    
                    // Now start collecting for the new day
                    collectAndStoreCurrentDayData();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to create fresh day: " + e.getMessage());
                });
                
        } catch (Exception e) {
            Log.e(TAG, "❌ Error creating fresh day data: " + e.getMessage());
        }
    }
    
    /**
     * 📅 Get day label (Monday, Tuesday, etc.) from date key
     */
    private String getDayLabelFromDate(String dateKey) {
        try {
            Date date = dateFormat.parse(dateKey);
            if (date != null) {
                Calendar cal = Calendar.getInstance();
                cal.setTime(date);
                String[] days = {"", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
                return days[cal.get(Calendar.DAY_OF_WEEK)];
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing date: " + e.getMessage());
        }
        return dateKey;
    }
    
    /**
     * 🗑️ Clean data older than 7 days
     */
    private void cleanOldData() {
        try {
            Calendar sevenDaysAgo = Calendar.getInstance();
            sevenDaysAgo.add(Calendar.DAY_OF_MONTH, -7);
            String cutoffDate = dateFormat.format(sevenDaysAgo.getTime());
            
            Log.d(TAG, "🗑️ Cleaning data older than: " + cutoffDate);
            
            // Get all dates in Firebase
            firebaseRef.get().addOnSuccessListener(snapshot -> {
                for (DataSnapshot dateSnapshot : snapshot.getChildren()) {
                    String dateKey = dateSnapshot.getKey();
                    
                    // Skip non-date keys
                    if (dateKey == null || dateKey.equals("latest") || dateKey.equals("summary")) {
                        continue;
                    }
                    
                    // Remove if older than 7 days
                    if (dateKey.compareTo(cutoffDate) < 0) {
                        firebaseRef.child(dateKey).removeValue()
                                .addOnSuccessListener(aVoid -> {
                                    Log.d(TAG, "🗑️ Removed old data for: " + dateKey);
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "❌ Failed to remove old data: " + e.getMessage());
                                });
                    }
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error cleaning old data: " + e.getMessage());
        }
    }
    
    /**
     * 📊 Update 7-day summary for parent dashboard
     */
    private void updateSevenDaySummary() {
        try {
            // Get last 7 days
            List<String> last7Days = new ArrayList<>();
            Calendar cal = Calendar.getInstance();
            
            for (int i = 6; i >= 0; i--) {
                Calendar day = (Calendar) cal.clone();
                day.add(Calendar.DAY_OF_MONTH, -i);
                last7Days.add(dateFormat.format(day.getTime()));
            }
            
            // Prepare summary data
            Map<String, Object> summary = new HashMap<>();
            summary.put("days", last7Days);
            summary.put("lastUpdated", System.currentTimeMillis());
            summary.put("deviceId", deviceId);
            
            // Store summary
            firebaseRef.child("summary").setValue(summary);
            
            Log.d(TAG, "📊 Updated 7-day summary");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating summary: " + e.getMessage());
        }
    }
    
    /**
     * 🏷️ Get app display name
     */
    private String getAppDisplayName(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return packageName;
        }
    }
    
    /**
     * 📂 Get app category (simplified)
     */
    private String getAppCategory(String packageName) {
        // Simple categorization based on package name
        if (packageName.contains("game")) return "Games";
        if (packageName.contains("social") || packageName.contains("facebook") || 
            packageName.contains("whatsapp") || packageName.contains("instagram")) return "Social";
        if (packageName.contains("video") || packageName.contains("youtube") || 
            packageName.contains("netflix")) return "Entertainment";
        if (packageName.contains("education") || packageName.contains("learn")) return "Education";
        return "Other";
    }
    
    /**
     * 🚫 Check if app should be excluded
     */
    private boolean shouldExcludeApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return true;
        }
        
        // Check against system apps list
        for (String systemApp : SYSTEM_APPS) {
            if (packageName.startsWith(systemApp)) {
                return true;
            }
        }
        
        // Check if it's a system app
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * ⏱️ Format duration to human-readable string
     */
    private String formatDuration(long millis) {
        if (millis < 1000) {
            return "0 min";
        }
        
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        
        if (hours > 0) {
            return String.format(Locale.getDefault(), "%d hr %d min", hours, minutes);
        } else {
            return String.format(Locale.getDefault(), "%d min", minutes);
        }
    }
}
