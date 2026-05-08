package com.example.master2.utils;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import com.example.master2.models.SUsageAppInfo;
import com.example.master2.models.SUsageDailyData;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manager class for fetching and processing app usage statistics.
 * Uses UsageEvents API for accurate per-day data (SUSAGE methodology).
 * 
 * IMPORTANT: We only use UsageEvents because queryUsageStats returns CUMULATIVE
 * foreground time (total since install), not time for the specific date range.
 */
public class SUsageDataManager {
    private static final String TAG = "SUsageDataManager";

    private static SUsageDataManager instance;
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

    private SUsageDataManager(Context context) {
        this.context = context.getApplicationContext();
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = context.getPackageManager();
    }

    public static synchronized SUsageDataManager getInstance(Context context) {
        if (instance == null) {
            instance = new SUsageDataManager(context);
        }
        return instance;
    }

    /**
     * Get usage statistics for today
     */
    public SUsageDailyData getTodayUsage() {
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
    public List<SUsageDailyData> getWeeklyUsage() {
        List<SUsageDailyData> weeklyData = new ArrayList<>();

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

            SUsageDailyData dailyUsage = getUsageForPeriodUsingEvents(startTime, endTime, dayCalendar);
            weeklyData.add(dailyUsage);
        }

        return weeklyData;
    }

    /**
     * Get usage for a specific time period using UsageEvents for accurate per-day
     * data.
     * This method calculates foreground time by tracking ACTIVITY_RESUMED and
     * ACTIVITY_PAUSED events.
     * 
     * IMPORTANT: We only use UsageEvents because queryUsageStats and
     * queryAndAggregateUsageStats
     * return CUMULATIVE foreground time, not time for the specific date range.
     */
    private SUsageDailyData getUsageForPeriodUsingEvents(long startTime, long endTime, Calendar date) {
        SUsageDailyData dailyUsage = new SUsageDailyData(date);

        if (usageStatsManager == null) {
            Log.e(TAG, "UsageStatsManager is null");
            return dailyUsage;
        }

        Map<String, Long> usageMap = new HashMap<>();

        // Use events-based calculation - this is the ONLY accurate method for per-day
        // data
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

        // Convert to SUsageAppInfo and calculate totals
        long totalTime = 0;
        long communicationTime = 0;
        long entertainmentTime = 0;
        long gamesTime = 0;

        List<SUsageAppInfo> appList = new ArrayList<>();

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
                String category = getAppCategory(packageName);

                SUsageAppInfo appUsageInfo = new SUsageAppInfo(
                        packageName, appName, usageTime, category);

                // Encode app icon to Base64 for Firebase sync
                try {
                    String iconBase64 = encodeIconToBase64(packageName);
                    appUsageInfo.setIconBase64(iconBase64);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to encode icon for " + packageName + ": " + e.getMessage());
                }

                appList.add(appUsageInfo);
                dailyUsage.addAppUsage(appUsageInfo);

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

        dailyUsage.setTotalScreenTimeMillis(totalTime);
        dailyUsage.setCommunicationTimeMillis(communicationTime);
        dailyUsage.setEntertainmentTimeMillis(entertainmentTime);
        dailyUsage.setGamesTimeMillis(gamesTime);
        dailyUsage.setOtherTimeMillis(totalTime - communicationTime - entertainmentTime - gamesTime);
        dailyUsage.setLastUpdated(System.currentTimeMillis());

        Log.d(TAG, "Collected usage for " + dailyUsage.getDateKey() + ": " +
                appList.size() + " apps, total " + dailyUsage.getFormattedTotalTime());

        return dailyUsage;
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
     * Optimized upload: Only upload TODAY'S data to save bandwidth and improve sync speed.
     * Icons are only uploaded if they are missing or if requested.
     */
    public void uploadTodayToFirebase(String deviceId, boolean includeIcons, final OnUploadCompleteListener listener) {
        if (deviceId == null || deviceId.isEmpty()) return;

        SUsageDailyData today = getTodayUsage();
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("susage_data")
                .child(deviceId);

        Map<String, Object> uploadData = new HashMap<>();
        uploadData.put("lastUpdated", System.currentTimeMillis());

        Map<String, Object> dailyMap = new HashMap<>();
        dailyMap.put("dateKey", today.getDateKey());
        dailyMap.put("totalScreenTimeMillis", today.getTotalScreenTimeMillis());
        dailyMap.put("communicationTimeMillis", today.getCommunicationTimeMillis());
        dailyMap.put("entertainmentTimeMillis", today.getEntertainmentTimeMillis());
        dailyMap.put("gamesTimeMillis", today.getGamesTimeMillis());
        dailyMap.put("otherTimeMillis", today.getOtherTimeMillis());
        dailyMap.put("lastUpdated", today.getLastUpdated());

        Map<String, Object> appsMap = new HashMap<>();
        for (SUsageAppInfo app : today.getAppList()) {
            Map<String, Object> appMap = new HashMap<>();
            appMap.put("packageName", app.getPackageName());
            appMap.put("appName", app.getAppName());
            appMap.put("usageTimeMillis", app.getUsageTimeMillis());
            appMap.put("category", app.getCategory());
            
            if (includeIcons && app.getIconBase64() != null) {
                appMap.put("iconBase64", app.getIconBase64());
            }
            
            appsMap.put(sanitizeFirebaseKey(app.getPackageName()), appMap);
        }
        dailyMap.put("apps", appsMap);

        // Update specifically today's data node
        uploadData.put("weeklyData/" + today.getDateKey(), dailyMap);

        ref.updateChildren(uploadData)
                .addOnSuccessListener(aVoid -> {
                    if (listener != null) listener.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
    }

    /**
     * Legacy method: still available but optimized to call internal logic
     */
    public void uploadToFirebase(String deviceId, final OnUploadCompleteListener listener) {
        uploadWeeklyToFirebase(deviceId, listener);
    }

    public void uploadWeeklyToFirebase(String deviceId, final OnUploadCompleteListener listener) {
        if (deviceId == null || deviceId.isEmpty()) return;

        List<SUsageDailyData> weeklyUsage = getWeeklyUsage();
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("susage_data")
                .child(deviceId);

        Map<String, Object> uploadData = new HashMap<>();
        uploadData.put("lastUpdated", System.currentTimeMillis());

        Map<String, Object> weeklyDataMap = new HashMap<>();
        for (SUsageDailyData daily : weeklyUsage) {
            Map<String, Object> dailyMap = new HashMap<>();
            dailyMap.put("dateKey", daily.getDateKey());
            dailyMap.put("totalScreenTimeMillis", daily.getTotalScreenTimeMillis());
            dailyMap.put("communicationTimeMillis", daily.getCommunicationTimeMillis());
            dailyMap.put("entertainmentTimeMillis", daily.getEntertainmentTimeMillis());
            dailyMap.put("gamesTimeMillis", daily.getGamesTimeMillis());
            dailyMap.put("otherTimeMillis", daily.getOtherTimeMillis());
            dailyMap.put("lastUpdated", daily.getLastUpdated());

            Map<String, Object> appsMap = new HashMap<>();
            for (SUsageAppInfo app : daily.getAppList()) {
                Map<String, Object> appMap = new HashMap<>();
                appMap.put("packageName", app.getPackageName());
                appMap.put("appName", app.getAppName());
                appMap.put("usageTimeMillis", app.getUsageTimeMillis());
                appMap.put("category", app.getCategory());
                if (app.getIconBase64() != null) {
                    appMap.put("iconBase64", app.getIconBase64());
                }
                appsMap.put(sanitizeFirebaseKey(app.getPackageName()), appMap);
            }
            dailyMap.put("apps", appsMap);
            weeklyDataMap.put(daily.getDateKey(), dailyMap);
        }
        uploadData.put("weeklyData", weeklyDataMap);

        ref.updateChildren(uploadData)
                .addOnSuccessListener(aVoid -> {
                    if (listener != null) listener.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (listener != null) listener.onError(e.getMessage());
                });
    }

    public long getTotalWeeklyScreenTime() {
        List<SUsageDailyData> weeklyData = getWeeklyUsage();
        long total = 0;
        for (SUsageDailyData daily : weeklyData) {
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

    /**
     * Listener interface for upload completion
     */
    public interface OnUploadCompleteListener {
        void onSuccess();

        void onError(String error);
    }

    /**
     * Sanitize a string to be used as a Firebase key.
     * Firebase does NOT allow: '.', '/', '#', '$', '[', ']'
     * Replaces all illegal characters with underscores.
     */
    private String sanitizeFirebaseKey(String key) {
        if (key == null || key.isEmpty()) {
            return "unknown";
        }
        // Replace all illegal Firebase characters with underscore
        return key.replaceAll("[.#$\\[\\]/]", "_");
    }

    /**
     * Encode app icon to Base64 string for Firebase storage.
     * Compresses icon to reduce data size.
     */
    private String encodeIconToBase64(String packageName) throws Exception {
        Drawable drawable = packageManager.getApplicationIcon(packageName);
        Bitmap bitmap = drawableToBitmap(drawable);

        // Resize to 48x48 to reduce size
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 48, 48, true);

        // Compress to PNG
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
        byte[] byteArray = byteArrayOutputStream.toByteArray();

        // Encode to Base64
        return Base64.encodeToString(byteArray, Base64.NO_WRAP);
    }

    /**
     * Convert Drawable to Bitmap
     */
    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        Bitmap bitmap = Bitmap.createBitmap(
                drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }
}
