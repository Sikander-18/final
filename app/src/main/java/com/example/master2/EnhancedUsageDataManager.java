package com.example.master2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import java.util.HashMap;
import java.util.Map;

/**
 * Enhanced Usage Data Manager for improved accuracy and real-time synchronization
 * Handles automatic refresh, data validation, and parent-child sync
 */
public class EnhancedUsageDataManager {
    private static final String TAG = "EnhancedUsageDataManager";
    
    // Auto-refresh intervals
    private static final long AUTO_REFRESH_INTERVAL = 30000; // 30 seconds
    private static final long DATA_STALENESS_THRESHOLD = 120000; // 2 minutes
    
    private Context context;
    private Handler refreshHandler;
    private Runnable refreshRunnable;
    private DatabaseReference usageRef;
    private ValueEventListener realTimeListener;
    
    // Callback interfaces
    public interface OnUsageDataUpdatedListener {
        void onUsageDataUpdated(DataSnapshot usageData, boolean isRealTime);
        void onUsageDataError(String error);
    }
    
    private OnUsageDataUpdatedListener dataListener;
    private String currentChildDeviceId;
    private boolean isAutoRefreshEnabled = true;
    
    public EnhancedUsageDataManager(Context context) {
        this.context = context;
        this.refreshHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * Start monitoring usage data for a specific child device
     */
    public void startMonitoring(String childDeviceId, OnUsageDataUpdatedListener listener) {
        Log.d(TAG, "🔍 Starting enhanced usage monitoring for device: " + childDeviceId);
        
        // ⭐ CRITICAL: Check if we're switching devices
        if (currentChildDeviceId != null && !currentChildDeviceId.equals(childDeviceId)) {
            Log.d(TAG, "📱 DEVICE SWITCH DETECTED: " + currentChildDeviceId + " → " + childDeviceId);
            // Complete stop of previous monitoring
            stopMonitoring();
            
            // Small delay to ensure clean transition
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        
        // Only update device ID if it's different to avoid unnecessary state changes
        if (this.currentChildDeviceId == null || !this.currentChildDeviceId.equals(childDeviceId)) {
            this.currentChildDeviceId = childDeviceId;
        }
        this.dataListener = listener;
        
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.w(TAG, "Invalid device ID provided");
            if (listener != null) {
                listener.onUsageDataError("No device selected");
            }
            return;
        }
        
        // Stop any existing monitoring (redundant but ensures clean state)
        stopMonitoring();
        
        // Setup Firebase reference for NEW device only
        usageRef = FirebaseDatabase.getInstance()
                .getReference("usage_snapshots")
                .child(childDeviceId)
                .child("latest");
        
        Log.d(TAG, "📊 Firebase reference set for device: " + childDeviceId);
        
        // Start real-time listener
        startRealTimeListener();
        
        // Start auto-refresh mechanism
        startAutoRefresh();
        
        // Request immediate data refresh from child
        requestImmediateDataRefresh();
    }
    
    /**
     * Setup real-time Firebase listener
     */
    private void startRealTimeListener() {
        if (usageRef == null) return;
        
        realTimeListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                // ⭐ CRITICAL: Validate that this data is for the current device
                if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
                    Log.w(TAG, "❌ No current device - ignoring data");
                    return;
                }
                
                Log.d(TAG, "📊 Real-time usage data received for device: " + currentChildDeviceId);
                
                if (snapshot.exists()) {
                    // Double-check data is for correct device
                    String deviceIdFromPath = usageRef != null ? usageRef.getParent().getKey() : null;
                    if (deviceIdFromPath != null && !deviceIdFromPath.equals(currentChildDeviceId)) {
                        Log.w(TAG, "⚠️ Data device mismatch! Expected: " + currentChildDeviceId + ", Got: " + deviceIdFromPath);
                        return;
                    }
                    
                    // Validate data freshness
                    Long timestamp = snapshot.child("timestamp").getValue(Long.class);
                    long currentTime = System.currentTimeMillis();
                    
                    if (timestamp != null && (currentTime - timestamp) < DATA_STALENESS_THRESHOLD) {
                        Log.d(TAG, "✅ Fresh usage data for device " + currentChildDeviceId + " (age: " + (currentTime - timestamp) + "ms)");
                        if (dataListener != null) {
                            dataListener.onUsageDataUpdated(snapshot, true);
                        }
                    } else {
                        Log.w(TAG, "⚠️ Stale usage data for device " + currentChildDeviceId + ", requesting refresh");
                        requestImmediateDataRefresh();
                        
                        // Still provide the data but mark as potentially stale
                        if (dataListener != null) {
                            dataListener.onUsageDataUpdated(snapshot, false);
                        }
                    }
                } else {
                    Log.w(TAG, "❌ No usage data available for device: " + currentChildDeviceId);
                    if (dataListener != null) {
                        dataListener.onUsageDataError("No usage data available");
                    }
                    
                    // Request data from child device
                    requestImmediateDataRefresh();
                }
            }
            
            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "❌ Real-time listener cancelled: " + error.getMessage());
                if (dataListener != null) {
                    dataListener.onUsageDataError("Database error: " + error.getMessage());
                }
            }
        };
        
        usageRef.addValueEventListener(realTimeListener);
        Log.d(TAG, "📡 Real-time listener attached for device: " + currentChildDeviceId);
    }
    
    /**
     * Start automatic refresh mechanism
     */
    private void startAutoRefresh() {
        if (!isAutoRefreshEnabled) return;
        
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
                    Log.d(TAG, "🔄 Auto-refreshing usage data for device: " + currentChildDeviceId);
                    requestImmediateDataRefresh();
                    
                    // Schedule next refresh
                    refreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL);
                } else {
                    Log.d(TAG, "⏹️ No active device - stopping auto-refresh");
                }
            }
        };
        
        // Start the refresh cycle
        refreshHandler.postDelayed(refreshRunnable, AUTO_REFRESH_INTERVAL);
        Log.d(TAG, "⏰ Auto-refresh started (interval: " + (AUTO_REFRESH_INTERVAL / 1000) + "s)");
    }
    
    /**
     * Request immediate data refresh from child device
     */
    public void requestImmediateDataRefresh() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Log.w(TAG, "Cannot request refresh - no device selected");
            return;
        }
        
        Log.d(TAG, "📤 Requesting immediate usage data refresh from child: " + currentChildDeviceId);
        
        // Send refresh command to child device
        DatabaseReference commandRef = FirebaseDatabase.getInstance()
                .getReference("usage_refresh_commands")
                .child(currentChildDeviceId);
        
        Map<String, Object> refreshCommand = new HashMap<>();
        refreshCommand.put("command", "refresh_usage_data");
        refreshCommand.put("timestamp", System.currentTimeMillis());
        refreshCommand.put("requestedBy", "parent");
        refreshCommand.put("priority", "high");
        
        commandRef.setValue(refreshCommand)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Usage refresh command sent to child device");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to send refresh command: " + e.getMessage());
                });
    }
    
    /**
     * Enable or disable auto-refresh
     */
    public void setAutoRefreshEnabled(boolean enabled) {
        this.isAutoRefreshEnabled = enabled;
        Log.d(TAG, "Auto-refresh " + (enabled ? "enabled" : "disabled"));
        
        if (enabled && currentChildDeviceId != null) {
            startAutoRefresh();
        } else {
            stopAutoRefresh();
        }
    }
    
    /**
     * Stop auto-refresh mechanism
     */
    private void stopAutoRefresh() {
        if (refreshHandler != null && refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
            Log.d(TAG, "⏹️ Auto-refresh stopped");
        }
    }
    
    /**
     * Stop monitoring and cleanup resources
     */
    public void stopMonitoring() {
        Log.d(TAG, "🛑 Stopping usage data monitoring");
        
        // Stop auto-refresh
        stopAutoRefresh();
        
        // Remove Firebase listener
        if (usageRef != null && realTimeListener != null) {
            usageRef.removeEventListener(realTimeListener);
            realTimeListener = null;
        }
        
        // Clear references to ensure complete isolation
        currentChildDeviceId = null;
        dataListener = null;
        usageRef = null;
    }
    
    /**
     * Force refresh usage data (manual trigger)
     */
    public void forceRefresh() {
        Log.d(TAG, "🔄 Force refreshing usage data");
        requestImmediateDataRefresh();
    }
    
    /**
     * Get current monitoring status
     */
    public boolean isMonitoring() {
        return currentChildDeviceId != null && !currentChildDeviceId.isEmpty();
    }
    
    /**
     * Get current device ID being monitored
     */
    public String getCurrentDeviceId() {
        return currentChildDeviceId;
    }
}
