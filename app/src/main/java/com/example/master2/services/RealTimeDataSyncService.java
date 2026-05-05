package com.example.master2.services;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for real-time data synchronization between parent and child devices
 * Handles continuous monitoring, status updates, and bidirectional communication
 */
public class RealTimeDataSyncService extends Service {
    private static final String TAG = "RealTimeDataSync";
    private static final String PREFS_NAME = "device_sync_prefs";
    
    private DatabaseReference database;
    private String deviceId;
    private String deviceType; // "parent" or "child"
    private String parentDeviceId;
    private ScheduledExecutorService scheduler;
    private ValueEventListener parentListener;
    private ValueEventListener childListener;
    
    // Sync intervals
    private static final int HEARTBEAT_INTERVAL = 30; // seconds
    private static final int STATUS_UPDATE_INTERVAL = 10; // seconds
    private static final int USAGE_SYNC_INTERVAL = 60; // seconds
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🚀 RealTimeDataSyncService started");
        
        database = FirebaseDatabase.getInstance().getReference();
        deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        
        // Determine device type
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        deviceType = prefs.getString("device_type", "unknown");
        parentDeviceId = prefs.getString("parent_device_id", "");
        
        if (deviceType.equals("unknown")) {
            determineDeviceType();
        }
        
        scheduler = Executors.newScheduledThreadPool(3);
        startSyncServices();
    }
    
    /**
     * Determine if this device is a parent or child
     */
    private void determineDeviceType() {
        // Check if device has children connected (parent)
        database.child("parents").child(deviceId).child("connected_devices")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (dataSnapshot.exists() && dataSnapshot.getChildrenCount() > 0) {
                            deviceType = "parent";
                            Log.d(TAG, "📱 Detected as PARENT device");
                        } else {
                            // Check if device is registered as child
                            database.child("children").child(deviceId)
                                    .addListenerForSingleValueEvent(new ValueEventListener() {
                                        @Override
                                        public void onDataChange(DataSnapshot snapshot) {
                                            if (snapshot.exists()) {
                                                deviceType = "child";
                                                parentDeviceId = (String) snapshot.child("parentDeviceId").getValue();
                                                Log.d(TAG, "👶 Detected as CHILD device");
                                            } else {
                                                deviceType = "standalone";
                                                Log.d(TAG, "🔍 Device not connected to monitoring network");
                                            }
                                            
                                            saveDeviceType();
                                        }
                                        
                                        @Override
                                        public void onCancelled(DatabaseError databaseError) {
                                            Log.e(TAG, "Failed to determine device type: " + databaseError.getMessage());
                                        }
                                    });
                        }
                        
                        saveDeviceType();
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e(TAG, "Failed to check parent status: " + databaseError.getMessage());
                    }
                });
    }
    
    private void saveDeviceType() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString("device_type", deviceType)
                .putString("parent_device_id", parentDeviceId)
                .putLong("last_updated", System.currentTimeMillis())
                .apply();
    }
    
    /**
     * Start all synchronization services
     */
    private void startSyncServices() {
        startHeartbeatService();
        startStatusUpdateService();
        
        if (deviceType.equals("parent")) {
            startParentSyncServices();
        } else if (deviceType.equals("child")) {
            startChildSyncServices();
        }
    }
    
    /**
     * Send periodic heartbeat to maintain connection
     */
    private void startHeartbeatService() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                Map<String, Object> heartbeat = new HashMap<>();
                heartbeat.put("timestamp", System.currentTimeMillis());
                heartbeat.put("status", "online");
                heartbeat.put("deviceId", deviceId);
                
                String path = deviceType.equals("parent") ? "parents" : "children";
                database.child(path).child(deviceId).child("heartbeat").setValue(heartbeat);
                
                Log.d(TAG, "💗 Heartbeat sent");
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Heartbeat failed: " + e.getMessage());
            }
        }, 0, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
    }
    
    /**
     * Update device status regularly
     */
    private void startStatusUpdateService() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateDeviceStatus();
            } catch (Exception e) {
                Log.e(TAG, "❌ Status update failed: " + e.getMessage());
            }
        }, 0, STATUS_UPDATE_INTERVAL, TimeUnit.SECONDS);
    }
    
    /**
     * Parent-specific sync services
     */
    private void startParentSyncServices() {
        Log.d(TAG, "👨‍👩‍👧‍👦 Starting parent sync services");
        
        // Monitor connected children
        startChildrenMonitoring();
        
        // Sync usage data from all children
        scheduler.scheduleAtFixedRate(() -> {
            try {
                syncUsageDataFromChildren();
            } catch (Exception e) {
                Log.e(TAG, "❌ Usage sync failed: " + e.getMessage());
            }
        }, 0, USAGE_SYNC_INTERVAL, TimeUnit.SECONDS);
    }
    
    /**
     * Child-specific sync services
     */
    private void startChildSyncServices() {
        Log.d(TAG, "👶 Starting child sync services");
        
        if (parentDeviceId.isEmpty()) {
            Log.w(TAG, "⚠️ No parent device ID available");
            return;
        }
        
        // Listen for commands from parent
        startParentCommandListener();
        
        // Send usage data to parent
        scheduler.scheduleAtFixedRate(() -> {
            try {
                sendUsageDataToParent();
            } catch (Exception e) {
                Log.e(TAG, "❌ Usage data send failed: " + e.getMessage());
            }
        }, 0, USAGE_SYNC_INTERVAL, TimeUnit.SECONDS);
        
        // Send app list changes
        startAppListMonitoring();
    }
    
    /**
     * Monitor connected children (Parent side)
     */
    private void startChildrenMonitoring() {
        database.child("parents").child(deviceId).child("connected_devices")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        Log.d(TAG, "👥 Connected children updated: " + dataSnapshot.getChildrenCount());
                        
                        for (DataSnapshot childSnapshot : dataSnapshot.getChildren()) {
                            String childId = childSnapshot.getKey();
                            Map<String, Object> childData = (Map<String, Object>) childSnapshot.getValue();
                            
                            if (childData != null) {
                                String childName = (String) childData.get("userName");
                                String status = (String) childData.get("status");
                                Log.d(TAG, "📱 Child device: " + childName + " - Status: " + status);
                                
                                // Start monitoring this specific child
                                startSpecificChildMonitoring(childId);
                            }
                        }
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e(TAG, "❌ Children monitoring failed: " + databaseError.getMessage());
                    }
                });
    }
    
    /**
     * Monitor a specific child device (Parent side)
     */
    private void startSpecificChildMonitoring(String childId) {
        database.child("children").child(childId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (!dataSnapshot.exists()) return;
                        
                        Map<String, Object> childData = (Map<String, Object>) dataSnapshot.getValue();
                        if (childData != null) {
                            String status = (String) childData.get("status");
                            Long lastSeen = (Long) childData.get("lastSeen");
                            
                            Log.d(TAG, "📊 Child " + childId + " status: " + status);
                            
                            // Update parent's view of child status
                            database.child("parents").child(deviceId).child("connected_devices").child(childId)
                                    .child("lastSeen").setValue(lastSeen);
                            database.child("parents").child(deviceId).child("connected_devices").child(childId)
                                    .child("status").setValue(status);
                        }
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e(TAG, "❌ Child monitoring failed: " + databaseError.getMessage());
                    }
                });
    }
    
    /**
     * Listen for commands from parent (Child side)
     */
    private void startParentCommandListener() {
        database.child("commands").child(deviceId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        for (DataSnapshot commandSnapshot : dataSnapshot.getChildren()) {
                            processParentCommand(commandSnapshot);
                        }
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e(TAG, "❌ Parent command listener failed: " + databaseError.getMessage());
                    }
                });
    }
    
    /**
     * Process commands from parent device
     */
    private void processParentCommand(DataSnapshot commandSnapshot) {
        Map<String, Object> command = (Map<String, Object>) commandSnapshot.getValue();
        if (command == null) return;
        
        String commandType = (String) command.get("type");
        String commandId = commandSnapshot.getKey();
        
        Log.d(TAG, "📨 Received parent command: " + commandType);
        
        try {
            switch (commandType) {
                case "set_timer":
                    handleSetTimerCommand(command);
                    break;
                case "enable_focus_mode":
                    handleFocusModeCommand(command);
                    break;
                case "request_usage_data":
                    handleUsageDataRequest(command);
                    break;
                case "update_restrictions":
                    handleRestrictionsUpdate(command);
                    break;
                default:
                    Log.w(TAG, "⚠️ Unknown command type: " + commandType);
            }
            
            // Mark command as processed
            markCommandAsProcessed(commandId);
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to process command: " + e.getMessage());
            markCommandAsFailed(commandId, e.getMessage());
        }
    }
    
    private void handleSetTimerCommand(Map<String, Object> command) {
        // Implementation for setting timer restrictions
        Log.d(TAG, "⏲️ Processing timer command");
        // TODO: Integrate with timer management system
    }
    
    private void handleFocusModeCommand(Map<String, Object> command) {
        // Implementation for enabling/disabling focus mode
        Log.d(TAG, "🎯 Processing focus mode command");
        // TODO: Integrate with focus mode system
    }
    
    private void handleUsageDataRequest(Map<String, Object> command) {
        // Send current usage data immediately
        Log.d(TAG, "📊 Processing usage data request");
        sendUsageDataToParent();
    }
    
    private void handleRestrictionsUpdate(Map<String, Object> command) {
        // Update app restrictions
        Log.d(TAG, "🚫 Processing restrictions update");
        // TODO: Integrate with app restriction system
    }
    
    private void markCommandAsProcessed(String commandId) {
        database.child("commands").child(deviceId).child(commandId).child("status")
                .setValue("completed");
        database.child("commands").child(deviceId).child(commandId).child("completedAt")
                .setValue(System.currentTimeMillis());
    }
    
    private void markCommandAsFailed(String commandId, String error) {
        database.child("commands").child(deviceId).child(commandId).child("status")
                .setValue("failed");
        database.child("commands").child(deviceId).child(commandId).child("error")
                .setValue(error);
        database.child("commands").child(deviceId).child(commandId).child("failedAt")
                .setValue(System.currentTimeMillis());
    }
    
    /**
     * Update device status information
     */
    private void updateDeviceStatus() {
        Map<String, Object> statusUpdate = new HashMap<>();
        statusUpdate.put("lastSeen", System.currentTimeMillis());
        statusUpdate.put("status", "online");
        statusUpdate.put("batteryLevel", getBatteryLevel());
        statusUpdate.put("appVersion", getAppVersion());
        statusUpdate.put("deviceInfo", getDeviceInfo());
        
        String path = deviceType.equals("parent") ? "parents" : "children";
        database.child(path).child(deviceId).updateChildren(statusUpdate);
        
        // Also update in parent's view if this is a child device
        if (deviceType.equals("child") && !parentDeviceId.isEmpty()) {
            database.child("parents").child(parentDeviceId).child("connected_devices").child(deviceId)
                    .updateChildren(statusUpdate);
        }
    }
    
    /**
     * Sync usage data from all children (Parent side)
     */
    private void syncUsageDataFromChildren() {
        database.child("parents").child(deviceId).child("connected_devices")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        for (DataSnapshot childSnapshot : dataSnapshot.getChildren()) {
                            String childId = childSnapshot.getKey();
                            requestUsageDataFromChild(childId);
                        }
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e(TAG, "❌ Failed to get children list: " + databaseError.getMessage());
                    }
                });
    }
    
    private void requestUsageDataFromChild(String childId) {
        Map<String, Object> command = new HashMap<>();
        command.put("type", "request_usage_data");
        command.put("from", deviceId);
        command.put("timestamp", System.currentTimeMillis());
        command.put("status", "pending");
        
        database.child("commands").child(childId).push().setValue(command);
    }
    
    /**
     * Send usage data to parent (Child side)
     */
    private void sendUsageDataToParent() {
        if (parentDeviceId.isEmpty()) return;
        
        Map<String, Object> usageData = new HashMap<>();
        usageData.put("timestamp", System.currentTimeMillis());
        usageData.put("dailyUsage", getDailyUsageData());
        usageData.put("appUsage", getAppUsageData());
        usageData.put("screenTime", getScreenTimeData());
        usageData.put("activeApps", getCurrentlyActiveApps());
        
        database.child("usage_data").child(parentDeviceId).child(deviceId).setValue(usageData);
        
        Log.d(TAG, "📤 Usage data sent to parent");
    }
    
    /**
     * Monitor app installations/changes (Child side)
     */
    private void startAppListMonitoring() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!parentDeviceId.isEmpty()) {
                    sendAppListToParent();
                }
            } catch (Exception e) {
                Log.e(TAG, "❌ App list monitoring failed: " + e.getMessage());
            }
        }, 0, 300, TimeUnit.SECONDS); // Every 5 minutes
    }
    
    private void sendAppListToParent() {
        // TODO: Implement app list collection
        Map<String, Object> appList = new HashMap<>();
        appList.put("timestamp", System.currentTimeMillis());
        appList.put("installedApps", getInstalledAppsData());
        
        database.child("app_data").child(parentDeviceId).child(deviceId).setValue(appList);
    }
    
    // Helper methods (implement as needed)
    private int getBatteryLevel() {
        // TODO: Implement battery level detection
        return 100;
    }
    
    private String getAppVersion() {
        // TODO: Get app version
        return "1.0.0";
    }
    
    private Map<String, Object> getDeviceInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("model", android.os.Build.MODEL);
        info.put("manufacturer", android.os.Build.MANUFACTURER);
        info.put("androidVersion", android.os.Build.VERSION.RELEASE);
        return info;
    }
    
    private Map<String, Object> getDailyUsageData() {
        // TODO: Implement daily usage data collection
        return new HashMap<>();
    }
    
    private Map<String, Object> getAppUsageData() {
        // TODO: Implement app usage data collection
        return new HashMap<>();
    }
    
    private Map<String, Object> getScreenTimeData() {
        // TODO: Implement screen time data collection
        return new HashMap<>();
    }
    
    private Map<String, Object> getCurrentlyActiveApps() {
        // TODO: Implement active apps detection
        return new HashMap<>();
    }
    
    private Map<String, Object> getInstalledAppsData() {
        // TODO: Implement installed apps list
        return new HashMap<>();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🛑 RealTimeDataSyncService stopped");
        
        if (scheduler != null) {
            scheduler.shutdown();
        }
        
        // Remove listeners
        if (parentListener != null) {
            // Remove parent listener
        }
        if (childListener != null) {
            // Remove child listener
        }
        
        // Update status to offline
        Map<String, Object> statusUpdate = new HashMap<>();
        statusUpdate.put("status", "offline");
        statusUpdate.put("lastSeen", System.currentTimeMillis());
        
        String path = deviceType.equals("parent") ? "parents" : "children";
        database.child(path).child(deviceId).updateChildren(statusUpdate);
    }
}