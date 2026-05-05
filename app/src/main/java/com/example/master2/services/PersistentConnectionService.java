package com.example.master2.services;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.util.Log;
import com.example.master2.ChildAppUtils;
import com.example.master2.SessionManager;
import com.example.master2.DeviceStatusManager;
import com.example.master2.UninstallDetectionManager;
import com.example.master2.MainActivity;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import androidx.annotation.NonNull;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Background service to maintain persistent connection to parent device
 * This ensures the child device stays connected even when app is closed
 * Also monitors for logout commands from parent
 */
public class PersistentConnectionService extends Service {
    private static final String TAG = "PersistentConnection";
    private static final long CONNECTION_CHECK_INTERVAL = 30000; // 30 seconds

    private SessionManager sessionManager;
    private DeviceStatusManager deviceStatusManager;
    private Timer connectionTimer;
    private boolean serviceRunning = false;

    // Logout command monitoring
    private DatabaseReference logoutCommandRef;
    private ValueEventListener logoutListener;
    private boolean isProcessingLogout = false; // Prevent duplicate logout processing

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "PersistentConnectionService created");

        sessionManager = new SessionManager(this);
        serviceRunning = true;

        // Start connection monitoring
        startConnectionMonitoring();

        // 🚨 CRITICAL: Start logout command monitoring
        startLogoutCommandMonitoring();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "PersistentConnectionService started");

        // Ensure connection monitoring is active
        if (!serviceRunning) {
            serviceRunning = true;
            startConnectionMonitoring();
        }

        // Return START_STICKY to restart service if killed by system
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // This is not a bound service
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "PersistentConnectionService destroyed");

        serviceRunning = false;

        // Clean up logout listener
        if (logoutCommandRef != null && logoutListener != null) {
            logoutCommandRef.removeEventListener(logoutListener);
            logoutCommandRef = null;
            logoutListener = null;
            Log.d(TAG, "Logout command listener cleaned up");
        }

        // Clean up timer
        if (connectionTimer != null) {
            connectionTimer.cancel();
            connectionTimer = null;
        }

        // Clean up device status manager
        if (deviceStatusManager != null) {
            // Don't stop tracking - keep connection alive
            deviceStatusManager = null;
        }
    }

    /**
     * Start monitoring connection to parent device
     */
    private void startConnectionMonitoring() {
        Log.d(TAG, "Starting connection monitoring");

        // Check if we have a valid child session
        if (!sessionManager.isLoggedIn() || !"child".equals(sessionManager.getUserType())) {
            Log.d(TAG, "No valid child session - stopping service");
            stopSelf();
            return;
        }

        // Set up periodic connection checks
        connectionTimer = new Timer();
        connectionTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                checkAndMaintainConnection();
            }
        }, 0, CONNECTION_CHECK_INTERVAL);
    }

    /**
     * Check connection status and restore if needed
     * Also sends heartbeat for uninstall detection
     */
    private void checkAndMaintainConnection() {
        try {
            if (!serviceRunning)
                return;

            String parentName = sessionManager.getParentName();
            String childDeviceId = sessionManager.getChildDeviceId();
            String parentUserId = sessionManager.getParentUserId();

            if (parentName == null || childDeviceId == null) {
                Log.w(TAG, "Missing connection data - cannot maintain connection");
                return;
            }

            // 💓 CRITICAL: Send heartbeat for uninstall detection
            sendHeartbeatToFirebase(childDeviceId);

            // Initialize device status manager if needed
            if (deviceStatusManager == null) {
                Log.d(TAG, "Re-initializing device status manager in background");
                deviceStatusManager = new DeviceStatusManager(this);

                if (parentUserId != null) {
                    String deviceName = ChildAppUtils.getChildDeviceName();
                    deviceStatusManager.startAsChildDevice(parentUserId, deviceName);
                    Log.d(TAG, "✅ Background connection to parent maintained");
                } else {
                    Log.w(TAG, "⚠️ No parent user ID - cannot start child device tracking");
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error maintaining connection: " + e.getMessage());
        }
    }

    /**
     * 💓 Send heartbeat to Firebase for uninstall detection
     * This is the critical signal that parent monitors
     */
    private void sendHeartbeatToFirebase(String deviceId) {
        try {
            UninstallDetectionManager detectionManager = new UninstallDetectionManager(this);
            detectionManager.sendHeartbeat(deviceId);
            Log.d(TAG, "💓 Heartbeat sent for uninstall detection");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send heartbeat: " + e.getMessage());
        }
    }

    /**
     * 🚨 CRITICAL: Monitor for logout commands from parent
     * This ensures child device logs out immediately when parent removes it
     */
    private void startLogoutCommandMonitoring() {
        String childDeviceId = sessionManager.getChildDeviceId();
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.w(TAG, "No child device ID - cannot monitor logout commands");
            return;
        }

        logoutCommandRef = FirebaseDatabase.getInstance()
                .getReference("logout_commands")
                .child(childDeviceId);

        logoutListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean trigger = snapshot.child("trigger").getValue(Boolean.class);
                if (trigger != null && trigger) {
                    // ⚠️ STABILITY FIX: Prevent duplicate logout processing
                    if (isProcessingLogout) {
                        Log.d(TAG, "⏳ Logout already in progress - ignoring duplicate trigger");
                        return;
                    }
                    isProcessingLogout = true;

                    Log.d(TAG, "🚨 LOGOUT COMMAND RECEIVED FROM PARENT - Initiating immediate logout");

                    // Remove the command first to prevent loops
                    logoutCommandRef.removeValue();

                    // Perform logout
                    performBackgroundLogout();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Logout command listener cancelled: " + error.getMessage());
            }
        };

        logoutCommandRef.addValueEventListener(logoutListener);
        Log.d(TAG, "✅ Logout command monitoring started for device: " + childDeviceId);
    }

    /**
     * Perform logout from background service
     */
    private void performBackgroundLogout() {
        try {
            Log.d(TAG, "🔄 Performing background logout");

            // Clear session data
            sessionManager.logoutUser();

            // Clear SharedPreferences
            getSharedPreferences("blocked_apps", MODE_PRIVATE).edit().clear().apply();
            getSharedPreferences("todo_prefs", MODE_PRIVATE).edit().clear().apply();
            getSharedPreferences("device_info", MODE_PRIVATE).edit().clear().apply();

            // ⚠️ FIX: Also clear device connection state locally AND explicitly set
            // disconnected
            SharedPreferences connectionPrefs = getSharedPreferences("device_connection", MODE_PRIVATE);
            connectionPrefs.edit()
                    .clear()
                    .putBoolean("is_connected", false) // Explicitly set false to override default true
                    .putBoolean("connection_valid", false)
                    .apply();

            // ⚠️ FIX: Ensure Firebase device_connections entry is removed
            String deviceId = sessionManager.getChildDeviceId();
            if (deviceId != null) {
                FirebaseDatabase.getInstance().getReference("device_connections")
                        .child(deviceId)
                        .removeValue()
                        .addOnFailureListener(e -> Log.e(TAG, "Failed to remove device_connection: " + e.getMessage()));
            }

            // Stop this service
            serviceRunning = false;
            stopSelf();

            // Launch MainActivity to show logout screen with FORCE flags
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra("logout_message", "You have been disconnected by the parent device");
            intent.putExtra("force_login_screen", true); // Force login screen
            intent.putExtra("device_was_removed", true); // Signal removal
            startActivity(intent);

            Log.d(TAG, "✅ Background logout completed - redirected to MainActivity");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error during background logout: " + e.getMessage());
        }
    }

    /**
     * Static method to start the persistent connection service
     */
    public static void startService(android.content.Context context) {
        try {
            Intent serviceIntent = new Intent(context, PersistentConnectionService.class);
            context.startService(serviceIntent);
            Log.d(TAG, "PersistentConnectionService start requested");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start PersistentConnectionService: " + e.getMessage());
        }
    }

    /**
     * Static method to stop the persistent connection service
     */
    public static void stopService(android.content.Context context) {
        try {
            Intent serviceIntent = new Intent(context, PersistentConnectionService.class);
            context.stopService(serviceIntent);
            Log.d(TAG, "PersistentConnectionService stop requested");
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop PersistentConnectionService: " + e.getMessage());
        }
    }
}
