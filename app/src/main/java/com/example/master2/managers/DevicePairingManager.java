package com.example.master2.managers;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import com.example.master2.ChildAppUtils;
import com.example.master2.ParentUtils;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages secure device pairing between parent and child devices
 * Handles QR session validation, device authentication, and connection establishment
 */
public class DevicePairingManager {
    private static final String TAG = "DevicePairingManager";
    
    private Context context;
    private DatabaseReference database;
    
    public DevicePairingManager(Context context) {
        this.context = context;
        this.database = FirebaseDatabase.getInstance().getReference();
    }
    
    /**
     * Interface for pairing callbacks
     */
    public interface PairingCallback {
        void onPairingSuccess(String parentDeviceId, String parentDeviceName);
        void onPairingFailed(String error);
        void onPairingProgress(String status);
    }
    
    /**
     * Interface for connection monitoring
     */
    public interface ConnectionMonitor {
        void onDeviceConnected(DeviceInfo deviceInfo);
        void onDeviceDisconnected(String deviceId);
        void onConnectionError(String error);
    }
    
    /**
     * Device information model
     */
    public static class DeviceInfo {
        public String deviceId;
        public String deviceName;
        public String userName;
        public String deviceType; // "parent" or "child"
        public long connectedAt;
        public String status;
        public String connectionMethod;
        
        public DeviceInfo(String deviceId, String deviceName, String userName, String deviceType) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.userName = userName;
            this.deviceType = deviceType;
            this.connectedAt = System.currentTimeMillis();
            this.status = "online";
            this.connectionMethod = "qr_scan";
        }
    }
    
    /**
     * Create a new QR pairing session (Parent side)
     */
    public String createPairingSession(String baseQRKey, int maxConnections) {
        String sessionId = UUID.randomUUID().toString();
        String deviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String deviceName = ParentUtils.getParentDeviceName();
        
        Log.d(TAG, "🔄 Creating pairing session: " + sessionId);
        
        Map<String, Object> sessionData = new HashMap<>();
        sessionData.put("sessionId", sessionId);
        sessionData.put("parentDeviceId", deviceId);
        sessionData.put("parentDeviceName", deviceName);
        sessionData.put("baseQRKey", baseQRKey);
        sessionData.put("createdAt", System.currentTimeMillis());
        sessionData.put("expiresAt", System.currentTimeMillis() + 120000); // 2 minutes
        sessionData.put("isActive", true);
        sessionData.put("maxConnections", maxConnections);
        sessionData.put("currentConnections", 0);
        
        database.child("qr_sessions").child(sessionId).setValue(sessionData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Pairing session created successfully");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to create pairing session: " + e.getMessage());
                });
                
        return sessionId;
    }
    
    /**
     * Join a pairing session (Child side)
     */
    public void joinPairingSession(String sessionId, String childUserName, PairingCallback callback) {
        String childDeviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        String childDeviceName = ChildAppUtils.getChildDeviceName();
        
        Log.d(TAG, "🔗 Attempting to join pairing session: " + sessionId);
        callback.onPairingProgress("Verifying QR session...");
        
        // First, verify the session is valid
        database.child("qr_sessions").child(sessionId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (!dataSnapshot.exists()) {
                            callback.onPairingFailed("QR session not found. Please scan a fresh QR code.");
                            return;
                        }
                        
                        Map<String, Object> sessionData = (Map<String, Object>) dataSnapshot.getValue();
                        if (sessionData == null) {
                            callback.onPairingFailed("Invalid session data.");
                            return;
                        }
                        
                        // Validate session
                        Boolean isActive = (Boolean) sessionData.get("isActive");
                        Long expiresAt = (Long) sessionData.get("expiresAt");
                        Long maxConnections = (Long) sessionData.get("maxConnections");
                        Long currentConnections = (Long) sessionData.get("currentConnections");
                        
                        if (!Boolean.TRUE.equals(isActive)) {
                            callback.onPairingFailed("QR session is no longer active.");
                            return;
                        }
                        
                        if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
                            callback.onPairingFailed("QR session has expired. Please scan a fresh QR code.");
                            return;
                        }
                        
                        if (maxConnections != null && currentConnections != null && currentConnections >= maxConnections) {
                            callback.onPairingFailed("Maximum connections reached for this QR session.");
                            return;
                        }
                        
                        // Session is valid, proceed with pairing
                        String parentDeviceId = (String) sessionData.get("parentDeviceId");
                        String parentDeviceName = (String) sessionData.get("parentDeviceName");
                        
                        callback.onPairingProgress("Establishing secure connection...");
                        performSecurePairing(sessionId, parentDeviceId, parentDeviceName, childDeviceId, childDeviceName, childUserName, callback);
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        callback.onPairingFailed("Failed to verify QR session: " + databaseError.getMessage());
                    }
                });
    }
    
    /**
     * Perform the actual secure pairing process
     */
    private void performSecurePairing(String sessionId, String parentDeviceId, String parentDeviceName, 
                                     String childDeviceId, String childDeviceName, String childUserName, 
                                     PairingCallback callback) {
        
        callback.onPairingProgress("Creating secure connection...");
        
        // Create connection data
        Map<String, Object> connectionData = new HashMap<>();
        connectionData.put("childDeviceId", childDeviceId);
        connectionData.put("childDeviceName", childDeviceName);
        connectionData.put("childUserName", childUserName);
        connectionData.put("connectedAt", System.currentTimeMillis());
        connectionData.put("status", "connected");
        connectionData.put("pairingMethod", "qr_scan");
        connectionData.put("securityLevel", "high");
        
        // Add to QR session connections
        database.child("qr_sessions").child(sessionId).child("connections").child(childDeviceId)
                .setValue(connectionData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Connection added to QR session");
                    
                    // Update connection count
                    database.child("qr_sessions").child(sessionId).child("currentConnections")
                            .setValue(com.google.firebase.database.ServerValue.increment(1));
                    
                    // Add to parent's device list
                    addToParentDeviceList(parentDeviceId, childDeviceId, childDeviceName, childUserName);
                    
                    // Add to child's parent reference
                    addParentReferenceToChild(childDeviceId, parentDeviceId, parentDeviceName, childUserName);
                    
                    // Create monitoring relationship
                    establishMonitoringRelationship(parentDeviceId, childDeviceId);
                    
                    callback.onPairingSuccess(parentDeviceId, parentDeviceName);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to establish connection: " + e.getMessage());
                    callback.onPairingFailed("Failed to establish connection: " + e.getMessage());
                });
    }
    
    /**
     * Add child device to parent's connected devices list
     */
    private void addToParentDeviceList(String parentDeviceId, String childDeviceId, String childDeviceName, String childUserName) {
        Map<String, Object> deviceInfo = new HashMap<>();
        deviceInfo.put("deviceName", childDeviceName);
        deviceInfo.put("userName", childUserName);
        deviceInfo.put("deviceType", "child");
        deviceInfo.put("connectedAt", System.currentTimeMillis());
        deviceInfo.put("status", "online");
        deviceInfo.put("lastSeen", System.currentTimeMillis());
        deviceInfo.put("connectionMethod", "qr_scan");
        deviceInfo.put("permissions", createDefaultPermissions());
        
        database.child("parents").child(parentDeviceId).child("connected_devices").child(childDeviceId)
                .setValue(deviceInfo)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Child device added to parent's device list");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to add child to parent list: " + e.getMessage());
                });
    }
    
    /**
     * Add parent reference to child device
     */
    private void addParentReferenceToChild(String childDeviceId, String parentDeviceId, String parentDeviceName, String childUserName) {
        Map<String, Object> childData = new HashMap<>();
        childData.put("deviceId", childDeviceId);
        childData.put("deviceName", ChildAppUtils.getChildDeviceName());
        childData.put("userName", childUserName);
        childData.put("parentDeviceId", parentDeviceId);
        childData.put("parentDeviceName", parentDeviceName);
        childData.put("connectedAt", System.currentTimeMillis());
        childData.put("status", "online");
        childData.put("isMonitored", true);
        childData.put("monitoringSettings", createDefaultMonitoringSettings());
        
        database.child("children").child(childDeviceId)
                .setValue(childData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Parent reference added to child device");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to add parent reference to child: " + e.getMessage());
                });
    }
    
    /**
     * Establish monitoring relationship for real-time data sync
     */
    private void establishMonitoringRelationship(String parentDeviceId, String childDeviceId) {
        // Create monitoring channels
        Map<String, Object> monitoringData = new HashMap<>();
        monitoringData.put("parentId", parentDeviceId);
        monitoringData.put("childId", childDeviceId);
        monitoringData.put("establishedAt", System.currentTimeMillis());
        monitoringData.put("status", "active");
        monitoringData.put("channels", createMonitoringChannels());
        
        database.child("monitoring_relationships").child(parentDeviceId).child(childDeviceId)
                .setValue(monitoringData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Monitoring relationship established");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to establish monitoring: " + e.getMessage());
                });
    }
    
    /**
     * Create default permissions for child device
     */
    private Map<String, Object> createDefaultPermissions() {
        Map<String, Object> permissions = new HashMap<>();
        permissions.put("can_set_timers", true);
        permissions.put("can_view_usage", true);
        permissions.put("can_enable_focus_mode", true);
        permissions.put("can_track_location", true);
        permissions.put("can_monitor_apps", true);
        permissions.put("can_receive_notifications", true);
        return permissions;
    }
    
    /**
     * Create default monitoring settings
     */
    private Map<String, Object> createDefaultMonitoringSettings() {
        Map<String, Object> settings = new HashMap<>();
        settings.put("app_monitoring", true);
        settings.put("usage_tracking", true);
        settings.put("location_tracking", false); // Disabled by default for privacy
        settings.put("notification_sync", true);
        settings.put("focus_mode_enabled", false);
        settings.put("timer_restrictions", true);
        return settings;
    }
    
    /**
     * Create monitoring channels for real-time communication
     */
    private Map<String, Object> createMonitoringChannels() {
        Map<String, Object> channels = new HashMap<>();
        channels.put("app_usage", true);
        channels.put("screen_time", true);
        channels.put("location_updates", false);
        channels.put("app_installs", true);
        channels.put("focus_mode_status", true);
        channels.put("device_status", true);
        return channels;
    }
    
    /**
     * Monitor device connections in real-time
     */
    public void startConnectionMonitoring(String parentDeviceId, ConnectionMonitor monitor) {
        database.child("parents").child(parentDeviceId).child("connected_devices")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                            Map<String, Object> deviceData = (Map<String, Object>) deviceSnapshot.getValue();
                            if (deviceData != null) {
                                DeviceInfo device = new DeviceInfo(
                                    deviceSnapshot.getKey(),
                                    (String) deviceData.get("deviceName"),
                                    (String) deviceData.get("userName"),
                                    (String) deviceData.get("deviceType")
                                );
                                device.status = (String) deviceData.get("status");
                                device.connectionMethod = (String) deviceData.get("connectionMethod");
                                
                                monitor.onDeviceConnected(device);
                            }
                        }
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        monitor.onConnectionError(databaseError.getMessage());
                    }
                });
    }
    
    /**
     * Disconnect a device pair
     */
    public void disconnectDevice(String parentDeviceId, String childDeviceId, DisconnectionCallback callback) {
        // Remove from parent's device list
        database.child("parents").child(parentDeviceId).child("connected_devices").child(childDeviceId)
                .removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Device removed from parent's list");
                    
                    // Remove parent reference from child
                    database.child("children").child(childDeviceId).removeValue();
                    
                    // Remove monitoring relationship
                    database.child("monitoring_relationships").child(parentDeviceId).child(childDeviceId).removeValue();
                    
                    callback.onDisconnectionSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to disconnect device: " + e.getMessage());
                    callback.onDisconnectionFailed(e.getMessage());
                });
    }
    
    /**
     * Interface for disconnection callbacks
     */
    public interface DisconnectionCallback {
        void onDisconnectionSuccess();
        void onDisconnectionFailed(String error);
    }
}