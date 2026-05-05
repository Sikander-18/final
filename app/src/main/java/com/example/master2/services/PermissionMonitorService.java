package com.example.master2.services;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.master2.BatteryOptimizationManager;
import com.example.master2.BlockService;
import com.example.master2.R;
import com.example.master2.SessionManager;
import com.example.master2.models.PermissionEvent;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Foreground service that monitors permission changes on child device
 * and reports them to the parent via Firebase.
 */
public class PermissionMonitorService extends Service {
    private static final String TAG = "PermissionMonitor";
    private static final String CHANNEL_ID = "permission_monitor_channel";
    private static final int NOTIFICATION_ID = 9001;
    private static final int CHECK_INTERVAL_MS = 30000; // 30 seconds

    private static final String PREF_NAME = "PermissionStatus";
    private static final String KEY_ACCESSIBILITY = "perm_accessibility";
    private static final String KEY_USAGE_STATS = "perm_usage_stats";
    private static final String KEY_NOTIFICATIONS = "perm_notifications";
    private static final String KEY_BATTERY_OPT = "perm_battery_opt";

    private Handler handler;
    private Runnable checkRunnable;
    private SharedPreferences prefs;
    private SessionManager sessionManager;
    private BatteryOptimizationManager batteryOptimizationManager;
    private DatabaseReference databaseRef;

    // Permission effect descriptions
    private static final Map<String, String[]> PERMISSION_EFFECTS = new HashMap<>();
    static {
        // [0] = Deactivated effect, [1] = Activated effect
        PERMISSION_EFFECTS.put("Accessibility Service", new String[] {
                "App blocking disabled. Child can bypass restrictions and open any app.",
                "App blocking enabled. Focus mode and restrictions are active."
        });
        PERMISSION_EFFECTS.put("Usage Stats", new String[] {
                "Usage tracking stopped. Daily reports will be incomplete or unavailable.",
                "Usage tracking active. Full usage reports are available for monitoring."
        });
        PERMISSION_EFFECTS.put("Notifications", new String[] {
                "Timer alerts will not display. Child won't see time limit warnings.",
                "Timer alerts enabled. Child will see warnings before limits are reached."
        });
        PERMISSION_EFFECTS.put("Battery Optimization", new String[] {
                "Background services may stop. Timer resets and monitoring may fail.",
                "Services protected from battery optimization. Reliable operation ensured."
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🔔 PermissionMonitorService STARTING...");

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sessionManager = new SessionManager(this);
        batteryOptimizationManager = new BatteryOptimizationManager(this);
        handler = new Handler(Looper.getMainLooper());

        // Initialize Firebase reference
        String parentUserId = sessionManager.getParentUserId();
        String childDeviceId = sessionManager.getChildDeviceId();

        Log.d(TAG, "🔍 Session data - parentUserId: " + parentUserId + ", childDeviceId: " + childDeviceId);

        // 🔧 FIX: Try to get parentUserId from Firebase if not in session
        if (parentUserId == null || parentUserId.isEmpty()) {
            Log.w(TAG, "⚠️ Parent ID not in session, attempting fallback...");
            // Try getting from shared prefs directly
            SharedPreferences sessionPrefs = getSharedPreferences("MasterAppSession", Context.MODE_PRIVATE);
            parentUserId = sessionPrefs.getString("parentUserId", null);
            Log.d(TAG, "🔧 Fallback parentUserId from prefs: " + parentUserId);
        }

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        // 🔧 CRITICAL FIX: Initialize Firebase with robust parent ID retrieval
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.e(TAG, "❌ CRITICAL: No child device ID! Cannot monitor permissions.");
            return;
        }

        // If we have parent ID, initialize immediately
        if (parentUserId != null && !parentUserId.isEmpty()) {
            setupDatabaseReference(parentUserId, childDeviceId);
        } else {
            // Query Firebase to find parent
            Log.w(TAG, "⚠️ Parent ID null - querying Firebase...");
            queryFirebaseForParentId(childDeviceId);
        }
    }

    /**
     * 🔧 NEW: Query Firebase to find which parent owns this child device
     */
    private void queryFirebaseForParentId(String childDeviceId) {
        FirebaseDatabase.getInstance()
                .getReference("device_status")
                .child(childDeviceId)
                .child("parentUserId")
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.exists()) {
                        String parentUserId = snapshot.getValue(String.class);
                        Log.d(TAG, "✅ Found parent ID from Firebase: " + parentUserId);

                        if (parentUserId != null && !parentUserId.isEmpty()) {
                            sessionManager.saveParentUserId(parentUserId);
                            setupDatabaseReference(parentUserId, childDeviceId);
                        } else {
                            useAlternativeFirebasePath(childDeviceId);
                        }
                    } else {
                        Log.w(TAG, "⚠️ No parentUserId in device_status");
                        useAlternativeFirebasePath(childDeviceId);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Query failed: " + e.getMessage());
                    useAlternativeFirebasePath(childDeviceId);
                });
    }

    /**
     * 🔧 FALLBACK: Use child-centric path
     */
    private void useAlternativeFirebasePath(String childDeviceId) {
        Log.w(TAG, "🔧 Using alternative: child_permission_events/" + childDeviceId);
        databaseRef = FirebaseDatabase.getInstance()
                .getReference("child_permission_events")
                .child(childDeviceId);
        startPermissionMonitoring();
    }

    /**
     * Setup database reference and start monitoring
     */
    private void setupDatabaseReference(String parentUserId, String childDeviceId) {
        databaseRef = FirebaseDatabase.getInstance()
                .getReference("permission_events")
                .child(parentUserId)
                .child(childDeviceId);
        Log.d(TAG, "✅ Firebase: permission_events/" + parentUserId + "/" + childDeviceId);
        startPermissionMonitoring();
    }

    /**
     * Start monitoring after Firebase is ready
     */
    private void startPermissionMonitoring() {
        Log.d(TAG, "🔄 Forcing fresh permission state...");
        initializePermissionStatesForced();

        handler.postDelayed(() -> {
            Log.d(TAG, "🔔 IMMEDIATE permission check...");
            checkPermissionsAndReport();
        }, 2000);

        startMonitoring();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Permission Monitor",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Monitors permission status for parental control");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control Active")
                .setContentText("Monitoring is enabled")
                .setSmallIcon(R.drawable.ic_child)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void initializePermissionStates() {
        // Only initialize if not already set (first run)
        if (!prefs.contains(KEY_ACCESSIBILITY)) {
            SharedPreferences.Editor editor = prefs.edit();
            editor.putBoolean(KEY_ACCESSIBILITY, isAccessibilityServiceEnabled());
            editor.putBoolean(KEY_USAGE_STATS, hasUsageStatsPermission());
            editor.putBoolean(KEY_NOTIFICATIONS, hasNotificationPermission());
            editor.putBoolean(KEY_BATTERY_OPT, batteryOptimizationManager.isBatteryOptimizationDisabled());
            editor.apply();
            Log.d(TAG, "Initialized permission states");
        }
    }

    /**
     * 🔧 FIX: Force capture current permission states (always overwrites)
     * This ensures we have accurate baseline when service starts
     */
    private void initializePermissionStatesForced() {
        boolean accessibility = isAccessibilityServiceEnabled();
        boolean usageStats = hasUsageStatsPermission();
        boolean notifications = hasNotificationPermission();
        boolean batteryOpt = batteryOptimizationManager.isBatteryOptimizationDisabled();

        Log.d(TAG, "📊 Current permission states:");
        Log.d(TAG, "   Accessibility: " + accessibility);
        Log.d(TAG, "   Usage Stats: " + usageStats);
        Log.d(TAG, "   Notifications: " + notifications);
        Log.d(TAG, "   Battery Opt: " + batteryOpt);

        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_ACCESSIBILITY, accessibility);
        editor.putBoolean(KEY_USAGE_STATS, usageStats);
        editor.putBoolean(KEY_NOTIFICATIONS, notifications);
        editor.putBoolean(KEY_BATTERY_OPT, batteryOpt);
        editor.apply();

        Log.d(TAG, "✅ Permission states saved to prefs");

        // 🔧 FIX: Also immediately update Firebase with current status
        if (databaseRef != null) {
            updateCurrentStatus(accessibility, usageStats, notifications, batteryOpt);
            Log.d(TAG, "📤 Initial status synced to Firebase");
        }
    }

    private void startMonitoring() {
        checkRunnable = new Runnable() {
            @Override
            public void run() {
                checkPermissionsAndReport();
                handler.postDelayed(this, CHECK_INTERVAL_MS);
            }
        };
        handler.post(checkRunnable);
        Log.d(TAG, "Started permission monitoring (interval: " + CHECK_INTERVAL_MS + "ms)");
    }

    private void checkPermissionsAndReport() {
        Log.d(TAG, "🔍 Checking permissions...");

        if (databaseRef == null) {
            Log.e(TAG, "❌ Firebase not initialized, cannot report changes!");
            return;
        }

        // Current states
        boolean currAccessibility = isAccessibilityServiceEnabled();
        boolean currUsageStats = hasUsageStatsPermission();
        boolean currNotifications = hasNotificationPermission();
        boolean currBatteryOpt = batteryOptimizationManager.isBatteryOptimizationDisabled();

        // Previous states
        boolean prevAccessibility = prefs.getBoolean(KEY_ACCESSIBILITY, false);
        boolean prevUsageStats = prefs.getBoolean(KEY_USAGE_STATS, false);
        boolean prevNotifications = prefs.getBoolean(KEY_NOTIFICATIONS, false);
        boolean prevBatteryOpt = prefs.getBoolean(KEY_BATTERY_OPT, false);

        // 🔧 FIX: Add verbose comparison logging
        Log.d(TAG, "📊 Permission comparison:");
        Log.d(TAG, "   Accessibility: CURRENT=" + currAccessibility + " vs SAVED=" + prevAccessibility +
                (currAccessibility != prevAccessibility ? " ⚠️ CHANGED!" : ""));
        Log.d(TAG, "   Usage Stats: CURRENT=" + currUsageStats + " vs SAVED=" + prevUsageStats +
                (currUsageStats != prevUsageStats ? " ⚠️ CHANGED!" : ""));
        Log.d(TAG, "   Notifications: CURRENT=" + currNotifications + " vs SAVED=" + prevNotifications +
                (currNotifications != prevNotifications ? " ⚠️ CHANGED!" : ""));
        Log.d(TAG, "   Battery Opt: CURRENT=" + currBatteryOpt + " vs SAVED=" + prevBatteryOpt +
                (currBatteryOpt != prevBatteryOpt ? " ⚠️ CHANGED!" : ""));

        // Detect and report changes
        if (currAccessibility != prevAccessibility) {
            reportPermissionChange("Accessibility Service", currAccessibility);
            prefs.edit().putBoolean(KEY_ACCESSIBILITY, currAccessibility).apply();
        }
        if (currUsageStats != prevUsageStats) {
            reportPermissionChange("Usage Stats", currUsageStats);
            prefs.edit().putBoolean(KEY_USAGE_STATS, currUsageStats).apply();
        }
        if (currNotifications != prevNotifications) {
            reportPermissionChange("Notifications", currNotifications);
            prefs.edit().putBoolean(KEY_NOTIFICATIONS, currNotifications).apply();
        }
        if (currBatteryOpt != prevBatteryOpt) {
            reportPermissionChange("Battery Optimization", currBatteryOpt);
            prefs.edit().putBoolean(KEY_BATTERY_OPT, currBatteryOpt).apply();
        }

        // Update current status snapshot
        updateCurrentStatus(currAccessibility, currUsageStats, currNotifications, currBatteryOpt);
    }

    private void reportPermissionChange(String permissionName, boolean isEnabled) {
        String action = isEnabled ? "ACTIVATED" : "DEACTIVATED";
        String[] effects = PERMISSION_EFFECTS.get(permissionName);
        String effect = (effects != null) ? (isEnabled ? effects[1] : effects[0]) : "Unknown effect";

        long timestamp = System.currentTimeMillis();
        SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
        SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());
        Date date = new Date(timestamp);

        PermissionEvent event = new PermissionEvent(
                permissionName,
                action,
                effect,
                timestamp,
                dateFormat.format(date),
                timeFormat.format(date));

        // Push to Firebase
        databaseRef.child("events").push().setValue(event)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Reported: " + permissionName + " " + action))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to report: " + e.getMessage()));

        Log.i(TAG, "🔔 Permission change detected: " + permissionName + " -> " + action);
    }

    private void updateCurrentStatus(boolean accessibility, boolean usageStats,
            boolean notifications, boolean batteryOpt) {
        Map<String, Object> status = new HashMap<>();
        status.put("accessibility", accessibility);
        status.put("usageStats", usageStats);
        status.put("notifications", notifications);
        status.put("batteryOptimization", batteryOpt);
        status.put("lastUpdated", System.currentTimeMillis());

        databaseRef.child("currentStatus").setValue(status);
    }

    // Permission checking methods (same as ChildPermissionsActivity)
    private boolean isAccessibilityServiceEnabled() {
        String settingValue = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (settingValue != null) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                String service = splitter.next();
                if (service.equalsIgnoreCase(getPackageName() + "/" + BlockService.class.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats",
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        return START_STICKY; // Restart if killed
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null && checkRunnable != null) {
            handler.removeCallbacks(checkRunnable);
        }
        Log.d(TAG, "PermissionMonitorService destroyed");
    }
}
