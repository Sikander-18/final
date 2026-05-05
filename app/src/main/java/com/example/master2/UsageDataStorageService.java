package com.example.master2;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Service to handle accurate date-based usage data storage for child devices
 * Collects and stores usage data every 5 minutes with proper date segregation
 */
public class UsageDataStorageService extends Service {
    private static final String TAG = "UsageDataStorageService";
    private static final long REFRESH_INTERVAL = 5 * 60 * 1000; // 5 minutes
    private static final long MIDNIGHT_CHECK_INTERVAL = 60 * 1000; // 1 minute
    
    private Handler refreshHandler;
    private Handler midnightHandler;
    private Runnable refreshRunnable;
    private Runnable midnightRunnable;
    
    private DatabaseReference databaseRef;
    private SimpleDateFormat dateFormat;
    private String currentDateKey;
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "UsageDataStorageService created");
        
        databaseRef = FirebaseDatabase.getInstance().getReference();
        dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        currentDateKey = dateFormat.format(new Date());
        
        refreshHandler = new Handler(Looper.getMainLooper());
        midnightHandler = new Handler(Looper.getMainLooper());
        
        setupRefreshRunnable();
        setupMidnightCheckRunnable();
        
        // Start with immediate data collection, then periodic refresh
        Log.d(TAG, "Collecting initial usage data immediately...");
        collectAndStoreUsageData();
        
        // Start periodic data collection
        startPeriodicRefresh();
        startMidnightCheck();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "UsageDataStorageService started");
        
        // Handle immediate collection requests
        if (intent != null && "collect_now".equals(intent.getStringExtra("action"))) {
            String deviceId = intent.getStringExtra("deviceId");
            Log.d(TAG, "Immediate collection requested for device: " + deviceId);
            
            if (deviceId != null && !deviceId.isEmpty()) {
                // Collect data for the specific device immediately
                collectUsageForDevice(deviceId);
            } else {
                // Collect data for all devices
                collectAndStoreUsageData();
            }
        }
        
        return START_STICKY; // Restart if killed
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // This is a started service, not bound
    }
    
    private void setupRefreshRunnable() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                collectAndStoreUsageData();
                refreshHandler.postDelayed(this, REFRESH_INTERVAL);
            }
        };
    }
    
    private void setupMidnightCheckRunnable() {
        midnightRunnable = new Runnable() {
            @Override
            public void run() {
                checkAndHandleDateChange();
                midnightHandler.postDelayed(this, MIDNIGHT_CHECK_INTERVAL);
            }
        };
    }
    
    private void startPeriodicRefresh() {
        Log.d(TAG, "Starting periodic usage data collection every 5 minutes");
        refreshHandler.post(refreshRunnable);
    }
    
    private void startMidnightCheck() {
        Log.d(TAG, "Starting midnight check for date changes");
        midnightHandler.post(midnightRunnable);
    }
    
    private void checkAndHandleDateChange() {
        String newDateKey = dateFormat.format(new Date());
        if (!newDateKey.equals(currentDateKey)) {
            Log.d(TAG, "Date changed from " + currentDateKey + " to " + newDateKey);
            currentDateKey = newDateKey;
            
            // Force immediate collection for the new date
            collectAndStoreUsageData();
        }
    }
    
    private void collectAndStoreUsageData() {
        Log.d(TAG, "Collecting usage data for all connected child devices...");
        
        // Get all connected child devices
        databaseRef.child("device_status")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        for (DataSnapshot deviceSnapshot : snapshot.getChildren()) {
                            String deviceId = deviceSnapshot.getKey();
                            String deviceType = deviceSnapshot.child("device_type").getValue(String.class);
                            Boolean isConnected = deviceSnapshot.child("connected").getValue(Boolean.class);
                            
                            if ("child".equals(deviceType) && Boolean.TRUE.equals(isConnected)) {
                                Log.d(TAG, "Collecting data for child device: " + deviceId);
                                collectUsageForDevice(deviceId);
                            }
                        }
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.e(TAG, "Error getting device list: " + error.getMessage());
                    }
                });
    }
    
    private void collectUsageForDevice(String deviceId) {
        // Get current usage data from the child device
        databaseRef.child("usage_snapshots").child(deviceId).child("latest")
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot latestSnapshot) {
                        if (latestSnapshot.exists()) {
                            storeUsageDataByDate(deviceId, latestSnapshot);
                        } else {
                            Log.d(TAG, "No latest usage data found for device: " + deviceId);
                        }
                    }
                    
                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.e(TAG, "Error collecting usage for device " + deviceId + ": " + error.getMessage());
                    }
                });
    }
    
    private void storeUsageDataByDate(String deviceId, DataSnapshot usageData) {
        String dateKey = dateFormat.format(new Date());
        long timestamp = System.currentTimeMillis();
        
        Log.d(TAG, "Storing usage data for device " + deviceId + " on date " + dateKey);
        
        // Store the complete usage snapshot under the specific date
        Map<String, Object> dateBasedData = new HashMap<>();
        
        // Copy all existing data
        if (usageData.child("apps").exists()) {
            dateBasedData.put("apps", usageData.child("apps").getValue());
        }
        if (usageData.child("dailyApps").exists()) {
            dateBasedData.put("dailyApps", usageData.child("dailyApps").getValue());
        }
        if (usageData.child("totalText").exists()) {
            dateBasedData.put("totalText", usageData.child("totalText").getValue());
        }
        if (usageData.child("totalTexts").exists()) {
            dateBasedData.put("totalTexts", usageData.child("totalTexts").getValue());
        }
        if (usageData.child("bars").exists()) {
            dateBasedData.put("bars", usageData.child("bars").getValue());
        }
        
        // Add metadata
        dateBasedData.put("timestamp", timestamp);
        dateBasedData.put("date", dateKey);
        dateBasedData.put("collected_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        
        // Store in date-based structure: usage_history/{deviceId}/{dateKey}/{timestamp}
        databaseRef.child("usage_history")
                .child(deviceId)
                .child(dateKey)
                .child(String.valueOf(timestamp))
                .setValue(dateBasedData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Successfully stored usage data for device " + deviceId + " on " + dateKey);
                    
                    // Also update the "latest_by_date" reference for easy access
                    updateLatestByDate(deviceId, dateKey, dateBasedData);
                })
                .addOnFailureListener(error -> {
                    Log.e(TAG, "❌ Failed to store usage data for device " + deviceId + ": " + error.getMessage());
                });
    }
    
    private void updateLatestByDate(String deviceId, String dateKey, Map<String, Object> usageData) {
        // Keep the most recent data for each date easily accessible
        databaseRef.child("usage_by_date")
                .child(deviceId)
                .child(dateKey)
                .setValue(usageData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Updated latest usage for device " + deviceId + " date " + dateKey);
                })
                .addOnFailureListener(error -> {
                    Log.e(TAG, "❌ Failed to update latest usage by date: " + error.getMessage());
                });
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "UsageDataStorageService destroyed");
        
        // Stop all handlers
        if (refreshHandler != null && refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }
        if (midnightHandler != null && midnightRunnable != null) {
            midnightHandler.removeCallbacks(midnightRunnable);
        }
    }
}