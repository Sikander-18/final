package com.example.master2;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Service to handle daily midnight reset of usage limiters
 * Monitors all active limiters and resets them at midnight
 */
public class UsageLimiterResetService extends Service {
    private static final String TAG = "UsageLimiterResetService";
    
    private Handler resetHandler;
    private Runnable midnightResetRunnable;
    private DatabaseReference limitersRef;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "UsageLimiterResetService created");
        
        limitersRef = FirebaseDatabase.getInstance().getReference("usage_limiters");
        resetHandler = new Handler(Looper.getMainLooper());
        
        startMidnightResetMonitoring();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "UsageLimiterResetService started");
        return START_STICKY; // Restart if killed
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // No binding
    }
    
    private void startMidnightResetMonitoring() {
        scheduleNextMidnightReset();
    }
    
    private void scheduleNextMidnightReset() {
        // Calculate time until next midnight
        Calendar now = Calendar.getInstance();
        Calendar midnight = Calendar.getInstance();
        midnight.add(Calendar.DAY_OF_YEAR, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        
        long timeUntilMidnight = midnight.getTimeInMillis() - now.getTimeInMillis();
        
        Log.d(TAG, "Scheduling next midnight reset in " + (timeUntilMidnight / 1000 / 60) + " minutes");
        
        // Cancel previous runnable if any
        if (midnightResetRunnable != null) {
            resetHandler.removeCallbacks(midnightResetRunnable);
        }
        
        midnightResetRunnable = () -> {
            performMidnightReset();
            // Schedule next reset
            scheduleNextMidnightReset();
        };
        
        resetHandler.postDelayed(midnightResetRunnable, timeUntilMidnight);
        
        // Also check for fresh connections every minute
        startFreshConnectionMonitoring();
    }
    
    private void startFreshConnectionMonitoring() {
        resetHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkForFreshConnections();
                // Schedule next check in 1 minute
                resetHandler.postDelayed(this, 60000);
            }
        }, 60000); // Start first check in 1 minute
    }
    
    private void checkForFreshConnections() {
        // Monitor device_connections for fresh connection flags
        DatabaseReference connectionsRef = FirebaseDatabase.getInstance()
                .getReference("device_connections");
        
        connectionsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                    String deviceId = deviceSnapshot.getKey();
                    Map<String, Object> connectionData = (Map<String, Object>) deviceSnapshot.getValue();
                    
                    if (connectionData != null) {
                        Boolean isFreshStart = (Boolean) connectionData.get("is_fresh_start");
                        Boolean dataCleared = (Boolean) connectionData.get("data_cleared");
                        Long connectionTime = (Long) connectionData.get("connection_time");
                        
                        if (Boolean.TRUE.equals(isFreshStart) && Boolean.TRUE.equals(dataCleared)) {
                            // Check if this is a recent connection (within 5 minutes)
                            long timeSinceConnection = System.currentTimeMillis() - (connectionTime != null ? connectionTime : 0);
                            if (timeSinceConnection < 300000) { // 5 minutes
                                Log.d(TAG, "🧹 Fresh connection detected for device: " + deviceId + " - resetting limiters");
                                resetLimitersForDevice(deviceId);
                            }
                        }
                    }
                }
            }
            
            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Failed to check fresh connections: " + databaseError.getMessage());
            }
        });
    }
    
    private void resetLimitersForDevice(String deviceId) {
        Log.d(TAG, "🔄 Resetting all limiters for device: " + deviceId);
        
        limitersRef.child(deviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    Map<String, Object> resetData = new HashMap<>();
                    resetData.put("remaining_time_ms", 0);
                    resetData.put("is_active", false);
                    resetData.put("reset_reason", "fresh_connection");
                    resetData.put("reset_time", System.currentTimeMillis());
                    
                    limitersRef.child(deviceId).updateChildren(resetData)
                        .addOnSuccessListener(aVoid -> {
                            Log.d(TAG, "✅ Limiters reset for device: " + deviceId);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "❌ Failed to reset limiters for device " + deviceId + ": " + e.getMessage());
                        });
                }
            }
            
            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Failed to reset limiters for device " + deviceId + ": " + databaseError.getMessage());
            }
        });
    }
    
    private void performMidnightReset() {
        String currentDate = getCurrentDateString();
        Log.d(TAG, "Performing midnight reset for date: " + currentDate);
        
    // Get all active limiters
    limitersRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                    String deviceId = deviceSnapshot.getKey();
                    
                    try {
                        // Check if limiter is active and needs reset
                        Boolean isActive = deviceSnapshot.child("isActive").getValue(Boolean.class);
                        String lastResetDate = deviceSnapshot.child("lastResetDate").getValue(String.class);
                        
            // Respect parent-cleared state: if node missing or inactive, do nothing
            if (isActive != null && isActive && !currentDate.equals(lastResetDate)) {
                            // Check if limiter should be active today
                            List<String> activeDays = (List<String>) deviceSnapshot.child("activeDays").getValue();
                            if (activeDays != null && isLimiterActiveForToday(activeDays)) {
                                resetLimiterForDevice(deviceId, deviceSnapshot);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing reset for device " + deviceId + ": " + e.getMessage());
                    }
                }
            }
            
            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error reading limiters for reset: " + databaseError.getMessage());
            }
        });
    }
    
    private void resetLimiterForDevice(String deviceId, DataSnapshot deviceSnapshot) {
        try {
            // If node was deleted or intentionally deactivated, skip
            Boolean isActive = deviceSnapshot.child("isActive").getValue(Boolean.class);
            if (isActive == null || !isActive) {
                Log.d(TAG, "Skipping reset for device " + deviceId + " (inactive or cleared)");
                return;
            }

            // Get original timer duration SET BY PARENT
            Integer hours = deviceSnapshot.child("hours").getValue(Integer.class);
            Integer minutes = deviceSnapshot.child("minutes").getValue(Integer.class);

            if (hours != null && minutes != null) {
                // 🔄 BULLETPROOF RESET: Calculate FRESH remaining time from original parent-set values
                long originalParentSetDuration = ((long) hours * 60 * 60 * 1000) + ((long) minutes * 60 * 1000);
                String currentDate = getCurrentDateString();

                Log.d(TAG, "🔄 RESETTING TIMER: Device " + deviceId + " back to original parent-set duration: " +
                      hours + "h " + minutes + "m (" + originalParentSetDuration + "ms)");

                // 💾 Update Firebase with COMPLETE reset values
                Map<String, Object> resetData = new HashMap<>();
                resetData.put("remainingTimeMs", originalParentSetDuration); // ✅ RESTORED to original parent amount
                resetData.put("lastResetDate", currentDate); // ✅ Mark as reset today
                resetData.put("startTime", System.currentTimeMillis()); // ✅ Fresh start time
                resetData.put("isActive", true); // ✅ Ensure it's active
                resetData.put("resetAt", System.currentTimeMillis()); // ✅ Track when reset happened
                resetData.put("resetReason", "midnight_auto_reset"); // ✅ Track why it was reset

                limitersRef.child(deviceId).updateChildren(resetData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ SUCCESSFULLY RESET: Device " + deviceId + " timer restored to original " +
                              hours + "h " + minutes + "m duration");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ FAILED TO RESET: Device " + deviceId + " timer reset failed: " + e.getMessage());
                    });
            } else {
                Log.w(TAG, "⚠️ MISSING DURATION DATA: Device " + deviceId + " has no hours/minutes data - cannot reset");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ ERROR RESETTING: Device " + deviceId + " reset failed: " + e.getMessage());
        }
    }
    
    private boolean isLimiterActiveForToday(List<String> activeDays) {
        if (activeDays == null || activeDays.isEmpty()) {
            return false;
        }
        
        Calendar calendar = Calendar.getInstance();
        String currentDay = getDayName(calendar.get(Calendar.DAY_OF_WEEK));
        
        return activeDays.contains(currentDay.toLowerCase());
    }
    
    private String getDayName(int dayOfWeek) {
        switch (dayOfWeek) {
            case Calendar.SUNDAY: return "Sunday";
            case Calendar.MONDAY: return "Monday";
            case Calendar.TUESDAY: return "Tuesday";
            case Calendar.WEDNESDAY: return "Wednesday";
            case Calendar.THURSDAY: return "Thursday";
            case Calendar.FRIDAY: return "Friday";
            case Calendar.SATURDAY: return "Saturday";
            default: return "Unknown";
        }
    }
    
    private String getCurrentDateString() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(new Date());
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "UsageLimiterResetService destroyed");
        
        if (resetHandler != null && midnightResetRunnable != null) {
            resetHandler.removeCallbacks(midnightResetRunnable);
        }
    }
    
    // Static method to start the service
    public static void startService(android.content.Context context) {
        Intent intent = new Intent(context, UsageLimiterResetService.class);
        context.startService(intent);
    }
    
    // Static method to stop the service
    public static void stopService(android.content.Context context) {
        Intent intent = new Intent(context, UsageLimiterResetService.class);
        context.stopService(intent);
    }
}