package com.example.master2;

import android.app.AppOpsManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 🎯 BULLETPROOF 7-DAY USAGE TRACKER
 * 
 * This is a complete rewrite that ACTUALLY WORKS:
 * - Uses UsageStatsManager.queryUsageStats() for reliable data
 * - Updates every 2 minutes for near real-time display
 * - Stores in Firebase under usage_7day/{deviceId}/{date}
 * - Parent can see data immediately
 * - Handles permission checks properly
 */
public class BulletproofUsageTracker {
    private static final String TAG = "BulletproofUsage";

    // Update every 2 minutes for near real-time
    private static final long UPDATE_INTERVAL_MS = 2 * 60 * 1000;

    private final Context context;
    private final String deviceId;
    private final UsageStatsManager usageStatsManager;
    private final PackageManager packageManager;
    private final DatabaseReference firebaseRef;

    private Handler updateHandler;
    private Runnable updateRunnable;
    private boolean isRunning = false;

    private final SimpleDateFormat dateKeyFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    private final SimpleDateFormat dayLabelFormat = new SimpleDateFormat("EEE", Locale.getDefault());

    // Apps to exclude (system apps)
    private static final Set<String> EXCLUDED_PREFIXES = new HashSet<>(Arrays.asList(
            "com.android.", "android", "com.google.android.gms",
            "com.google.android.gsf", "com.google.android.ext",
            "com.samsung.android", "com.sec.", "com.miui.",
            "com.example.master2" // Don't track ourselves
    ));

    public BulletproofUsageTracker(Context context) {
        this.context = context.getApplicationContext();
        this.deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.usageStatsManager = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        this.packageManager = context.getPackageManager();
        this.firebaseRef = FirebaseDatabase.getInstance().getReference("usage_7day").child(deviceId);
        this.updateHandler = new Handler(Looper.getMainLooper());

        Log.d(TAG, "🎯 BulletproofUsageTracker created for device: " + deviceId);
    }

    /**
     * ✅ Check if we have usage stats permission
     */
    public boolean hasUsagePermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.getPackageName());

            boolean hasPermission = mode == AppOpsManager.MODE_ALLOWED;
            Log.d(TAG, "📱 Usage permission: " + (hasPermission ? "GRANTED" : "NOT GRANTED"));
            return hasPermission;
        } catch (Exception e) {
            Log.e(TAG, "Error checking permission: " + e.getMessage());
            return false;
        }
    }

    /**
     * 📱 Open usage access settings
     */
    public void requestUsagePermission() {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error opening usage settings: " + e.getMessage());
        }
    }

    /**
     * 🚀 START tracking
     */
    public void start() {
        if (isRunning) {
            Log.d(TAG, "⚠️ Already running");
            return;
        }

        if (!hasUsagePermission()) {
            Log.e(TAG, "❌ No usage permission - cannot start");
            return;
        }

        isRunning = true;
        Log.d(TAG, "🚀 Starting bulletproof usage tracking");

        // Collect immediately
        collectAndUpload();

        // Schedule periodic updates
        updateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRunning) {
                    collectAndUpload();
                    updateHandler.postDelayed(this, UPDATE_INTERVAL_MS);
                }
            }
        };
        updateHandler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS);
    }

    /**
     * 🛑 STOP tracking
     */
    public void stop() {
        isRunning = false;
        if (updateHandler != null && updateRunnable != null) {
            updateHandler.removeCallbacks(updateRunnable);
        }
        Log.d(TAG, "🛑 Stopped usage tracking");
    }

    /**
     * 📊 COLLECT and UPLOAD - the main workhorse
     */
    public void collectAndUpload() {
        try {
            Log.d(TAG, "📊 Collecting usage data...");

            // Get today's date
            Date now = new Date();
            String todayKey = dateKeyFormat.format(now);
            String dayLabel = dayLabelFormat.format(now);

            // Get today's start time (midnight)
            Calendar startCal = Calendar.getInstance();
            startCal.set(Calendar.HOUR_OF_DAY, 0);
            startCal.set(Calendar.MINUTE, 0);
            startCal.set(Calendar.SECOND, 0);
            startCal.set(Calendar.MILLISECOND, 0);
            long startTime = startCal.getTimeInMillis();
            long endTime = System.currentTimeMillis();

            // Query usage stats for today
            List<UsageStats> usageStatsList = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    startTime,
                    endTime);

            if (usageStatsList == null || usageStatsList.isEmpty()) {
                Log.w(TAG, "⚠️ No usage stats returned - might be permission issue");
                uploadEmptyDay(todayKey, dayLabel);
                return;
            }

            Log.d(TAG, "📱 Got " + usageStatsList.size() + " usage stats entries");

            // Process the stats
            final Map<String, Map<String, Object>> appsData = new HashMap<>();
            long totalMs = 0;

            for (UsageStats stats : usageStatsList) {
                String packageName = stats.getPackageName();
                long usageTime = stats.getTotalTimeInForeground();

                // Skip if no usage or excluded
                if (usageTime <= 0 || shouldExclude(packageName)) {
                    continue;
                }

                // Get app name
                String appName = getAppName(packageName);

                // Create app data
                Map<String, Object> appData = new HashMap<>();
                appData.put("packageName", packageName);
                appData.put("appName", appName);
                appData.put("usageTime", usageTime);
                appData.put("usageText", formatDuration(usageTime));
                appData.put("category", getCategory(packageName));

                appsData.put(packageName.replace(".", "_"), appData);
                totalMs += usageTime;

                Log.d(TAG, "  📱 " + appName + ": " + formatDuration(usageTime));
            }

            // Make final for lambda
            final long totalUsageMs = totalMs;
            final int appCount = appsData.size();
            final String totalText = formatDuration(totalUsageMs);

            // Create day data structure
            Map<String, Object> dayData = new HashMap<>();
            dayData.put("date", todayKey);
            dayData.put("dayLabel", dayLabel);
            dayData.put("totalUsageMs", totalUsageMs);
            dayData.put("totalUsageText", totalText);
            dayData.put("appCount", appCount);
            dayData.put("apps", appsData);
            dayData.put("lastUpdated", System.currentTimeMillis());
            dayData.put("deviceId", deviceId);

            // Upload to Firebase
            firebaseRef.child(todayKey).setValue(dayData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Uploaded usage data: " + appCount + " apps, " + totalText + " total");

                        // Also update "latest" for quick access
                        firebaseRef.child("latest").setValue(dayData);

                        // Update 7-day summary
                        update7DaySummary();
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to upload: " + e.getMessage());
                    });

        } catch (Exception e) {
            Log.e(TAG, "❌ Error collecting usage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Upload empty day data when no usage found
     */
    private void uploadEmptyDay(String dateKey, String dayLabel) {
        Map<String, Object> dayData = new HashMap<>();
        dayData.put("date", dateKey);
        dayData.put("dayLabel", dayLabel);
        dayData.put("totalUsageMs", 0L);
        dayData.put("totalUsageText", "0 min");
        dayData.put("appCount", 0);
        dayData.put("apps", new HashMap<>());
        dayData.put("lastUpdated", System.currentTimeMillis());
        dayData.put("deviceId", deviceId);
        dayData.put("noData", true);

        firebaseRef.child(dateKey).setValue(dayData);
        Log.d(TAG, "📊 Uploaded empty day data for: " + dateKey);
    }

    /**
     * 📊 Update 7-day summary for easy retrieval
     */
    private void update7DaySummary() {
        try {
            List<String> last7Days = new ArrayList<>();
            Calendar cal = Calendar.getInstance();

            for (int i = 6; i >= 0; i--) {
                cal.setTime(new Date());
                cal.add(Calendar.DAY_OF_YEAR, -i);
                last7Days.add(dateKeyFormat.format(cal.getTime()));
            }

            Map<String, Object> summary = new HashMap<>();
            summary.put("days", last7Days);
            summary.put("lastUpdated", System.currentTimeMillis());
            summary.put("deviceId", deviceId);

            firebaseRef.child("summary").setValue(summary);

        } catch (Exception e) {
            Log.e(TAG, "Error updating summary: " + e.getMessage());
        }
    }

    /**
     * 🚫 Check if package should be excluded
     */
    private boolean shouldExclude(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return true;
        }

        for (String prefix : EXCLUDED_PREFIXES) {
            if (packageName.startsWith(prefix) || packageName.equals(prefix)) {
                return true;
            }
        }

        // Check if it's a system app
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                // Only exclude if it's in the system partition AND not updated
                if ((appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    // Check if it has a launcher icon (user-facing app)
                    Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
                    if (launchIntent == null) {
                        return true; // No launcher icon = background system service
                    }
                }
            }
        } catch (Exception e) {
            // If we can't get info, don't exclude
        }

        return false;
    }

    /**
     * 🏷️ Get app display name
     */
    private String getAppName(String packageName) {
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return packageManager.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            // Clean up package name for display
            String[] parts = packageName.split("\\.");
            if (parts.length > 0) {
                String name = parts[parts.length - 1];
                // Capitalize first letter
                return name.substring(0, 1).toUpperCase() + name.substring(1);
            }
            return packageName;
        }
    }

    /**
     * 📂 Get simple category
     */
    private String getCategory(String packageName) {
        String pkg = packageName.toLowerCase();

        if (pkg.contains("game") || pkg.contains("play"))
            return "Games";
        if (pkg.contains("social") || pkg.contains("facebook") || pkg.contains("instagram") ||
                pkg.contains("twitter") || pkg.contains("tiktok") || pkg.contains("snapchat"))
            return "Social";
        if (pkg.contains("whatsapp") || pkg.contains("telegram") || pkg.contains("messenger"))
            return "Messaging";
        if (pkg.contains("youtube") || pkg.contains("netflix") || pkg.contains("video") ||
                pkg.contains("spotify") || pkg.contains("music"))
            return "Entertainment";
        if (pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox"))
            return "Browser";
        if (pkg.contains("camera") || pkg.contains("photo") || pkg.contains("gallery"))
            return "Photos";

        return "Other";
    }

    /**
     * ⏱️ Format duration
     */
    private String formatDuration(long millis) {
        if (millis < 60000) { // Less than 1 minute
            return "< 1 min";
        }

        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
        } else {
            return String.format(Locale.getDefault(), "%d min", minutes);
        }
    }

    /**
     * 🔧 Force refresh now
     */
    public void forceRefresh() {
        Log.d(TAG, "🔄 Force refresh requested");
        collectAndUpload();
    }

    /**
     * 📊 Get status for debugging
     */
    public String getStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("BulletproofUsageTracker Status:\n");
        sb.append("- Device ID: ").append(deviceId).append("\n");
        sb.append("- Running: ").append(isRunning).append("\n");
        sb.append("- Has Permission: ").append(hasUsagePermission()).append("\n");
        sb.append("- Firebase Path: usage_7day/").append(deviceId).append("\n");
        return sb.toString();
    }
}
