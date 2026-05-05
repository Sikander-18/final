package com.example.susage.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;

import com.example.susage.models.AppUsageInfo;
import com.example.susage.models.DailyUsage;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manager class for fetching and processing app usage statistics.
 * Uses UsageStatsManager API available on Android 5.0+ (fully functional on Android 10+)
 */
public class UsageDataManager {
    
    private static UsageDataManager instance;
    private final Context context;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;

    // Categories for app classification
    private static final Map<String, String> CATEGORY_MAP = new HashMap<>();
    
    // Apps that should always be included even if they appear as system apps
    private static final List<String> ALWAYS_INCLUDE = new ArrayList<>();
    
    static {
        // Communication apps
        CATEGORY_MAP.put("com.whatsapp", "Communication");
        CATEGORY_MAP.put("com.facebook.orca", "Communication");
        CATEGORY_MAP.put("org.telegram.messenger", "Communication");
        CATEGORY_MAP.put("com.discord", "Communication");
        CATEGORY_MAP.put("com.skype.raider", "Communication");
        CATEGORY_MAP.put("com.viber.voip", "Communication");
        CATEGORY_MAP.put("com.google.android.apps.messaging", "Communication");
        
        // Entertainment apps
        CATEGORY_MAP.put("com.google.android.youtube", "Entertainment");
        CATEGORY_MAP.put("com.netflix.mediaclient", "Entertainment");
        CATEGORY_MAP.put("com.spotify.music", "Entertainment");
        CATEGORY_MAP.put("com.amazon.avod.thirdpartyclient", "Entertainment");
        CATEGORY_MAP.put("com.disney.disneyplus", "Entertainment");
        
        // Social apps
        CATEGORY_MAP.put("com.instagram.android", "Entertainment");
        CATEGORY_MAP.put("com.facebook.katana", "Entertainment");
        CATEGORY_MAP.put("com.twitter.android", "Entertainment");
        CATEGORY_MAP.put("com.zhiliaoapp.musically", "Entertainment"); // TikTok
        CATEGORY_MAP.put("com.reddit.frontpage", "Entertainment");
        CATEGORY_MAP.put("com.snapchat.android", "Entertainment");
        
        // Games (common prefixes)
        CATEGORY_MAP.put("com.supercell", "Games");
        CATEGORY_MAP.put("com.king", "Games");
        CATEGORY_MAP.put("com.miniclip", "Games");
        CATEGORY_MAP.put("com.ea", "Games");
        CATEGORY_MAP.put("com.gameloft", "Games");
        
        // Apps to always include (even if system)
        ALWAYS_INCLUDE.add("com.android.chrome");
        ALWAYS_INCLUDE.add("com.google.android.youtube");
        ALWAYS_INCLUDE.add("com.google.android.gm"); // Gmail
        ALWAYS_INCLUDE.add("com.google.android.apps.photos");
        ALWAYS_INCLUDE.add("com.google.android.apps.maps");
        ALWAYS_INCLUDE.add("com.android.vending"); // Play Store
        ALWAYS_INCLUDE.add("com.google.android.dialer");
        ALWAYS_INCLUDE.add("com.google.android.contacts");
        ALWAYS_INCLUDE.add("com.google.android.calendar");
        ALWAYS_INCLUDE.add("com.google.android.keep");
        ALWAYS_INCLUDE.add("com.google.android.apps.docs");
        ALWAYS_INCLUDE.add("com.google.android.apps.drive");
        ALWAYS_INCLUDE.add("com.android.settings");
        ALWAYS_INCLUDE.add("com.sec.android.app.sbrowser"); // Samsung Browser
        ALWAYS_INCLUDE.add("com.brave.browser");
        ALWAYS_INCLUDE.add("org.mozilla.firefox");
    }

    private UsageDataManager(Context context) {
        this.context = context.getApplicationContext();
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = context.getPackageManager();
    }

    public static synchronized UsageDataManager getInstance(Context context) {
        if (instance == null) {
            instance = new UsageDataManager(context);
        }
        return instance;
    }

    /**
     * Get usage statistics for today
     */
    public DailyUsage getTodayUsage() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        
        long startTime = calendar.getTimeInMillis();
        long endTime = System.currentTimeMillis();
        
        return getUsageForPeriodUsingEvents(startTime, endTime, Calendar.getInstance());
    }

    /**
     * Get weekly usage data (last 7 days)
     */
    public List<DailyUsage> getWeeklyUsage() {
        List<DailyUsage> weeklyData = new ArrayList<>();
        
        Calendar calendar = Calendar.getInstance();
        
        // Get data for last 7 days (including today)
        for (int i = 6; i >= 0; i--) {
            Calendar dayCalendar = (Calendar) calendar.clone();
            dayCalendar.add(Calendar.DAY_OF_YEAR, -i);
            dayCalendar.set(Calendar.HOUR_OF_DAY, 0);
            dayCalendar.set(Calendar.MINUTE, 0);
            dayCalendar.set(Calendar.SECOND, 0);
            dayCalendar.set(Calendar.MILLISECOND, 0);
            
            long startTime = dayCalendar.getTimeInMillis();
            
            Calendar endCalendar = (Calendar) dayCalendar.clone();
            endCalendar.add(Calendar.DAY_OF_YEAR, 1);
            long endTime = endCalendar.getTimeInMillis();
            
            // For today, use current time as end
            if (i == 0) {
                endTime = System.currentTimeMillis();
            }
            
            DailyUsage dailyUsage = getUsageForPeriodUsingEvents(startTime, endTime, dayCalendar);
            weeklyData.add(dailyUsage);
        }
        
        return weeklyData;
    }

    /**
     * Get usage for a specific time period using UsageEvents for accurate per-day data.
     * This method calculates foreground time by tracking ACTIVITY_RESUMED and ACTIVITY_PAUSED events.
     * 
     * IMPORTANT: We only use UsageEvents because queryUsageStats and queryAndAggregateUsageStats
     * return CUMULATIVE foreground time (total since install), not time for the specific date range.
     */
    private DailyUsage getUsageForPeriodUsingEvents(long startTime, long endTime, Calendar date) {
        DailyUsage dailyUsage = new DailyUsage(date);
        
        if (usageStatsManager == null) {
            return dailyUsage;
        }
        
        Map<String, Long> usageMap = new HashMap<>();
        
        // Use events-based calculation - this is the ONLY accurate method for per-day data
        UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
        
        if (usageEvents != null) {
            Map<String, Long> lastResumeTime = new HashMap<>();
            
            UsageEvents.Event event = new UsageEvents.Event();
            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);
                String packageName = event.getPackageName();
                long eventTime = event.getTimeStamp();
                
                // Only process events within our time range
                if (eventTime < startTime || eventTime > endTime) {
                    continue;
                }
                
                int eventType = event.getEventType();
                
                if (eventType == UsageEvents.Event.ACTIVITY_RESUMED || 
                    eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    // App moved to foreground
                    lastResumeTime.put(packageName, eventTime);
                } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED || 
                           eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    // App moved to background
                    Long resumeTime = lastResumeTime.get(packageName);
                    if (resumeTime != null && resumeTime >= startTime) {
                        long duration = eventTime - resumeTime;
                        if (duration > 0 && duration < 24 * 60 * 60 * 1000) { // Max 24 hours
                            long currentTotal = usageMap.getOrDefault(packageName, 0L);
                            usageMap.put(packageName, currentTotal + duration);
                        }
                    }
                    lastResumeTime.remove(packageName);
                }
            }
            
            // Handle apps that are still in foreground (only for today/current period)
            long now = System.currentTimeMillis();
            if (now >= startTime && now <= endTime) {
                for (Map.Entry<String, Long> entry : lastResumeTime.entrySet()) {
                    long resumeTime = entry.getValue();
                    if (resumeTime >= startTime) {
                        long duration = now - resumeTime;
                        if (duration > 0 && duration < 24 * 60 * 60 * 1000) { // Max 24 hours sanity check
                            long currentTotal = usageMap.getOrDefault(entry.getKey(), 0L);
                            usageMap.put(entry.getKey(), currentTotal + duration);
                        }
                    }
                }
            }
        }
        
        // Convert to AppUsageInfo list
        List<AppUsageInfo> appUsageList = new ArrayList<>();
        long totalTime = 0;
        long communicationTime = 0;
        long entertainmentTime = 0;
        long gamesTime = 0;
        
        for (Map.Entry<String, Long> entry : usageMap.entrySet()) {
            String packageName = entry.getKey();
            long usageTime = entry.getValue();
            
            // Skip apps with almost no usage (less than 1 second)
            if (usageTime < 1000) {
                continue;
            }
            
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                
                // Skip system apps unless they are in the whitelist
                boolean isSystemApp = (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean isWhitelisted = ALWAYS_INCLUDE.contains(packageName);
                
                if (isSystemApp && !isWhitelisted) {
                    continue;
                }
                
                String appName = packageManager.getApplicationLabel(appInfo).toString();
                Drawable appIcon = packageManager.getApplicationIcon(appInfo);
                String category = getAppCategory(packageName);
                
                AppUsageInfo appUsageInfo = new AppUsageInfo(
                        packageName, appName, appIcon, usageTime, category
                );
                appUsageList.add(appUsageInfo);
                
                totalTime += usageTime;
                
                // Categorize time
                switch (category) {
                    case "Communication":
                        communicationTime += usageTime;
                        break;
                    case "Entertainment":
                        entertainmentTime += usageTime;
                        break;
                    case "Games":
                        gamesTime += usageTime;
                        break;
                }
                
            } catch (PackageManager.NameNotFoundException e) {
                // App not found, skip
            }
        }
        
        // Sort by usage time (descending)
        Collections.sort(appUsageList, (a, b) -> 
                Long.compare(b.getUsageTimeMillis(), a.getUsageTimeMillis()));
        
        dailyUsage.setAppUsageList(appUsageList);
        dailyUsage.setTotalScreenTimeMillis(totalTime);
        dailyUsage.setCommunicationTimeMillis(communicationTime);
        dailyUsage.setEntertainmentTimeMillis(entertainmentTime);
        dailyUsage.setGamesTimeMillis(gamesTime);
        dailyUsage.setOtherTimeMillis(totalTime - communicationTime - entertainmentTime - gamesTime);
        
        return dailyUsage;
    }

    /**
     * Get app usage history for a specific app
     */
    public List<DailyUsage> getAppWeeklyUsage(String targetPackageName) {
        List<DailyUsage> weeklyData = new ArrayList<>();
        Calendar calendar = Calendar.getInstance();
        
        for (int i = 6; i >= 0; i--) {
            Calendar dayCalendar = (Calendar) calendar.clone();
            dayCalendar.add(Calendar.DAY_OF_YEAR, -i);
            dayCalendar.set(Calendar.HOUR_OF_DAY, 0);
            dayCalendar.set(Calendar.MINUTE, 0);
            dayCalendar.set(Calendar.SECOND, 0);
            dayCalendar.set(Calendar.MILLISECOND, 0);
            
            long startTime = dayCalendar.getTimeInMillis();
            
            Calendar endCalendar = (Calendar) dayCalendar.clone();
            endCalendar.add(Calendar.DAY_OF_YEAR, 1);
            long endTime = endCalendar.getTimeInMillis();
            
            if (i == 0) {
                endTime = System.currentTimeMillis();
            }
            
            DailyUsage dailyUsage = new DailyUsage(dayCalendar);
            long appUsageTime = 0;
            
            if (usageStatsManager != null) {
                // Use events for accurate per-day data - ONLY method that gives per-day time
                UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, endTime);
                
                if (usageEvents != null) {
                    long lastResumeTime = 0;
                    boolean isInForeground = false;
                    
                    UsageEvents.Event event = new UsageEvents.Event();
                    while (usageEvents.hasNextEvent()) {
                        usageEvents.getNextEvent(event);
                        long eventTime = event.getTimeStamp();
                        
                        // Only process events within our time range
                        if (eventTime < startTime || eventTime > endTime) {
                            continue;
                        }
                        
                        if (event.getPackageName().equals(targetPackageName)) {
                            int eventType = event.getEventType();
                            
                            if (eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                                eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                                lastResumeTime = eventTime;
                                isInForeground = true;
                            } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                                       eventType == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                                if (isInForeground && lastResumeTime >= startTime) {
                                    long duration = eventTime - lastResumeTime;
                                    if (duration > 0 && duration < 24 * 60 * 60 * 1000) {
                                        appUsageTime += duration;
                                    }
                                }
                                isInForeground = false;
                                lastResumeTime = 0;
                            }
                        }
                    }
                    
                    // If still in foreground and this is today's period
                    long now = System.currentTimeMillis();
                    if (isInForeground && lastResumeTime >= startTime && now >= startTime && now <= endTime) {
                        long duration = now - lastResumeTime;
                        if (duration > 0 && duration < 24 * 60 * 60 * 1000) {
                            appUsageTime += duration;
                        }
                    }
                }
            }
            
            dailyUsage.setTotalScreenTimeMillis(appUsageTime);
            weeklyData.add(dailyUsage);
        }
        
        return weeklyData;
    }

    /**
     * Determine app category based on package name
     */
    private String getAppCategory(String packageName) {
        // Check exact match first
        if (CATEGORY_MAP.containsKey(packageName)) {
            return CATEGORY_MAP.get(packageName);
        }
        
        // Check prefix matches (for games from known publishers)
        for (Map.Entry<String, String> entry : CATEGORY_MAP.entrySet()) {
            if (packageName.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        
        // Try to determine from package name patterns
        String lowerPackage = packageName.toLowerCase();
        if (lowerPackage.contains("game") || lowerPackage.contains("play")) {
            return "Games";
        }
        if (lowerPackage.contains("chat") || lowerPackage.contains("message")) {
            return "Communication";
        }
        if (lowerPackage.contains("video") || lowerPackage.contains("music") || 
            lowerPackage.contains("stream")) {
            return "Entertainment";
        }
        
        return "Other";
    }

    /**
     * Get total weekly screen time
     */
    public long getTotalWeeklyScreenTime() {
        List<DailyUsage> weeklyData = getWeeklyUsage();
        long total = 0;
        for (DailyUsage daily : weeklyData) {
            total += daily.getTotalScreenTimeMillis();
        }
        return total;
    }

    /**
     * Get average daily screen time for the week
     */
    public String getAverageDailyTime() {
        long totalWeekly = getTotalWeeklyScreenTime();
        long averageDaily = totalWeekly / 7;
        
        long hours = averageDaily / (1000 * 60 * 60);
        long minutes = (averageDaily / (1000 * 60)) % 60;
        
        if (hours > 0) {
            return hours + " hrs " + minutes + " min";
        }
        return minutes + " min";
    }
}
