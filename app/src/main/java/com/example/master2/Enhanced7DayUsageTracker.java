package com.example.master2;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import com.example.master2.models.AppUsage;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 🎯 ENHANCED 7-DAY ROLLING USAGE TRACKER - REAL-TIME MIDNIGHT DETECTION
 * 
 * Features:
 * - Smart tracking from connection time
 * - Rolling 7-day window that auto-updates
 * - REAL-TIME MIDNIGHT DETECTION: Automatically creates new day at 00:00
 * - Day 8 removes Day 1 data automatically
 * - Real-time bar chart data generation
 * - Clickable daily breakdown
 * - Automatic daily reset monitoring like SmartTimerService
 */
public class Enhanced7DayUsageTracker {
    private static final String TAG = "Enhanced7DayTracker";
    
    // Constants
    private static final int MAX_DAYS = 7;
    private static final long DAY_IN_MILLIS = TimeUnit.DAYS.toMillis(1);
    private static final String PREF_NAME = "enhanced_7day_tracker";
    private static final String KEY_CONNECTION_TIME = "connection_time";
    private static final String KEY_LAST_UPDATE_DAY = "last_update_day";
    private static final String KEY_LAST_CALENDAR_DAY = "last_calendar_day";
    
    // Context and Firebase
    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final DatabaseReference firebaseRef;
    private final SharedPreferences prefs;
    private final String deviceId;
    private final PackageManager packageManager;
    
    // 📱 INSTALLED APPS CACHE - Efficient like other parental control apps
    private Set<String> installedUserApps = null;
    private long lastInstalledAppsUpdate = 0;
    private static final long INSTALLED_APPS_CACHE_DURATION = 5 * 60 * 1000L; // 5 minutes cache
    
    // 🌙 REAL-TIME MIDNIGHT MONITORING - Like SmartTimerService
    private Handler midnightHandler;
    private Runnable midnightChecker;
    private boolean isMidnightMonitoringActive = false;
    private String lastDetectedDay = "";
    
    // 🚫 COMPREHENSIVE SYSTEM APP FILTERING - Only real user apps
    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
        // Core Android System
        "android", "com.android.systemui", "com.android.launcher3", "com.android.settings",
        "com.android.permissioncontroller", "com.android.providers.settings",
        "com.android.shell", "com.android.externalstorage", "com.android.documentsui",
        "com.android.packageinstaller", "com.android.server.telecom", "system",
        
        // Google System Apps
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.googlequicksearchbox",
        "com.google.android.permissioncontroller", "com.google.android.cellbroadcastreceiver",
        "com.google.android.networkstack", "com.google.android.connectivitymonitor",
        
        // Launchers and Home Screens 
        "com.miui.home", "com.miui.systemui", "com.xiaomi.launcher", "com.mi.android.globallauncher",
        "com.huawei.android.launcher", "com.oneplus.launcher", "com.oppo.launcher",
        "com.samsung.android.launcher", "com.sec.android.app.launcher", "nova.launcher",
        "com.android.launcher", "com.google.android.launcher", "com.vivo.launcher",
        
        // Input Methods and Keyboards
        "com.google.android.inputmethod.latin", "com.touchtype.swiftkey", "com.samsung.android.honeyboard",
        "com.miui.securityinputmethod", "com.sohu.inputmethod.sogou", "com.baidu.input",
        "com.android.inputmethod", "com.google.android.inputmethod",
        
        // System Services and Background
        "com.android.externalstorage", "com.android.providers.media", "com.android.bluetooth",
        "com.android.nfc", "com.android.phone", "com.android.dialer", "com.android.calendar",
        "com.android.contacts", "com.android.mms", "com.android.email", "com.android.gallery3d",
        "com.android.keychain", "com.android.wallpaper", "com.miui.wallpaper", 
        "com.android.wallpaper.livepicker",
        
        // Device Manufacturer System Apps
        "com.miui.securitycenter", "com.miui.cleaner", "com.xiaomi.market", "com.mi.globalbrowser",
        "com.samsung.android.app.settings", "com.samsung.android.spay", "com.samsung.android.bixby",
        "com.huawei.appmarket", "com.huawei.systemmanager", "com.oppo.safe", "com.oneplus.security",
        
        // This App (Parental Control)
        "com.example.master2"
    ));

    public Enhanced7DayUsageTracker(Context context) {
        this.context = context;
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = context.getPackageManager();
        this.deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.firebaseRef = FirebaseDatabase.getInstance().getReference("enhanced_usage_data").child(deviceId);
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        initializeConnectionTime();
        
        // 📱 Initialize installed apps cache for efficient tracking
        updateInstalledAppsCache();
        
        // 🌙 START REAL-TIME MIDNIGHT MONITORING for automatic daily data separation
        startMidnightMonitoring();
    }
    
    /**
     * 🔗 Initialize connection time from when device was first connected
     */
    private void initializeConnectionTime() {
        long savedConnectionTime = prefs.getLong(KEY_CONNECTION_TIME, 0);
        
        if (savedConnectionTime == 0) {
            // Try to get from session manager or connection preferences
            SessionManager sessionManager = new SessionManager(context);
            if (sessionManager.isLoggedIn() && "child".equals(sessionManager.getUserType())) {
                long connectionTime = System.currentTimeMillis();
                
                // Try to get actual connection time from other sources
                SharedPreferences connectionPrefs = context.getSharedPreferences("device_connection", Context.MODE_PRIVATE);
                long actualConnectionTime = connectionPrefs.getLong("connection_time", 0);
                
                if (actualConnectionTime > 0) {
                    connectionTime = actualConnectionTime;
                }
                
                prefs.edit().putLong(KEY_CONNECTION_TIME, connectionTime).apply();
                Log.d(TAG, "🔗 Connection time set to: " + new Date(connectionTime));
            }
        } else {
            Log.d(TAG, "📅 Using saved connection time: " + new Date(savedConnectionTime));
        }
    }
    
    /**
     * 📊 Generate and upload 7-day rolling usage data - FIXED DAILY SEPARATION
     * FIXED: Preserve past day data, only update current day if needed
     */
    public void generateAndUpload7DayData() {
        long connectionTime = prefs.getLong(KEY_CONNECTION_TIME, 0);
        if (connectionTime == 0) {
            Log.e(TAG, "❌ No connection time available - cannot generate usage data");
            return;
        }
        
        Log.d(TAG, "📊 FIXED: Generating 7-day data preserving past day data");
        
        // Store today's data if not already stored
        String todayKey = getTodayDateKey();
        String lastStoredDay = prefs.getString(KEY_LAST_CALENDAR_DAY, "");
        
        if (!todayKey.equals(lastStoredDay)) {
            Log.d(TAG, "📅 Storing fresh data for today: " + todayKey);
            storeTodayDataSeparately();
            prefs.edit().putString(KEY_LAST_CALENDAR_DAY, todayKey).apply();
        } else {
            Log.d(TAG, "📅 Today's data already stored for: " + todayKey);
        }
        
        // Update 7-day summary from stored daily data
        updateSevenDaySummaryPreservingPastData();
    }
    
    /**
     * 📅 Calculate which calendar days to include - ENHANCED DAILY SEPARATION
     * Always shows last 7 days including today, with proper midnight-to-midnight separation
     */
    private List<Calendar> calculate7DayCalendarWindow() {
        List<Calendar> daysToInclude = new ArrayList<>();
        
        Log.d(TAG, "📅 Creating 7-day window with enhanced daily separation");
        
        // Always show last 7 days including today for consistent UI
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        
        // Generate 7 days: 6 days ago, 5 days ago, ..., yesterday, today
        for (int i = 6; i >= 0; i--) {
            Calendar day = (Calendar) today.clone();
            day.add(Calendar.DAY_OF_YEAR, -i);
            daysToInclude.add(day);
        }
        
        // Log the calendar days for debugging
        StringBuilder dayLabels = new StringBuilder();
        for (Calendar day : daysToInclude) {
            dayLabels.append(getCalendarDayLabel(day)).append(" ");
        }
        
        Log.d(TAG, "🗓️ Enhanced 7-day window: " + dayLabels.toString());
        Log.d(TAG, "📊 Showing " + daysToInclude.size() + " days with midnight-to-midnight separation");
        
        return daysToInclude;
    }
    
    /**
     * 📊 Collect usage data for a specific calendar day - MIDNIGHT TO MIDNIGHT
     */
    private DayUsageData collectUsageForCalendarDay(Calendar calendarDay) {
        // Get start of day (00:00:00.000)
        Calendar dayStart = (Calendar) calendarDay.clone();
        dayStart.set(Calendar.HOUR_OF_DAY, 0);
        dayStart.set(Calendar.MINUTE, 0);
        dayStart.set(Calendar.SECOND, 0);
        dayStart.set(Calendar.MILLISECOND, 0);
        
        // Get end of day (23:59:59.999)
        Calendar dayEnd = (Calendar) calendarDay.clone();
        dayEnd.set(Calendar.HOUR_OF_DAY, 23);
        dayEnd.set(Calendar.MINUTE, 59);
        dayEnd.set(Calendar.SECOND, 59);
        dayEnd.set(Calendar.MILLISECOND, 999);
        
        // Don't go beyond current time
        long dayEndTime = Math.min(dayEnd.getTimeInMillis(), System.currentTimeMillis());
        
        String dayLabel = getCalendarDayLabel(calendarDay);
        
        Log.d(TAG, "🗓️ Collecting data for " + dayLabel + 
               " from " + dayStart.getTime() + " to " + new Date(dayEndTime));
        
        Map<String, Long> appUsageMap = collectUsageForTimeRange(dayStart.getTimeInMillis(), dayEndTime);
        
        // Convert to AppUsage objects
        List<AppUsage> appUsageList = new ArrayList<>();
        long totalUsageMs = 0;
        
        for (Map.Entry<String, Long> entry : appUsageMap.entrySet()) {
            String packageName = entry.getKey();
            Long usageMs = entry.getValue();
            
            if (usageMs > 0) {
                AppUsage appUsage = new AppUsage();
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
     * 📊 Collect usage for specific time range using UsageEvents
     * ENHANCED: Only tracks installed user apps efficiently like other parental controls
     */
    private Map<String, Long> collectUsageForTimeRange(long startTime, long endTime) {
        Map<String, Long> usageMap = new HashMap<>();
        
        if (usageStatsManager == null) {
            Log.e(TAG, "❌ UsageStatsManager is null");
            return usageMap;
        }
        
        try {
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
            Map<String, Long> appStartTimes = new HashMap<>();
            
            int totalEvents = 0;
            int filteredSystemEvents = 0;
            int trackedUserAppEvents = 0;
            
            while (usageEvents.hasNextEvent()) {
                UsageEvents.Event event = new UsageEvents.Event();
                usageEvents.getNextEvent(event);
                totalEvents++;
                
                String packageName = event.getPackageName();
                
                // 🚀 FAST FILTERING: Check installed user apps cache first (most efficient)
                if (!isInstalledUserApp(packageName)) {
                    filteredSystemEvents++;
                    continue;
                }
                
                // 🚫 BACKUP FILTERING: Double-check with ignored packages (in case cache missed something)
                if (IGNORED_PACKAGES.contains(packageName)) {
                    filteredSystemEvents++;
                    continue;
                }
                
                trackedUserAppEvents++;
                
                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    appStartTimes.put(packageName, event.getTimeStamp());
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                    Long startTime1 = appStartTimes.get(packageName);
                    if (startTime1 != null) {
                        long sessionDuration = event.getTimeStamp() - startTime1;
                        usageMap.put(packageName, usageMap.getOrDefault(packageName, 0L) + sessionDuration);
                        appStartTimes.remove(packageName);
                    }
                }
            }
            
            Log.d(TAG, "📊 Usage collection efficiency: " + trackedUserAppEvents + " user app events tracked, " +
                  filteredSystemEvents + " system events filtered out of " + totalEvents + " total events");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error collecting usage events: " + e.getMessage());
        }
        
        return usageMap;
    }
    
    /**
     * 🔄 Upload snapshot to Firebase
     */
    private void uploadSnapshotToFirebase(UsageSnapshot snapshot, List<String> dayLabels) {
        Map<String, Object> dataToUpload = new HashMap<>();
        dataToUpload.put("timestamp", snapshot.timestamp);
        dataToUpload.put("bars", snapshot.bars);
        dataToUpload.put("dailyApps", snapshot.dailyApps);
        dataToUpload.put("totalTexts", snapshot.totalTexts);
        dataToUpload.put("dayLabels", dayLabels);
        dataToUpload.put("deviceId", snapshot.deviceId);
        
        firebaseRef.child("latest").setValue(dataToUpload)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ 7-day usage data uploaded successfully!");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to upload 7-day data: " + e.getMessage());
            });
    }
    
    /**
     * � Check if app should be filtered out based on package name patterns or app names
     */
    private boolean isSystemAppToFilter(String packageName) {
        try {
            // Check package name patterns - COMPREHENSIVE FILTERING
            if (packageName.contains("launcher") || 
                packageName.contains("systemui") || 
                packageName.contains("wallpaper") ||
                packageName.contains("inputmethod") ||
                packageName.contains("keyboard") ||
                packageName.contains("homescreen") ||
                packageName.contains("system") ||
                packageName.contains("android.") ||
                packageName.startsWith("com.android.") ||
                packageName.startsWith("com.google.android.") ||
                packageName.startsWith("android.") ||
                packageName.startsWith("com.miui.") ||
                packageName.startsWith("com.xiaomi.") ||
                packageName.startsWith("com.samsung.android.") ||
                packageName.startsWith("com.huawei.") ||
                packageName.startsWith("com.oppo.") ||
                packageName.startsWith("com.vivo.") ||
                packageName.startsWith("com.oneplus.")) {
                return true;
            }
            
            // Check app display name for system apps - ENHANCED
            String appName = getAppDisplayName(packageName).toLowerCase();
            if (appName.contains("system") ||
                appName.contains("home screen") ||
                appName.contains("launcher") ||
                appName.contains("wallpaper") ||
                appName.contains("keyboard") ||
                appName.contains("input method") ||
                appName.contains("android system") ||
                appName.contains("system ui") ||
                appName.equals("system") ||
                appName.equals("android") ||
                appName.isEmpty() ||
                appName.length() < 2) {
                return true;
            }
            
            // Check if it's a system app using ApplicationInfo
            try {
                ApplicationInfo appInfo = context.getPackageManager().getApplicationInfo(packageName, 0);
                // Filter system apps that aren't user-installed
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && 
                    (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    return true;
                }
            } catch (Exception appInfoException) {
                // If package not found, filter it out
                return true;
            }
            
            return false;
        } catch (Exception e) {
            // If we can't determine, err on the side of filtering system apps
            Log.w(TAG, "Error filtering package " + packageName + ": " + e.getMessage());
            return true;
        }
    }
    
    /**
     * 📱 Get app display name - ENHANCED with caching
     */
    private String getAppDisplayName(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            CharSequence appLabel = packageManager.getApplicationLabel(appInfo);
            return appLabel != null ? appLabel.toString() : packageName;
        } catch (Exception e) {
            // If app is not found, it's likely uninstalled - return package name
            return packageName;
        }
    }
    
    /**
     * ⏰ Format usage time
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
     * 📅 Get calendar day label for display
     */
    private String getCalendarDayLabel(Calendar calendar) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd", Locale.getDefault());
        return sdf.format(calendar.getTime());
    }
    
    /**
     * 📅 Get today's date key for tracking last processed calendar day
     */
    private String getTodayDateKey() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(new Date());
    }
    
    /**
     * 📱 INSTALLED APPS CACHE MANAGEMENT - Efficient app tracking like other parental controls
     */
    private void updateInstalledAppsCache() {
        long currentTime = System.currentTimeMillis();
        
        // Check if cache is still valid
        if (installedUserApps != null && 
            currentTime - lastInstalledAppsUpdate < INSTALLED_APPS_CACHE_DURATION) {
            return; // Cache is still fresh
        }
        
        Log.d(TAG, "📱 Updating installed user apps cache for efficient tracking");
        
        Set<String> newInstalledApps = new HashSet<>();
        
        try {
            // Get all installed packages
            List<ApplicationInfo> installedPackages = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA
            );
            
            int totalPackages = installedPackages.size();
            int userApps = 0;
            int systemAppsFiltered = 0;
            
            for (ApplicationInfo appInfo : installedPackages) {
                String packageName = appInfo.packageName;
                
                // Skip if in ignored packages list
                if (IGNORED_PACKAGES.contains(packageName)) {
                    systemAppsFiltered++;
                    continue;
                }
                
                // Skip system apps and pre-installed apps (be more strict like other parental controls)
                if (isSystemApp(appInfo) || isSystemAppToFilter(packageName)) {
                    systemAppsFiltered++;
                    continue;
                }
                
                // Only include user-installed apps that have a proper name and are visible
                if (isValidUserApp(appInfo)) {
                    newInstalledApps.add(packageName);
                    userApps++;
                }
            }
            
            installedUserApps = newInstalledApps;
            lastInstalledAppsUpdate = currentTime;
            
            Log.d(TAG, "✅ Installed apps cache updated: " + userApps + " user apps out of " + 
                  totalPackages + " total packages (" + systemAppsFiltered + " system apps filtered)");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating installed apps cache: " + e.getMessage());
            // Fallback to empty set if there's an error
            if (installedUserApps == null) {
                installedUserApps = new HashSet<>();
            }
        }
    }
    
    /**
     * 🔍 Check if app is a system app (more precise than just checking flags)
     */
    private boolean isSystemApp(ApplicationInfo appInfo) {
        // Check various system app indicators
        return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
               (appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 ||
               appInfo.sourceDir.startsWith("/system/") ||
               appInfo.sourceDir.startsWith("/vendor/") ||
               appInfo.sourceDir.startsWith("/product/");
    }
    
    /**
     * ✅ Check if this is a valid user app worth tracking
     */
    private boolean isValidUserApp(ApplicationInfo appInfo) {
        try {
            String packageName = appInfo.packageName;
            
            // Must have a proper display name
            CharSequence appLabel = packageManager.getApplicationLabel(appInfo);
            if (appLabel == null || appLabel.toString().trim().isEmpty() || 
                appLabel.toString().trim().length() < 2) {
                return false;
            }
            
            // Must be launchable (have a launcher intent)
            Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                return false;
            }
            
            // Must be enabled
            if (!appInfo.enabled) {
                return false;
            }
            
            // Additional filtering for hidden or system-like apps
            String appName = appLabel.toString().toLowerCase();
            if (appName.contains("system") || appName.contains("android") || 
                appName.contains("google play services") || appName.contains("framework")) {
                return false;
            }
            
            return true;
            
        } catch (Exception e) {
            Log.w(TAG, "Error validating app " + appInfo.packageName + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 📱 Check if package is in our installed user apps cache
     */
    private boolean isInstalledUserApp(String packageName) {
        // Update cache if needed
        updateInstalledAppsCache();
        
        return installedUserApps != null && installedUserApps.contains(packageName);
    }
    
    /**
     * 🌙 REAL-TIME MIDNIGHT DETECTION for automatic new day creation
     * Same logic as SmartTimerService for consistent behavior
     */
    private void startMidnightMonitoring() {
        if (isMidnightMonitoringActive) {
            Log.d(TAG, "🌙 Midnight monitoring already active");
            return;
        }
        
        Log.d(TAG, "🌙 Starting real-time midnight monitoring for usage data daily separation");
        
        // Initialize current day
        lastDetectedDay = getTodayDateKey();
        
        // Create a handler that checks time every minute
        midnightHandler = new Handler(Looper.getMainLooper());
        midnightChecker = new Runnable() {
            @Override
            public void run() {
                Calendar now = Calendar.getInstance();
                int currentHour = now.get(Calendar.HOUR_OF_DAY);
                int currentMinute = now.get(Calendar.MINUTE);
                String currentDay = getTodayDateKey();
                
                // Check if it's midnight (00:00) OR if the day has changed
                if ((currentHour == 0 && currentMinute == 0) || !currentDay.equals(lastDetectedDay)) {
                    Log.d(TAG, "🕛 MIDNIGHT/NEW DAY DETECTED! Creating fresh daily usage data");
                    Log.d(TAG, "   Previous day: " + lastDetectedDay + " → Current day: " + currentDay);
                    
                    // Update last detected day
                    lastDetectedDay = currentDay;
                    
                    // Automatically generate fresh 7-day data with new day
                    onNewDayDetected();
                }
                
                // Schedule next check in 60 seconds
                if (midnightHandler != null) {
                    midnightHandler.postDelayed(this, 60000);
                }
            }
        };
        
        // Start monitoring
        isMidnightMonitoringActive = true;
        midnightHandler.post(midnightChecker);
        
        Log.d(TAG, "✅ Real-time midnight monitoring started for usage data");
    }
    
    /**
     * 🌅 Handle new day detection - create fresh daily usage data
     * FIXED: Only process NEW day data, preserve past day data
     */
    private void onNewDayDetected() {
        try {
            Log.d(TAG, "🌅 NEW DAY USAGE DATA CREATION!");
            Log.d(TAG, "   FIXED: Only processing NEW day, preserving all past day data");
            
            // Get today's date key
            String todayKey = getTodayDateKey();
            String lastProcessedDay = prefs.getString(KEY_LAST_CALENDAR_DAY, "");
            
            if (!todayKey.equals(lastProcessedDay)) {
                Log.d(TAG, "📅 Processing new day: " + todayKey + " (last processed: " + lastProcessedDay + ")");
                
                // Only collect and store TODAY's data to its own Firebase node
                storeTodayDataSeparately();
                
                // Update the last processed day
                prefs.edit().putString(KEY_LAST_CALENDAR_DAY, todayKey).apply();
                
                // Update the 7-day summary WITHOUT regenerating past data
                updateSevenDaySummaryPreservingPastData();
                
                Log.d(TAG, "✅ New day data stored successfully - past data preserved");
            } else {
                Log.d(TAG, "📅 Same day (" + todayKey + ") - no new day processing needed");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error creating new day usage data: " + e.getMessage());
        }
    }
    
    /**
     * 🛑 Stop midnight monitoring (cleanup method)
     */
    public void stopMidnightMonitoring() {
        if (midnightHandler != null && midnightChecker != null) {
            midnightHandler.removeCallbacks(midnightChecker);
            midnightHandler = null;
            midnightChecker = null;
            isMidnightMonitoringActive = false;
            Log.d(TAG, "🛑 Midnight monitoring stopped");
        }
    }
    
    /**
     * � Store TODAY's usage data in its own Firebase node (date-specific)
     * This preserves today's data without touching past days
     */
    private void storeTodayDataSeparately() {
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        
        String todayKey = getTodayDateKey();
        Log.d(TAG, "📊 Storing TODAY's data separately for: " + todayKey);
        
        // Collect ONLY today's usage data
        DayUsageData todayData = collectUsageForCalendarDay(today);
        
        // Store today's data in its own Firebase node
        Map<String, Object> todayDataMap = new HashMap<>();
        todayDataMap.put("date", todayKey);
        todayDataMap.put("timestamp", System.currentTimeMillis());
        todayDataMap.put("totalMinutes", todayData.totalMinutes);
        todayDataMap.put("totalTimeText", todayData.totalTimeText);
        todayDataMap.put("appUsageList", todayData.appUsageList);
        todayDataMap.put("dayLabel", todayData.dayLabel);
        
        // Store in date-specific Firebase path
        firebaseRef.child("daily_data").child(todayKey).setValue(todayDataMap)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ TODAY's data (" + todayKey + ") stored successfully!");
                Log.d(TAG, "   📈 " + todayData.totalTimeText + " across " + todayData.appUsageList.size() + " apps");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to store today's data: " + e.getMessage());
            });
    }
    
    /**
     * 📊 Update 7-day summary by reading individual day data (preserves past data)
     * Instead of regenerating all days, reads stored daily data
     */
    private void updateSevenDaySummaryPreservingPastData() {
        Log.d(TAG, "📋 Updating 7-day summary by reading stored daily data");
        
        List<String> last7Days = getLast7DayKeys();
        List<Float> barValues = new ArrayList<>();
        List<List<AppUsage>> dailyAppLists = new ArrayList<>();
        List<String> dailyTotals = new ArrayList<>();
        List<String> dayLabels = new ArrayList<>();
        
        final int[] processedCount = {0}; // Counter for async operations
        
        // Read each day's stored data from Firebase
        for (String dayKey : last7Days) {
            firebaseRef.child("daily_data").child(dayKey).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    if (snapshot.exists()) {
                        // Use stored data for this day
                        Float totalMinutes = snapshot.child("totalMinutes").getValue(Float.class);
                        String totalTimeText = snapshot.child("totalTimeText").getValue(String.class);
                        String dayLabel = snapshot.child("dayLabel").getValue(String.class);
                        
                        barValues.add(totalMinutes != null ? totalMinutes : 0f);
                        dailyTotals.add(totalTimeText != null ? totalTimeText : "0m");
                        dayLabels.add(dayLabel != null ? dayLabel : dayKey);
                        
                        // Get app list
                        List<AppUsage> appList = new ArrayList<>();
                        DataSnapshot appListSnapshot = snapshot.child("appUsageList");
                        if (appListSnapshot.exists()) {
                            for (DataSnapshot appSnapshot : appListSnapshot.getChildren()) {
                                AppUsage app = appSnapshot.getValue(AppUsage.class);
                                if (app != null) {
                                    appList.add(app);
                                }
                            }
                        }
                        dailyAppLists.add(appList);
                        
                        Log.d(TAG, "📅 Read stored data for " + dayKey + ": " + totalTimeText);
                    } else {
                        // No data for this day yet - use empty data
                        barValues.add(0f);
                        dailyAppLists.add(new ArrayList<>());
                        dailyTotals.add("0m");
                        dayLabels.add(dayKey);
                        Log.d(TAG, "📭 No data found for " + dayKey + " - using empty data");
                    }
                    
                    processedCount[0]++;
                    
                    // When all days are processed, upload the summary
                    if (processedCount[0] == last7Days.size()) {
                        uploadSevenDaySummary(barValues, dailyAppLists, dailyTotals, dayLabels);
                    }
                }
                
                @Override
                public void onCancelled(DatabaseError error) {
                    Log.e(TAG, "❌ Error reading day data for " + dayKey + ": " + error.getMessage());
                    processedCount[0]++;
                    
                    // Still proceed even if some reads fail
                    if (processedCount[0] == last7Days.size()) {
                        uploadSevenDaySummary(barValues, dailyAppLists, dailyTotals, dayLabels);
                    }
                }
            });
        }
    }
    
    /**
     * 📅 Get the last 7 day keys (date strings) in YYYY-MM-DD format
     */
    private List<String> getLast7DayKeys() {
        List<String> dayKeys = new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        
        // Generate last 7 days including today
        for (int i = 6; i >= 0; i--) {
            Calendar day = (Calendar) cal.clone();
            day.add(Calendar.DAY_OF_YEAR, -i);
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            dayKeys.add(sdf.format(day.getTime()));
        }
        
        return dayKeys;
    }
    
    /**
     * 📊 Upload the 7-day summary to Firebase
     */
    private void uploadSevenDaySummary(List<Float> barValues, List<List<AppUsage>> dailyAppLists, 
                                      List<String> dailyTotals, List<String> dayLabels) {
        
        UsageSnapshot snapshot = new UsageSnapshot(
            System.currentTimeMillis(),
            barValues,
            dailyAppLists,
            dailyTotals
        );
        snapshot.deviceId = deviceId;
        
        Map<String, Object> summaryData = new HashMap<>();
        summaryData.put("timestamp", snapshot.timestamp);
        summaryData.put("bars", snapshot.bars);
        summaryData.put("dailyApps", snapshot.dailyApps);
        summaryData.put("totalTexts", snapshot.totalTexts);
        summaryData.put("dayLabels", dayLabels);
        summaryData.put("deviceId", snapshot.deviceId);
        summaryData.put("generated_from_stored_daily_data", true); // Mark as assembled from stored data
        
        firebaseRef.child("latest").setValue(summaryData)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ 7-day summary updated successfully from stored daily data!");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to upload 7-day summary: " + e.getMessage());
            });
    }

    /**
     * �📊 Internal class for day usage data
     */
    private static class DayUsageData {
        final List<AppUsage> appUsageList;
        final float totalMinutes;
        final String totalTimeText;
        final String dayLabel;
        
        DayUsageData(List<AppUsage> appUsageList, float totalMinutes, String totalTimeText, String dayLabel) {
            this.appUsageList = appUsageList;
            this.totalMinutes = totalMinutes;
            this.totalTimeText = totalTimeText;
            this.dayLabel = dayLabel;
        }
    }
}