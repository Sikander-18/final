package com.example.master2;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.auth.FirebaseAuth;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.app.PendingIntent;
import androidx.annotation.NonNull;
import android.os.PowerManager;

public class RemoteBlockService extends Service {
    private static final String TAG = "RemoteBlockService";
    private static final String PREF_NAME = "blocked_apps";

    // 🔧 OEM COMPATIBILITY: Wake lock for aggressive OEMs
    private PowerManager.WakeLock wakeLock;
    private OEMCompatibilityManager oemManager;

    // === Usage-snapshot constants ===
    private static final int DAYS_WINDOW = 7; // today + previous 6 days
    private static final long LIMIT_MILLIS = 150 * 60 * 1000L; // 2.5 h threshold for trimming
    private static final long SNAPSHOT_INTERVAL_MS = 30 * 1000L; // every 30 seconds for faster updates

    private static final Set<String> IGNORED_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.as",
            "com.google.android.permissioncontroller",
            "com.miui.home",
            "com.miui.systemui",
            "com.mi.android.globallauncher",
            "com.miui.securitycenter",
            "com.miui.cleanmaster",
            "com.miui.securityadd",
            "com.miui.miservice"));

    private static final String CHANNEL_ID = "remote_block_bg";

    private SharedPreferences blockedAppsPrefs;
    private DatabaseReference blockCommandsRef;
    private String myDeviceId;
    private ValueEventListener commandListener;
    private DeviceStatusManager deviceStatusManager;
    private SessionManager sessionManager;

    private Handler usageHandler;
    private Runnable usageRunnable;

    // 🎯 SMART USAGE TRACKER - GAME CHANGER!
    private SmartUsageTracker smartUsageTracker;

    // NEW: Logout listener variables
    private DatabaseReference logoutRef;
    private ValueEventListener logoutListener;
    private String parentUserId;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🛡️ RemoteBlockService created - BULLETPROOF MODE");

        // 🛡️ BULLETPROOF: Wrap everything in try-catch to prevent crashes
        try {
            // 🔧 OEM COMPATIBILITY: Initialize OEM manager and wake lock
            oemManager = new OEMCompatibilityManager(this);
            oemManager.logOEMInfo();
            acquireOEMWakeLock();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize OEM manager: " + e.getMessage());
        }

        // 🛡️ DEVICE OWNER: Protections removed as requested
        Log.d(TAG, "🛡️ Device Owner checks disabled");

        // Create notification channel & promote to foreground (CRITICAL)
        try {
            createNotificationChannel();

            // Create high-priority persistent notification
            Notification.Builder notificationBuilder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                notificationBuilder = new Notification.Builder(this);
            }

            Notification notification = notificationBuilder
                    .setContentTitle("🛡️ Parental Control Active")
                    .setContentText("Monitoring & protecting device")
                    .setSmallIcon(R.drawable.ic_shield)
                    .setOngoing(true) // Cannot be dismissed
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build();

            // Use FOREGROUND_SERVICE_TYPE_DATA_SYNC for Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }

            Log.d(TAG, "✅ High-priority foreground service started");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start foreground: " + e.getMessage());
        }

        try {
            blockedAppsPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            myDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            Log.d(TAG, "RemoteBlockService myDeviceId: " + myDeviceId);

            // Initialize session manager
            sessionManager = new SessionManager(this);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize core components: " + e.getMessage());
        }

        // 🚫 DISABLED: SmartUsageTracker - now using BulletproofUsageTracker in
        // ChildDashboardActivity
        // This was causing duplicate Firebase writes and conflicts
        // Usage tracking is now handled ONLY by BulletproofUsageTracker
        Log.d(TAG, "ℹ️ SmartUsageTracker DISABLED - using BulletproofUsageTracker instead");

        try {
            FirebaseDatabase database = FirebaseDatabase.getInstance();
            blockCommandsRef = database.getReference("block_commands");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to get Firebase reference: " + e.getMessage());
        }

        // Initialize device status manager with error handling
        try {
            deviceStatusManager = new DeviceStatusManager(this);
            String deviceName = ChildAppUtils.getChildDeviceName();
            deviceStatusManager.startAsChildDevice(myDeviceId, deviceName);
            Log.d(TAG, "Device status manager started");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start device status manager: " + e.getMessage());
        }

        // Start listeners with error handling
        try {
            startListeningForBlockCommands();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start block commands listener: " + e.getMessage());
        }

        // Delay non-critical listeners to prevent initialization race
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // NEW: Start listening for logout signals
                setupLogoutListener();
                Log.d(TAG, "✅ Logout listener started");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to setup logout listener: " + e.getMessage());
            }

            try {
                // NEW: Start listening for usage refresh commands from parent
                setupUsageRefreshListener();
                Log.d(TAG, "✅ Usage refresh listener started");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to setup usage refresh listener: " + e.getMessage());
            }

            try {
                // NEW: Start listening for SUSAGE update requests from parent
                setupSUsageUpdateListener();
                Log.d(TAG, "✅ SUSAGE update listener started");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to setup SUSAGE update listener: " + e.getMessage());
            }
        }, 2000); // 2 second delay

        // 🚫 DISABLED: Smart Usage Tracking - now handled by BulletproofUsageTracker
        // The ChildDashboardActivity handles all usage tracking via
        // BulletproofUsageTracker
        // This service only handles blocking, not usage tracking
        Log.d(TAG, "ℹ️ Usage tracking handled by BulletproofUsageTracker in ChildDashboardActivity");

        // Delay non-critical operations
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // AUTO-REFRESH: Upload latest app list to Firebase
                refreshDeviceAppList();
                Log.d(TAG, "✅ Device app list refreshed");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to refresh device app list: " + e.getMessage());
            }

            try {
                // 🔧 DB CONNECTION FIX: Enable Firebase persistence and keepAlive
                enableFirebaseConnectionStability();
                Log.d(TAG, "✅ Firebase connection stability enabled");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to enable Firebase stability: " + e.getMessage());
            }
        }, 3000); // 3 second delay

        Log.d(TAG, "✅ RemoteBlockService onCreate completed - BULLETPROOF");
    }

    /**
     * 🔧 DB CONNECTION FIX: Enable Firebase connection stability features
     * This helps prevent disconnections on OEM devices
     */
    private void enableFirebaseConnectionStability() {
        try {
            // Enable disk persistence for offline support
            FirebaseDatabase database = FirebaseDatabase.getInstance();

            // 🔧 FIX: Don't call keepSynced on .info paths - they don't support it
            // Keep important DATA paths synced instead (not .info paths)
            if (myDeviceId != null && !myDeviceId.isEmpty()) {
                database.getReference("block_commands").child(myDeviceId).keepSynced(true);
                database.getReference("usage_limiters").child(myDeviceId).keepSynced(true);
                database.getReference("device_status").child(myDeviceId).keepSynced(true);
                Log.d(TAG, "✅ Firebase paths kept synced for device: " + myDeviceId);
            }

            // Setup connection state listener
            DatabaseReference connectedRef = database.getReference(".info/connected");
            connectedRef.addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    Boolean connected = snapshot.getValue(Boolean.class);
                    if (connected != null) {
                        if (connected) {
                            Log.d(TAG, "✅ Firebase CONNECTED - Database is online");
                            // Device status is automatically updated by DeviceStatusManager's own listener
                        } else {
                            Log.w(TAG, "⚠️ Firebase DISCONNECTED - Will auto-reconnect");

                            // Try to force reconnect on aggressive OEMs
                            if (oemManager != null && oemManager.isAggressiveOEM()) {
                                Log.d(TAG, "🔄 Aggressive OEM detected - scheduling reconnect attempt");
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    database.goOnline();
                                    Log.d(TAG, "🔄 Forced Firebase reconnection attempt");
                                }, 5000); // 5 second delay
                            }
                        }
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "❌ Firebase connection listener cancelled: " + error.getMessage());
                }
            });

            Log.d(TAG, "✅ Firebase connection stability enabled");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error enabling Firebase stability: " + e.getMessage());
        }
    }

    // Enhanced setupLogoutListener method with extensive debugging
    private void setupLogoutListener() {
        Log.d(TAG, "=== LOGOUT LISTENER SETUP DEBUG ===");

        if (sessionManager == null) {
            Log.e(TAG, "SessionManager is null!");
            return;
        }

        if (!sessionManager.isLoggedIn()) {
            Log.w(TAG, "User is not logged in, skipping logout listener setup");
            return;
        }

        if (!sessionManager.getUserType().equals("child")) {
            Log.w(TAG, "User type is not 'child': " + sessionManager.getUserType());
            return;
        }

        String childDeviceId = sessionManager.getChildDeviceId();
        parentUserId = sessionManager.getParentUserId();

        Log.d(TAG, "Session Manager Values:");
        Log.d(TAG, "- childDeviceId: " + childDeviceId);
        Log.d(TAG, "- parentUserId: " + parentUserId);
        Log.d(TAG, "- userType: " + sessionManager.getUserType());

        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.e(TAG, "Child device ID is null or empty!");
            return;
        }

        if (parentUserId == null || parentUserId.isEmpty()) {
            Log.e(TAG, "Parent user ID is null or empty!");
            return;
        }

        // Check Firebase Authentication
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            Log.d(TAG, "Firebase Auth User ID: " + FirebaseAuth.getInstance().getCurrentUser().getUid());
            Log.d(TAG, "Firebase Auth User Email: " + FirebaseAuth.getInstance().getCurrentUser().getEmail());
        } else {
            Log.w(TAG, "No Firebase Auth user found");
        }

        Log.d(TAG, "Setting up logout listener for:");
        Log.d(TAG, "- Path: parents/" + parentUserId + "/connectedChildDevices/" + childDeviceId);

        logoutRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(parentUserId)
                .child("connectedChildDevices")
                .child(childDeviceId);

        logoutListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Log.d(TAG, "=== LOGOUT LISTENER TRIGGERED ===");
                Log.d(TAG, "DataSnapshot exists: " + dataSnapshot.exists());
                Log.d(TAG, "DataSnapshot value: " + dataSnapshot.getValue());

                if (dataSnapshot.exists()) {
                    // Log all children
                    for (DataSnapshot child : dataSnapshot.getChildren()) {
                        Log.d(TAG, "Child key: " + child.getKey() + ", value: " + child.getValue());
                    }

                    Boolean logoutFlag = dataSnapshot.child("logout").getValue(Boolean.class);
                    Log.d(TAG, "Logout flag value: " + logoutFlag);

                    if (logoutFlag != null && logoutFlag) {
                        Log.d(TAG, "🚨 LOGOUT FLAG DETECTED - performing child logout");
                        handleChildLogout();
                    } else {
                        Log.d(TAG, "Logout flag is false or null, continuing normal operation");
                    }
                } else {
                    Log.d(TAG, "🚨 CHILD DEVICE DATA NO LONGER EXISTS - device removed");
                    handleChildLogout();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "=== LOGOUT LISTENER CANCELLED ===");
                Log.e(TAG, "Error code: " + databaseError.getCode());
                Log.e(TAG, "Error message: " + databaseError.getMessage());
                Log.e(TAG, "Error details: " + databaseError.getDetails());

                if (databaseError.getCode() == DatabaseError.PERMISSION_DENIED) {
                    Log.e(TAG, "🚨 PERMISSION DENIED - Check Firebase rules!");
                }
            }
        };

        logoutRef.addValueEventListener(logoutListener);
        Log.d(TAG, "✅ Logout listener successfully attached");
        Log.d(TAG, "=== END LOGOUT LISTENER SETUP ===");
    }

    // Enhanced handleChildLogout with more debugging
    private void handleChildLogout() {
        Log.d(TAG, "=== HANDLING CHILD LOGOUT ===");

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Log.d(TAG, "Executing logout on main thread");

                // Show toast notification
                Toast.makeText(this, "🚨 Device removed by parent. Logging out...", Toast.LENGTH_LONG).show();

                // Clear local session data
                if (sessionManager != null) {
                    Log.d(TAG, "Clearing session data");
                    sessionManager.logoutUser();
                } else {
                    Log.e(TAG, "SessionManager is null during logout");
                }

                // Clear blocked apps preferences
                if (blockedAppsPrefs != null) {
                    Log.d(TAG, "Clearing blocked apps preferences");
                    blockedAppsPrefs.edit().clear().apply();
                } else {
                    Log.e(TAG, "blockedAppsPrefs is null during logout");
                }

                // Clear any other app-specific preferences
                try {
                    SharedPreferences todoPrefs = getSharedPreferences("todo_prefs", MODE_PRIVATE);
                    todoPrefs.edit().clear().apply();
                    SharedPreferences devicePrefs = getSharedPreferences("device_info", MODE_PRIVATE);
                    devicePrefs.edit().clear().apply();
                    // Add more SharedPreferences clears here if you use others
                    Log.d(TAG, "Cleared additional app-specific preferences");
                } catch (Exception e) {
                    Log.e(TAG, "Error clearing additional preferences: " + e.getMessage());
                }

                // Send broadcast to notify activities
                Log.d(TAG, "Sending logout broadcast");
                Intent logoutIntent = new Intent("com.example.master2.CHILD_LOGOUT");
                sendBroadcast(logoutIntent);

                // Small delay to ensure broadcast is received
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        Log.d(TAG, "Starting login activity");
                        // Navigate to login screen
                        Intent intent = new Intent(this, ChildLoginActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(intent);

                        Log.d(TAG, "✅ Child logout handled successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "Error starting login activity: " + e.getMessage());
                    }
                }, 1000);

            } catch (Exception e) {
                Log.e(TAG, "Error handling child logout: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Parental Control Background",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("Keeps the monitoring service alive");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null)
                nm.createNotificationChannel(channel);
        }
    }

    /**
     * Setup listener for usage data refresh commands from parent
     */
    private void setupUsageRefreshListener() {
        Log.d(TAG, "🔄 Setting up usage refresh command listener for device: " + myDeviceId);

        DatabaseReference refreshCommandRef = FirebaseDatabase.getInstance()
                .getReference("usage_refresh_commands")
                .child(myDeviceId);

        refreshCommandRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists())
                    return;

                Log.d(TAG, "📥 Usage refresh command received");

                try {
                    String command = dataSnapshot.child("command").getValue(String.class);
                    Long timestamp = dataSnapshot.child("timestamp").getValue(Long.class);
                    String requestedBy = dataSnapshot.child("requestedBy").getValue(String.class);
                    String priority = dataSnapshot.child("priority").getValue(String.class);

                    if ("refresh_usage_data".equals(command) && timestamp != null) {
                        // Check if command is recent (within 5 minutes)
                        long currentTime = System.currentTimeMillis();
                        long commandAge = currentTime - timestamp;

                        if (commandAge < 300000) { // 5 minutes
                            Log.d(TAG, "🚀 Processing usage refresh command (priority: " + priority + ")");

                            // Force immediate usage snapshot upload
                            if (hasUsageStatsPermission()) {
                                // 🎯 Use Smart Usage Tracker instead of old method
                                smartUsageTracker.collectAndUploadSmartUsageData();
                                Log.d(TAG, "✅ Immediate usage snapshot uploaded");
                            } else {
                                Log.w(TAG, "❌ Cannot upload usage data - missing permission");
                            }

                            // Clear the command to prevent re-processing
                            refreshCommandRef.removeValue()
                                    .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Refresh command cleared"))
                                    .addOnFailureListener(
                                            e -> Log.e(TAG, "❌ Failed to clear refresh command: " + e.getMessage()));
                        } else {
                            Log.w(TAG, "⏰ Ignoring old refresh command (age: " + (commandAge / 1000) + " seconds)");
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Error processing refresh command: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "❌ Usage refresh listener cancelled: " + databaseError.getMessage());
            }
        });

        Log.d(TAG, "✅ Usage refresh listener setup complete");
    }

    /**
     * Setup listener for SUSAGE-style update requests from parent
     * When parent clicks Update/Refresh, this triggers immediate usage data
     * collection and upload
     */
    private void setupSUsageUpdateListener() {
        Log.d(TAG, "🔄 Setting up SUSAGE update request listener for device: " + myDeviceId);

        DatabaseReference susageRequestRef = FirebaseDatabase.getInstance()
                .getReference("susage_update_requests")
                .child(myDeviceId);

        susageRequestRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists())
                    return;

                Boolean requestUpdate = dataSnapshot.child("requestUpdate").getValue(Boolean.class);

                if (Boolean.TRUE.equals(requestUpdate)) {
                    Log.d(TAG, "📥 SUSAGE update request received from parent!");

                    if (hasUsageStatsPermission()) {
                        Log.d(TAG, "🚀 Collecting and uploading SUSAGE data...");

                        // Use SUsageDataManager to collect and upload data
                        try {
                            com.example.master2.utils.SUsageDataManager usageManager = com.example.master2.utils.SUsageDataManager
                                    .getInstance(RemoteBlockService.this);

                            usageManager.uploadToFirebase(myDeviceId,
                                    new com.example.master2.utils.SUsageDataManager.OnUploadCompleteListener() {
                                        @Override
                                        public void onSuccess() {
                                            Log.d(TAG, "✅ SUSAGE data uploaded successfully");
                                            // Clear the request flag
                                            susageRequestRef.child("requestUpdate").setValue(false);
                                        }

                                        @Override
                                        public void onError(String error) {
                                            Log.e(TAG, "❌ SUSAGE upload failed: " + error);
                                            // Clear the request flag even on error
                                            susageRequestRef.child("requestUpdate").setValue(false);
                                        }
                                    });
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error uploading SUSAGE data: " + e.getMessage());
                            susageRequestRef.child("requestUpdate").setValue(false);
                        }
                    } else {
                        Log.w(TAG, "❌ Cannot upload SUSAGE data - missing UsageStats permission");
                        susageRequestRef.child("requestUpdate").setValue(false);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "❌ SUSAGE update listener cancelled: " + databaseError.getMessage());
            }
        });

        Log.d(TAG, "✅ SUSAGE update listener setup complete");
    }

    private void startListeningForBlockCommands() {

        Log.d(TAG, "Starting to listen for block commands for device: " + myDeviceId);

        commandListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                Log.d(TAG, "Block commands data changed");

                for (DataSnapshot commandSnapshot : dataSnapshot.getChildren()) {
                    try {
                        String commandKey = commandSnapshot.getKey();
                        Log.d(TAG, "Processing command: " + commandKey);

                        // Check if it's a focus mode command
                        Map<String, Object> commandData = (Map<String, Object>) commandSnapshot.getValue();
                        if (commandData != null && "focus_mode".equals(commandData.get("action"))) {

                            // Check if command is already processed
                            Boolean processed = (Boolean) commandData.get("processed");
                            if (processed != null && processed) {
                                Log.d(TAG, "Command already processed, skipping: " + commandKey);
                                continue;
                            }

                            Log.d(TAG, "Processing new focus mode command: " + commandKey);
                            handleFocusModeCommand(commandData, commandKey);
                            continue;
                        }

                        // Try to parse as BlockCommand for individual app commands
                        BlockCommand command = commandSnapshot.getValue(BlockCommand.class);
                        if (command != null && !command.executed) {
                            Log.d(TAG, "Processing individual block command: " + command.packageName + " -> "
                                    + command.blockStatus);
                            executeBlockCommand(command);
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Error processing command: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Failed to listen for block commands: " + databaseError.getMessage());
            }
        };

        blockCommandsRef.child(myDeviceId).addValueEventListener(commandListener);
    }

    private void executeBlockCommand(BlockCommand command) {
        Log.d(TAG, "🔒 Executing block command: " + command.appName + " -> " +
                (command.blockStatus ? "BLOCK" : "UNBLOCK"));

        // Update local SharedPreferences
        blockedAppsPrefs.edit()
                .putBoolean(command.packageName, command.blockStatus)
                .apply();

        // 🔧 IMMEDIATE UPDATE FIX: Broadcast to BlockService immediately
        broadcastBlockedAppsUpdate();

        // Mark command as executed
        command.executed = true;
        blockCommandsRef.child(myDeviceId).child(command.commandId)
                .setValue(command)
                .addOnSuccessListener(aVoid -> {
                    String action = command.blockStatus ? "BLOCKED" : "UNBLOCKED";
                    String message = command.appName + " " + action + " remotely";

                    Log.d(TAG, "✅ Command executed successfully: " + message);

                    // Show system notification (works even when app is closed)
                    showBlockNotification(command.appName, command.blockStatus);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to mark command as executed: " + e.getMessage());
                });
    }

    /**
     * 🔧 IMMEDIATE UPDATE: Broadcast to BlockService that blocked apps list changed
     */
    private void broadcastBlockedAppsUpdate() {
        try {
            // Count blocked apps
            int blockedCount = 0;
            Map<String, ?> allPrefs = blockedAppsPrefs.getAll();
            for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
                if (entry.getValue() instanceof Boolean && (Boolean) entry.getValue()) {
                    blockedCount++;
                }
            }

            // Send broadcast to BlockService
            Intent broadcastIntent = new Intent("com.example.master2.BLOCKED_APPS_UPDATED");
            broadcastIntent.putExtra("blocked_count", blockedCount);
            broadcastIntent.setPackage(getPackageName()); // Explicit package for security
            sendBroadcast(broadcastIntent);

            Log.d(TAG, "📡 Broadcasted blocked apps update - count: " + blockedCount);
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to broadcast update: " + e.getMessage());
        }
    }

    /**
     * 🔔 Show system notification for block/unblock (works when app is closed)
     */
    private void showBlockNotification(String appName, boolean blocked) {
        try {
            String title = blocked ? "🚫 App Blocked" : "✅ App Unblocked";
            String message = appName + " has been " + (blocked ? "blocked" : "unblocked") + " by parent";

            android.app.NotificationManager notificationManager = (android.app.NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);

            // Create notification channel for Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "block_notifications",
                        "Block Notifications",
                        android.app.NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Notifications for app blocking");
                notificationManager.createNotificationChannel(channel);
            }

            android.app.Notification notification = new android.app.Notification.Builder(this,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "block_notifications" : null)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setAutoCancel(true)
                    .setPriority(android.app.Notification.PRIORITY_HIGH)
                    .build();

            notificationManager.notify((int) System.currentTimeMillis() % 10000, notification);
            Log.d(TAG, "🔔 Block notification shown: " + message);

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to show notification: " + e.getMessage());
        }
    }

    /**
     * 🔔 Show system notification for Focus Mode (works when app is closed)
     */
    private void showFocusModeNotification(boolean activated, int appCount) {
        try {
            String title = activated ? "🎯 Focus Mode Activated" : "✅ Focus Mode Deactivated";
            String message = activated ? appCount + " apps are now blocked by parent" : "All apps have been unblocked";

            android.app.NotificationManager notificationManager = (android.app.NotificationManager) getSystemService(
                    Context.NOTIFICATION_SERVICE);

            // Create notification channel for Android 8+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        "focus_mode_notifications",
                        "Focus Mode Notifications",
                        android.app.NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription("Notifications for focus mode changes");
                notificationManager.createNotificationChannel(channel);
            }

            android.app.Notification notification = new android.app.Notification.Builder(this,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "focus_mode_notifications" : null)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(R.drawable.ic_shield)
                    .setAutoCancel(true)
                    .setPriority(android.app.Notification.PRIORITY_HIGH)
                    .build();

            // Use a fixed ID for focus mode so it replaces previous notifications
            notificationManager.notify(9001, notification);
            Log.d(TAG, "🔔 Focus mode notification shown: " + title);

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to show focus mode notification: " + e.getMessage());
        }
    }

    private void handleFocusModeCommand(Map<String, Object> commandData, String commandKey) {
        try {
            Boolean enabled = (Boolean) commandData.get("enabled");
            if (enabled == null) {
                Log.e(TAG, "Focus mode command missing 'enabled' field");
                return;
            }

            Log.d(TAG, "Focus mode command: " + (enabled ? "ACTIVATE" : "DEACTIVATE"));

            if (enabled) {
                // Get custom app list from parent, or use default if not provided
                List<String> appsToBlock = (List<String>) commandData.get("apps");
                if (appsToBlock == null || appsToBlock.isEmpty()) {
                    Log.d(TAG, "No custom apps provided, using basic focus mode");
                    activateBasicFocusMode();
                } else {
                    Log.d(TAG, "Custom apps provided: " + appsToBlock.size() + " apps");
                    // Log the apps being blocked for debugging
                    for (String pkg : appsToBlock) {
                        Log.d(TAG, "Blocking app: " + pkg);
                    }
                    activateCustomFocusMode(appsToBlock);
                }

                // 🔧 IMMEDIATE UPDATE: Broadcast to BlockService
                broadcastBlockedAppsUpdate();

                // Verify the apps are actually blocked
                verifyAppsBlocked();

                // Show system notification (works when app is closed)
                showFocusModeNotification(true, appsToBlock != null ? appsToBlock.size() : 0);
            } else {
                // Deactivate focus mode - unblock all apps
                deactivateBasicFocusMode();

                // 🔧 IMMEDIATE UPDATE: Broadcast to BlockService
                broadcastBlockedAppsUpdate();

                // Show system notification
                showFocusModeNotification(false, 0);
            }

            // Mark command as processed by updating timestamp
            Map<String, Object> updatedCommand = new HashMap<>(commandData);
            updatedCommand.put("processed", true);
            updatedCommand.put("processedAt", System.currentTimeMillis());

            blockCommandsRef.child(myDeviceId).child(commandKey)
                    .setValue(updatedCommand)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Focus mode command marked as processed");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to mark focus mode command as processed: " + e.getMessage());
                    });

        } catch (Exception e) {
            Log.e(TAG, "Error handling focus mode command: " + e.getMessage());
            Toast.makeText(this, "❌ Error processing focus mode command", Toast.LENGTH_SHORT).show();
        }
    }

    private void activateBasicFocusMode() {
        // Block common distracting apps
        String[] distractingApps = {
                "com.instagram.android",
                "com.snapchat.android",
                "com.tiktok.android",
                "com.facebook.katana",
                "com.twitter.android",
                "com.whatsapp",
                "com.spotify.music",
                "com.netflix.mediaclient",
                "com.youtube.android",
                "com.reddit.frontpage",
                "com.discord"
        };

        for (String packageName : distractingApps) {
            blockedAppsPrefs.edit().putBoolean(packageName, true).apply();
            Log.d(TAG, "Blocked app: " + packageName);
        }

        // CRITICAL FIX: Notify BlockService to reload blocked apps immediately
        Intent intent = new Intent("com.example.master2.BLOCKED_APPS_UPDATED");
        intent.putExtra("blocked_count", distractingApps.length);
        sendBroadcast(intent);

        Log.d(TAG, "Basic focus mode activated - blocked " + distractingApps.length + " common distracting apps");
    }

    private void activateCustomFocusMode(List<String> appsToBlock) {
        // Block custom list of apps provided by parent
        int blockedCount = 0;
        for (String packageName : appsToBlock) {
            if (packageName != null && !packageName.trim().isEmpty()) {
                blockedAppsPrefs.edit().putBoolean(packageName, true).apply();
                Log.d(TAG, "Blocked custom app: " + packageName);
                blockedCount++;
            } else {
                Log.w(TAG, "Skipping invalid package name: " + packageName);
            }
        }

        // CRITICAL FIX: Notify BlockService to reload blocked apps immediately
        Intent intent = new Intent("com.example.master2.BLOCKED_APPS_UPDATED");
        intent.putExtra("blocked_count", blockedCount);
        sendBroadcast(intent);

        Log.d(TAG, "Custom focus mode activated - blocked " + blockedCount + " apps from parent");
    }

    private void deactivateBasicFocusMode() {
        // Unblock all apps by clearing preferences
        blockedAppsPrefs.edit().clear().apply();

        // CRITICAL FIX: Notify BlockService to reload blocked apps immediately
        Intent intent = new Intent("com.example.master2.BLOCKED_APPS_UPDATED");
        intent.putExtra("blocked_count", 0);
        sendBroadcast(intent);

        Log.d(TAG, "Basic focus mode deactivated - all apps unblocked");
    }

    private void verifyAppsBlocked() {
        // Log all currently blocked apps for debugging
        Map<String, ?> allPrefs = blockedAppsPrefs.getAll();
        int blockedCount = 0;

        Log.d(TAG, "=== BLOCKED APPS VERIFICATION ===");
        for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
            if (entry.getValue() instanceof Boolean && (Boolean) entry.getValue()) {
                Log.d(TAG, "BLOCKED: " + entry.getKey());
                blockedCount++;
            }
        }
        Log.d(TAG, "Total blocked apps: " + blockedCount);
        Log.d(TAG, "=== END VERIFICATION ===");

        if (blockedCount == 0) {
            Log.w(TAG, "WARNING: No apps are currently blocked!");
            Toast.makeText(this, "⚠️ Warning: No apps are blocked", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 🛡️ CRITICAL FIX: Call startForeground IMMEDIATELY to prevent crash
        // This must be done before any other logic
        try {
            createNotificationChannel();

            Notification.Builder notificationBuilder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
            } else {
                notificationBuilder = new Notification.Builder(this);
            }

            Notification notification = notificationBuilder
                    .setContentTitle("🛡️ Parental Control Active")
                    .setContentText("Monitoring & protecting device")
                    .setSmallIcon(R.drawable.ic_shield)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, notification);
            }

            Log.d(TAG, "✅ startForeground called in onStartCommand");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start foreground: " + e.getMessage());
        }

        try {
            Log.d(TAG, "RemoteBlockService started");

            // Handle refresh logout listener request
            if (intent != null && "refresh_logout_listener".equals(intent.getStringExtra("action"))) {
                Log.d(TAG, "🔄 Received refresh logout listener request");

                try {
                    // Remove existing listener first
                    if (logoutListener != null && logoutRef != null) {
                        logoutRef.removeEventListener(logoutListener);
                        Log.d(TAG, "Removed existing logout listener");
                    }

                    // Setup new listener
                    setupLogoutListener();

                    // Show toast confirmation
                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            Toast.makeText(this, "🔄 Logout listener refreshed", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e(TAG, "Error showing toast: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Error refreshing logout listener: " + e.getMessage());
                }
            }

            // Handle immediate usage data collection refresh
            if (intent != null && "refresh_usage_collection".equals(intent.getStringExtra("action"))) {
                Log.d(TAG, "🚀 Received immediate SUSAGE collection refresh request");

                try {
                    // Trigger immediate SUSAGE upload if permission is available
                    if (hasUsageStatsPermission()) {
                        Log.d(TAG, "✅ Usage permission available - triggering SUSAGE upload");

                        // Use SUSAGE data manager for upload
                        new Thread(() -> {
                            try {
                                com.example.master2.utils.SUsageDataManager susageManager = com.example.master2.utils.SUsageDataManager
                                        .getInstance(this);

                                susageManager.uploadToFirebase(myDeviceId,
                                        new com.example.master2.utils.SUsageDataManager.OnUploadCompleteListener() {
                                            @Override
                                            public void onSuccess() {
                                                Log.d(TAG, "✅ SUSAGE data refreshed successfully");
                                                new Handler(Looper.getMainLooper()).post(() -> {
                                                    try {
                                                        Toast.makeText(RemoteBlockService.this,
                                                                "📊 Usage data refreshed", Toast.LENGTH_SHORT).show();
                                                    } catch (Exception e) {
                                                        Log.e(TAG, "Error showing toast: " + e.getMessage());
                                                    }
                                                });
                                            }

                                            @Override
                                            public void onError(String error) {
                                                Log.e(TAG, "❌ SUSAGE refresh failed: " + error);
                                            }
                                        });
                            } catch (Exception e) {
                                Log.e(TAG, "Exception in SUSAGE refresh: " + e.getMessage());
                            }
                        }).start();
                    } else {
                        Log.w(TAG, "❌ Usage permission not available");
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                Toast.makeText(this, "❌ Usage permission required",
                                        Toast.LENGTH_SHORT).show();
                            } catch (Exception e) {
                                Log.e(TAG, "Error showing toast: " + e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error refreshing SUSAGE collection: " + e.getMessage());
                }
            }

            // Handle parent-triggered upload request
            if (intent != null && "UPLOAD_USAGE_DATA".equals(intent.getAction())) {
                Log.d(TAG, "🔄 Received parent-triggered SUSAGE upload request");

                try {
                    // Trigger immediate SUSAGE upload if permission is available
                    if (hasUsageStatsPermission()) {
                        Log.d(TAG, "✅ Usage permission available - triggering parent-requested SUSAGE upload");

                        // Use SUSAGE data manager for upload
                        new Thread(() -> {
                            try {
                                com.example.master2.utils.SUsageDataManager susageManager = com.example.master2.utils.SUsageDataManager
                                        .getInstance(this);

                                susageManager.uploadToFirebase(myDeviceId,
                                        new com.example.master2.utils.SUsageDataManager.OnUploadCompleteListener() {
                                            @Override
                                            public void onSuccess() {
                                                Log.d(TAG, "✅ Parent-requested SUSAGE upload successful");
                                                new Handler(Looper.getMainLooper()).post(() -> {
                                                    try {
                                                        Toast.makeText(RemoteBlockService.this,
                                                                "📤 Data uploaded for parent", Toast.LENGTH_SHORT)
                                                                .show();
                                                    } catch (Exception e) {
                                                        Log.e(TAG, "Error showing toast: " + e.getMessage());
                                                    }
                                                });
                                            }

                                            @Override
                                            public void onError(String error) {
                                                Log.e(TAG, "❌ Parent-requested SUSAGE upload failed: " + error);
                                            }
                                        });
                            } catch (Exception e) {
                                Log.e(TAG, "Exception in parent-requested upload: " + e.getMessage());
                            }
                        }).start();
                    } else {
                        Log.w(TAG, "❌ Usage permission not available");
                        new Handler(Looper.getMainLooper()).post(() -> {
                            try {
                                Toast.makeText(this, "❌ Usage permission required for data upload", Toast.LENGTH_SHORT)
                                        .show();
                            } catch (Exception e) {
                                Log.e(TAG, "Error showing toast: " + e.getMessage());
                            }
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error handling parent upload request: " + e.getMessage());
                }
            }

            // 🛡️ BULLETPROOF: Always return START_STICKY to auto-restart
            return START_STICKY;
        } catch (Exception e) {
            Log.e(TAG, "Critical error in onStartCommand: " + e.getMessage());
            // Don't stop - schedule restart instead
            scheduleServiceRestart();
            return START_STICKY;
        }
    }

    /**
     * 🔧 BULLETPROOF: Schedule service restart if it crashes
     */
    private void scheduleServiceRestart() {
        try {
            Log.d(TAG, "🔄 Scheduling service restart...");

            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager == null)
                return;

            Intent restartIntent = new Intent(this, RemoteBlockService.class);
            restartIntent.setAction("RESTART_SERVICE");

            int flags = PendingIntent.FLAG_ONE_SHOT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent pendingIntent = PendingIntent.getService(
                    this, 9999, restartIntent, flags);

            // Restart in 3 seconds
            long restartTime = android.os.SystemClock.elapsedRealtime() + 3000;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        restartTime,
                        pendingIntent);
            } else {
                alarmManager.setExact(
                        android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        restartTime,
                        pendingIntent);
            }

            Log.d(TAG, "✅ Service restart scheduled in 3 seconds");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to schedule restart: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "⚠️ RemoteBlockService being destroyed!");

        // Remove command listener
        if (commandListener != null && blockCommandsRef != null) {
            try {
                blockCommandsRef.child(myDeviceId).removeEventListener(commandListener);
            } catch (Exception e) {
                Log.e(TAG, "Error removing command listener: " + e.getMessage());
            }
        }

        // NEW: Remove logout listener
        if (logoutListener != null && logoutRef != null) {
            try {
                logoutRef.removeEventListener(logoutListener);
                Log.d(TAG, "Logout listener removed from background service");
            } catch (Exception e) {
                Log.e(TAG, "Error removing logout listener: " + e.getMessage());
            }
        }

        // Stop periodic uploads
        if (usageHandler != null && usageRunnable != null) {
            usageHandler.removeCallbacks(usageRunnable);
        }

        // Stop device status tracking
        if (deviceStatusManager != null) {
            try {
                deviceStatusManager.stopStatusTracking();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping status tracking: " + e.getMessage());
            }
        }

        // 🔧 OEM COMPATIBILITY: Release wake lock
        releaseOEMWakeLock();

        // 🛡️ BULLETPROOF: Schedule service restart when destroyed
        // This ensures the service comes back even if killed by OEM
        try {
            SessionManager sm = new SessionManager(this);
            if (sm.isLoggedIn() && "child".equals(sm.getUserType())) {
                Log.d(TAG, "🔄 Service destroyed - scheduling automatic restart...");
                scheduleServiceRestart();

                // Also notify the watchdog
                ServiceWatchdog.schedulePeriodicChecks(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error scheduling restart: " + e.getMessage());
        }
    }

    /**
     * 🎯 Start Smart Usage Tracking - GAME CHANGER!
     * Replaces old fixed 7-day system with rolling window from connection time
     */
    private void startSmartUsageTracking() {
        try {
            Log.d(TAG, "🎯 Starting Smart Usage Tracking with rolling 7-day window");

            usageHandler = new Handler(Looper.getMainLooper());
            usageRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        // SAFETY CHECK: Don't run if service is being destroyed
                        if (usageHandler == null || smartUsageTracker == null) {
                            Log.d(TAG, "Handler or tracker is null, stopping smart tracking");
                            return;
                        }

                        // 🎯 Use Smart Usage Tracker instead of old method
                        smartUsageTracker.collectAndUploadSmartUsageData();

                        // Schedule next run only if handler is still valid
                        if (usageHandler != null) {
                            usageHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Smart usage tracking failed", e);
                        // Continue the loop even if one upload fails
                        try {
                            if (usageHandler != null) {
                                usageHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS * 2); // Wait longer on error
                            }
                        } catch (Exception retryE) {
                            Log.e(TAG, "Failed to schedule smart tracking retry: " + retryE.getMessage());
                        }
                    }
                }
            };

            if (usageHandler != null) {
                usageHandler.post(usageRunnable);
            }

            Log.d(TAG, "🎯 Smart Usage Tracking started successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start Smart Usage Tracking: " + e.getMessage());
        }
    }

    /**
     * 🔄 OLD METHOD - Replaced by Smart Usage Tracking
     * 
     * @deprecated Use startSmartUsageTracking() instead
     */
    @Deprecated
    private void startUsageSnapshotLoop() {
        Log.w(TAG, "⚠️ Old usage snapshot method called - redirecting to Smart Usage Tracking");
        startSmartUsageTracking();
    }

    private void uploadUsageSnapshot() {
        Log.d(TAG, "uploadUsageSnapshot called for device: " + myDeviceId);
        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "❌ Usage access permission not granted – skipping snapshot for device: " + myDeviceId);
            Log.w(TAG, "Please grant Usage Access permission in Settings for data collection to work");
            return;
        }

        Log.d(TAG, "✅ Usage access permission granted - proceeding with data collection");
        Log.d(TAG, "Preparing usage snapshot for upload with historical data merge");

        // First, get existing data from Firebase to merge with new data
        DatabaseReference existingRef = FirebaseDatabase.getInstance()
                .getReference("usage_snapshots")
                .child(myDeviceId)
                .child("latest");

        existingRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot existingSnapshot) {
                Log.d(TAG, "Retrieved existing usage data for merging");

                // Get current usage data
                UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                if (usm == null) {
                    Log.e(TAG, "Failed to get UsageStatsManager");
                    return;
                }

                long dayMillis = 24 * 60 * 60 * 1000L;
                List<Float> barValues = new ArrayList<>();
                List<List<com.example.master2.models.AppUsage>> dailyAppLists = new ArrayList<>();
                List<String> totalTexts = new ArrayList<>();

                // Get existing data if available
                Map<Integer, List<com.example.master2.models.AppUsage>> existingDailyApps = new HashMap<>();
                Map<Integer, String> existingTotalTexts = new HashMap<>();

                if (existingSnapshot.exists()) {
                    Log.d(TAG, "Found existing data to merge with new usage");

                    // Parse existing dailyApps
                    if (existingSnapshot.child("dailyApps").exists()) {
                        int dayIndex = 0;
                        for (DataSnapshot daySnap : existingSnapshot.child("dailyApps").getChildren()) {
                            List<com.example.master2.models.AppUsage> dayApps = new ArrayList<>();
                            for (DataSnapshot appSnap : daySnap.getChildren()) {
                                com.example.master2.models.AppUsage appUsage = appSnap
                                        .getValue(com.example.master2.models.AppUsage.class);
                                if (appUsage != null) {
                                    dayApps.add(appUsage);
                                }
                            }
                            existingDailyApps.put(dayIndex, dayApps);
                            dayIndex++;
                        }
                    }

                    // Parse existing totalTexts
                    if (existingSnapshot.child("totalTexts").exists()) {
                        int textIndex = 0;
                        for (DataSnapshot textSnap : existingSnapshot.child("totalTexts").getChildren()) {
                            String totalText = textSnap.getValue(String.class);
                            if (totalText != null) {
                                existingTotalTexts.put(textIndex, totalText);
                            }
                            textIndex++;
                        }
                    }

                    Log.d(TAG, "Parsed " + existingDailyApps.size() + " days of existing usage data");
                }

                // Process each day with historical data merge
                for (int i = 0; i < DAYS_WINDOW; i++) {
                    long start = startOfDayMillis((DAYS_WINDOW - 1) - i);
                    long end = start + dayMillis;
                    int currentDayIndex = i;

                    Log.d(TAG, "Processing day " + ((DAYS_WINDOW - 1) - i) + ": " + new Date(start));

                    // Get new usage data for this day
                    Map<String, Long> newUsageMap = computeUsageFromEvents(usm, start, end);
                    Map<String, com.example.master2.models.AppUsage> mergedApps = new HashMap<>();

                    // Start with existing data for this day if available
                    if (existingDailyApps.containsKey(currentDayIndex)) {
                        List<com.example.master2.models.AppUsage> existingApps = existingDailyApps.get(currentDayIndex);
                        for (com.example.master2.models.AppUsage app : existingApps) {
                            mergedApps.put(app.getPackageName(), app);
                        }
                        Log.d(TAG,
                                "Starting with " + existingApps.size() + " existing apps for day " + currentDayIndex);
                    }

                    long dailyTotal = 0;
                    List<com.example.master2.models.AppUsage> dayApps = new ArrayList<>();

                    Log.d(TAG, "Processing usage for day " + ((DAYS_WINDOW - 1) - i) + " - found " + newUsageMap.size()
                            + " apps with new usage");

                    // Merge new usage data
                    for (Map.Entry<String, Long> entry : newUsageMap.entrySet()) {
                        String pkg = entry.getKey();

                        // Log system apps we're checking
                        if (pkg.contains("youtube") || pkg.contains("phone") || pkg.contains("dialer")
                                || pkg.contains("camera")) {
                            Log.d(TAG, "Checking important app: " + pkg + " - Skip: " + shouldSkipPackage(pkg));
                        }

                        if (shouldSkipPackage(pkg))
                            continue;

                        long newUsage = adjustUsage(entry.getValue());
                        if (newUsage <= 0)
                            continue;

                        String appName;
                        try {
                            appName = getPackageManager().getApplicationLabel(
                                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
                        } catch (Exception ex) {
                            appName = pkg;
                        }

                        // Merge with existing data
                        long totalUsage = newUsage;
                        if (mergedApps.containsKey(pkg)) {
                            // Add to existing usage (preserve historical data)
                            long existingUsage = mergedApps.get(pkg).getUsageTime();
                            totalUsage = Math.max(newUsage, existingUsage); // Use the higher value to avoid going
                                                                            // backwards
                            Log.d(TAG, "Merging " + appName + ": existing=" + formatDuration(existingUsage) + ", new="
                                    + formatDuration(newUsage) + ", final=" + formatDuration(totalUsage));
                        }

                        // Log important apps that are being included
                        if (pkg.contains("youtube") || pkg.contains("phone") || pkg.contains("dialer")
                                || pkg.contains("camera")) {
                            Log.d(TAG, "✅ INCLUDING important app: " + appName + " (" + pkg + ") - "
                                    + formatDuration(totalUsage));
                        }

                        mergedApps.put(pkg,
                                new com.example.master2.models.AppUsage(pkg, appName, totalUsage, "Other", false));
                    }

                    // Convert merged apps to list and calculate total
                    for (com.example.master2.models.AppUsage app : mergedApps.values()) {
                        dayApps.add(app);
                        dailyTotal += app.getUsageTime();
                    }

                    Collections.sort(dayApps, (a, b) -> Long.compare(b.getUsageTime(), a.getUsageTime()));
                    dailyAppLists.add(dayApps);
                    barValues.add(dailyTotal / 60000f); // minutes for chart
                    totalTexts.add(formatDuration(dailyTotal));

                    Log.d(TAG, "Day " + ((DAYS_WINDOW - 1) - i) + " processed with merged data: "
                            + dayApps.size() + " apps, total usage: " + formatDuration(dailyTotal));
                }

                Log.d(TAG, "Creating UsageSnapshot with " + barValues.size() + " days of merged historical data");
                UsageSnapshot snapshot = new UsageSnapshot(System.currentTimeMillis(), barValues, dailyAppLists,
                        totalTexts);

                // Log the data being uploaded
                Log.d(TAG, "Snapshot structure:");
                Log.d(TAG, "- timestamp: " + snapshot.timestamp);
                Log.d(TAG, "- bars: " + (snapshot.bars != null ? snapshot.bars.size() : "null"));
                Log.d(TAG, "- dailyApps: " + (snapshot.dailyApps != null ? snapshot.dailyApps.size() : "null"));
                Log.d(TAG, "- totalTexts: " + (snapshot.totalTexts != null ? snapshot.totalTexts.size() : "null"));

                // Upload to Firebase with enhanced device isolation
                DatabaseReference snapshotRef = FirebaseDatabase.getInstance()
                        .getReference("usage_snapshots")
                        .child(myDeviceId)
                        .child("latest");

                Log.d(TAG, "Uploading merged historical data to: usage_snapshots/" + myDeviceId + "/latest");
                Log.d(TAG, "🔒 Device isolation confirmed - data path: " + myDeviceId);

                // Add device metadata to snapshot for better isolation validation
                snapshot.deviceId = myDeviceId;
                snapshot.timestamp = System.currentTimeMillis();

                snapshotRef.setValue(snapshot)
                        .addOnSuccessListener(aVoid -> {
                            Log.d(TAG, "✅ Usage snapshot with accurate foreground data uploaded successfully!");
                            // Log summary of what was uploaded
                            if (snapshot.dailyApps != null && !snapshot.dailyApps.isEmpty()) {
                                List<com.example.master2.models.AppUsage> todayApps = snapshot.dailyApps
                                        .get(snapshot.dailyApps.size() - 1);
                                if (!todayApps.isEmpty()) {
                                    Log.d(TAG, "📊 Today's top apps:");
                                    for (int j = 0; j < Math.min(5, todayApps.size()); j++) {
                                        com.example.master2.models.AppUsage app = todayApps.get(j);
                                        Log.d(TAG, (j + 1) + ". " + app.getAppName() + ": "
                                                + formatDuration(app.getUsageTime()));
                                    }
                                }
                            }
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "❌ Failed to upload usage snapshot: " + e.getMessage());
                            Log.e(TAG, "Parent dashboard will not show usage data until this is fixed");
                        });
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to retrieve existing usage data: " + error.getMessage());
                // Fall back to regular upload without merge
                uploadUsageSnapshotWithoutMerge();
            }
        });
    }

    private void uploadUsageSnapshotWithoutMerge() {
        Log.d(TAG, "Uploading usage snapshot without historical merge (fallback)");

        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            Log.e(TAG, "Failed to get UsageStatsManager");
            return;
        }

        long dayMillis = 24 * 60 * 60 * 1000L;
        List<Float> barValues = new ArrayList<>();
        List<List<com.example.master2.models.AppUsage>> dailyAppLists = new ArrayList<>();
        List<String> totalTexts = new ArrayList<>();

        for (int i = 0; i < DAYS_WINDOW; i++) {
            long start = startOfDayMillis((DAYS_WINDOW - 1) - i);
            long end = start + dayMillis;
            Log.d(TAG, "Processing day " + ((DAYS_WINDOW - 1) - i) + ": " + new Date(start));

            Map<String, Long> usageMap = computeUsageFromEvents(usm, start, end);
            long dailyTotal = 0;
            List<com.example.master2.models.AppUsage> dayApps = new ArrayList<>();

            for (Map.Entry<String, Long> entry : usageMap.entrySet()) {
                String pkg = entry.getKey();
                if (shouldSkipPackage(pkg))
                    continue;

                long adjusted = adjustUsage(entry.getValue());
                if (adjusted <= 0)
                    continue;

                dailyTotal += adjusted;

                String appName;
                try {
                    appName = getPackageManager().getApplicationLabel(
                            getPackageManager().getApplicationInfo(pkg, 0)).toString();
                } catch (Exception ex) {
                    appName = pkg;
                }

                dayApps.add(new com.example.master2.models.AppUsage(pkg, appName, adjusted, "Other", false));
            }

            Collections.sort(dayApps, (a, b) -> Long.compare(b.getUsageTime(), a.getUsageTime()));
            dailyAppLists.add(dayApps);
            barValues.add(dailyTotal / 60000f);
            totalTexts.add(formatDuration(dailyTotal));
        }

        UsageSnapshot snapshot = new UsageSnapshot(System.currentTimeMillis(), barValues, dailyAppLists, totalTexts);

        DatabaseReference snapshotRef = FirebaseDatabase.getInstance()
                .getReference("usage_snapshots")
                .child(myDeviceId)
                .child("latest");

        snapshotRef.setValue(snapshot)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "Fallback usage snapshot uploaded"))
                .addOnFailureListener(e -> Log.e(TAG, "Failed to upload fallback snapshot: " + e.getMessage()));
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private long adjustUsage(long millis) {
        // Don't count usage sessions shorter than 1 second (likely system transitions)
        if (millis < 1000) {
            return 0;
        }

        // Cap maximum usage per session to 3 hours to avoid unrealistic values
        long maxSessionTime = 3 * 60 * 60 * 1000; // 3 hours
        if (millis > maxSessionTime) {
            Log.d(TAG, "⚠️ Capping unrealistic usage session from " + formatDuration(millis) + " to "
                    + formatDuration(maxSessionTime));
            millis = maxSessionTime;
        }

        // For usage over 1 hour, apply a small adjustment to account for possible
        // inaccuracies
        // This is more conservative than the previous 90% reduction
        if (millis > 3600000) { // 1 hour
            return (millis * 95) / 100; // 5% reduction for very long sessions
        }

        // For reasonable usage times (1 second to 1 hour), use the actual time
        return millis;
    }

    private long startOfDayMillis(int daysAgo) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.DAY_OF_YEAR, -daysAgo);
        return cal.getTimeInMillis();
    }

    private String formatDuration(long millis) {
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis);
        long hours = minutes / 60;
        long rem = minutes % 60;
        return hours > 0 ? hours + " hr " + rem + " min" : rem + " min";
    }

    private boolean shouldSkipPackage(String pkgName) {
        if (pkgName == null || pkgName.isEmpty())
            return true;

        // Never skip our own app for debugging purposes
        if (pkgName.equals(getPackageName()))
            return false;

        // Allow important apps that users commonly interact with (even if system apps)
        String[] allowedSystemApps = {
                "com.android.chrome", "com.google.android.googlequicksearchbox",
                "com.google.android.youtube", "com.youtube.android",
                "com.android.dialer", "com.android.phone", "com.google.android.dialer", "com.samsung.android.dialer",
                "com.android.contacts", "com.google.android.contacts",
                "com.android.camera", "com.android.camera2", "com.google.android.camera", "com.samsung.android.camera",
                "com.android.gallery3d", "com.google.android.apps.photos",
                "com.android.music", "com.google.android.music", "com.spotify.music",
                "com.whatsapp", "com.facebook.katana", "com.instagram.android", "com.twitter.android",
                "com.google.android.gm", "com.android.email", "com.samsung.android.email.provider",
                "com.android.calculator2", "com.google.android.calculator",
                "com.android.settings", // Settings is user-interactive
                "com.google.android.maps", "com.google.android.apps.maps"
        };

        for (String allowedApp : allowedSystemApps) {
            if (pkgName.equals(allowedApp)) {
                return false;
            }
        }

        String lower = pkgName.toLowerCase();

        // Skip obvious system components that users don't interact with
        String[] systemComponents = {
                "launcher", "systemui", "wallpaper", "inputmethod", "keyboard",
                "com.android.systemui", "com.miui.home", "com.samsung.android.launcher",
                "com.android.nfc", "com.android.bluetooth", "com.android.providers",
                "com.google.android.syncadapters", "com.google.android.gsf",
                "com.android.packageinstaller", "com.android.permissioncontroller"
        };

        for (String component : systemComponents) {
            if (lower.contains(component)) {
                return true;
            }
        }

        // Skip system processes but be more selective
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(pkgName, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                // For system apps, check if they're user-facing by looking at common patterns
                if (lower.contains("youtube") || lower.contains("chrome") || lower.contains("music") ||
                        lower.contains("video") || lower.contains("photo") || lower.contains("camera") ||
                        lower.contains("dialer") || lower.contains("phone") || lower.contains("contacts") ||
                        lower.contains("gallery") || lower.contains("player") || lower.contains("browser") ||
                        lower.contains("calculator") || lower.contains("calendar") || lower.contains("clock") ||
                        lower.contains("messenger") || lower.contains("email") || lower.contains("maps")) {
                    Log.d(TAG, "✅ Allowing system app with user interaction: " + pkgName);
                    return false;
                }

                // Skip other system apps
                Log.v(TAG, "⏭️ Skipping system app: " + pkgName);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error checking app flags for " + pkgName + ": " + e.getMessage());
        }

        // Skip if it's in the ignored packages list
        return IGNORED_PACKAGES.contains(pkgName);
    }

    private Map<String, Long> computeUsageFromEvents(UsageStatsManager usm, long start, long end) {
        Map<String, Long> usage = new HashMap<>();
        if (usm == null)
            return usage;

        try {
            UsageEvents events = usm.queryEvents(start, end);
            if (events == null)
                return usage;

            Map<String, Long> resumeTimes = new HashMap<>();
            UsageEvents.Event ev = new UsageEvents.Event();

            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                String pkg = ev.getPackageName();
                long timestamp = ev.getTimeStamp();
                int eventType = ev.getEventType();

                if (pkg == null || shouldSkipPackage(pkg))
                    continue;

                if (eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    // App came to foreground
                    resumeTimes.put(pkg, timestamp);
                    Log.v(TAG, "🚀 App resumed: " + pkg + " at " + new Date(timestamp));

                } else if (eventType == UsageEvents.Event.ACTIVITY_PAUSED) {
                    // App left foreground
                    Long resumeTime = resumeTimes.remove(pkg);
                    if (resumeTime != null && timestamp > resumeTime) {
                        long dur = timestamp - resumeTime;
                        // Only count realistic foreground time (minimum 1 second, max 2 hours per
                        // session)
                        if (dur >= 1000 && dur <= 7200000) {
                            usage.put(pkg, dur + usage.getOrDefault(pkg, 0L));
                            Log.v(TAG, "⏸️ App paused: " + pkg + " - " + formatDuration(dur));
                        } else if (dur < 1000) {
                            Log.v(TAG, "⚡ Skipped very short session: " + pkg + " - " + dur + "ms");
                        } else {
                            Log.v(TAG, "⏰ Skipped unrealistic session: " + pkg + " - " + formatDuration(dur));
                        }
                    }
                }
            }

            // Handle apps that are still active at the end of the period
            for (Map.Entry<String, Long> entry : resumeTimes.entrySet()) {
                String pkg = entry.getKey();
                Long resumeTime = entry.getValue();
                if (resumeTime != null && end > resumeTime) {
                    long dur = end - resumeTime;
                    // Only count realistic foreground time
                    if (dur >= 1000 && dur <= 7200000) {
                        usage.put(pkg, dur + usage.getOrDefault(pkg, 0L));
                        Log.v(TAG, "⏳ Session ended - " + pkg + ": " + formatDuration(dur));
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error computing usage from events: " + e.getMessage());
        }

        return usage;
    }

    private void showUsageAccessNotification() {
        // Create intent for usage access settings
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

        // Create a notification to prompt the user
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Permission Required")
                .setContentText("Usage access permission needed to track app usage")
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.notify(2, notification);
        }

        // Check again after some delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (hasUsageStatsPermission()) {
                // 🎯 Start Smart Usage Tracking when permission is granted
                startSmartUsageTracking();
                // Cancel the notification
                NotificationManager nm = getSystemService(NotificationManager.class);
                if (nm != null)
                    nm.cancel(2);
            }
        }, 10000); // 10 seconds
    }

    // Also add this method to manually trigger logout listener setup
    public void manualSetupLogoutListener() {
        Log.d(TAG, "🔧 Manual logout listener setup requested");
        setupLogoutListener();
    }

    private void refreshDeviceAppList() {
        Log.d(TAG, "Auto-refreshing device app list to Firebase");

        try {
            ChildConnectionManager connectionManager = new ChildConnectionManager(this);
            connectionManager.refreshDeviceAppList(myDeviceId, new ChildConnectionManager.OnConnectionListener() {
                @Override
                public void onSuccess(String parentUserId) {
                    Log.d(TAG, "Device app list refreshed successfully");
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Failed to refresh device app list: " + error);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error refreshing device app list: " + e.getMessage());
        }
    }

    /**
     * 🔧 OEM COMPATIBILITY: Acquire wake lock based on OEM aggressiveness
     * This helps keep the service alive on MIUI, Vivo, OPPO devices
     */
    private void acquireOEMWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // Release existing wake lock if any
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }

                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Master2:RemoteBlockService");

                // Aggressive OEMs need longer wake lock
                long wakeLockDuration = oemManager.isAggressiveOEM() ? 24 * 60 * 60 * 1000L : // 24 hours for aggressive
                                                                                              // OEMs
                        10 * 60 * 60 * 1000L; // 10 hours for normal OEMs

                wakeLock.acquire(wakeLockDuration);
                Log.d(TAG, "🔋 OEM Wake lock acquired for " + (wakeLockDuration / (60 * 60 * 1000)) + " hours");
                Log.d(TAG, "🔋 OEM Type: " + oemManager.getOEMType().name() +
                        " (Aggressive: " + oemManager.isAggressiveOEM() + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to acquire OEM wake lock: " + e.getMessage());
        }
    }

    /**
     * 🔧 OEM COMPATIBILITY: Release wake lock safely
     */
    private void releaseOEMWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "🔋 OEM Wake lock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error releasing wake lock: " + e.getMessage());
        }
    }
}