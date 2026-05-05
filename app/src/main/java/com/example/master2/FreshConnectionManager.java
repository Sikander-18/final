package com.example.master2;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages fresh device connections and ensures no previous data persists
 */
public class FreshConnectionManager {
    private static final String TAG = "FreshConnectionManager";
    private static final String PREFS_NAME = "connection_state";
    private static final String KEY_LAST_CONNECTION_TIME = "last_connection_time";
    private static final String KEY_CONNECTION_SESSION_ID = "connection_session_id";

    private Context context;
    private String deviceId;
    private SharedPreferences prefs;
    private DatabaseReference database;

    public FreshConnectionManager(Context context, String deviceId) {
        this.context = context;
        this.deviceId = deviceId;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.database = FirebaseDatabase.getInstance().getReference();

        Log.d(TAG, "✅ FreshConnectionManager initialized for device: " + deviceId);
    }

    /**
     * Handle fresh device connection - clear all previous session data
     */
    public void handleFreshConnection() {
        Log.d(TAG, "🔄 Handling fresh device connection");

        String newSessionId = generateSessionId();
        long currentTime = System.currentTimeMillis();

        // Clear all local data
        clearLocalTimerData();
        clearLocalUsageData();
        clearLocalLimiterData();

        // Clear Firebase data
        clearFirebaseTimerData();
        clearFirebaseUsageData();
        clearFirebaseLimiterData();

        // Update connection state
        prefs.edit()
                .putLong(KEY_LAST_CONNECTION_TIME, currentTime)
                .putString(KEY_CONNECTION_SESSION_ID, newSessionId)
                .apply();

        // Set fresh connection flag in Firebase
        setFreshConnectionFlag(newSessionId, currentTime);

        Log.d(TAG, "✅ Fresh connection setup completed with session: " + newSessionId);
    }

    /**
     * Check if this is a reconnection that should clear data
     */
    public boolean shouldClearDataOnConnection() {
        long lastConnection = prefs.getLong(KEY_LAST_CONNECTION_TIME, 0);
        long timeSinceLastConnection = System.currentTimeMillis() - lastConnection;

        // If more than 5 minutes since last connection, treat as fresh
        boolean isFreshConnection = (lastConnection == 0 || timeSinceLastConnection > 300000);

        Log.d(TAG, "🔍 Connection check - Time since last: " + (timeSinceLastConnection / 1000) + "s, Fresh: "
                + isFreshConnection);

        return isFreshConnection;
    }

    /**
     * Clear local timer-related data
     */
    private void clearLocalTimerData() {
        Log.d(TAG, "🗑️ Clearing local timer data");

        // Clear timer SharedPreferences
        SharedPreferences timerPrefs = context.getSharedPreferences("smart_timer_prefs", Context.MODE_PRIVATE);
        timerPrefs.edit().clear().apply();

        // Clear timer state
        SharedPreferences timerStatePrefs = context.getSharedPreferences("timer_state", Context.MODE_PRIVATE);
        timerStatePrefs.edit().clear().apply();

        Log.d(TAG, "✅ Local timer data cleared");
    }

    /**
     * Clear local usage data
     */
    private void clearLocalUsageData() {
        Log.d(TAG, "🗑️ Clearing local usage data");

        // Clear usage SharedPreferences
        SharedPreferences usagePrefs = context.getSharedPreferences("usage_data", Context.MODE_PRIVATE);
        usagePrefs.edit().clear().apply();

        // Clear app usage cache
        SharedPreferences cachePrefs = context.getSharedPreferences("app_usage_cache", Context.MODE_PRIVATE);
        cachePrefs.edit().clear().apply();

        Log.d(TAG, "✅ Local usage data cleared");
    }

    /**
     * Clear local limiter data
     */
    private void clearLocalLimiterData() {
        Log.d(TAG, "🗑️ Clearing local limiter data");

        // Clear usage limiter SharedPreferences
        SharedPreferences limiterPrefs = context.getSharedPreferences("usage_limiter_prefs", Context.MODE_PRIVATE);
        limiterPrefs.edit().clear().apply();

        // Clear blocked apps (but preserve parent-set blocks)
        // We'll only clear timer-related blocks, not permanent restrictions

        Log.d(TAG, "✅ Local limiter data cleared");
    }

    /**
     * Clear Firebase timer data
     */
    private void clearFirebaseTimerData() {
        Log.d(TAG, "🗑️ Clearing Firebase timer data");

        // Clear active timers
        database.child("active_timers").child(deviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Firebase active timers cleared"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear active timers: " + e.getMessage()));

        // Clear timer history
        database.child("timer_history").child(deviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Firebase timer history cleared"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear timer history: " + e.getMessage()));

        // Clear parent timer commands for this device
        database.child("parent_timers").child(deviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Firebase parent timer commands cleared"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear parent timer commands: " + e.getMessage()));
    }

    /**
     * Clear Firebase usage data
     */
    private void clearFirebaseUsageData() {
        Log.d(TAG, "🗑️ Clearing Firebase usage data");

        // Clear usage snapshots
        database.child("usage_snapshots").child(deviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Firebase usage snapshots cleared"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear usage snapshots: " + e.getMessage()));

        // Clear daily usage data
        database.child("daily_usage").child(deviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Firebase daily usage cleared"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear daily usage: " + e.getMessage()));
    }

    /**
     * Clear Firebase limiter data
     */
    private void clearFirebaseLimiterData() {
        Log.d(TAG, "🗑️ Clearing Firebase limiter data");

        // Clear usage limiter state
        database.child("usage_limiters").child(deviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Firebase usage limiters cleared"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear usage limiters: " + e.getMessage()));

        // Clear limiter history
        database.child("limiter_history").child(deviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Firebase limiter history cleared"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear limiter history: " + e.getMessage()));
    }

    /**
     * Set fresh connection flag in Firebase
     */
    private void setFreshConnectionFlag(String sessionId, long connectionTime) {
        Map<String, Object> connectionData = new HashMap<>();
        connectionData.put("session_id", sessionId);
        connectionData.put("connection_time", connectionTime);
        connectionData.put("is_fresh_start", true);
        connectionData.put("data_cleared", true);
        connectionData.put("device_id", deviceId);

        database.child("device_connections").child(deviceId).setValue(connectionData)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Fresh connection flag set in Firebase"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to set connection flag: " + e.getMessage()));
    }

    /**
     * Generate unique session ID
     */
    private String generateSessionId() {
        return "session_" + System.currentTimeMillis() + "_" + Math.random();
    }

    /**
     * Check if device has been freshly connected
     */
    public boolean isFreshlyConnected() {
        long lastConnection = prefs.getLong(KEY_LAST_CONNECTION_TIME, 0);
        long timeSinceConnection = System.currentTimeMillis() - lastConnection;

        // Consider fresh if connected in last 30 seconds
        return timeSinceConnection < 30000;
    }

    /**
     * Mark connection as established (call after successful QR pairing)
     */
    public void markConnectionEstablished() {
        long currentTime = System.currentTimeMillis();
        String sessionId = generateSessionId();

        prefs.edit()
                .putLong(KEY_LAST_CONNECTION_TIME, currentTime)
                .putString(KEY_CONNECTION_SESSION_ID, sessionId)
                .apply();

        Log.d(TAG, "✅ Connection marked as established with session: " + sessionId);
    }

    /**
     * Get current session ID
     */
    public String getCurrentSessionId() {
        return prefs.getString(KEY_CONNECTION_SESSION_ID, "no_session");
    }

    /**
     * Force clear all data (emergency reset)
     */
    public void forceCompleteReset() {
        Log.d(TAG, "🚨 FORCE COMPLETE RESET - Clearing ALL data");

        clearLocalTimerData();
        clearLocalUsageData();
        clearLocalLimiterData();

        clearFirebaseTimerData();
        clearFirebaseUsageData();
        clearFirebaseLimiterData();

        // Clear connection state
        prefs.edit().clear().apply();

        // Clear device pairing data
        SharedPreferences pairingPrefs = context.getSharedPreferences("device_pairing", Context.MODE_PRIVATE);
        pairingPrefs.edit().clear().apply();

        // Set complete reset flag
        Map<String, Object> resetData = new HashMap<>();
        resetData.put("reset_time", System.currentTimeMillis());
        resetData.put("reset_type", "complete_force_reset");
        resetData.put("device_id", deviceId);

        database.child("device_resets").child(deviceId).setValue(resetData);

        Log.d(TAG, "🚨 FORCE COMPLETE RESET FINISHED");
    }
}