package com.example.master2;

import android.content.Context;
import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages uninstall detection for child devices
 * Uses heartbeat monitoring to detect when child app is uninstalled
 */
public class UninstallDetectionManager {
    private static final String TAG = "UninstallDetection";

    // Time thresholds (in milliseconds)
    public static final long OFFLINE_THRESHOLD = 5 * 60 * 1000; // 5 minutes - device offline
    public static final long SUSPECTED_UNINSTALL_THRESHOLD = 15 * 60 * 1000; // 15 minutes - suspected uninstall
    public static final long CONFIRMED_UNINSTALL_THRESHOLD = 60 * 60 * 1000; // 1 hour - likely uninstalled

    // Device status constants
    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_OFFLINE = "offline";
    public static final String STATUS_SUSPECTED_UNINSTALL = "suspected_uninstall";
    public static final String STATUS_LIKELY_UNINSTALLED = "likely_uninstalled";

    private Context context;
    private DatabaseReference databaseRef;
    private Map<String, ValueEventListener> deviceListeners = new HashMap<>();

    public interface DeviceStatusCallback {
        void onStatusChanged(String deviceId, String status, long lastHeartbeat);
    }

    public UninstallDetectionManager(Context context) {
        this.context = context;
        this.databaseRef = FirebaseDatabase.getInstance().getReference();
    }

    /**
     * Send heartbeat from child device to Firebase
     * Called periodically to indicate app is still installed and running
     */
    public void sendHeartbeat(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG, "Cannot send heartbeat - no device ID");
            return;
        }

        try {
            DatabaseReference heartbeatRef = databaseRef
                    .child("child_session_usage")
                    .child(deviceId)
                    .child("heartbeat");

            Map<String, Object> heartbeatData = new HashMap<>();
            heartbeatData.put("lastHeartbeat", System.currentTimeMillis());
            heartbeatData.put("deviceAlive", true);
            heartbeatData.put("status", STATUS_ONLINE);
            heartbeatData.put("appVersion", getAppVersion());

            heartbeatRef.updateChildren(heartbeatData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "💓 Heartbeat sent successfully for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to send heartbeat: " + e.getMessage());
                    });

        } catch (Exception e) {
            Log.e(TAG, "Error sending heartbeat: " + e.getMessage());
        }
    }

    /**
     * Start monitoring a child device for uninstall detection
     * Called from parent side to monitor child devices
     */
    public void startMonitoringDevice(String deviceId, DeviceStatusCallback callback) {
        if (deviceId == null || deviceId.isEmpty()) {
            Log.w(TAG, "Cannot monitor - no device ID");
            return;
        }

        // Remove existing listener if any
        stopMonitoringDevice(deviceId);

        DatabaseReference heartbeatRef = databaseRef
                .child("child_session_usage")
                .child(deviceId)
                .child("heartbeat");

        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Long lastHeartbeat = snapshot.child("lastHeartbeat").getValue(Long.class);

                if (lastHeartbeat == null) {
                    // No heartbeat ever recorded - device never connected properly
                    callback.onStatusChanged(deviceId, STATUS_OFFLINE, 0);
                    return;
                }

                long currentTime = System.currentTimeMillis();
                long timeSinceHeartbeat = currentTime - lastHeartbeat;

                String status = calculateStatus(timeSinceHeartbeat);
                callback.onStatusChanged(deviceId, status, lastHeartbeat);

                Log.d(TAG, "📊 Device " + deviceId + " status: " + status +
                        " (last heartbeat " + (timeSinceHeartbeat / 1000) + "s ago)");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Error monitoring device " + deviceId + ": " + error.getMessage());
            }
        };

        heartbeatRef.addValueEventListener(listener);
        deviceListeners.put(deviceId, listener);

        Log.d(TAG, "👀 Started monitoring device: " + deviceId);
    }

    /**
     * Stop monitoring a child device
     */
    public void stopMonitoringDevice(String deviceId) {
        if (deviceId == null)
            return;

        ValueEventListener listener = deviceListeners.remove(deviceId);
        if (listener != null) {
            databaseRef.child("child_session_usage")
                    .child(deviceId)
                    .child("heartbeat")
                    .removeEventListener(listener);
            Log.d(TAG, "🛑 Stopped monitoring device: " + deviceId);
        }
    }

    /**
     * Stop all device monitoring
     */
    public void stopAllMonitoring() {
        for (Map.Entry<String, ValueEventListener> entry : deviceListeners.entrySet()) {
            databaseRef.child("child_session_usage")
                    .child(entry.getKey())
                    .child("heartbeat")
                    .removeEventListener(entry.getValue());
        }
        deviceListeners.clear();
        Log.d(TAG, "🛑 Stopped all device monitoring");
    }

    /**
     * Calculate device status based on time since last heartbeat
     */
    public static String calculateStatus(long timeSinceHeartbeat) {
        if (timeSinceHeartbeat < OFFLINE_THRESHOLD) {
            return STATUS_ONLINE;
        } else if (timeSinceHeartbeat < SUSPECTED_UNINSTALL_THRESHOLD) {
            return STATUS_OFFLINE;
        } else if (timeSinceHeartbeat < CONFIRMED_UNINSTALL_THRESHOLD) {
            return STATUS_SUSPECTED_UNINSTALL;
        } else {
            return STATUS_LIKELY_UNINSTALLED;
        }
    }

    /**
     * Check if a device status indicates app was uninstalled
     */
    public static boolean isUninstalled(String status) {
        return STATUS_SUSPECTED_UNINSTALL.equals(status) ||
                STATUS_LIKELY_UNINSTALLED.equals(status);
    }

    /**
     * Get human-readable status message
     */
    public static String getStatusMessage(String status) {
        switch (status) {
            case STATUS_ONLINE:
                return "Online";
            case STATUS_OFFLINE:
                return "Device offline";
            case STATUS_SUSPECTED_UNINSTALL:
                return "App may have been uninstalled";
            case STATUS_LIKELY_UNINSTALLED:
                return "App has been uninstalled from this device.\nPlease reinstall on the child's device and reconnect.";
            default:
                return "Unknown status";
        }
    }

    /**
     * Get time since last heartbeat as human-readable string
     */
    public static String getLastSeenText(long lastHeartbeat) {
        if (lastHeartbeat == 0) {
            return "Never connected";
        }

        long timeSince = System.currentTimeMillis() - lastHeartbeat;

        if (timeSince < 60 * 1000) {
            return "Just now";
        } else if (timeSince < 60 * 60 * 1000) {
            long minutes = timeSince / (60 * 1000);
            return minutes + " minute" + (minutes > 1 ? "s" : "") + " ago";
        } else if (timeSince < 24 * 60 * 60 * 1000) {
            long hours = timeSince / (60 * 60 * 1000);
            return hours + " hour" + (hours > 1 ? "s" : "") + " ago";
        } else {
            long days = timeSince / (24 * 60 * 60 * 1000);
            return days + " day" + (days > 1 ? "s" : "") + " ago";
        }
    }

    private String getAppVersion() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }
}
