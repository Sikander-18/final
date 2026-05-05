package com.example.master2;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class DeviceStatusManager {
    private static final String TAG = "DeviceStatusManager";
    private Context context;
    private DatabaseReference deviceStatusRef;
    private DatabaseReference connectedRef;
    private String myDeviceId;
    private ValueEventListener connectionListener;
    private boolean isOnline = false;
    private boolean isAppActive = false;
    private boolean isInternetConnected = true; 
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Thread internetCheckThread;
    private Thread heartbeatThread;
    private volatile boolean shouldKeepRunning = true;
    
    // Debouncing mechanism to prevent flickering
    private boolean lastReportedOnlineStatus = false;
    private long lastStatusChangeTime = 0;
    private static final long STABILITY_DELAY = 3000; // 3 seconds stability before reporting change
    
    // Track listeners to prevent duplicates
    private final Map<String, ValueEventListener> activeListeners = new HashMap<>();

    public interface OnDeviceStatusChangeListener {
        void onDeviceStatusChanged(String deviceId, boolean isOnline, long lastSeen);
    }

    public interface OnInternetStatusChangeListener {
        void onInternetStatusChanged(boolean isConnected);
    }

    public DeviceStatusManager(Context context) {
        this.context = context;
        this.myDeviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        this.deviceStatusRef = database.getReference("device_status");
        this.connectedRef = database.getReference(".info/connected");
        
        setupConnectionListener();
        setupNetworkCallback();
    }

    private void setupConnectionListener() {
        connectionListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                Boolean connected = snapshot.getValue(Boolean.class);
                if (connected != null) {
                    isOnline = connected;
                    Log.d(TAG, "Firebase connection status changed: " + connected);
                    updateMyDeviceStatus();
                } else {
                    Log.w(TAG, "Firebase connection status is null, assuming offline");
                    isOnline = false;
                    updateMyDeviceStatus();
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Connection listener cancelled: " + error.getMessage());
            }
        };
        
        connectedRef.addValueEventListener(connectionListener);
    }

    private void setupNetworkCallback() {
        // Initial internet status check
        checkInternetConnectivity();
        
        // Start continuous internet monitoring
        startPeriodicInternetCheck();
        
        Log.d(TAG, "Network monitoring started with real connectivity checking");
    }

    private void startPeriodicInternetCheck() {
        // Stop existing thread if any
        if (internetCheckThread != null && internetCheckThread.isAlive()) {
            internetCheckThread.interrupt();
        }
        
        internetCheckThread = new Thread(() -> {
            while (shouldKeepRunning) {
                try {
                    // SAFETY CHECK: Exit if thread should stop
                    if (!shouldKeepRunning || Thread.currentThread().isInterrupted()) {
                        Log.d(TAG, "Internet check thread stopping");
                        break;
                    }
                    
                    boolean previousStatus = isInternetConnected;
                    checkInternetConnectivity();
                    
                    // Always update status to ensure it's current
                    if (shouldKeepRunning) {
                        updateMyDeviceStatus();
                    }
                    
                    if (previousStatus != isInternetConnected) {
                        Log.d(TAG, "Internet connectivity changed: " + isInternetConnected);
                    }
                    
                    Thread.sleep(15000); // Check every 15 seconds to reduce Firebase load
                } catch (InterruptedException e) {
                    Log.d(TAG, "Internet check thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in internet check: " + e.getMessage());
                    // Don't break on generic exceptions, just continue
                    try {
                        Thread.sleep(10000); // Wait longer on error
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            Log.d(TAG, "Internet check thread finished");
        });
        
        internetCheckThread.setDaemon(true);
        internetCheckThread.start();
    }

    private void checkInternetConnectivity() {
        try {
            // Method 1: Check if we have an active network
            Network activeNetwork = connectivityManager.getActiveNetwork();
            if (activeNetwork == null) {
                isInternetConnected = false;
                Log.d(TAG, "No active network - Internet: DISCONNECTED");
                return;
            }
            
            // Method 2: Check network capabilities
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(activeNetwork);
            if (capabilities == null || 
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                isInternetConnected = false;
                Log.d(TAG, "Network capabilities check failed - Internet: DISCONNECTED");
                return;
            }
            
            // Method 3: Try to reach a reliable server (async to avoid blocking)
            Thread connectivityTestThread = new Thread(() -> {
                try {
                    URL url = new URL("https://8.8.8.8/");
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(3000);
                    
                    int responseCode = connection.getResponseCode();
                    boolean canReachInternet = (responseCode == 200);
                    
                    if (isInternetConnected && !canReachInternet) {
                        isInternetConnected = false;
                        Log.d(TAG, "Internet reachability test failed - Internet: DISCONNECTED");
                        updateMyDeviceStatus();
                    } else if (!isInternetConnected && canReachInternet) {
                        isInternetConnected = true;
                        Log.d(TAG, "Internet reachability test passed - Internet: CONNECTED");
                        updateMyDeviceStatus();
                    }
                    
                    connection.disconnect();
                } catch (IOException e) {
                    if (isInternetConnected) {
                        isInternetConnected = false;
                        Log.d(TAG, "Internet reachability test exception - Internet: DISCONNECTED");
                        updateMyDeviceStatus();
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in connectivity test: " + e.getMessage());
                }
            });
            
            connectivityTestThread.setDaemon(true);
            connectivityTestThread.start();
            
            // For now, assume connected if we have validated capabilities
            isInternetConnected = true;
            Log.d(TAG, "Network capabilities validated - Internet: CONNECTED");
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking internet connectivity: " + e.getMessage());
            isInternetConnected = false;
        }
    }

    public void startAsChildDevice(String parentDeviceId, String deviceName) {
        Log.d(TAG, "Starting as child device: " + deviceName);
        shouldKeepRunning = true;
        isAppActive = true;
        updateMyDeviceStatus();
        
        // CRITICAL FIX: Delay setting up onDisconnect to prevent immediate disconnection
        // Wait for Firebase connection to stabilize before setting disconnect handler
        new Thread(() -> {
            try {
                Thread.sleep(5000); // Wait 5 seconds for Firebase to stabilize
                
                // Only set up onDisconnect if device is still active
                if (shouldKeepRunning && isAppActive) {
                    DatabaseReference myStatusRef = deviceStatusRef.child(myDeviceId);
                    Map<String, Object> disconnectStatus = createDisconnectedStatus();
                    disconnectStatus.put("reason", "app_closed");
                    disconnectStatus.put("timestamp", System.currentTimeMillis());
                    myStatusRef.onDisconnect().setValue(disconnectStatus);
                    Log.d(TAG, "🔒 OnDisconnect handler set up after stabilization period");
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "OnDisconnect setup interrupted");
            }
        }).start();
        
        // Update status every 30 seconds while app is active
        startHeartbeat(deviceName, "child", parentDeviceId);
        
        Log.d(TAG, "✅ Child device status tracking started - persistent connection enabled");
    }

    public void startAsParentDevice(String deviceName) {
        Log.d(TAG, "Starting as parent device: " + deviceName);
        shouldKeepRunning = true;
        isAppActive = true;
        updateMyDeviceStatus();
        
        // Set up auto-disconnect when app truly closes (not just minimized)
        DatabaseReference myStatusRef = deviceStatusRef.child(myDeviceId);
        myStatusRef.onDisconnect().setValue(createDisconnectedStatus());
        
        // Update status every 30 seconds while app is active
        startHeartbeat(deviceName, "parent", null);
    }

    public void setAppActive(boolean active) {
        isAppActive = active;
        Log.d(TAG, "App active status changed: " + active);
        
        // For child devices, don't immediately disconnect when app becomes inactive
        // Instead, maintain connection and just update the status
        updateMyDeviceStatus();
        
        if (active) {
            Log.d(TAG, "📱 Child device app is now active - resuming full tracking");
        } else {
            Log.d(TAG, "⏸️ Child device app paused - maintaining connection but marking as inactive");
        }
    }

    private void updateMyDeviceStatus() {
        try {
            // SAFETY CHECK: Don't update if manager is stopped
            if (!shouldKeepRunning) {
                Log.d(TAG, "Skipping status update - manager is stopped");
                return;
            }
            
            Map<String, Object> status = new HashMap<>();
            
            // For child devices, always maintain some level of connectivity
            // App is considered "online" if it has internet connectivity (even if not active)
            boolean isDeviceOnline = isInternetConnected; // More lenient for child devices
            
            status.put("isOnline", isDeviceOnline);
            status.put("isAppActive", isAppActive);
            status.put("isInternetConnected", isInternetConnected);
            status.put("lastSeen", System.currentTimeMillis());
            status.put("deviceId", myDeviceId);
            status.put("connectionPersistent", true); // Flag to indicate this is a persistent connection
            
            // SAFETY CHECK: Ensure we have a valid reference
            if (deviceStatusRef != null && myDeviceId != null) {
                deviceStatusRef.child(myDeviceId).setValue(status)
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Failed to update device status: " + e.getMessage());
                        });
                
                Log.d(TAG, "Device status updated - Online: " + isDeviceOnline + 
                      ", App Active: " + isAppActive + ", Internet: " + isInternetConnected +
                      ", Persistent: true");
            } else {
                Log.w(TAG, "Cannot update status - null reference or device ID");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating device status: " + e.getMessage());
        }
    }

    private void startHeartbeat(String deviceName, String deviceType, String parentDeviceId) {
        // Stop existing heartbeat thread if any
        if (heartbeatThread != null && heartbeatThread.isAlive()) {
            heartbeatThread.interrupt();
        }
        
        // Create a background thread for heartbeat
        heartbeatThread = new Thread(() -> {
            while (shouldKeepRunning) { // Continue heartbeat even when app is inactive
                try {
                    // SAFETY CHECK: Exit if thread should stop
                    if (!shouldKeepRunning || Thread.currentThread().isInterrupted()) {
                        Log.d(TAG, "Heartbeat thread stopping");
                        break;
                    }
                    
                    // SAFETY CHECK: Ensure we have valid references
                    if (deviceStatusRef == null || myDeviceId == null) {
                        Log.w(TAG, "Heartbeat stopping - null references");
                        break;
                    }
                    
                    Map<String, Object> status = new HashMap<>();
                    
                    // For child devices, maintain connection even when app is not active
                    boolean isDeviceOnline = isInternetConnected; // More lenient for persistent connections
                    
                    status.put("isOnline", isDeviceOnline);
                    status.put("isAppActive", isAppActive);
                    status.put("isInternetConnected", isInternetConnected);
                    status.put("lastSeen", System.currentTimeMillis());
                    status.put("deviceId", myDeviceId);
                    status.put("deviceName", deviceName);
                    status.put("deviceType", deviceType);
                    status.put("connectionPersistent", true);
                    
                    if (parentDeviceId != null) {
                        status.put("parentDeviceId", parentDeviceId);
                    }
                    
                    deviceStatusRef.child(myDeviceId).setValue(status)
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Heartbeat update failed: " + e.getMessage());
                            });
                    
                    Thread.sleep(45000); // Update every 45 seconds to reduce flickering
                } catch (InterruptedException e) {
                    Log.d(TAG, "Heartbeat thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in heartbeat: " + e.getMessage());
                    // Don't break on generic exceptions, just continue
                    try {
                        Thread.sleep(60000); // Wait longer on error
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }
            Log.d(TAG, "Heartbeat thread finished");
        });
        
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private Map<String, Object> createDisconnectedStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("isOnline", false);
        status.put("isAppActive", false);
        status.put("isInternetConnected", isInternetConnected);
        status.put("lastSeen", System.currentTimeMillis());
        status.put("deviceId", myDeviceId);
        status.put("connectionPersistent", false);
        status.put("disconnectedAt", System.currentTimeMillis());
        return status;
    }

    public void listenForChildDeviceStatus(String childDeviceId, OnDeviceStatusChangeListener listener) {
        Log.d(TAG, "Listening for child device status: " + childDeviceId);
        
        // Remove existing listener if any to prevent duplicates
        ValueEventListener existingListener = activeListeners.get(childDeviceId);
        if (existingListener != null) {
            deviceStatusRef.child(childDeviceId).removeEventListener(existingListener);
            Log.d(TAG, "Removed existing listener for device: " + childDeviceId);
        }
        
        ValueEventListener newListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        Boolean appActive = snapshot.child("isAppActive").getValue(Boolean.class);
                        Boolean internetConnected = snapshot.child("isInternetConnected").getValue(Boolean.class);
                        Long lastSeen = snapshot.child("lastSeen").getValue(Long.class);
                        
                        if (appActive != null && lastSeen != null) {
                            // Check if device is recently active (within last 2 minutes for faster offline detection)
                            long currentTime = System.currentTimeMillis();
                            long timeDiff = currentTime - lastSeen;
                            boolean isRecentlyActive = timeDiff < 120000; // 2 minutes (120 seconds)
                            
                            // Device is considered online if app is active AND recently seen
                            // More strict - require both conditions for better offline detection
                            boolean actuallyOnline = appActive && isRecentlyActive;
                            
                            // Store internet connectivity status for parent to display
                            boolean hasInternet = internetConnected != null ? internetConnected : false;
                            
                            // Use debounced reporting to prevent flickering
                            reportStatusWithDebouncing(childDeviceId, actuallyOnline, lastSeen, listener);
                            
                            Log.d(TAG, "Child device " + childDeviceId + " status: " + 
                                  "Online=" + actuallyOnline + 
                                  ", App Active=" + appActive + 
                                  ", Recently Active=" + isRecentlyActive +
                                  ", Internet=" + hasInternet + 
                                  " (last seen: " + timeDiff/1000 + " seconds ago)");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing device status: " + e.getMessage());
                    }
                } else {
                    Log.d(TAG, "No status data for child device: " + childDeviceId);
                    reportStatusWithDebouncing(childDeviceId, false, 0, listener);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error listening for child device status: " + error.getMessage());
                // Remove from active listeners on cancellation
                activeListeners.remove(childDeviceId);
            }
        };
        
        // Add the new listener to Firebase and track it
        deviceStatusRef.child(childDeviceId).addValueEventListener(newListener);
        activeListeners.put(childDeviceId, newListener);
        Log.d(TAG, "Added new status listener for device: " + childDeviceId);
    }
    
    /**
     * Reports device status with debouncing to prevent flickering
     */
    private void reportStatusWithDebouncing(String deviceId, boolean isOnline, long lastSeen, OnDeviceStatusChangeListener listener) {
        long currentTime = System.currentTimeMillis();
        
        // If status hasn't changed, just report it immediately
        if (isOnline == lastReportedOnlineStatus) {
            listener.onDeviceStatusChanged(deviceId, isOnline, lastSeen);
            return;
        }
        
        // Status has changed - check if we should wait for stability
        if (lastStatusChangeTime == 0 || (currentTime - lastStatusChangeTime) < STABILITY_DELAY) {
            // First change or not enough time has passed - update change time and wait
            if (lastStatusChangeTime == 0) {
                lastStatusChangeTime = currentTime;
                Log.d(TAG, "Status change detected for " + deviceId + ": " + lastReportedOnlineStatus + " -> " + isOnline + " (starting stability timer)");
            }
            
            // Report the old status for now to avoid flickering
            listener.onDeviceStatusChanged(deviceId, lastReportedOnlineStatus, lastSeen);
            
            // Schedule a delayed check to confirm the status change
            new Thread(() -> {
                try {
                    Thread.sleep(STABILITY_DELAY);
                    
                    // Re-check the status after delay
                    deviceStatusRef.child(deviceId).get().addOnSuccessListener(snapshot -> {
                        if (snapshot.exists()) {
                            Boolean appActive = snapshot.child("isAppActive").getValue(Boolean.class);
                            Long recentLastSeen = snapshot.child("lastSeen").getValue(Long.class);
                            
                            if (appActive != null && recentLastSeen != null) {
                                long recentTimeDiff = System.currentTimeMillis() - recentLastSeen;
                                boolean recentlyActive = recentTimeDiff < 120000;
                                boolean confirmedOnline = appActive && recentlyActive;
                                
                                // If status is still the same after stability delay, report the change
                                if (confirmedOnline == isOnline) {
                                    lastReportedOnlineStatus = isOnline;
                                    lastStatusChangeTime = 0; // Reset
                                    listener.onDeviceStatusChanged(deviceId, isOnline, recentLastSeen);
                                    Log.d(TAG, "Status change confirmed for " + deviceId + ": " + isOnline);
                                } else {
                                    Log.d(TAG, "Status change for " + deviceId + " was temporary, keeping previous status");
                                    lastStatusChangeTime = 0; // Reset
                                }
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    Log.d(TAG, "Status stability check interrupted");
                }
            }).start();
            
        } else {
            // Enough time has passed, confirm the status change
            lastReportedOnlineStatus = isOnline;
            lastStatusChangeTime = 0; // Reset
            listener.onDeviceStatusChanged(deviceId, isOnline, lastSeen);
            Log.d(TAG, "Status change confirmed for " + deviceId + " after stability delay: " + isOnline);
        }
    }

    public void listenForChildInternetStatus(String childDeviceId, OnInternetStatusChangeListener listener) {
        Log.d(TAG, "Listening for child internet status: " + childDeviceId);
        
        deviceStatusRef.child(childDeviceId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        Boolean internetConnected = snapshot.child("isInternetConnected").getValue(Boolean.class);
                        Long lastSeen = snapshot.child("lastSeen").getValue(Long.class);
                        
                        // Check if the status is recent (within last 30 seconds for internet status)
                        long currentTime = System.currentTimeMillis();
                        long timeDiff = lastSeen != null ? currentTime - lastSeen : Long.MAX_VALUE;
                        boolean isRecentStatus = timeDiff < 30000; // 30 seconds
                        
                        // Only consider internet connected if status is recent and explicitly true
                        boolean hasInternet = internetConnected != null && internetConnected && isRecentStatus;
                        
                        listener.onInternetStatusChanged(hasInternet);
                        
                        Log.d(TAG, "Child device " + childDeviceId + " internet status: " + hasInternet + 
                              " (status age: " + timeDiff/1000 + " seconds)");
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing internet status: " + e.getMessage());
                    }
                } else {
                    listener.onInternetStatusChanged(false);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error listening for child internet status: " + error.getMessage());
            }
        });
    }

    public boolean isInternetConnected() {
        return isInternetConnected;
    }

    public void forceRefreshStatus() {
        try {
            Log.d(TAG, "Force refreshing device status");
            
            // Force immediate internet connectivity check
            checkInternetConnectivity();
            
            // Restart internet monitoring if it's not running
            if (internetCheckThread == null || !internetCheckThread.isAlive()) {
                startPeriodicInternetCheck();
            }
            
            // Immediately update status
            updateMyDeviceStatus();
            
            Log.d(TAG, "Forced status refresh completed - Internet: " + isInternetConnected + 
                  ", App Active: " + isAppActive);
        } catch (Exception e) {
            Log.e(TAG, "Error in force refresh status: " + e.getMessage());
        }
    }

    public void stopStatusTracking() {
        try {
            Log.d(TAG, "🛑 Stopping status tracking...");
            
            // Stop all running threads first
            shouldKeepRunning = false;
            
            // Mark as offline
            isAppActive = false;
            
            // Send final offline status update
            try {
                if (deviceStatusRef != null && myDeviceId != null) {
                    Map<String, Object> finalStatus = createDisconnectedStatus();
                    finalStatus.put("reason", "app_stopped");
                    deviceStatusRef.child(myDeviceId).setValue(finalStatus);
                    Log.d(TAG, "📴 Final offline status sent");
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to send final status: " + e.getMessage());
            }
            
            // Remove Firebase listeners
            try {
                if (connectionListener != null && connectedRef != null) {
                    connectedRef.removeEventListener(connectionListener);
                    connectionListener = null;
                    Log.d(TAG, "🔥 Firebase connection listener removed");
                }
                
                // Remove all active device status listeners
                for (Map.Entry<String, ValueEventListener> entry : activeListeners.entrySet()) {
                    deviceStatusRef.child(entry.getKey()).removeEventListener(entry.getValue());
                    Log.d(TAG, "🔥 Removed status listener for device: " + entry.getKey());
                }
                activeListeners.clear();
                Log.d(TAG, "🔥 All device status listeners removed");
                
            } catch (Exception e) {
                Log.w(TAG, "Failed to remove listeners: " + e.getMessage());
            }
            
            // Stop and wait for threads to finish
            try {
                if (internetCheckThread != null && internetCheckThread.isAlive()) {
                    internetCheckThread.interrupt();
                    internetCheckThread.join(2000); // Wait up to 2 seconds
                    if (internetCheckThread.isAlive()) {
                        Log.w(TAG, "Internet check thread did not stop gracefully");
                    } else {
                        Log.d(TAG, "🧵 Internet check thread stopped");
                    }
                }
                
                if (heartbeatThread != null && heartbeatThread.isAlive()) {
                    heartbeatThread.interrupt();
                    heartbeatThread.join(2000); // Wait up to 2 seconds
                    if (heartbeatThread.isAlive()) {
                        Log.w(TAG, "Heartbeat thread did not stop gracefully");
                    } else {
                        Log.d(TAG, "🧵 Heartbeat thread stopped");
                    }
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Thread join interrupted: " + e.getMessage());
            }
            
            // Clear references
            internetCheckThread = null;
            heartbeatThread = null;
            
            Log.d(TAG, "✅ Status tracking stopped successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping status tracking: " + e.getMessage());
        }
    }
} 