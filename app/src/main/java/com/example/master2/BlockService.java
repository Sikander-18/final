package com.example.master2;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.widget.Toast;
import android.util.Log;
import android.app.ActivityManager;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import java.util.List;
import java.util.Map;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import android.app.usage.UsageEvents;

public class BlockService extends AccessibilityService {
    private static final String TAG = "BlockService";
    private SharedPreferences prefs;
    private String lastBroadcastPkg = "";
    private String currentForegroundApp = "";
    private Handler accuracyHandler;
    private Runnable accuracyRunnable;
    private long lastUsageCheckTime = 0;
    private UsageStatsManager usageStatsManager;
    private BroadcastReceiver blockedAppsReceiver;

    // CRITICAL: Device type detection
    private boolean isParentDevice = false;
    private SessionManager sessionManager;

    // For extremely accurate monitoring with interaction detection
    private static final int ACCURACY_CHECK_INTERVAL = 100; // Check every 100ms for accuracy

    // 🆕 ENHANCED BLOCKING: Track visible windows for split-screen/floating
    // detection
    private Set<String> visibleBlockedApps = new HashSet<>();
    private boolean isMultiWindowCheckRunning = false;

    // 🛡️ DEVICE ADMIN PROTECTION: Prevent uninstall
    private boolean deviceAdminProtectionEnabled = false; // Default: DISABLED until Firebase confirms otherwise
    private static final String[] DEVICE_ADMIN_TEXTS = {
            "Device admin", "Device administrator", "Device administrators",
            "admin apps", "administrator apps", "Deactivate", "deactivate this device admin"
    };
    private static final String SETTINGS_PACKAGE = "com.android.settings";
    private com.google.firebase.database.ValueEventListener protectionStateListener;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "🔗 BlockService connected");

        // CRITICAL: Detect device type FIRST
        detectDeviceType();

        // If this is a parent device, disable blocking completely
        if (isParentDevice) {
            Log.d(TAG, "🚫 PARENT DEVICE DETECTED - BLOCKING DISABLED");
            Toast.makeText(this, "📱 Parent Device - Blocking Disabled", Toast.LENGTH_LONG).show();

            // Clear any existing blocked apps
            prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
            prefs.edit().clear().commit();

            // Don't set up blocking functionality on parent devices
            return;
        }

        Log.d(TAG, "✅ CHILD DEVICE DETECTED - BLOCKING ENABLED");

        prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
        usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);

        // Register broadcast receiver for blocked apps updates
        setupBlockedAppsReceiver();

        // Initialize accuracy monitoring
        accuracyHandler = new Handler(Looper.getMainLooper());
        startAccuracyMonitoring();

        // 🚀 SYNC: Start frequent usage data upload (every 2 minutes)
        // This runs within the robust Accessibility Service for maximum reliability
        startFrequentUsageSync();

        // 🛡️ Setup Firebase listener for uninstall protection state
        setupProtectionStateListener();

        // Run diagnostics to help troubleshoot focus mode issues
        runDiagnostics();

        Log.d(TAG, "✅ BlockService fully initialized and ready to block apps");
    }

    // 🔄 SYNC: Frequent usage upload
    private Handler syncHandler;
    private Runnable syncRunnable;
    private static final long SYNC_INTERVAL_MS = 2 * 60 * 1000; // 2 minutes

    private void startFrequentUsageSync() {
        Log.d(TAG, "🔄 Starting frequent usage sync (2 min interval)");
        syncHandler = new Handler(Looper.getMainLooper());
        syncRunnable = new Runnable() {
            @Override
            public void run() {
                performUsageUpload();

                // SELF-HEALING: Retry listener setup if it failed previously
                if (protectionStateListener == null && !isParentDevice) {
                    Log.d(TAG, "🔄 Retrying protection listener setup during sync...");
                    setupProtectionStateListener();
                }

                // Schedule next run
                syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
            }
        };
        // Run immediately first time
        syncHandler.post(syncRunnable);
    }

    private void performUsageUpload() {
        if (isParentDevice)
            return;

        try {
            Log.d(TAG, "📤 Performing frequent usage uploads...");
            String deviceId = sessionManager.getChildDeviceId();
            if (deviceId != null && !deviceId.isEmpty()) {
                com.example.master2.utils.SUsageDataManager.getInstance(this).uploadToFirebase(deviceId, null);

                // Update heartbeat clearly
                com.google.firebase.database.FirebaseDatabase.getInstance()
                        .getReference("susage_data")
                        .child(deviceId)
                        .child("heartbeat")
                        .setValue(System.currentTimeMillis());
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Sync failed: " + e.getMessage());
        }
    }

    private void detectDeviceType() {
        try {
            sessionManager = new SessionManager(this);

            // Method 1: Check SessionManager for explicit user type
            if (sessionManager.isLoggedIn()) {
                String userType = sessionManager.getUserType();
                Log.d(TAG, "📱 Session user type: " + userType);

                if ("parent".equals(userType)) {
                    isParentDevice = true;
                    Log.d(TAG, "🚫 CONFIRMED: This is a PARENT device (Session)");
                    return;
                } else if ("child".equals(userType)) {
                    isParentDevice = false;
                    Log.d(TAG, "✅ CONFIRMED: This is a CHILD device (Session)");
                    return;
                }
            }

            // Method 2: Check if device has parent-specific data
            String qrShareKey = sessionManager.getQRShareKey();
            String parentName = sessionManager.getParentName();
            String phoneNumber = sessionManager.getPhoneNumber();

            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                // Parent devices have phone numbers
                isParentDevice = true;
                Log.d(TAG, "🚫 CONFIRMED: This is a PARENT device (has phone number)");
                return;
            } else if (parentName != null && !parentName.isEmpty()) {
                // Child devices have parent names
                isParentDevice = false;
                Log.d(TAG, "✅ CONFIRMED: This is a CHILD device (has parent name)");
                return;
            }

            // Method 3: Check for existing parent/child specific SharedPreferences
            SharedPreferences parentPrefs = getSharedPreferences("focus_mode_prefs", MODE_PRIVATE);
            SharedPreferences childPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);

            if (!parentPrefs.getAll().isEmpty()) {
                // Parent devices have focus mode preferences
                isParentDevice = true;
                Log.d(TAG, "🚫 DETECTED: This is likely a PARENT device (has focus mode prefs)");
                return;
            }

            // Method 4: Check package name and installation context
            String packageName = getPackageName();
            if (packageName.contains("parent") || packageName.contains("master")) {
                // Check if this is specifically a parent app installation
                // This is a fallback method
            }

            // DEFAULT: Enable blocking (assume child device)
            // This is safer than disabling blocking by default
            isParentDevice = false;
            Log.d(TAG, "✅ DEFAULT: Enabling blocking (assuming CHILD device for safety)");
            Log.d(TAG, "📱 Note: If this is a parent device, it will be protected by emergency unblock methods");

        } catch (Exception e) {
            Log.e(TAG, "Error detecting device type: " + e.getMessage());
            // DEFAULT: Enable blocking (safer for functionality)
            isParentDevice = false;
            Log.d(TAG, "✅ ERROR FALLBACK: Enabling blocking (assuming CHILD device)");
        }

        Log.d(TAG, "📱 Device type detection complete: " + (isParentDevice ? "PARENT" : "CHILD"));
    }

    private void setupBlockedAppsReceiver() {
        // Only set up receiver on child devices
        if (isParentDevice) {
            Log.d(TAG, "🚫 Skipping blocked apps receiver setup on parent device");
            return;
        }

        Log.d(TAG, "📡 Setting up blocked apps broadcast receiver");

        blockedAppsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.master2.BLOCKED_APPS_UPDATED".equals(intent.getAction())) {
                    int blockedCount = intent.getIntExtra("blocked_count", 0);
                    Log.d(TAG, "📡 Received blocked apps update broadcast - count: " + blockedCount);

                    // Reload blocked apps immediately
                    reloadBlockedApps();

                    // 🔥 IMMEDIATE KILL: Check if the currently running app was just blocked
                    enforceBlockOnCurrentApp();

                    // FORCE RE-CHECK: Add a small delay for background processing
                    accuracyHandler.postDelayed(() -> enforceBlockOnCurrentApp(), 500);

                    // Show feedback to user
                    String message = blockedCount > 0 ? "🚫 " + blockedCount + " apps now blocked"
                            : "✅ All apps unblocked";
                    Toast.makeText(BlockService.this, message, Toast.LENGTH_SHORT).show();
                }
            }
        };

        IntentFilter filter = new IntentFilter("com.example.master2.BLOCKED_APPS_UPDATED");

        // Fix for Android 8.0+ (API 26+) - specify receiver export flag for security
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(blockedAppsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "✅ Broadcast receiver registered with RECEIVER_NOT_EXPORTED flag");
        } else {
            registerReceiver(blockedAppsReceiver, filter);
            Log.d(TAG, "✅ Broadcast receiver registered (legacy mode)");
        }
        Log.d(TAG, "✅ Broadcast receiver registered");
    }

    private void reloadBlockedApps() {
        // Only reload on child devices
        if (isParentDevice) {
            Log.d(TAG, "🚫 Skipping blocked apps reload on parent device");
            return;
        }

        Log.d(TAG, "🔄 Reloading blocked apps from SharedPreferences");

        // Force reload SharedPreferences (this is crucial for synchronization)
        prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);

        Map<String, ?> allEntries = prefs.getAll();
        Log.d(TAG, "📋 Reloaded " + allEntries.size() + " blocked apps:");
        for (Map.Entry<String, ?> entry : allEntries.entrySet()) {
            Log.d(TAG, "🔒 " + entry.getKey() + " = " + entry.getValue());
        }
    }

    /**
     * 🔥 IMMEDIATE KILL: If the app currently in the foreground is blocked,
     * force-kill it RIGHT NOW — don't wait for the next app switch event.
     * This is called when a new block command arrives via broadcast.
     */
    private void enforceBlockOnCurrentApp() {
        if (isParentDevice) return;

        try {
            // Get the app that is currently on screen
            String foreground = getCurrentForegroundAppFromUsageStats();
            if (foreground == null || foreground.isEmpty()) return;

            // Skip system apps and our own app
            if (shouldSkipForBlocking(foreground)) return;
            if (foreground.contains("com.example.master2")) return;

            // Check if this foreground app is now blocked
            SharedPreferences freshPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
            if (freshPrefs.getBoolean(foreground, false)) {
                Log.w(TAG, "🔥 IMMEDIATE KILL: Foreground app '" + foreground + "' is BLOCKED — killing NOW!");
                blockAppEnhanced(foreground);

                // Extra safety: re-check after a short delay in case the app resists
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    String stillRunning = getCurrentForegroundAppFromUsageStats();
                    if (foreground.equals(stillRunning)) {
                        Log.w(TAG, "🔥 RE-KILL: App '" + foreground + "' survived — blocking again!");
                        blockAppEnhanced(foreground);
                    }
                }, 800);
            } else {
                Log.d(TAG, "✅ Foreground app '" + foreground + "' is NOT blocked — no action needed.");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error in enforceBlockOnCurrentApp: " + e.getMessage());
        }
    }

    private void startAccuracyMonitoring() {
        accuracyRunnable = new Runnable() {
            @Override
            public void run() {
                checkCurrentForegroundApp();
                accuracyHandler.postDelayed(this, ACCURACY_CHECK_INTERVAL);
            }
        };
        accuracyHandler.post(accuracyRunnable);
    }

    private void checkCurrentForegroundApp() {
        String currentApp = null;
        
        // 🚀 INSTANT DETECTION: Use Accessibility window first (faster than UsageStats)
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                if (root.getPackageName() != null) {
                    currentApp = root.getPackageName().toString();
                }
                root.recycle();
            }
        } catch (Exception ignored) {}

        // Fallback to UsageStats if Accessibility fails
        if (currentApp == null) {
            currentApp = getCurrentForegroundAppFromUsageStats();
        }

        if (currentApp != null) {
            // 🛡️ CONTINUOUS ENFORCEMENT: Check if app is blocked regardless of whether it's "new"
            if (!isParentDevice && !shouldSkipForBlocking(currentApp)) {
                SharedPreferences freshPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
                if (freshPrefs.getBoolean(currentApp, false)) {
                    Log.w(TAG, "⚡ CONTINUOUS KILL: Blocked app detected: " + currentApp);
                    blockAppEnhanced(currentApp);
                    return; 
                }
            }

            // Only broadcast and update state if the app actually changed
            if (!currentApp.equals(currentForegroundApp)) {
                currentForegroundApp = currentApp;
                broadcastForegroundApp(currentApp);
            }
        }
    }

    private String getCurrentForegroundAppFromUsageStats() {
        if (usageStatsManager == null)
            return null;

        long currentTime = System.currentTimeMillis();
        long startTime = currentTime - 3000; // Last 3 seconds for better accuracy

        try {
            UsageEvents usageEvents = usageStatsManager.queryEvents(startTime, currentTime);
            UsageEvents.Event event = new UsageEvents.Event();
            String lastForegroundApp = null;
            long latestTimestamp = 0;

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event);

                if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                    // Track the most recent app that came to foreground
                    if (event.getTimeStamp() > latestTimestamp) {
                        lastForegroundApp = event.getPackageName();
                        latestTimestamp = event.getTimeStamp();
                        Log.v(TAG, "🚀 Recent app resumed: " + event.getPackageName() + " at "
                                + new Date(event.getTimeStamp()));
                    }
                } else if (event.getEventType() == UsageEvents.Event.ACTIVITY_PAUSED) {
                    Log.v(TAG, "⏸️ App paused: " + event.getPackageName() + " at " + new Date(event.getTimeStamp()));
                }
            }

            return lastForegroundApp;
        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app from usage stats: " + e.getMessage());
            return null;
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // CRITICAL: Do not block anything on parent devices
        if (isParentDevice) {
            // Still broadcast foreground app for analytics but don't block anything
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (event.getPackageName() != null) {
                    String packageName = event.getPackageName().toString();
                    broadcastForegroundApp(packageName);
                }
            }
            return;
        }

        // ENHANCED USER INTERACTION DETECTION (CHILD DEVICES ONLY)
        String packageName = event.getPackageName() != null ? event.getPackageName().toString() : "";

        // 🆕 ENHANCED: Handle multi-window detection (split-screen, floating windows,
        // PIP)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            Log.d(TAG, "🪟 WINDOWS_CHANGED event detected - checking multi-window mode");
            checkAndBlockMultiWindowApps();
        }

        // ENHANCED AND BULLETPROOF BLOCKING LOGIC (CHILD DEVICES ONLY)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) {
                // Use the packageName already declared above
                packageName = event.getPackageName().toString();

                // Update current foreground app immediately
                currentForegroundApp = packageName;

                // *** CRITICAL: NEVER BLOCK OUR OWN APP ***
                String ourPackageName = getPackageName();
                if (packageName.equals(ourPackageName) ||
                        packageName.contains("com.example.master2") ||
                        packageName.equals("com.example.master2")) {
                    Log.d(TAG, "🛡️ PROTECTING OUR OWN APP: " + packageName);
                    broadcastForegroundApp(packageName);
                    return;
                }

                // Skip system apps and launchers for blocking
                if (shouldSkipForBlocking(packageName)) {
                    // Broadcast for timer logic but don't block
                    broadcastForegroundApp(packageName);
                    return;
                }

                // *** CRITICAL BLOCKING LOGIC ***
                // 🔧 ALWAYS read fresh data from SharedPreferences for INSTANT updates
                // This ensures block/unblock commands take effect IMMEDIATELY
                SharedPreferences freshPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
                boolean blocked = freshPrefs.getBoolean(packageName, false);

                // Log every single app launch for debugging
                Log.d(TAG,
                        "🔍 APP LAUNCHED: " + packageName + " | BLOCKED: " + blocked + " | OUR APP: " + ourPackageName);

                // DOUBLE CHECK: Make sure we're not blocking ourselves
                if (packageName.equals(ourPackageName)) {
                    Log.d(TAG, "🛡️ DOUBLE PROTECTION: Not blocking our own app");
                    broadcastForegroundApp(packageName);
                    return;
                }

                // If app is blocked, block it immediately with enhanced logic
                if (blocked) {
                    Log.d(TAG, "🚫 BLOCKING APP NOW: " + packageName);
                    blockAppEnhanced(packageName);
                    return; // Don't broadcast blocked app
                }

                // If not blocked, broadcast normally for timer logic
                broadcastForegroundApp(packageName);
            }
        }

        // 🆕 ENHANCED: Handle view focus changes for floating windows
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            if (event.getPackageName() != null) {
                packageName = event.getPackageName().toString();
                if (!shouldSkipForBlocking(packageName)) {
                    SharedPreferences freshPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
                    if (freshPrefs.getBoolean(packageName, false)) {
                        Log.d(TAG, "🔍 BLOCKED APP FOCUSED (floating window?): " + packageName);
                        blockAppEnhanced(packageName);
                    }
                }
            }
        }

        // 🛡️ DEVICE ADMIN PROTECTION: Detect attempts to access Device Admin settings
        // Check if protection is enabled (controlled by parent toggle in settings)
        if (!isParentDevice) {
            // SELF-HEALING: If listener is missing, try to setup again (e.g. if device ID
            // was missing at startup)
            if (protectionStateListener == null) {
                // Only retry occasionally to avoiding spamming logs/Firebase calls,
                // but here we are in a user interaction event, so it's a good time to check
                setupProtectionStateListener();
            }

            if (isUninstallProtectionEnabled()) {
                if (isProtectedSettingsPage(event)) {
                    Log.w(TAG, "🚨 PROTECTED SETTINGS PAGE DETECTED! Blocking...");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    Toast.makeText(this, "🛡️ Protection is enabled - Settings blocked", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * 🛡️ Check if parent has enabled uninstall protection via settings toggle
     * Uses the synced Firebase state stored in deviceAdminProtectionEnabled field
     * CRITICAL: BOTH conditions must be true:
     * 1. Device must be connected (have a valid childDeviceId)
     * 2. Parent must have enabled protection in Firebase
     */
    private boolean isUninstallProtectionEnabled() {
        // CONDITION 1: Check if device is actually connected
        if (sessionManager == null) {
            Log.d(TAG, "🛡️ Protection check: No sessionManager - NOT blocking");
            return false;
        }

        String deviceId = sessionManager.getChildDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            Log.d(TAG, "🛡️ Protection check: No childDeviceId (not connected) - NOT blocking");
            return false;
        }

        // CONDITION 2: Check Firebase-synced protection state
        Log.d(TAG, "🛡️ Protection check: Connected=YES, FirebaseState=" + deviceAdminProtectionEnabled);
        return deviceAdminProtectionEnabled;
    }

    /**
     * 🛡️ Setup Firebase listener for protection state changes from parent
     */
    private void setupProtectionStateListener() {
        if (sessionManager == null) {
            Log.e(TAG, "❌ SessionManager is NULL - cannot setup protection listener");
            return;
        }

        String deviceId = sessionManager.getChildDeviceId();
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG, "⚠️ No child device ID - cannot listen for protection state");
            Log.w(TAG, "⚠️ SessionManager data: " + sessionManager.getSessionInfo());
            return;
        }

        Log.d(TAG, "🛡️ ========== PROTECTION LISTENER SETUP ==========");
        Log.d(TAG, "🛡️ Device ID: " + deviceId);
        Log.d(TAG, "🛡️ Firebase Path: child_devices/" + deviceId + "/uninstall_protection");
        Log.d(TAG, "🛡️ Current protection state: " + deviceAdminProtectionEnabled);

        com.google.firebase.database.DatabaseReference protectionRef = com.google.firebase.database.FirebaseDatabase
                .getInstance()
                .getReference("child_devices")
                .child(deviceId)
                .child("uninstall_protection");

        protectionStateListener = new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                Log.d(TAG, "🛡️ ========== FIREBASE CALLBACK TRIGGERED ==========");
                Log.d(TAG, "🛡️ Snapshot exists: " + snapshot.exists());
                Log.d(TAG, "🛡️ Snapshot value: " + snapshot.getValue());

                if (snapshot.exists()) {
                    Boolean enabled = snapshot.getValue(Boolean.class);
                    if (enabled != null) {
                        boolean previousState = deviceAdminProtectionEnabled;
                        deviceAdminProtectionEnabled = enabled;

                        Log.d(TAG, "🛡️ Protection state CHANGED:");
                        Log.d(TAG, "  BEFORE: " + previousState);
                        Log.d(TAG, "  AFTER:  " + enabled);
                        Log.d(TAG, "  Device ID: " + deviceId);

                        if (enabled) {
                            Toast.makeText(BlockService.this, "🛡️ Uninstall protection ENABLED by parent",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(BlockService.this, "⚠️ Uninstall protection DISABLED by parent",
                                    Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Log.w(TAG, "⚠️ Protection value is NULL in Firebase");
                    }
                } else {
                    // If the node doesn't exist, it means the device was removed by the parent
                    // or the protection setting was cleared. We should DISABLE protection.
                    deviceAdminProtectionEnabled = false;
                    Log.d(TAG, "🛡️ No protection state in Firebase (Device Removed?) - Defaulting to DISABLED");
                    Toast.makeText(BlockService.this, "⚠️ Device removed/unmanaged - Protection DISABLED",
                            Toast.LENGTH_LONG).show();
                }
                Log.d(TAG, "🛡️ ================================================");
            }

            @Override
            public void onCancelled(com.google.firebase.database.DatabaseError error) {
                Log.e(TAG, "❌ ========== FIREBASE LISTENER CANCELLED ==========");
                Log.e(TAG, "❌ Error: " + error.getMessage());
                Log.e(TAG, "❌ Code: " + error.getCode());
                Log.e(TAG, "❌ Details: " + error.getDetails());
                Log.e(TAG, "❌ ==================================================");

                // If permission denied (e.g. device removed so child loses read access),
                // we should probably disable protection too?
                if (error.getCode() == com.google.firebase.database.DatabaseError.PERMISSION_DENIED) {
                    deviceAdminProtectionEnabled = false;
                    Log.d(TAG, "🛡️ Permission denied (Device Removed) - Protection DISABLED");
                }
            }
        };

        protectionRef.addValueEventListener(protectionStateListener);
        Log.d(TAG, "✅ Protection listener ATTACHED to Firebase");
        Log.d(TAG, "✅ ================================================");
    }

    /**
     * 🛡️ GUARANTEED: Block when on Sentinel's Protected Settings Pages
     * Covers:
     * 1. Device Admin Detail Page (prevents deactivation)
     * 2. Accessibility Service Detail Page (prevents disabling service)
     */
    private boolean isProtectedSettingsPage(AccessibilityEvent event) {
        if (event == null)
            return false;

        // Check if we're in Settings app
        CharSequence pkgName = event.getPackageName();
        if (pkgName == null || !pkgName.toString().equals(SETTINGS_PACKAGE)) {
            return false;
        }

        // Only check on window content changes (when pages load/update)
        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return false;
        }

        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return false;
            }

            // Scan ALL text on the screen
            String screenText = getAllTextOnScreen(root).toLowerCase();
            root.recycle(); // release immediately

            // DEBUG: Log screen text to help identify why detection might fail
            if (screenText.contains("sentinel") || screenText.contains("master2")) {
                Log.d(TAG, "🔍 Scanned Settings Page Text: " + screenText);
            }

            // 1. Check for App Name (Primary Filter)
            boolean hasAppName = screenText.contains("sentinel") ||
                    screenText.contains("master2") ||
                    screenText.contains("sparent");

            if (!hasAppName)
                return false;

            // ⛔ EXCLUSION: Don't block the LIST of apps (so user can navigate)
            if (screenText.contains("downloaded apps") ||
                    screenText.contains("installed services") ||
                    screenText.contains("installed apps") ||
                    screenText.contains("more services") ||
                    screenText.contains("general")) { // 'General' often appears in headers of settings lists
                // But wait, 'General' also appears in detail pages sometimes.
                // Let's stick to list titles.
                if (screenText.contains("downloaded apps") ||
                        screenText.contains("installed services") ||
                        screenText.contains("installed apps")) {
                    Log.d(TAG, "✅ Detected Accessibility LIST page - Allowing access");
                    return false;
                }
            }

            // 2. Check for Device Admin Page Indicators
            boolean isDeviceAdminPage = screenText.contains("this admin app is active") ||
                    screenText.contains("this device admin app") ||
                    screenText.contains("deactivate");

            // 3. Check for Accessibility Service Page Indicators
            // Expanded to catch more variations (Oppo, Vivo, Xiaomi, Samsung)
            boolean isAccessibilityPage = screenText.contains("use service") ||
                    screenText.contains("use sentinel") ||
                    screenText.contains("stop sentinel") ||
                    screenText.contains("allow sentinel") ||
                    screenText.contains("stop") ||
                    screenText.contains("shortcut") ||
                    screenText.contains("capability") || // "Capability: Control screen..."
                    screenText.contains("info"); // "App Info" or "Service Info"

            if (isDeviceAdminPage) {
                Log.e(TAG, "🚨 DETECTED DEVICE ADMIN PAGE for Sentinel");
                return true;
            }

            if (isAccessibilityPage) {
                Log.e(TAG, "🚨 DETECTED ACCESSIBILITY PAGE for Sentinel");
                return true;
            } else {
                // FALLBACK 1: App Name + Checkable Switch (Strong indicator of detail page)
                // "back sentinel [ui_switch]" is a common pattern on some devices
                if (screenText.contains("[ui_switch]")) {
                    Log.e(TAG, "🚨 DETECTED ACCESSIBILITY PAGE (App Name + Switch)");
                    return true;
                }

                // FALLBACK 2: Strict Short Text Rule (e.g. just "back sentinel")
                // If we see ONLY "Sentinel" and maybe navigation terms, and ZERO list context
                if (screenText.length() < 100) {
                    Log.w(TAG, "⚠️ Suspected Accessibility Detail Page (Short Text: " + screenText + ")");
                    return true;
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error checking Protected Settings page: " + e.getMessage());
        }

        return false;
    }

    /**
     * Get all visible text from the screen
     */
    private String getAllTextOnScreen(AccessibilityNodeInfo root) {
        if (root == null)
            return "";
        StringBuilder allText = new StringBuilder();
        collectTextRecursive(root, allText, 0);
        return allText.toString();
    }

    /**
     * Recursively collect all text from node tree
     */
    private void collectTextRecursive(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null || depth > 10)
            return; // Limit depth to prevent infinite loops

        // Get node text
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            sb.append(text).append(" ");
        }

        // Get content description
        CharSequence desc = node.getContentDescription();
        if (desc != null && desc.length() > 0) {
            sb.append(desc).append(" ");
        }

        // 🔍 DETECT UI ELEMENTS (Switches/Toggles)
        // Ensure to handle null class names safely
        if (node.getClassName() != null) {
            String className = node.getClassName().toString().toLowerCase();
            if (className.contains("switch") || className.contains("toggle")) {
                sb.append("[UI_SWITCH] ");
            }
        }

        // Recurse through children
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && i < 100; i++) { // Limit children to prevent too much recursion
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectTextRecursive(child, sb, depth + 1);
                child.recycle();
            }
        }
    }

    /**
     * Check if a node contains references to our app
     */
    private boolean checkNodeForOurApp(AccessibilityNodeInfo node) {
        if (node == null)
            return false;

        // Check text content
        CharSequence text = node.getText();
        if (text != null) {
            String textStr = text.toString().toLowerCase();
            if (textStr.contains("sentinel") ||
                    textStr.contains("sparent") ||
                    textStr.contains("master2") ||
                    textStr.contains(getPackageName().toLowerCase())) {
                Log.w(TAG, "🎯🎯🎯 FOUND OUR APP in node text: '" + text + "'");
                return true;
            }
        }

        // Check content description
        CharSequence desc = node.getContentDescription();
        if (desc != null) {
            String descStr = desc.toString().toLowerCase();
            if (descStr.contains("sentinel") ||
                    descStr.contains("sparent") ||
                    descStr.contains("master2") ||
                    descStr.contains(getPackageName().toLowerCase())) {
                Log.w(TAG, "🎯🎯🎯 FOUND OUR APP in node description: '" + desc + "'");
                return true;
            }
        }

        return false;
    }

    /**
     * Verify we're actually in Device Admin settings context
     */
    private boolean isInDeviceAdminContext(AccessibilityNodeInfo root) {
        if (root == null)
            return false;

        // Search for Device Admin related text on screen
        for (String keyword : DEVICE_ADMIN_TEXTS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(keyword);
            if (nodes != null && !nodes.isEmpty()) {
                Log.d(TAG, "✅ Confirmed in Device Admin context - found: " + keyword);
                return true;
            }
        }

        return false;
    }

    /**
     * 🆕 ENHANCED: Check all visible windows for blocked apps
     * This handles split-screen, floating windows, and PIP mode
     */
    private void checkAndBlockMultiWindowApps() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP)
            return;

        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            SharedPreferences freshPrefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
            Set<String> blockedPackages = new HashSet<>();

            for (AccessibilityWindowInfo window : windows) {
                // Get the root node to find the package name
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null) {
                    CharSequence pkgName = root.getPackageName();
                    if (pkgName != null) {
                        String pkg = pkgName.toString();

                        // Check if this package is blocked
                        if (freshPrefs.getBoolean(pkg, false)) {
                            blockedPackages.add(pkg);
                            Log.d(TAG, "🪟 Found blocked app in multi-window: " + pkg +
                                    " | Window type: " + window.getType());
                        }
                    }
                    root.recycle();
                }
            }

            // Block all found blocked apps
            for (String blockedPkg : blockedPackages) {
                Log.d(TAG, "🚫 BLOCKING MULTI-WINDOW APP: " + blockedPkg);
                blockAppEnhanced(blockedPkg);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error checking multi-window apps: " + e.getMessage());
        }
    }

    /**
     * Check if package should be skipped for blocking
     * 🔧 FIXED: Only skip ACTUAL system components, not user-facing apps like
     * Chrome/Play Store
     */
    private boolean shouldSkipForBlocking(String packageName) {
        if (packageName == null)
            return true;

        // NEVER skip our own app
        if (packageName.contains("com.example.master2"))
            return false;

        // Only skip CRITICAL system components
        return packageName.equals("android") ||
                packageName.contains("launcher") ||
                packageName.equals("com.android.systemui") ||
                packageName.equals("com.android.settings") ||
                packageName.startsWith("com.android.inputmethod"); // System keyboard
    }

    private void broadcastForegroundApp(String packageName) {
        if (packageName == null)
            packageName = "";

        // Avoid spam by checking if package changed
        if (packageName.equals(lastBroadcastPkg))
            return;
        lastBroadcastPkg = packageName;

        // Determine if system app
        boolean isSystem = isSystemApp(packageName);

        Intent intent = new Intent("com.example.master2.APP_FOREGROUND");
        intent.putExtra("package_name", packageName);
        intent.putExtra("package", packageName);
        intent.putExtra("isSystem", isSystem);
        intent.putExtra("interaction_type", "foreground_change");
        sendBroadcast(intent);

        Log.d(TAG, "📱 Broadcasted foreground app: " + packageName);
    }

    // REMOVED - using simple app switching only

    private void blockAppEnhanced(String packageName) {
        Log.d(TAG, "🚫 ENHANCED MULTI-LAYER BLOCKING: " + packageName);
        String appName = getAppName(packageName);

        // LAYER 1: IMMEDIATE HOME ACTION (do this first)
        performGlobalAction(GLOBAL_ACTION_HOME);
        performGlobalAction(GLOBAL_ACTION_RECENTS); // Clear recents
        performGlobalAction(GLOBAL_ACTION_HOME); // Return home
        Log.d(TAG, "🏠 Layer 1: Home actions performed");

        // LAYER 2: TASK REMOVAL (Android 5.0+)
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            if (am != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                List<ActivityManager.AppTask> tasks = am.getAppTasks();
                for (ActivityManager.AppTask task : tasks) {
                    if (task.getTaskInfo() != null &&
                            task.getTaskInfo().baseIntent != null &&
                            task.getTaskInfo().baseIntent.getComponent() != null) {
                        String taskPkg = task.getTaskInfo().baseIntent.getComponent().getPackageName();
                        if (taskPkg.equals(packageName)) {
                            task.finishAndRemoveTask();
                            Log.d(TAG, "� Layer 2: Removed task " + taskPkg);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Layer 2 error: " + e.getMessage());
        }

        // LAYER 3: KILL BACKGROUND PROCESSES
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses(packageName);
                    Log.d(TAG, "💀 Layer 3: Killed background processes");
                }
            } catch (Exception e) {
                Log.e(TAG, "Layer 3 error: " + e.getMessage());
            }

            // LAYER 4: FORCE STOP (for specific devices)
            if (isMIUI() || Build.MANUFACTURER.equalsIgnoreCase("samsung") ||
                    Build.MANUFACTURER.equalsIgnoreCase("oppo") ||
                    Build.MANUFACTURER.equalsIgnoreCase("vivo")) {
                try {
                    Runtime.getRuntime().exec("am force-stop " + packageName);
                    Log.d(TAG, "🛑 Layer 4: Force-stop command sent");
                } catch (Exception e) {
                    Log.e(TAG, "Layer 4 error: " + e.getMessage());
                }
            }

            // LAYER 5: REPEATED HOME ACTIONS (ensure we stay on home)
            performGlobalAction(GLOBAL_ACTION_HOME);
            Log.d(TAG, "🏠 Layer 5: Repeated home action");

        }, 200); // Faster blocking

        // LAYER 6: CONTINUOUS RE-BLOCKING (in case app reopens)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String currentApp = getCurrentForegroundAppFromUsageStats();
            if (currentApp != null && currentApp.equals(packageName)) {
                Log.w(TAG, "⚠️ Layer 6: App STILL RUNNING! Re-blocking...");
                performGlobalAction(GLOBAL_ACTION_HOME);
                performGlobalAction(GLOBAL_ACTION_HOME); // Double home

                // Try force stop again
                try {
                    ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    if (am != null) {
                        am.killBackgroundProcesses(packageName);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Re-block error: " + e.getMessage());
                }
            }
        }, 500); // Check again after 500ms

        // User feedback
        Toast.makeText(this, "🚫 BLOCKED: " + appName, Toast.LENGTH_SHORT).show();

        // Broadcast blocking event
        Intent blockingIntent = new Intent("com.example.master2.APP_BLOCKED");
        blockingIntent.putExtra("package_name", packageName);
        blockingIntent.putExtra("app_name", appName);
        blockingIntent.putExtra("timestamp", System.currentTimeMillis());
        sendBroadcast(blockingIntent);

        Log.d(TAG, "✅ Multi-layer blocking complete for: " + appName);
    }

    private boolean shouldIgnoreForTimer(String packageName) {
        if (packageName == null)
            return true;

        // More comprehensive filtering for timer accuracy
        return packageName.equals("android") ||
                packageName.contains("launcher") ||
                packageName.contains("systemui") ||
                packageName.contains("system") ||
                packageName.startsWith("com.android.") ||
                packageName.contains("settings") ||
                packageName.contains("com.miui.") ||
                packageName.contains("com.google.android.gms") ||
                packageName.contains("com.google.android.permissioncontroller") ||
                packageName.contains("wallpaper") ||
                packageName.contains("keyboard") ||
                packageName.contains("inputmethod") ||
                packageName.equals("com.example.master2") || // Don't track our own app
                isSystemApp(packageName); // Filter out all system apps for timer purposes
    }

    private boolean isSystemApp(String packageName) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(packageName, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isMIUI() {
        return "Xiaomi".equalsIgnoreCase(Build.MANUFACTURER) ||
                Build.MODEL.toLowerCase().contains("redmi") ||
                Build.MODEL.toLowerCase().contains("poco") ||
                System.getProperty("ro.miui.ui.version.code") != null;
    }

    private String getAppName(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "BlockService interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "💀 BlockService destroyed");

        // Stop accuracy monitoring
        if (accuracyHandler != null && accuracyRunnable != null) {
            accuracyHandler.removeCallbacks(accuracyRunnable);
        }

        // Stop sync monitoring
        if (syncHandler != null && syncRunnable != null) {
            syncHandler.removeCallbacks(syncRunnable);
        }

        // Unregister broadcast receiver
        if (blockedAppsReceiver != null) {
            try {
                unregisterReceiver(blockedAppsReceiver);
                Log.d(TAG, "📡 Broadcast receiver unregistered");
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering broadcast receiver: " + e.getMessage());
            }
        }
    }

    private void runDiagnostics() {
        Log.d(TAG, "=== BLOCKSERVICE DIAGNOSTICS ===");

        // Check blocked apps
        Map<String, ?> allPrefs = prefs.getAll();
        int blockedCount = 0;

        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            if (entry.getValue() instanceof Boolean && (Boolean) entry.getValue()) {
                String appName = getAppName(entry.getKey());
                Log.d(TAG, "BLOCKED APP: " + appName + " (" + entry.getKey() + ")");
                blockedCount++;
            }
        }

        Log.d(TAG, "Total blocked apps: " + blockedCount);

        if (blockedCount == 0) {
            Log.w(TAG, "WARNING: No apps are currently blocked!");
        }

        // Check service permissions
        try {
            Log.d(TAG, "Service running: " + (getApplicationContext() != null ? "YES" : "NO"));
            Log.d(TAG, "Accessibility connected: YES");
        } catch (Exception e) {
            Log.e(TAG, "Service check failed: " + e.getMessage());
        }

        Log.d(TAG, "=== END DIAGNOSTICS ===");
    }
}