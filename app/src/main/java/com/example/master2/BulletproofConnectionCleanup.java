package com.example.master2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.Map;

/**
 * 🛡️ BULLETPROOF CONNECTION CLEANUP SYSTEM
 * 
 * This class ensures that when a child device is removed, ALL traces of connection data
 * are completely obliterated to prevent automatic reconnection or dashboard redirection.
 * 
 * Features:
 * - Complete SharedPreferences cleanup
 * - Firebase path cleanup 
 * - Session data obliteration
 * - Connection state invalidation
 * - Fresh start mechanism
 */
public class BulletproofConnectionCleanup {
    private static final String TAG = "BulletproofCleanup";
    
    private final Context context;
    
    public BulletproofConnectionCleanup(Context context) {
        this.context = context;
    }
    
    /**
     * 🔥 NUCLEAR CONNECTION CLEANUP
     * Called when parent removes a child device
     */
    public void performNuclearChildDeviceCleanup(String deviceId) {
        Log.d(TAG, "🔥 NUCLEAR CLEANUP: Obliterating all connection data for device: " + deviceId);
        
        // Step 1: Clear all child session data
        clearChildSessionData();
        
        // Step 2: Clear all connection-related SharedPreferences
        clearAllConnectionPreferences();
        
        // Step 3: Clear Firebase connection paths
        clearFirebaseConnectionPaths(deviceId);
        
        // Step 4: Set disconnection flags
        setDisconnectionFlags();
        
        // Step 5: Invalidate any cached connection state
        invalidateCachedConnectionState();
        
        Log.d(TAG, "✅ NUCLEAR CLEANUP COMPLETE: Device " + deviceId + " completely disconnected");
    }
    
    /**
     * 🧹 Clear all child session data from SessionManager
     */
    private void clearChildSessionData() {
        try {
            SessionManager sessionManager = new SessionManager(context);
            
            // Get current data before clearing for logging
            String childDeviceId = sessionManager.getChildDeviceId();
            String parentName = sessionManager.getParentName();
            
            Log.d(TAG, "  🧹 Clearing session data for device: " + childDeviceId + ", parent: " + parentName);
            
            // Complete session logout
            sessionManager.logoutUser();
            
            Log.d(TAG, "  ✅ Session data cleared completely");
            
        } catch (Exception e) {
            Log.e(TAG, "  ❌ Error clearing session data: " + e.getMessage());
        }
    }
    
    /**
     * 🗑️ Clear ALL connection-related SharedPreferences
     */
    private void clearAllConnectionPreferences() {
        String[] connectionPreferences = {
            "device_connection",      // Connection state flags
            "child_prefs",           // Child-specific preferences
            "child_session",         // Child session data
            "connection_prefs",      // Connection preferences
            "qr_scanning_state",     // QR scanning state
            "parent_connection",     // Parent connection data
            "device_info",           // Device information
            "focus_mode_prefs",      // Focus mode settings
            "timer_state",           // Timer state data
            "Enhanced7DayUsageTracker_prefs", // Usage tracking data
            "blocked_apps",          // Blocked apps data
            "app_usage_limits"       // App usage limits
        };
        
        for (String prefName : connectionPreferences) {
            try {
                SharedPreferences prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
                int itemCount = prefs.getAll().size();
                
                if (itemCount > 0) {
                    prefs.edit().clear().apply();
                    Log.d(TAG, "  ✅ Cleared: " + prefName + " (" + itemCount + " items)");
                } else {
                    Log.d(TAG, "  ⏭️ Already empty: " + prefName);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "  ❌ Error clearing " + prefName + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * 🔥 Clear Firebase connection paths
     */
    private void clearFirebaseConnectionPaths(String deviceId) {
        try {
            // Clear device status path
            DatabaseReference deviceStatusRef = FirebaseDatabase.getInstance()
                .getReference("device_status")
                .child(deviceId);
            deviceStatusRef.removeValue();
            
            // Clear children path
            DatabaseReference childrenRef = FirebaseDatabase.getInstance()
                .getReference("children")
                .child(deviceId);
            childrenRef.removeValue();
            
            // Clear any active timer data
            DatabaseReference activeTimerRef = FirebaseDatabase.getInstance()
                .getReference("active_timers")
                .child(deviceId);
            activeTimerRef.removeValue();
            
            // Clear usage data
            DatabaseReference usageRef = FirebaseDatabase.getInstance()
                .getReference("enhanced_usage_data")
                .child(deviceId);
            usageRef.removeValue();
            
            Log.d(TAG, "  ✅ Firebase paths cleared for device: " + deviceId);
            
        } catch (Exception e) {
            Log.e(TAG, "  ❌ Error clearing Firebase paths: " + e.getMessage());
        }
    }
    
    /**
     * 🚨 Set disconnection flags to prevent reconnection
     */
    private void setDisconnectionFlags() {
        try {
            // Set explicit disconnection flag
            SharedPreferences disconnectionPrefs = context.getSharedPreferences("disconnection_state", Context.MODE_PRIVATE);
            disconnectionPrefs.edit()
                .putBoolean("device_was_removed", true)
                .putBoolean("require_qr_reconnection", true)
                .putLong("disconnection_timestamp", System.currentTimeMillis())
                .putString("disconnection_reason", "parent_device_removal")
                .apply();
            
            // Clear connection state flags
            SharedPreferences connectionPrefs = context.getSharedPreferences("device_connection", Context.MODE_PRIVATE);
            connectionPrefs.edit()
                .putBoolean("is_connected", false)
                .putBoolean("connection_valid", false)
                .putLong("disconnection_time", System.currentTimeMillis())
                .apply();
            
            Log.d(TAG, "  ✅ Disconnection flags set");
            
        } catch (Exception e) {
            Log.e(TAG, "  ❌ Error setting disconnection flags: " + e.getMessage());
        }
    }
    
    /**
     * 🗑️ Invalidate any cached connection state
     */
    private void invalidateCachedConnectionState() {
        try {
            // Clear any cached authentication state
            SharedPreferences authPrefs = context.getSharedPreferences("auth_cache", Context.MODE_PRIVATE);
            authPrefs.edit().clear().apply();
            
            // Clear app state preferences
            SharedPreferences appStatePrefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE);
            appStatePrefs.edit()
                .putBoolean("force_fresh_start", true)
                .putLong("cleanup_timestamp", System.currentTimeMillis())
                .apply();
            
            Log.d(TAG, "  ✅ Cached connection state invalidated");
            
        } catch (Exception e) {
            Log.e(TAG, "  ❌ Error invalidating cached state: " + e.getMessage());
        }
    }
    
    /**
     * 🔍 Verify that cleanup was successful
     */
    public boolean verifyCleanupSuccess() {
        try {
            SessionManager sessionManager = new SessionManager(context);
            
            // Check if session is truly cleared
            boolean sessionCleared = !sessionManager.isLoggedIn() || 
                                   sessionManager.getChildDeviceId() == null ||
                                   sessionManager.getChildDeviceId().isEmpty();
            
            // Check if connection preferences are cleared
            SharedPreferences connectionPrefs = context.getSharedPreferences("device_connection", Context.MODE_PRIVATE);
            boolean connectionCleared = !connectionPrefs.getBoolean("is_connected", false);
            
            // Check if disconnection flags are set
            SharedPreferences disconnectionPrefs = context.getSharedPreferences("disconnection_state", Context.MODE_PRIVATE);
            boolean disconnectionFlagged = disconnectionPrefs.getBoolean("device_was_removed", false);
            
            boolean success = sessionCleared && connectionCleared && disconnectionFlagged;
            
            Log.d(TAG, "🔍 CLEANUP VERIFICATION:");
            Log.d(TAG, "  Session cleared: " + sessionCleared);
            Log.d(TAG, "  Connection cleared: " + connectionCleared);
            Log.d(TAG, "  Disconnection flagged: " + disconnectionFlagged);
            Log.d(TAG, "  Overall success: " + success);
            
            return success;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error verifying cleanup: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 🔄 Prepare for fresh connection (called before new QR scan)
     */
    public void prepareFreshConnection() {
        Log.d(TAG, "🔄 PREPARING FRESH CONNECTION - Clearing old connection traces");
        
        // Clear disconnection flags
        SharedPreferences disconnectionPrefs = context.getSharedPreferences("disconnection_state", Context.MODE_PRIVATE);
        disconnectionPrefs.edit().clear().apply();
        
        // Clear old connection state
        SharedPreferences connectionPrefs = context.getSharedPreferences("device_connection", Context.MODE_PRIVATE);
        connectionPrefs.edit().clear().apply();
        
        // Clear app state for fresh start
        SharedPreferences appStatePrefs = context.getSharedPreferences("app_state", Context.MODE_PRIVATE);
        appStatePrefs.edit()
            .putBoolean("fresh_connection_prepared", true)
            .putLong("preparation_timestamp", System.currentTimeMillis())
            .apply();
        
        Log.d(TAG, "✅ Fresh connection prepared");
    }
    
    /**
     * 🛡️ Check if device should be blocked from automatic reconnection
     */
    public boolean shouldBlockAutomaticReconnection() {
        try {
            SharedPreferences disconnectionPrefs = context.getSharedPreferences("disconnection_state", Context.MODE_PRIVATE);
            boolean wasRemoved = disconnectionPrefs.getBoolean("device_was_removed", false);
            boolean requiresQR = disconnectionPrefs.getBoolean("require_qr_reconnection", false);
            
            // Check if disconnection was recent (within last 24 hours)
            long disconnectionTime = disconnectionPrefs.getLong("disconnection_timestamp", 0);
            long currentTime = System.currentTimeMillis();
            long timeSinceDisconnection = currentTime - disconnectionTime;
            boolean recentDisconnection = timeSinceDisconnection < (24 * 60 * 60 * 1000); // 24 hours
            
            boolean shouldBlock = wasRemoved && requiresQR && recentDisconnection;
            
            Log.d(TAG, "🛡️ AUTOMATIC RECONNECTION CHECK:");
            Log.d(TAG, "  Was removed: " + wasRemoved);
            Log.d(TAG, "  Requires QR: " + requiresQR);
            Log.d(TAG, "  Recent disconnection: " + recentDisconnection);
            Log.d(TAG, "  Should block: " + shouldBlock);
            
            return shouldBlock;
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error checking reconnection block: " + e.getMessage());
            return true; // Default to blocking for safety
        }
    }
}