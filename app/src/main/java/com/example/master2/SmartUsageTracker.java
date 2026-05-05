package com.example.master2;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 🎯 SMART USAGE TRACKER - GAME CHANGER!
 * 
 * Revolutionary usage tracking that:
 * - Starts tracking from device connection time (not app download)
 * - Maintains rolling 7-day window that auto-updates
 * - Smart daily segregation with automatic cleanup
 * - Removes oldest data when 8th day starts
 * - Accurate, connection-based data tracking
 */
public class SmartUsageTracker {
    private static final String TAG = "SmartUsageTracker";
    
    // Smart tracking constants
    private static final int ROLLING_WINDOW_DAYS = 7;
    private static final long DAY_IN_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final String PREF_USAGE_TRACKING = "smart_usage_tracking";
    private static final String KEY_TRACKING_START_TIME = "tracking_start_time";
    private static final String KEY_LAST_CLEANUP_DAY = "last_cleanup_day";
    
    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final SharedPreferences trackingPrefs;
    private final DatabaseReference usageDataRef;
    private final String deviceId;
    
    // Ignored system packages
    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
        "com.android.settings", "com.android.systemui", "com.google.android.gms",
        "com.google.android.permissioncontroller", "com.miui.home", "com.miui.systemui",
        "com.mi.android.globallauncher", "com.example.master2"
    ));
    
    public SmartUsageTracker(Context context, String deviceId) {
        this.context = context;
        this.deviceId = deviceId;
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.trackingPrefs = context.getSharedPreferences(PREF_USAGE_TRACKING, Context.MODE_PRIVATE);
        this.usageDataRef = FirebaseDatabase.getInstance().getReference("usage_data").child(deviceId);
        
        initializeSmartTracking();
    }
    
    /**
     * 🚀 Initialize smart tracking from connection time
     */
    private void initializeSmartTracking() {
        long trackingStartTime = getTrackingStartTime();
        
        if (trackingStartTime == 0) {
            // First time setup - start tracking from now
            setTrackingStartTime(System.currentTimeMillis());
            Log.d(TAG, "🎯 Smart tracking initialized - Starting from connection time");
        } else {
            Log.d(TAG, "🎯 Smart tracking active since: " + new Date(trackingStartTime));
            performDailyCleanupIfNeeded();
        }
    }
    
    /**
     * 🎯 Get the tracking start time (when device first connected)
     */
    private long getTrackingStartTime() {
        // Try to get from connection manager first
        SharedPreferences connectionPrefs = context.getSharedPreferences("connection_preferences", Context.MODE_PRIVATE);
        long connectionTime = connectionPrefs.getLong("connection_time", 0);
        
        if (connectionTime > 0) {
            // Use connection time if available
            return trackingPrefs.getLong(KEY_TRACKING_START_TIME, connectionTime);
        } else {
            // Fallback to stored tracking time
            return trackingPrefs.getLong(KEY_TRACKING_START_TIME, 0);
        }
    }
    
    /**
     * 🎯 Set tracking start time
     */
    private void setTrackingStartTime(long startTime) {
        trackingPrefs.edit()
            .putLong(KEY_TRACKING_START_TIME, startTime)
            .apply();
        Log.d(TAG, "📅 Tracking start time set to: " + new Date(startTime));
    }
    
    /**
     * 🧹 Perform daily cleanup if needed - Remove data older than 7 days
     */
    private void performDailyCleanupIfNeeded() {
        long currentDay = getCurrentDayNumber();
        long lastCleanupDay = trackingPrefs.getLong(KEY_LAST_CLEANUP_DAY, 0);
        
        if (currentDay > lastCleanupDay) {
            Log.d(TAG, "🧹 Performing smart cleanup for day " + currentDay);
            cleanupOldUsageData(currentDay);
            
            trackingPrefs.edit()
                .putLong(KEY_LAST_CLEANUP_DAY, currentDay)
                .apply();
        }
    }
    
    /**
     * 📊 Get current day number since tracking started
     */
    private long getCurrentDayNumber() {
        long trackingStartTime = getTrackingStartTime();
        if (trackingStartTime == 0) return 0;
        
        long millisSinceStart = System.currentTimeMillis() - trackingStartTime;
        return millisSinceStart / DAY_IN_MILLIS;
    }
    
    /**
     * 🧹 Clean up usage data older than 7 days from current day
     */
    private void cleanupOldUsageData(long currentDay) {
        try {
            // Calculate which days to keep (last 7 days including today)
            long oldestDayToKeep = Math.max(0, currentDay - (ROLLING_WINDOW_DAYS - 1));
            
            Log.d(TAG, "🧹 Cleaning up data older than day " + oldestDayToKeep);
            
            // Remove old days from Firebase
            for (long dayToRemove = 0; dayToRemove < oldestDayToKeep; dayToRemove++) {
                String dayKey = "day_" + dayToRemove;
                usageDataRef.child(dayKey).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Removed old usage data for " + dayKey);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to remove old data for " + dayKey + ": " + e.getMessage());
                    });
            }
            
            Log.d(TAG, "🎯 Smart cleanup completed - Keeping last " + ROLLING_WINDOW_DAYS + " days");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error during cleanup: " + e.getMessage());
        }
    }
    
    /**
     * 📊 Collect and upload smart usage data for rolling 7-day window with enhanced bar chart support
     */
    public void collectAndUploadSmartUsageData() {
        try {
            Log.d(TAG, "📊 Starting enhanced 7-day smart usage data collection...");
            
            long trackingStartTime = getTrackingStartTime();
            if (trackingStartTime == 0) {
                Log.w(TAG, "❌ No tracking start time set - cannot collect usage data");
                return;
            }
            
            long currentDay = getCurrentDayNumber();
            
            // Perform cleanup first
            performDailyCleanupIfNeeded();
            
            // Generate enhanced 7-day rolling data with bar chart support
            generateEnhanced7DayData(trackingStartTime, currentDay);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error collecting smart usage data: " + e.getMessage());
        }
    }
    
    /**
     * 🎯 Generate enhanced 7-day data with rolling window and bar chart visualization
     */
    private void generateEnhanced7DayData(long trackingStartTime, long currentDay) {
        try {
            // Calculate which 7 days to include in our rolling window
            List<Long> daysToInclude = calculate7DayWindow(currentDay);
            
            // Generate bar chart data and daily breakdowns
            List<Float> barValues = new ArrayList<>();
            List<List<com.example.master2.models.AppUsage>> dailyAppLists = new ArrayList<>();
            List<String> dailyTotals = new ArrayList<>();
            List<String> dayLabels = new ArrayList<>();
            
            Log.d(TAG, "🎯 Generating data for rolling window: " + daysToInclude);
            
            for (Long dayIndex : daysToInclude) {
                DayUsageData dayData = collectUsageForDay(trackingStartTime, dayIndex);
                
                barValues.add(dayData.totalMinutes);
                dailyAppLists.add(dayData.appUsageList);
                dailyTotals.add(dayData.totalTimeText);
                dayLabels.add(dayData.dayLabel);
                
                Log.d(TAG, "📅 Day " + dayIndex + " (" + dayData.dayLabel + "): " + 
                      dayData.totalTimeText + " across " + dayData.appUsageList.size() + " apps");
            }
            
            // Create enhanced usage snapshot with proper structure for parent dashboard
            UsageSnapshot snapshot = new UsageSnapshot(
                System.currentTimeMillis(),
                barValues,
                dailyAppLists,
                dailyTotals
            );
            
            snapshot.deviceId = deviceId;
            
            // Upload to Firebase in both legacy format and new enhanced format
            uploadEnhancedSnapshotToFirebase(snapshot, dayLabels, trackingStartTime, currentDay, daysToInclude);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error generating enhanced 7-day data: " + e.getMessage());
        }
    }
    
    /**
     * 📅 Calculate which 7 days to include in our rolling window
     */
    private List<Long> calculate7DayWindow(long currentDay) {
        List<Long> daysToInclude = new ArrayList<>();
        
        if (currentDay < ROLLING_WINDOW_DAYS) {
            // We haven't reached 7 days yet - include from day 0 to current day
            for (long i = 0; i <= currentDay; i++) {
                daysToInclude.add(i);
            }
        } else {
            // We have more than 7 days - use rolling window (exclude oldest days)
            long startDay = currentDay - (ROLLING_WINDOW_DAYS - 1);
            for (long i = startDay; i <= currentDay; i++) {
                daysToInclude.add(i);
            }
        }
        
        Log.d(TAG, "🎯 Rolling window includes days: " + daysToInclude);
        return daysToInclude;
    }
    
    /**
     * � Collect usage data for a specific day
     */
    private DayUsageData collectUsageForDay(long trackingStartTime, long dayIndex) {
        long dayStartTime = trackingStartTime + (dayIndex * DAY_IN_MILLIS);
        long dayEndTime = Math.min(dayStartTime + DAY_IN_MILLIS, System.currentTimeMillis());
        
        // Get calendar for day labeling
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(dayStartTime);
        String dayLabel = getDayLabel(cal);
        
        Map<String, Long> appUsageMap = collectUsageForTimeRange(dayStartTime, dayEndTime);
        
        // Convert to AppUsage objects
        List<com.example.master2.models.AppUsage> appUsageList = new ArrayList<>();
        long totalUsageMs = 0;
        
        for (Map.Entry<String, Long> entry : appUsageMap.entrySet()) {
            String packageName = entry.getKey();
            Long usageMs = entry.getValue();
            
            if (usageMs > 0) {
                com.example.master2.models.AppUsage appUsage = new com.example.master2.models.AppUsage();
                appUsage.setPackageName(packageName);
                appUsage.setUsageTime(usageMs);
                appUsage.setAppName(getAppDisplayName(packageName));
                appUsageList.add(appUsage);
                totalUsageMs += usageMs;
            }
        }
        
        // Sort by usage time (descending)
        Collections.sort(appUsageList, (a, b) -> Long.compare(b.getUsageTime(), a.getUsageTime()));
        
        return new DayUsageData(
            appUsageList,
            totalUsageMs / 60000f, // Convert to minutes
            formatUsageTime(totalUsageMs),
            dayLabel
        );
    }
    
    /**
     * 📱 Get day label (Mon, Tue, Wed, etc.)
     */
    private String getDayLabel(Calendar cal) {
        String[] dayNames = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        return dayNames[cal.get(Calendar.DAY_OF_WEEK) - 1];
    }
    
    /**
     * 🔄 Upload enhanced snapshot to Firebase with proper structure
     */
    private void uploadEnhancedSnapshotToFirebase(UsageSnapshot snapshot, List<String> dayLabels, long trackingStartTime, long currentDay, List<Long> dayIndices) {
        // Upload to usage_snapshots for parent dashboard (legacy compatibility)
        DatabaseReference snapshotRef = FirebaseDatabase.getInstance()
            .getReference("usage_snapshots")
            .child(deviceId)
            .child("latest");
            
        Map<String, Object> snapshotData = new HashMap<>();
        snapshotData.put("timestamp", snapshot.timestamp);
        snapshotData.put("bars", snapshot.bars);
        snapshotData.put("dailyApps", snapshot.dailyApps);
        snapshotData.put("totalTexts", snapshot.totalTexts);
        snapshotData.put("dayLabels", dayLabels);
        snapshotData.put("deviceId", snapshot.deviceId);
        
        snapshotRef.setValue(snapshotData)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ Enhanced usage snapshot uploaded successfully!");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to upload usage snapshot: " + e.getMessage());
            });
        
        // Also upload to smart tracking location with metadata
        // Build per-day structure expected by parent smart loader: usage_data/day_X/apps
        Map<String, Object> perDayUsageData = new HashMap<>();
        try {
            for (int i = 0; i < dayIndices.size() && i < snapshot.dailyApps.size(); i++) {
                long dayIndex = dayIndices.get(i);
                String dayKey = "day_" + dayIndex;

                List<Map<String, Object>> appsList = new ArrayList<>();
                List<com.example.master2.models.AppUsage> apps = snapshot.dailyApps.get(i);
                if (apps != null) {
                    for (com.example.master2.models.AppUsage app : apps) {
                        if (app == null) continue;
                        Map<String, Object> appMap = new HashMap<>();
                        appMap.put("packageName", app.getPackageName());
                        appMap.put("appName", app.getAppName());
                        appMap.put("usageTime", app.getUsageTime());
                        appsList.add(appMap);
                    }
                }

                Map<String, Object> dayData = new HashMap<>();
                dayData.put("apps", appsList);
                if (i < snapshot.totalTexts.size()) {
                    dayData.put("totalText", snapshot.totalTexts.get(i));
                }
                if (i < snapshot.bars.size()) {
                    dayData.put("totalMinutes", snapshot.bars.get(i));
                }
                if (i < dayLabels.size()) {
                    dayData.put("dayLabel", dayLabels.get(i));
                }

                perDayUsageData.put(dayKey, dayData);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed building per-day usage data: " + e.getMessage());
        }

        Map<String, Object> smartData = new HashMap<>();
        smartData.put("tracking_start_time", trackingStartTime);
        smartData.put("current_day", currentDay);
        smartData.put("last_updated", System.currentTimeMillis());
        smartData.put("rolling_window_days", ROLLING_WINDOW_DAYS);
        smartData.put("enhanced_snapshot", snapshotData);
        smartData.put("usage_data", perDayUsageData);
        
        usageDataRef.setValue(smartData)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "🎯 Smart tracking data with enhanced snapshot uploaded!");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to upload smart tracking data: " + e.getMessage());
            });
    }
    
    /**
     * 📱 Get app display name
     */
    private String getAppDisplayName(String packageName) {
        try {
            return context.getPackageManager()
                .getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName, 0))
                .toString();
        } catch (Exception e) {
            return packageName;
        }
    }
    
    /**
     * ⏰ Format usage time to readable format
     */
    private String formatUsageTime(long usageTimeMs) {
        if (usageTimeMs <= 0) {
            return "0h 0m";
        }
        long hours = usageTimeMs / (1000 * 60 * 60);
        long minutes = (usageTimeMs % (1000 * 60 * 60)) / (1000 * 60);
        return hours + "h " + minutes + "m";
    }
    
    /**
     * 📊 Internal class for day usage data
     */
    private static class DayUsageData {
        final List<com.example.master2.models.AppUsage> appUsageList;
        final float totalMinutes;
        final String totalTimeText;
        final String dayLabel;
        
        DayUsageData(List<com.example.master2.models.AppUsage> appUsageList, float totalMinutes, String totalTimeText, String dayLabel) {
            this.appUsageList = appUsageList;
            this.totalMinutes = totalMinutes;
            this.totalTimeText = totalTimeText;
            this.dayLabel = dayLabel;
        }
    }
    
    /**
     * 📊 Collect usage data for specific time range
     */
    private Map<String, Long> collectUsageForTimeRange(long startTime, long endTime) {
        Map<String, Long> usageMap = new HashMap<>();
        
        try {
            if (usageStatsManager == null) {
                Log.e(TAG, "❌ UsageStatsManager is null");
                return usageMap;
            }
            
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
            Map<String, Long> appStartTimes = new HashMap<>();
            
            while (usageEvents.hasNextEvent()) {
                UsageEvents.Event event = new UsageEvents.Event();
                usageEvents.getNextEvent(event);
                
                String packageName = event.getPackageName();
                
                // Skip ignored packages
                if (IGNORED_PACKAGES.contains(packageName)) {
                    continue;
                }
                
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    appStartTimes.put(packageName, event.getTimeStamp());
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                    Long startTimeStamp = appStartTimes.get(packageName);
                    if (startTimeStamp != null) {
                        long usageTime = event.getTimeStamp() - startTimeStamp;
                        usageMap.put(packageName, usageMap.getOrDefault(packageName, 0L) + usageTime);
                        appStartTimes.remove(packageName);
                    }
                }
            }
            
            Log.d(TAG, "📊 Collected usage for " + usageMap.size() + " apps in time range");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error collecting usage for time range: " + e.getMessage());
        }
        
        return usageMap;
    }
    
    /**
     * 🏗️ Create day data object for Firebase
     */
    private Map<String, Object> createDayDataObject(long dayStartTime, Map<String, Long> usageData) {
        Map<String, Object> dayData = new HashMap<>();
        dayData.put("date", dayStartTime);
        dayData.put("total_apps", usageData.size());
        
        List<Map<String, Object>> appsList = new ArrayList<>();
        for (Map.Entry<String, Long> entry : usageData.entrySet()) {
            Map<String, Object> appData = new HashMap<>();
            appData.put("packageName", entry.getKey());
            appData.put("usageTime", entry.getValue());
            appsList.add(appData);
        }
        
        dayData.put("apps", appsList);
        return dayData;
    }
    
    /**
     * 🎯 Get days since tracking started
     */
    public long getDaysSinceTrackingStarted() {
        return getCurrentDayNumber() + 1; // +1 because day 0 is the first day
    }
    
    /**
     * 📅 Get tracking start date
     */
    public Date getTrackingStartDate() {
        long startTime = getTrackingStartTime();
        return startTime > 0 ? new Date(startTime) : null;
    }
    
    /**
     * 🎯 Check if we're in the smart tracking window
     */
    public boolean isInTrackingWindow() {
        return getTrackingStartTime() > 0 && getCurrentDayNumber() >= 0;
    }
}