package com.example.master2;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SmartTimerService extends Service {

    private static final String TAG = "SmartTimerService";
    private static final String CHANNEL_ID = "smart_timer_channel";
    private static final int NOTIFICATION_ID = 123; // Unique ID for the foreground notification

    private DatabaseReference parentTimerRef; // To listen for parent's timer commands
    private DatabaseReference childActiveTimerRef; // To update child's active timer state

    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private Runnable timerRunnable;

    private long timerDurationMs = 0; // Total duration set by parent
    private long timerRemainingMs = 0; // Remaining time
    private long lastCheckedTime = 0; // For pausing/resuming logic

    private List<String> monitoredPackages = new ArrayList<>();
    private String childDeviceId;
    private String currentMonitoredApp = "None";

    private boolean isTimerActive = false; // Whether the parent has set an active timer
    private boolean isTimerRunning = false; // Whether the timer is currently counting down (app in foreground)
    private boolean isTimerDoneForDay = false; // NEW: Whether timer reached 00:00 but still active
    
    // Timer stability variables to prevent fluctuations - ENHANCED
    private String lastDetectedApp = "unknown";
    private int appDetectionStability = 0; // Counter for stable app detection
    private static final int STABILITY_THRESHOLD = 3; // INCREASED: Require 3 consistent detections
    private long lastFirebaseUpdate = 0;
    private static final long FIREBASE_UPDATE_INTERVAL = 3000; // INCREASED: Update Firebase every 3 seconds
    
    // 🔧 FLUCTUATION FIX: Enhanced timing control
    private long lastTimerUpdate = 0;
    private static final long TIMER_UPDATE_INTERVAL = 1000; // Update timer every 1 second exactly
    private boolean timerUpdateInProgress = false; // Prevent concurrent updates

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🚀 SmartTimerService.onCreate() - Service is starting");
        createNotificationChannel();
        
        // 🔧 CHECK CRITICAL PERMISSIONS ON STARTUP
        checkRequiredPermissions();
        
        // Start as foreground service immediately
        startForeground(NOTIFICATION_ID, buildNotification("Timer service active", "Monitoring apps..."));
        Log.d(TAG, "📱 Foreground service started with notification");

        // Get childDeviceId from SessionManager
        SessionManager sessionManager = new SessionManager(this);
        childDeviceId = sessionManager.getChildDeviceId();
        Log.d(TAG, "🆔 Retrieved childDeviceId: " + (childDeviceId != null ? childDeviceId : "NULL"));

        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            // Initialize Firebase references only if childDeviceId is available
            parentTimerRef = FirebaseDatabase.getInstance().getReference("parent_timers");
            childActiveTimerRef = FirebaseDatabase.getInstance().getReference("active_timers").child(childDeviceId);
            Log.d(TAG, "🔥 Firebase references initialized:");
            Log.d(TAG, "   📊 parentTimerRef: parent_timers");
            Log.d(TAG, "   📱 childActiveTimerRef: active_timers/" + childDeviceId);
            Log.d(TAG, "⚠️ Note: SmartTimerService should only be started by parent when setting timers");
            
            // Check for fresh connection and clear timer data if needed
            FreshConnectionManager freshManager = new FreshConnectionManager(this, childDeviceId);
            if (freshManager.isFreshlyConnected()) {
                Log.d(TAG, "🧹 Fresh connection detected - clearing timer state");
                clearTimerState();
            }
            
            startListeningForParentTimerCommands();
        } else {
            Log.e(TAG, "❌ Child Device ID is null or empty. SmartTimerService cannot start listening to Firebase.");
            stopSelf(); // Cannot function without a device ID
        }
    }
    
    /**
     * 🔧 PERMISSION CHECK: Verify required permissions are granted
     */
    private void checkRequiredPermissions() {
        Log.d(TAG, "🔐 Checking required permissions...");
        
        // Check Usage Stats permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
                long now = System.currentTimeMillis();
                long oneMinuteAgo = now - TimeUnit.MINUTES.toMillis(1);
                
                if (usm != null) {
                    UsageEvents events = usm.queryEvents(oneMinuteAgo, now);
                    if (events != null && events.hasNextEvent()) {
                        Log.d(TAG, "✅ Usage Stats permission is GRANTED");
                    } else {
                        Log.w(TAG, "⚠️ Usage Stats permission might be DENIED or no recent app usage");
                    }
                } else {
                    Log.e(TAG, "❌ UsageStatsManager is null");
                }
            } catch (SecurityException se) {
                Log.e(TAG, "❌ Usage Stats permission is DENIED!");
                Log.e(TAG, "   Please go to: Settings > Apps > Special access > Usage access");
                Log.e(TAG, "   Find this app and enable usage access");
            } catch (Exception e) {
                Log.e(TAG, "Error checking Usage Stats permission: " + e.getMessage());
            }
        }
        
        // Check if we can access ActivityManager
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();
                if (processes != null && !processes.isEmpty()) {
                    Log.d(TAG, "✅ ActivityManager access is working (" + processes.size() + " processes)");
                } else {
                    Log.w(TAG, "⚠️ ActivityManager returned empty process list");
                }
            } else {
                Log.e(TAG, "❌ ActivityManager is null");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking ActivityManager: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "SmartTimerService started.");
        // We handle start/stop logic based on Firebase commands, so START_STICKY is appropriate
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Smart Timer Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Notifications for Smart Timer monitoring.");
            serviceChannel.enableLights(true);
            serviceChannel.setLightColor(Color.GREEN);
            serviceChannel.enableVibration(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
                Log.d(TAG, "Notification channel created.");
            } else {
                Log.e(TAG, "NotificationManager is null, channel not created.");
            }
        }
    }

    private Notification buildNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, ChildDashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info) // Replaced with a standard Android icon
                .setContentIntent(pendingIntent)
                .setOngoing(true) // Makes the notification non-dismissible
                .build();
    }

    private void startListeningForParentTimerCommands() {
        Log.d(TAG, "🔄 Setting up Firebase listener for parent timer commands");
        Log.d(TAG, "   🎯 Listening to: parent_timers where deviceId = " + childDeviceId);
        
        // Listener for the parent's timer settings specific to this child
        parentTimerRef.orderByChild("deviceId").equalTo(childDeviceId)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        Log.d(TAG, "🔥 FIREBASE DATA CHANGE DETECTED!");
                        Log.d(TAG, "   📊 Snapshot exists: " + dataSnapshot.exists());
                        Log.d(TAG, "   📋 Children count: " + dataSnapshot.getChildrenCount());
                        
                        if (dataSnapshot.exists()) {
                            // Find the latest active timer for this device
                            Map<String, Object> latestTimerData = null;
                            long latestTimestamp = 0;

                            for (DataSnapshot timerSnap : dataSnapshot.getChildren()) {
                                Log.d(TAG, "Processing timerSnap: " + timerSnap.getKey());
                                Log.d(TAG, "isActive for timerSnap: " + timerSnap.child("isActive").getValue());
                                Log.d(TAG, "selectedApps for timerSnap: " + timerSnap.child("selectedApps").getValue());

                                if (timerSnap.child("isActive").exists() && Boolean.TRUE.equals(timerSnap.child("isActive").getValue(Boolean.class))) {
                                    Long timestamp = timerSnap.child("timestamp").getValue(Long.class);
                                    if (timestamp != null && timestamp > latestTimestamp) {
                                        latestTimestamp = timestamp;
                                        latestTimerData = (Map<String, Object>) timerSnap.getValue();
                                        Log.d(TAG, "Selected latest active timer. Key: " + timerSnap.getKey());
                                    }
                                }
                            }

                            if (latestTimerData != null) {
                                Log.d(TAG, "✅ TIMER FOUND! Final latestTimerData selected:");
                                Log.d(TAG, "   📱 selectedApps: " + latestTimerData.get("selectedApps"));
                                Log.d(TAG, "   ⏱️ duration: " + latestTimerData.get("duration") + "ms");
                                Log.d(TAG, "   🎯 deviceId: " + latestTimerData.get("deviceId"));
                                
                                List<String> newMonitoredPackages = (List<String>) latestTimerData.get("selectedApps");
                                Long durationObj = (Long) latestTimerData.get("duration");
                                long newTimerDuration = durationObj != null ? durationObj : 0;

                                Log.d(TAG, "🔄 Updating monitored packages from " + monitoredPackages.size() + " to " + (newMonitoredPackages != null ? newMonitoredPackages.size() : 0));
                                
                                monitoredPackages.clear();
                                if (newMonitoredPackages != null) {
                                    monitoredPackages.addAll(newMonitoredPackages);
                                    Log.d(TAG, "📋 Now monitoring " + monitoredPackages.size() + " apps: " + monitoredPackages.toString());
                                } else {
                                    Log.w(TAG, "⚠️ No apps to monitor - selectedApps is null");
                                }

                                // Check if the duration itself has changed or if it's a new timer being set
                                if (!isTimerActive || newTimerDuration != timerDurationMs) {
                                    Log.d(TAG, "🆕 NEW TIMER SETUP:");
                                    Log.d(TAG, "   Was active: " + isTimerActive + " | Old duration: " + timerDurationMs + "ms | New duration: " + newTimerDuration + "ms");
                                    timerDurationMs = newTimerDuration;
                                    
                                    // 🔧 PERSISTENCE FIX: Check if there's existing timer state to restore
                                    checkAndRestoreExistingTimerState();
                                    
                                    isTimerActive = true;
                                    isTimerRunning = false; // Not running initially, waiting for app to be in foreground
                                    Log.d(TAG, "🎯 Timer initialized: " + formatDuration(timerRemainingMs) + " total, waiting for monitored app");
                                } else {
                                    Log.d(TAG, "🔄 TIMER UPDATE: Existing timer app list updated. Maintaining current timer state.");
                                    Log.d(TAG, "   Current remaining: " + formatDuration(timerRemainingMs) + " | Running: " + isTimerRunning);
                                    // If only app list is updated, don't reset timerRemainingMs or isTimerRunning
                                    // These values will be preserved from the last active timer update
                                }

                                                                    
                                    // Monitor real-time for midnight reset
                                    startMidnightMonitoring();
                                    
                                    startMonitoringAndTimer();
                            } else {
                                Log.w(TAG, "❌ NO ACTIVE TIMER COMMAND FOUND!");
                                Log.w(TAG, "   Checked " + dataSnapshot.getChildrenCount() + " timer entries, none were active");
                                stopMonitoringAndTimer();
                            }
                        } else {
                            Log.w(TAG, "❌ NO PARENT TIMER DATA for device ID: " + childDeviceId);
                            Log.w(TAG, "     This means no timer has been set for this device");
                            
                            // Clean shutdown when no timer data exists
                            stopMonitoringAndTimer();
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e(TAG, "Firebase timer listener cancelled: " + databaseError.getMessage());
                        stopMonitoringAndTimer();
                    }
                });
    }

    /**
     * Real-time midnight detection for automatic timer reset
     * Monitors system clock and resets timer when new day starts (00:00)
     */
    private void startMidnightMonitoring() {
        Log.d(TAG, "� Starting real-time midnight monitoring for timer reset");
        
        // Create a handler that checks time every minute
        Handler midnightHandler = new Handler(Looper.getMainLooper());
        Runnable midnightChecker = new Runnable() {
            @Override
            public void run() {
                Calendar now = Calendar.getInstance();
                int currentHour = now.get(Calendar.HOUR_OF_DAY);
                int currentMinute = now.get(Calendar.MINUTE);
                
                // Check if it's midnight (00:00)
                if (currentHour == 0 && currentMinute == 0) {
                    if (isTimerActive && isTimerDoneForDay) {
                        Log.d(TAG, "🕛 MIDNIGHT DETECTED! Resetting timer for new day");
                        resetTimerForNewDay();
                    }
                }
                
                // Schedule next check in 60 seconds
                midnightHandler.postDelayed(this, 60000);
            }
        };
        
        // Start monitoring
        midnightHandler.post(midnightChecker);
    }
    
    /**
     * Reset timer for new day when midnight is detected
     */
    private void resetTimerForNewDay() {
        Log.d(TAG, "🌅 NEW DAY TIMER RESET!");
        Log.d(TAG, "   Resetting timer from 00:00 to original duration: " + formatDuration(timerDurationMs));
        
        // Reset timer to original duration
        timerRemainingMs = timerDurationMs;
        isTimerDoneForDay = false;
        isTimerRunning = false; // Will start when monitored app comes to foreground
        
        // Update Firebase with reset state
        updateActiveTimerInFirebase();
        
        // Update notification to show reset state
        updateNotification();
        
        Log.d(TAG, "✅ Timer successfully reset for new day - waiting for monitored app");
    }

    private void startMonitoringAndTimer() {
        if (timerRunnable == null) {
            timerRunnable = new Runnable() {
                @Override
                public void run() {
                    // 🔧 FLUCTUATION FIX: Prevent concurrent timer updates
                    if (timerUpdateInProgress) {
                        Log.d(TAG, "Timer update already in progress, skipping this cycle");
                        timerHandler.postDelayed(this, 1000);
                        return;
                    }
                    
                    timerUpdateInProgress = true;
                    
                    try {
                        String foregroundPackage = getForegroundAppPackage();
                        
                        // 🔧 ENHANCED: More stable app detection
                        if (foregroundPackage != null && foregroundPackage.equals(lastDetectedApp)) {
                            appDetectionStability++;
                        } else {
                            appDetectionStability = 0;
                            lastDetectedApp = foregroundPackage != null ? foregroundPackage : "unknown";
                        }
                        
                        // Only process timer changes if app detection is stable
                        boolean isMonitoredAppInForeground = foregroundPackage != null && monitoredPackages.contains(foregroundPackage);
                        boolean shouldChangeTimerState = appDetectionStability >= STABILITY_THRESHOLD;
                        
                        // 🔧 INTEGRITY CHECK: Validate timer state before changes
                        if (timerRemainingMs < 0) {
                            timerRemainingMs = 0;
                            Log.w(TAG, "Timer corruption detected and fixed - reset to 0");
                        }
                        
                        Log.d(TAG, "🎯 Timer Check - App: [" + foregroundPackage + "] Stability: " + appDetectionStability + "/" + STABILITY_THRESHOLD);
                        Log.d(TAG, "   Monitored: " + isMonitoredAppInForeground + " | Running: " + isTimerRunning + " | Should Change: " + shouldChangeTimerState);
                        
                        long currentTime = System.currentTimeMillis();
                        
                        if (isMonitoredAppInForeground && shouldChangeTimerState) {
                            if (!isTimerRunning) {
                                Log.d(TAG, "✅ Starting timer - monitored app detected");
                                isTimerRunning = true;
                                lastTimerUpdate = currentTime;
                            } else {
                                // 🔧 FLUCTUATION FIX: More precise timer calculation
                                long timeSinceLastUpdate = currentTime - lastTimerUpdate;
                                
                                // Only update if enough time has passed (prevents micro-fluctuations)
                                if (timeSinceLastUpdate >= TIMER_UPDATE_INTERVAL) {
                                    // Calculate actual seconds passed (round to nearest second)
                                    long secondsPassed = Math.round(timeSinceLastUpdate / 1000.0);
                                    long actualTimeToSubtract = secondsPassed * 1000;
                                    
                                    timerRemainingMs -= actualTimeToSubtract;
                                    lastTimerUpdate = currentTime;
                                    
                                    Log.d(TAG, "⏰ Timer Updated - Remaining: " + formatDuration(timerRemainingMs) + " (-" + secondsPassed + "s)");
                                }
                            }
                            
                            // Check if timer finished
                            if (timerRemainingMs <= 0) {
                                Log.d(TAG, "🔥 DAILY TIMER FINISHED! Daily limit reached - Timer done for day");
                                timerRemainingMs = 0;
                                isTimerRunning = false;
                                isTimerDoneForDay = true; // NEW: Mark as done for day, NOT expired
                                updateActiveTimerInFirebase();
                                
                                // 🎯 NEW PERSISTENT TIMER LOGIC: Never auto-remove timer
                                Log.d(TAG, "⏰ Timer shows 00:00 but stays active - will reset at midnight");
                                Log.d(TAG, "   📅 Timer persists until manually cleared by parent");
                                // Timer stays active, just done for the current day
                                // Real-time midnight detection will reset it tomorrow
                                return;
                            }
                            
                        } else if (!isMonitoredAppInForeground && shouldChangeTimerState) {
                            if (isTimerRunning) {
                                Log.d(TAG, "⏸️ Pausing timer - no monitored app in foreground");
                                isTimerRunning = false;
                                // Keep lastTimerUpdate unchanged when pausing
                            }
                        }
                        
                        // 🔧 FLUCTUATION FIX: Less frequent Firebase updates
                        if (currentTime - lastFirebaseUpdate >= FIREBASE_UPDATE_INTERVAL) {
                            updateActiveTimerInFirebase();
                            lastFirebaseUpdate = currentTime;
                        }
                        
                        updateNotification();
                        
                    } catch (Exception e) {
                        Log.e(TAG, "Error in timer runnable: " + e.getMessage());
                    } finally {
                        timerUpdateInProgress = false;
                        // Schedule next run
                        if (isTimerActive) {
                            timerHandler.postDelayed(this, 1000);
                        }
                    }
                }
            };
        }
        timerHandler.post(timerRunnable);
        Log.d(TAG, "Started usage monitoring and timer loop.");
    }

    /**
     * 🔧 PERSISTENCE FIX: Check and restore existing timer state from Firebase
     */
    private void checkAndRestoreExistingTimerState() {
        if (childActiveTimerRef == null) {
            Log.w(TAG, "Cannot restore timer state - childActiveTimerRef is null");
            timerRemainingMs = timerDurationMs; // Default to full duration
            return;
        }
        
        Log.d(TAG, "🔄 Checking for existing timer state in Firebase...");
        
        childActiveTimerRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        Long remainingTime = snapshot.child("remainingTime").getValue(Long.class);
                        Boolean wasRunning = snapshot.child("isTimerRunning").getValue(Boolean.class);
                        Boolean wasActive = snapshot.child("isTimerActive").getValue(Boolean.class);
                        Long lastUpdate = snapshot.child("timestamp").getValue(Long.class);
                        
                        if (remainingTime != null && remainingTime > 0 && Boolean.TRUE.equals(wasActive)) {
                            // Calculate time that might have passed since last update
                            long timeSinceLastUpdate = 0;
                            if (lastUpdate != null) {
                                timeSinceLastUpdate = System.currentTimeMillis() - lastUpdate;
                            }
                            
                            // If the timer was running and some time has passed, account for it
                            long adjustedRemainingTime = remainingTime;
                            if (Boolean.TRUE.equals(wasRunning) && timeSinceLastUpdate > 0) {
                                // Don't subtract more than what's remaining
                                adjustedRemainingTime = Math.max(0, remainingTime - timeSinceLastUpdate);
                            }
                            
                            timerRemainingMs = adjustedRemainingTime;
                            Log.d(TAG, "✅ RESTORED TIMER STATE:");
                            Log.d(TAG, "   🔄 Original remaining: " + formatDuration(remainingTime));
                            Log.d(TAG, "   ⏰ Time since update: " + formatDuration(timeSinceLastUpdate));
                            Log.d(TAG, "   📊 Adjusted remaining: " + formatDuration(timerRemainingMs));
                            Log.d(TAG, "   🏃 Was running: " + wasRunning);
                        } else {
                            timerRemainingMs = timerDurationMs; // Default to full duration
                            Log.d(TAG, "🆕 No valid existing timer state found - using full duration: " + formatDuration(timerRemainingMs));
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error restoring timer state: " + e.getMessage());
                        timerRemainingMs = timerDurationMs; // Default to full duration
                    }
                } else {
                    timerRemainingMs = timerDurationMs; // Default to full duration
                    Log.d(TAG, "🆕 No existing timer state found - using full duration: " + formatDuration(timerRemainingMs));
                }
            }
            
            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "❌ Failed to check existing timer state: " + error.getMessage());
                timerRemainingMs = timerDurationMs; // Default to full duration
            }
        });
    }
    
    private void clearTimerState() {
        Log.d(TAG, "🧹 Clearing all timer state for fresh connection");
        
        // Reset all timer variables
        isTimerActive = false;
        isTimerRunning = false;
        timerDurationMs = 0;
        timerRemainingMs = 0;
        lastCheckedTime = 0;
        currentMonitoredApp = "None";
        monitoredPackages.clear();
        
        // Stop any running timer
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerRunnable = null;
        }
        
        // Clear Firebase timer data
        if (childActiveTimerRef != null) {
            Map<String, Object> clearData = new HashMap<>();
            clearData.put("isActive", false);
            clearData.put("remainingTimeMs", 0);
            clearData.put("cleared_reason", "fresh_connection");
            clearData.put("cleared_time", System.currentTimeMillis());
            
            childActiveTimerRef.setValue(clearData)
                .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Timer state cleared in Firebase"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear timer state: " + e.getMessage()));
        }
        
        // Update notification to reflect cleared state
        updateNotification();
        
        Log.d(TAG, "✅ Timer state cleared successfully");
    }

    private void stopMonitoringAndTimer() {
        if (timerRunnable != null) {
            timerHandler.removeCallbacks(timerRunnable);
            timerRunnable = null;
            Log.d(TAG, "Stopped usage monitoring and timer loop.");
        }
        isTimerActive = false;
        isTimerRunning = false;
        timerRemainingMs = 0;
        updateActiveTimerInFirebase(); // Clear timer from Firebase
        updateNotification(); // Update notification to reflect no active timer
        stopForeground(true); // Remove notification and stop foreground
        stopSelf(); // Stop the service
    }

    private void updateActiveTimerInFirebase() {
        if (childActiveTimerRef != null) {
            // First check if parent has cleared this timer
            DatabaseReference flagsRef = FirebaseDatabase.getInstance().getReference()
                    .child("parent_cleared_flags").child(childDeviceId);
                    
            flagsRef.addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                @Override
                public void onDataChange(@NonNull com.google.firebase.database.DataSnapshot flagSnapshot) {
                    if (flagSnapshot.exists()) {
                        try {
                            java.util.Map<String, Object> flagData = (java.util.Map<String, Object>) flagSnapshot.getValue();
                            if (flagData != null && flagData.containsKey("clearedAt")) {
                                Object clearedAtObj = flagData.get("clearedAt");
                                if (clearedAtObj instanceof Long) {
                                    long clearedAt = (Long) clearedAtObj;
                                    long currentTime = System.currentTimeMillis();
                                    long fiveMinutesMs = 5 * 60 * 1000L; // 5 minutes
                                    
                                    if (currentTime - clearedAt < fiveMinutesMs) {
                                        Log.d(TAG, "🛑 Parent recently cleared timer, stopping SmartTimerService");
                                        // Stop the service completely when parent clears timer
                                        isTimerActive = false;
                                        isTimerRunning = false;
                                        stopSelf();
                                        return; // Don't update Firebase
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error checking cleared flag in SmartTimerService: " + e.getMessage());
                        }
                    }
                    
                    // No recent clear flag - proceed with normal Firebase update
                    performFirebaseUpdate();
                }
                
                @Override
                public void onCancelled(@NonNull com.google.firebase.database.DatabaseError databaseError) {
                    Log.e(TAG, "Error reading cleared flags in SmartTimerService: " + databaseError.getMessage());
                    // Proceed with update on error to be safe
                    performFirebaseUpdate();
                }
            });
        } else {
            Log.e(TAG, "childActiveTimerRef is null. Cannot update Firebase.");
        }
    }
    
    private void performFirebaseUpdate() {
        if (childActiveTimerRef != null) {
            // 🔧 FLUCTUATION FIX: Optimize Firebase updates
            Map<String, Object> updates = new HashMap<>();
            updates.put("remainingTime", timerRemainingMs);
            updates.put("isTimerRunning", isTimerRunning);
            updates.put("isTimerActive", isTimerActive);
            updates.put("isTimerDoneForDay", isTimerDoneForDay); // NEW: Timer done status
            updates.put("currentMonitoredApp", currentMonitoredApp != null ? currentMonitoredApp : "None");
            updates.put("timestamp", System.currentTimeMillis());
            updates.put("monitoredPackages", monitoredPackages != null ? monitoredPackages : new ArrayList<>());
            
            // 🔧 Add stability marker to prevent conflicts
            updates.put("sourceService", "SmartTimerService");
            updates.put("updateId", System.currentTimeMillis() + "_" + hashCode());

            childActiveTimerRef.setValue(updates)
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Timer state synchronized to Firebase"))
                    .addOnFailureListener(e -> Log.e(TAG, "❌ Firebase sync failed: " + e.getMessage()));
        } else {
            Log.e(TAG, "childActiveTimerRef is null. Cannot update Firebase.");
        }
    }

    private void updateNotification() {
        String title = "Smart Timer: " + formatDuration(timerRemainingMs);
        String content;
        
        if (isTimerActive) {
            if (isTimerDoneForDay) {
                // Timer is done for the day but still active
                content = "⏰ Timer done for today - Resets at midnight";
            } else {
                // Normal timer operation
                content = isTimerRunning ? "Monitoring " + currentMonitoredApp : "Paused (App in background)";
            }
        } else {
            content = "No active timer";
        }
        
        Notification notification = buildNotification(title, content);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    private String getForegroundAppPackage() {
        currentMonitoredApp = "None"; // Reset for each check
        String foregroundApp = "unknown";
        
        // 🔧 ENHANCED: Try multiple detection methods for better reliability
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            foregroundApp = getUsageStatsForegroundApp();
            
            // If UsageStats failed, try fallback
            if ("unknown".equals(foregroundApp)) {
                Log.d(TAG, "UsageStats failed, trying fallback method...");
                foregroundApp = getFallbackForegroundApp();
            }
        } else { 
            // For API < LOLLIPOP_MR1
            foregroundApp = getFallbackForegroundApp();
        }

        // 🎯 DEBUG: Always log detected app for troubleshooting
        Log.d(TAG, "🔍 Detected foreground app: " + foregroundApp);
        if (monitoredPackages != null && !monitoredPackages.isEmpty()) {
            Log.d(TAG, "📋 Monitored packages: " + monitoredPackages.toString());
            Log.d(TAG, "✅ Is monitored: " + monitoredPackages.contains(foregroundApp));
        } else {
            Log.w(TAG, "⚠️ No monitored packages configured!");
        }

        // Update currentMonitoredApp if the foreground app is one of our monitored ones
        if (monitoredPackages.contains(foregroundApp)) {
            try {
                currentMonitoredApp = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(foregroundApp, 0)).toString();
                Log.d(TAG, "📱 Monitored app detected: " + currentMonitoredApp + " (" + foregroundApp + ")");
            } catch (Exception e) {
                currentMonitoredApp = foregroundApp;
                Log.e(TAG, "Could not get application label for package: " + foregroundApp + ", error: " + e.getMessage());
            }
        }

        return foregroundApp;
    }
    
    private String getUsageStatsForegroundApp() {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                Log.e(TAG, "UsageStatsManager is null");
                return "unknown";
            }
            
            long now = System.currentTimeMillis();
            // Try different time windows for better detection
            long[] timeWindows = {
                TimeUnit.SECONDS.toMillis(10),   // 10 seconds
                TimeUnit.SECONDS.toMillis(30),   // 30 seconds  
                TimeUnit.MINUTES.toMillis(1),    // 1 minute
                TimeUnit.MINUTES.toMillis(5)     // 5 minutes
            };
            
            for (long timeWindow : timeWindows) {
                long startTime = now - timeWindow;
                
                try {
                    UsageEvents events = usm.queryEvents(startTime, now);
                    if (events == null) {
                        Log.w(TAG, "UsageEvents is null for time window: " + timeWindow + "ms");
                        continue;
                    }
                    
                    UsageEvents.Event event = new UsageEvents.Event();
                    String lastForegroundPackage = null;
                    long lastTimestamp = -1;

                    // Find the most recent ACTIVITY_RESUMED event
                    while (events.hasNextEvent()) {
                        events.getNextEvent(event);
                        if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                            if (event.getTimeStamp() > lastTimestamp) {
                                lastForegroundPackage = event.getPackageName();
                                lastTimestamp = event.getTimeStamp();
                            }
                        }
                    }
                    
                    if (lastForegroundPackage != null && !lastForegroundPackage.isEmpty()) {
                        Log.d(TAG, "✅ Found foreground app via UsageStats (window: " + timeWindow + "ms): " + lastForegroundPackage);
                        return lastForegroundPackage;
                    }
                } catch (SecurityException se) {
                    Log.e(TAG, "⚠️ Permission denied for UsageStats. Need to grant permission in Settings > Apps > Special access > Usage access");
                    break; // No point trying other time windows
                } catch (Exception e) {
                    Log.e(TAG, "Error querying UsageStats (window: " + timeWindow + "ms): " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in getUsageStatsForegroundApp: " + e.getMessage());
        }
        
        return "unknown";
    }
    
    private String getFallbackForegroundApp() {
        String foregroundApp = "unknown";
        
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                Log.e(TAG, "ActivityManager is null");
                return foregroundApp;
            }
            
            // 🔧 ENHANCED: Try multiple fallback methods
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Method 1: Running app processes
                List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = am.getRunningAppProcesses();
                if (runningAppProcesses != null && !runningAppProcesses.isEmpty()) {
                    for (ActivityManager.RunningAppProcessInfo processInfo : runningAppProcesses) {
                        if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                            // Extract package name from process name
                            String processName = processInfo.processName;
                            if (processName != null && !processName.isEmpty()) {
                                // If process name contains ':', take the part before it (main app process)
                                if (processName.contains(":")) {
                                    foregroundApp = processName.split(":")[0];
                                } else {
                                    foregroundApp = processName;
                                }
                                Log.d(TAG, "✅ Found foreground app via RunningAppProcesses: " + foregroundApp);
                                break;
                            }
                        }
                    }
                }
                
                // Method 2: If still unknown, try recent tasks (requires permission)
                if ("unknown".equals(foregroundApp)) {
                    try {
                        List<ActivityManager.RecentTaskInfo> recentTasks = am.getRecentTasks(1, ActivityManager.RECENT_WITH_EXCLUDED);
                        if (recentTasks != null && !recentTasks.isEmpty()) {
                            ActivityManager.RecentTaskInfo recentTask = recentTasks.get(0);
                            if (recentTask.baseIntent != null && recentTask.baseIntent.getComponent() != null) {
                                foregroundApp = recentTask.baseIntent.getComponent().getPackageName();
                                Log.d(TAG, "✅ Found foreground app via RecentTasks: " + foregroundApp);
                            }
                        }
                    } catch (SecurityException se) {
                        Log.w(TAG, "⚠️ No permission for recent tasks");
                    } catch (Exception e) {
                        Log.e(TAG, "Error getting recent tasks: " + e.getMessage());
                    }
                }
                
            } else {
                // For older Android versions - try running tasks
                try {
                    @SuppressWarnings("deprecation")
                    List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                    if (tasks != null && !tasks.isEmpty()) {
                        ComponentName topActivity = tasks.get(0).topActivity;
                        if (topActivity != null) {
                            foregroundApp = topActivity.getPackageName();
                            Log.d(TAG, "✅ Found foreground app via RunningTasks (legacy): " + foregroundApp);
                        }
                    }
                } catch (SecurityException se) {
                    Log.w(TAG, "⚠️ No permission for running tasks");
                } catch (Exception e) {
                    Log.e(TAG, "Error getting running tasks: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in getFallbackForegroundApp: " + e.getMessage());
        }
        
        // 🎯 Final verification
        if ("unknown".equals(foregroundApp)) {
            Log.w(TAG, "⚠️ ALL DETECTION METHODS FAILED - Need to check app permissions!");
            Log.w(TAG, "   Required permissions:");
            Log.w(TAG, "   1. Usage Access (Settings > Apps > Special access > Usage access)");
            Log.w(TAG, "   2. Accessibility Service (if available)");
            Log.w(TAG, "   3. Device admin permissions");
        }
        
        return foregroundApp;
    }

    private String formatDuration(long millis) {
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private void sendBlockCommandToLocalService(List<String> packagesToBlock) {
        if (packagesToBlock == null || packagesToBlock.isEmpty()) {
            Log.d(TAG, "No packages to block after timer completion.");
            return;
        }
        Intent blockIntent = new Intent(this, BlockService.class);
        blockIntent.setAction("ACTION_BLOCK_APPS");
        blockIntent.putStringArrayListExtra("PACKAGES_TO_BLOCK", new ArrayList<>(packagesToBlock));
        startService(blockIntent);
        Log.d(TAG, "Sent block command to BlockService for " + packagesToBlock.size() + " apps.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SmartTimerService destroyed.");
        stopMonitoringAndTimer(); // Ensure all monitoring stops
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
} 