package com.example.master2;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

public class LiveTimerService extends Service {
    private static final String TAG = "LiveTimerService";
    private static final long UPDATE_INTERVAL = 5000; // 🔧 ANTI-FLUCTUATION: Update every 5 seconds to reduce conflicts
    
    // 🔧 FOREGROUND SERVICE CONSTANTS
    private static final String CHANNEL_ID = "LiveTimerServiceChannel";
    private static final int NOTIFICATION_ID = 1003;
    
    private DatabaseReference parentTimersRef;
    private DatabaseReference activeTimersRef;
    private ValueEventListener parentTimersListener;
    private Handler timerHandler;
    private Map<String, TimerInfo> activeTimers = new ConcurrentHashMap<>();
    private BroadcastReceiver appForegroundReceiver;
    
    // Simple app tracking
    private String currentForegroundApp = "";
    private String previousForegroundApp = "";
    
    private static class TimerInfo {
        String deviceId;
        String deviceName;
        long startTime;
        long duration;
        List<String> selectedApps;
        boolean isActive; // Timer counting down when using monitored app
        String currentApp = "";
        long totalElapsedTime = 0; // Track total time spent using selected apps
        long lastActivationTime = 0; // When timer last started
        
        public TimerInfo(String deviceId, String deviceName, long startTime, long duration, List<String> selectedApps) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.startTime = startTime;
            this.duration = duration;
            this.selectedApps = selectedApps;
            this.isActive = false;
            this.totalElapsedTime = 0;
            this.lastActivationTime = 0;
        }
        
        public void setInteracting(boolean interacting) {
            long currentTime = System.currentTimeMillis();
            
            // SIMPLE: Just change state if different
            if (this.isActive == interacting) return; // No change needed
            
            if (this.isActive && !interacting) {
                // Timer was running, now stopping
                if (lastActivationTime > 0) {
                    totalElapsedTime += (currentTime - lastActivationTime);
                }
            } else if (!this.isActive && interacting) {
                // Timer was stopped, now starting
                lastActivationTime = currentTime;
            }
            
            this.isActive = interacting;
        }
        
        // Simple timer methods - no complex tracking needed
        
        public long getTimeLeft() {
            long totalUsedTime = totalElapsedTime;
            
            // If currently actively interacting, add current session time
            if (isActive && lastActivationTime > 0) {
                totalUsedTime += (System.currentTimeMillis() - lastActivationTime);
            }
            
            return Math.max(0, duration - totalUsedTime);
        }
        
        public boolean isExpired() {
            return getTimeLeft() <= 0;
        }
        
        public String getStatusText() {
            if (isExpired()) {
                return "TIME EXPIRED";
            } else if (isActive) {
                return "Timer running";
            } else {
                return "Timer stopped";
            }
        }
        
        public long getTotalUsedTime() {
            long totalUsedTime = totalElapsedTime;
            if (isActive && lastActivationTime > 0) {
                totalUsedTime += (System.currentTimeMillis() - lastActivationTime);
            }
            return totalUsedTime;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "LiveTimerService created with INTERACTION TRACKING");
        
        try {
            // 🔧 CRITICAL: Create notification channel and start foreground IMMEDIATELY
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, createNotification());
            Log.d(TAG, "✅ LiveTimerService started in foreground - crash prevented");
            
            FirebaseDatabase database = FirebaseDatabase.getInstance();
            parentTimersRef = database.getReference("parent_timers");
            activeTimersRef = database.getReference("active_timers");
            
            timerHandler = new Handler(Looper.getMainLooper());
            
            setupAppForegroundReceiver();
            // 🔧 TIMER CONFLICT FIX: Disable timer listening in LiveTimerService 
            // SmartTimerService handles all timer functionality to prevent conflicts
            // startListeningForTimers(); // DISABLED to prevent timer fluctuations
            startTimerUpdateLoop();
            
            Log.d(TAG, "LiveTimerService initialized successfully with interaction tracking");
        } catch (Exception e) {
            Log.e(TAG, "Error creating LiveTimerService: " + e.getMessage());
            // If foreground service setup fails, stop the service to prevent crash
            stopSelf();
        }
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "LiveTimerService onStartCommand called");
        // Return START_STICKY so the service restarts if killed
        return START_STICKY;
    }
    
    private void setupAppForegroundReceiver() {
        try {
            if (appForegroundReceiver != null) {
                // Already registered, unregister first
                try {
                    unregisterReceiver(appForegroundReceiver);
                } catch (Exception e) {
                    Log.d(TAG, "Receiver not registered, continuing...");
                }
            }
            
            appForegroundReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    try {
                        String action = intent.getAction();
                        if ("com.example.master2.APP_FOREGROUND".equals(action)) {
                            String packageName = intent.getStringExtra("package_name");
                            boolean isSystem = intent.getBooleanExtra("isSystem", false);
                            String interactionType = intent.getStringExtra("interaction_type");
                            
                            if (!isSystem && packageName != null && !packageName.isEmpty()) {
                                
                                // SIMPLE: Any app switch = timer update
                                if (!packageName.equals(previousForegroundApp)) {
                                    previousForegroundApp = packageName;
                                    currentForegroundApp = packageName;
                                    
                                    Log.d(TAG, "📱 App changed: " + packageName);
                                    // 🔧 TIMER CONFLICT FIX: Disabled timer updates in LiveTimerService
                                    // SmartTimerService handles all timer functionality
                                    // updateTimersForApp(packageName); // DISABLED
                                }
                            } else {
                                // System app or empty package - user may have left monitored apps
                                if (!previousForegroundApp.isEmpty()) {
                                    previousForegroundApp = "";
                                    currentForegroundApp = "";
                                    
                                    Log.d(TAG, "🏠 User went to home");
                                    // 🔧 TIMER CONFLICT FIX: Disabled timer updates in LiveTimerService
                                    // SmartTimerService handles all timer functionality
                                    // updateTimersForApp(""); // DISABLED
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in foreground receiver: " + e.getMessage());
                    }
                }
            };
            
            IntentFilter filter = new IntentFilter("com.example.master2.APP_FOREGROUND");
            
            // Fix for Android 8.0+ (API 26+) - specify receiver export flag for security
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(appForegroundReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                Log.d(TAG, "App foreground receiver registered with RECEIVER_NOT_EXPORTED flag");
            } else {
                registerReceiver(appForegroundReceiver, filter);
                Log.d(TAG, "App foreground receiver registered (legacy mode)");
            }
            Log.d(TAG, "App foreground receiver registered successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Error setting up app foreground receiver: " + e.getMessage());
            appForegroundReceiver = null; // Reset on error
        }
    }
    
    private void updateTimersForApp(String packageName) {
        try {
            if (activeTimers == null) return;
            
            // SIMPLE: Check if app is in timer list
            for (TimerInfo timer : activeTimers.values()) {
                if (timer != null && timer.selectedApps != null) {
                    boolean isSelectedApp = timer.selectedApps.contains(packageName);
                    
                    if (isSelectedApp) {
                        // App is monitored - start timer
                        timer.setInteracting(true);
                        timer.currentApp = packageName;
                        Log.d(TAG, "▶️ Timer STARTED: " + packageName);
                    } else {
                        // App not monitored - stop timer
                        timer.setInteracting(false);
                        timer.currentApp = "";
                        Log.d(TAG, "⏹️ Timer STOPPED");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating timers: " + e.getMessage());
        }
    }
    
    // Simple app switching logic - no complex interaction tracking
    
    // Simple timer logic - no complex monitoring needed
    
    private void verifyInteractionStates() {
        try {
            Log.d(TAG, "🔍 SIMPLE TIMER VERIFICATION:");
            Log.d(TAG, "📱 Current app: " + currentForegroundApp);
            
            for (TimerInfo timer : activeTimers.values()) {
                if (timer != null && timer.selectedApps != null) {
                    boolean isInSelectedApp = timer.selectedApps.contains(currentForegroundApp);
                    boolean isActive = timer.isActive;
                    
                    String status = isActive ? "RUNNING" : "STOPPED";
                    String expected = isInSelectedApp ? "RUNNING" : "STOPPED";
                    String match = status.equals(expected) ? "✅" : "❌";
                    
                    Log.d(TAG, match + " " + timer.deviceName + ": " + status);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error verifying timer states: " + e.getMessage());
        }
    }
    

    
    private boolean isSystemAppOrLauncher(String packageName) {
        if (packageName == null) return true;
        
        // Filter out system apps and components that shouldn't trigger timers
        return packageName.equals("android") ||
               packageName.contains("launcher") ||
               packageName.contains("systemui") ||
               packageName.contains("system") ||
               packageName.startsWith("com.android.") ||
               packageName.contains("settings") ||
               packageName.contains("com.miui.") ||
               packageName.contains("com.google.android.gms") ||
               packageName.equals("com.example.master2"); // Don't track our own app
    }
    
    private void startListeningForTimers() {
        try {
            if (parentTimersRef == null) {
                Log.e(TAG, "Parent timers reference is null, cannot start listening");
                return;
            }
            
            parentTimersListener = new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    try {
                        Log.d(TAG, "Parent timers data changed");
                        
                        if (activeTimers == null) {
                            Log.w(TAG, "Active timers map is null, cannot update");
                            return;
                        }
                        
                        // Clear existing timers safely
                        activeTimers.clear();
                        
                        if (dataSnapshot.exists()) {
                            for (DataSnapshot timerSnapshot : dataSnapshot.getChildren()) {
                                try {
                                    Map<String, Object> timerData = (Map<String, Object>) timerSnapshot.getValue();
                                    if (timerData != null) {
                                        String deviceId = (String) timerData.get("deviceId");
                                        String deviceName = (String) timerData.get("deviceName");
                                        Long startTime = (Long) timerData.get("startTime");
                                        Long duration = (Long) timerData.get("duration");
                                        List<String> selectedApps = (List<String>) timerData.get("selectedApps");
                                        
                                        if (deviceId != null && startTime != null && duration != null && selectedApps != null) {
                                            TimerInfo timer = new TimerInfo(deviceId, deviceName, startTime, duration, selectedApps);
                                            
                                            // Restore timer state from Firebase if available
                                            Long totalElapsedTime = (Long) timerData.get("totalElapsedTime");
                                            if (totalElapsedTime != null) {
                                                timer.totalElapsedTime = totalElapsedTime;
                                            }
                                            
                                            Boolean isActive = (Boolean) timerData.get("isActive");
                                            if (isActive != null && isActive) {
                                                timer.setInteracting(true);
                                                String currentApp = (String) timerData.get("currentApp");
                                                if (currentApp != null) {
                                                    timer.currentApp = currentApp;
                                                }
                                            }
                                            
                                            activeTimers.put(deviceId, timer);
                                            
                                            Log.d(TAG, "Added timer for device: " + deviceName + " (" + deviceId + ")");
                                            Log.d(TAG, "  Duration: " + formatTime(duration));
                                            Log.d(TAG, "  Selected apps: " + selectedApps.size() + " apps");
                                            for (String app : selectedApps) {
                                                Log.d(TAG, "    - " + app);
                                            }
                                            Log.d(TAG, "  Time left: " + formatTime(timer.getTimeLeft()));
                                            Log.d(TAG, "  Total used: " + formatTime(timer.getTotalUsedTime()));
                                            Log.d(TAG, "  Currently active: " + timer.isActive);
                                        } else {
                                            Log.w(TAG, "Incomplete timer data for snapshot: " + timerSnapshot.getKey());
                                            Log.w(TAG, "  deviceId: " + deviceId);
                                            Log.w(TAG, "  startTime: " + startTime);
                                            Log.w(TAG, "  duration: " + duration);
                                            Log.w(TAG, "  selectedApps: " + (selectedApps != null ? selectedApps.size() + " apps" : "null"));
                                        }
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error processing timer snapshot: " + e.getMessage());
                                }
                            }
                        } else {
                            Log.d(TAG, "No parent timers found");
                        }
                        
                        // Update active timers immediately
                        updateActiveTimers();
                        
                        // Verify timer functionality for debugging
                        verifyTimerFunctionality();
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error in timer data change listener: " + e.getMessage());
                    }
                }
                
                @Override
                public void onCancelled(DatabaseError databaseError) {
                    Log.e(TAG, "Error listening for parent timers: " + databaseError.getMessage());
                    // Don't crash, just log the error
                }
            };
            
            parentTimersRef.addValueEventListener(parentTimersListener);
            Log.d(TAG, "Started listening for parent timers");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting timer listener: " + e.getMessage());
        }
    }
    
    private void startTimerUpdateLoop() {
        Runnable updateRunnable = new Runnable() {
            @Override
            public void run() {
                updateActiveTimers();
                // 🔧 ANTI-FLUCTUATION: Stable update frequency to prevent conflicts
                timerHandler.postDelayed(this, UPDATE_INTERVAL); // Update every 5 seconds to reduce conflicts
            }
        };
        
        timerHandler.post(updateRunnable);
    }
    
    private void updateActiveTimers() {
        try {
            if (activeTimers == null || activeTimersRef == null) {
                Log.w(TAG, "Active timers or Firebase reference is null, skipping update");
                return;
            }
            
            // SIMPLE RULE: Timer counts down when using monitored apps
            // Timer starts when user opens monitored app
            // Timer stops when user leaves monitored app
            
            for (Map.Entry<String, TimerInfo> entry : activeTimers.entrySet()) {
                String deviceId = entry.getKey();
                TimerInfo timer = entry.getValue();
                
                if (deviceId == null || timer == null) {
                    Log.w(TAG, "Null deviceId or timer, skipping");
                    continue;
                }
                
                try {
                    // Check if parent has cleared this timer before updating Firebase
                    checkClearedFlagAndUpdate(deviceId, timer);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error updating timer for device " + deviceId + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error updating active timers: " + e.getMessage());
        }
    }
    
    private void checkClearedFlagAndUpdate(String deviceId, TimerInfo timer) {
        DatabaseReference flagsRef = FirebaseDatabase.getInstance().getReference()
                .child("parent_cleared_flags").child(deviceId);
                
        flagsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot flagSnapshot) {
                if (flagSnapshot.exists()) {
                    try {
                        Map<String, Object> flagData = (Map<String, Object>) flagSnapshot.getValue();
                        if (flagData != null && flagData.containsKey("clearedAt")) {
                            Object clearedAtObj = flagData.get("clearedAt");
                            if (clearedAtObj instanceof Long) {
                                long clearedAt = (Long) clearedAtObj;
                                long currentTime = System.currentTimeMillis();
                                long fiveMinutesMs = 5 * 60 * 1000L; // 5 minutes
                                
                                if (currentTime - clearedAt < fiveMinutesMs) {
                                    Log.d(TAG, "🛑 Parent recently cleared timer for device " + deviceId + ", removing from LiveTimerService");
                                    // Remove this timer from tracking
                                    activeTimers.remove(deviceId);
                                    return; // Don't update Firebase
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error checking cleared flag in LiveTimerService: " + e.getMessage());
                    }
                }
                
                // No recent clear flag - proceed with normal Firebase update
                updateTimerDataToFirebase(deviceId, timer);
            }
            
            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error reading cleared flags in LiveTimerService: " + databaseError.getMessage());
                // Don't update on error to be safe
            }
        });
    }
    
    private void updateTimerDataToFirebase(String deviceId, TimerInfo timer) {
        try {
            Map<String, Object> activeTimerData = new HashMap<>();
            activeTimerData.put("deviceId", timer.deviceId != null ? timer.deviceId : "");
            activeTimerData.put("deviceName", timer.deviceName != null ? timer.deviceName : "Unknown");
            activeTimerData.put("startTime", timer.startTime);
            activeTimerData.put("duration", timer.duration);
            activeTimerData.put("timeLeft", timer.getTimeLeft());
            activeTimerData.put("isActive", timer.isActive);
            activeTimerData.put("isExpired", timer.isExpired());
            activeTimerData.put("currentApp", timer.currentApp != null ? timer.currentApp : "");
            activeTimerData.put("selectedApps", timer.selectedApps != null ? timer.selectedApps : new ArrayList<>());
            activeTimerData.put("lastUpdate", System.currentTimeMillis());
            activeTimerData.put("statusText", timer.getStatusText());
            activeTimerData.put("totalElapsedTime", timer.getTotalUsedTime());
            
            // Simple logging
            if (timer.isActive) {
                Log.d(TAG, "▶️ RUNNING: " + timer.deviceName + " - " + formatTime(timer.getTimeLeft()) + " left");
            } else {
                Log.d(TAG, "⏹️ STOPPED: " + timer.deviceName + " - " + formatTime(timer.getTimeLeft()) + " left");
            }
            
            // Set data with completion listener to handle errors
            activeTimersRef.child(deviceId).setValue(activeTimerData)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to update timer for device " + deviceId + ": " + e.getMessage());
                });
                
            // Also update parent_timers with current state for persistence
            parentTimersRef.orderByChild("deviceId").equalTo(deviceId).addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                        Map<String, Object> parentTimerData = new HashMap<>();
                        parentTimerData.put("totalElapsedTime", timer.getTotalUsedTime());
                        parentTimerData.put("isActive", timer.isActive);
                        parentTimerData.put("currentApp", timer.currentApp);
                        snapshot.getRef().updateChildren(parentTimerData);
                    }
                }
                
                @Override
                public void onCancelled(DatabaseError databaseError) {
                    Log.e(TAG, "Error updating parent timer: " + databaseError.getMessage());
                }
            });
            
            // Remove expired timers
            if (timer.isExpired()) {
                Log.d(TAG, "Timer expired for device: " + timer.deviceName);
                removeExpiredTimer(deviceId);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error updating timer data for device " + deviceId + ": " + e.getMessage());
        }
    }
    
    private void removeExpiredTimer(String deviceId) {
        // Clean up activation handler before removing timer
        TimerInfo timerToRemove = activeTimers.get(deviceId);
        
        // Remove from parent_timers
        parentTimersRef.orderByChild("deviceId").equalTo(deviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                    snapshot.getRef().removeValue();
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error removing expired timer: " + databaseError.getMessage());
            }
        });

        // Remove from active_timers
        activeTimersRef.child(deviceId).removeValue();
        
        // Remove from local map
        activeTimers.remove(deviceId);
        
        Log.d(TAG, "Expired timer removed for device: " + deviceId);
    }

    private void stopAllTimers() {
        try {
            if (activeTimers == null) {
                return;
            }

            // Simple: Stop all timers
            currentForegroundApp = "";
            previousForegroundApp = "";
            
            Log.d(TAG, "🏠 Stopping all timers");
            
            // Stop all active timers
            for (TimerInfo timer : activeTimers.values()) {
                if (timer != null && timer.isActive) {
                    timer.setInteracting(false);
                    timer.currentApp = "";
                    Log.d(TAG, "⏹️ Timer STOPPED: " + timer.deviceName);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping timers: " + e.getMessage());
        }
    }
    
    private String formatTime(long timeMillis) {
        if (timeMillis <= 0) return "00:00:00";
        
        long hours = timeMillis / (1000 * 60 * 60);
        long minutes = (timeMillis % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (timeMillis % (1000 * 60)) / 1000;
        
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "LiveTimerService being destroyed");
        
        try {
            // 🔧 CRITICAL: Stop foreground service properly
            stopForeground(true);
            Log.d(TAG, "✅ Foreground service stopped properly");
            
            // Clean up timer handler
            if (timerHandler != null) {
                timerHandler.removeCallbacksAndMessages(null);
                timerHandler = null;
            }
            
            // Simple timer handler cleanup
            
            // Clean up active timers
            if (activeTimers != null) {
                for (TimerInfo timer : activeTimers.values()) {
                    if (timer != null) {
                        // Clean up timer state
                        timer.setInteracting(false);
                    }
                }
                activeTimers.clear();
                activeTimers = null;
            }
            
                    // Unregister broadcast receiver
        if (appForegroundReceiver != null) {
            try {
                unregisterReceiver(appForegroundReceiver);
                Log.d(TAG, "App foreground receiver unregistered");
            } catch (Exception e) {
                Log.d(TAG, "App foreground receiver was not registered: " + e.getMessage());
            }
            appForegroundReceiver = null;
        }
            
            // Remove Firebase listeners
            if (parentTimersRef != null && parentTimersListener != null) {
                try {
                    parentTimersRef.removeEventListener(parentTimersListener);
                    Log.d(TAG, "Parent timers listener removed");
                } catch (Exception e) {
                    Log.e(TAG, "Error removing Firebase listener: " + e.getMessage());
                }
            }
            
            // Clear Firebase references
            parentTimersListener = null;
            parentTimersRef = null;
            activeTimersRef = null;
            
            Log.d(TAG, "LiveTimerService cleanup completed");
            
        } catch (Exception e) {
            Log.e(TAG, "Error during service cleanup: " + e.getMessage());
        }
    }
    
    // Helper method to verify timer functionality
    private void verifyTimerFunctionality() {
        try {
            Log.d(TAG, "=== TIMER FUNCTIONALITY VERIFICATION ===");
            Log.d(TAG, "Active timers count: " + (activeTimers != null ? activeTimers.size() : 0));
            
            if (activeTimers != null && !activeTimers.isEmpty()) {
                for (Map.Entry<String, TimerInfo> entry : activeTimers.entrySet()) {
                    TimerInfo timer = entry.getValue();
                    Log.d(TAG, "Timer " + timer.deviceName + ":");
                    Log.d(TAG, "  Status: " + timer.getStatusText());
                    Log.d(TAG, "  Time left: " + formatTime(timer.getTimeLeft()));
                    Log.d(TAG, "  Is active: " + timer.isActive);
                    Log.d(TAG, "  Current app: " + timer.currentApp);
                    Log.d(TAG, "  Total used: " + formatTime(timer.getTotalUsedTime()));
                    Log.d(TAG, "  Selected apps: " + timer.selectedApps.size());
                    Log.d(TAG, "  Last activation: " + (timer.lastActivationTime > 0 ? formatTime(System.currentTimeMillis() - timer.lastActivationTime) + " ago" : "never"));
                }
            } else {
                Log.d(TAG, "No active timers found");
            }
            
            // Check if broadcast receiver is registered
            Log.d(TAG, "Foreground receiver registered: " + (appForegroundReceiver != null));
            
            // Check if Firebase references are valid
            Log.d(TAG, "Firebase references valid: " + (parentTimersRef != null && activeTimersRef != null));
            
            Log.d(TAG, "=== END VERIFICATION ===");
            
        } catch (Exception e) {
            Log.e(TAG, "Error during timer verification: " + e.getMessage());
        }
    }
    
    /**
     * 🔧 FOREGROUND SERVICE METHODS - CRASH PREVENTION
     */
    
    /**
     * Create notification channel for foreground service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Live Timer Service",
                NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Monitors app usage for parental timers");
            serviceChannel.setShowBadge(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "✅ Notification channel created");
            }
        }
    }
    
    /**
     * Create notification for foreground service
     */
    private Notification createNotification() {
        try {
            Intent notificationIntent = new Intent(this, ChildDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Sentinel Timer Service")
                .setContentText("Monitoring app usage for parental controls")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
                
        } catch (Exception e) {
            Log.e(TAG, "Error creating notification: " + e.getMessage());
            // Return basic notification as fallback
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Timer Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setSilent(true)
                .build();
        }
    }
} 
