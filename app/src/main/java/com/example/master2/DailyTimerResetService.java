package com.example.master2;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.example.master2.models.DailyUsageLimit;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

public class DailyTimerResetService extends Service {
    private static final String TAG = "DailyTimerResetService";
    private static final String CHANNEL_ID = "timer_reset_channel";
    private static final int NOTIFICATION_ID = 2001;
    
    private DatabaseReference parentTimersRef;
    private DatabaseReference activeTimersRef;
    private DatabaseReference dailyUsageLimitsRef;
    private DatabaseReference usageLimitersRef;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "DailyTimerResetService created - Enhanced version with battery optimization resistance");
        
        // Create notification channel and start as foreground service
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createServiceNotification());
        
        // Acquire wake lock to prevent doze mode interference
        acquireWakeLock();
        
        try {
            // Initialize Firebase references safely
            parentTimersRef = FirebaseDatabase.getInstance().getReference("parent_timers");
            activeTimersRef = FirebaseDatabase.getInstance().getReference("active_timers");
            dailyUsageLimitsRef = FirebaseDatabase.getInstance().getReference("daily_usage_limits");
            usageLimitersRef = FirebaseDatabase.getInstance().getReference("usage_limiters");
            
            // Schedule the next midnight reset with enhanced reliability
            scheduleReliableMidnightReset();
            
            // Check and reset timers for today if needed (async operations)
            checkAndResetExpiredTimers();
            
            // Check and reset daily usage limits that need reset (async operations)
            checkAndResetDailyUsageLimits();
            
            Log.d(TAG, "✅ Enhanced DailyTimerResetService initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing DailyTimerResetService: " + e.getMessage());
            // Don't crash, just log the error and continue
        }
    }
    
    /**
     * Create notification channel for foreground service
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Timer Reset Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Ensures automatic timer resets at midnight");
            channel.setSound(null, null);
            channel.enableVibration(false);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    /**
     * Create persistent notification for foreground service
     */
    private Notification createServiceNotification() {
        Intent intent = new Intent(this, ChildDashboardActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Timer Reset Service Active")
            .setContentText("Ensuring automatic midnight timer resets")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }
    
    /**
     * Acquire wake lock to prevent doze mode interference
     */
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Master2:TimerResetService"
                );
                wakeLock.acquire(24 * 60 * 60 * 1000L); // 24 hours max
                Log.d(TAG, "🔋 Wake lock acquired for timer service");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire wake lock: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Enhanced DailyTimerResetService started");
        
        if (intent != null) {
            String action = intent.getAction();
            if ("MIDNIGHT_RESET".equals(action)) {
                Log.d(TAG, "🌙 Performing scheduled midnight timer reset");
                performEnhancedMidnightReset();
            } else if ("ENHANCED_HOURLY_BACKUP_CHECK".equals(action)) {
                Log.d(TAG, "⏰ Performing enhanced hourly backup check");
                performEnhancedHourlyBackupCheck();
            } else if ("PRE_MIDNIGHT_CHECK".equals(action)) {
                Log.d(TAG, "🌅 Performing pre-midnight check (11:58 PM)");
                checkForMissedResets();
            } else if ("POST_MIDNIGHT_CHECK_1".equals(action)) {
                Log.d(TAG, "🌙 Performing post-midnight check 1 (12:02 AM)");
                performEnhancedMidnightReset();
            } else if ("POST_MIDNIGHT_CHECK_2".equals(action)) {
                Log.d(TAG, "🌙 Performing post-midnight check 2 (12:05 AM)");
                performEnhancedMidnightReset();
            } else if ("HOURLY_BACKUP_CHECK".equals(action)) {
                Log.d(TAG, "⏰ Performing legacy hourly backup check");
                performHourlyBackupCheck();
            } else {
                Log.d(TAG, "📅 Regular enhanced service start - checking for missed resets");
                // Check if we missed any resets since last run
                checkForMissedResets();
            }
        } else {
            Log.d(TAG, "📅 Enhanced service started without intent - checking for missed resets");
            checkForMissedResets();
        }
        
        return START_STICKY; // Keep service running even if killed
    }
    
    /**
     * Check if we missed any midnight resets and catch up
     */
    private void checkForMissedResets() {
        Log.d(TAG, "🔍 Checking for missed resets...");
        
        // Check and reset timers that might have been missed
        checkAndResetExpiredTimers();
        
        // Check and reset daily usage limits that might need reset
        checkAndResetDailyUsageLimits();
        
        // Ensure midnight reset is scheduled
        scheduleReliableMidnightReset();
    }
    
    /**
     * Backup check that runs every hour to ensure we don't miss resets
     */
    private void performHourlyBackupCheck() {
        Calendar now = Calendar.getInstance();
        int currentHour = now.get(Calendar.HOUR_OF_DAY);
        
        Log.d(TAG, "⏰ Hourly backup check at " + currentHour + ":00");
        
        // If it's between 00:00 and 01:00, perform reset check
        if (currentHour == 0) {
            Log.d(TAG, "🌙 Midnight detected during hourly check - performing reset");
            performMidnightReset();
        } else {
            // Just check for any missed resets
            Log.d(TAG, "🔄 Checking for any missed resets during hourly backup");
            checkAndResetExpiredTimers();
            checkAndResetDailyUsageLimits();
        }
    }
    
    /**
     * Enhanced hourly backup check with comprehensive logging
     */
    private void performEnhancedHourlyBackupCheck() {
        Calendar now = Calendar.getInstance();
        int currentHour = now.get(Calendar.HOUR_OF_DAY);
        
        Log.d(TAG, "⏰ Enhanced hourly backup check at " + currentHour + ":00");
        
        // If it's between 00:00 and 01:00, perform reset check
        if (currentHour == 0) {
            Log.d(TAG, "🌙 Midnight detected during enhanced hourly check - performing reset");
            performEnhancedMidnightReset();
        } else {
            // Just check for any missed resets
            Log.d(TAG, "🔄 Checking for any missed resets during enhanced hourly backup");
            checkAndResetExpiredTimers();
            checkAndResetDailyUsageLimits();
        }
        
        // Log system status for debugging
        logSystemStatus();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    /**
     * Enhanced midnight reset scheduling with multiple fallbacks
     */
    private void scheduleReliableMidnightReset() {
        Calendar midnight = Calendar.getInstance();
        midnight.add(Calendar.DAY_OF_YEAR, 1);
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);

        Intent intent = new Intent(this, DailyTimerResetService.class);
        intent.setAction("MIDNIGHT_RESET");
        
        PendingIntent pendingIntent = PendingIntent.getService(
            this, 
            1001, // Use unique ID to avoid conflicts
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            try {
                // Cancel any existing alarm first
                alarmManager.cancel(pendingIntent);
                
                // Try to use the most reliable method based on Android version
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    // For Android 6.0+ (API 23+), use setExactAndAllowWhileIdle for better doze mode compatibility
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        midnight.getTimeInMillis(),
                        pendingIntent
                    );
                    Log.d(TAG, "🕛 Enhanced midnight reset scheduled using setExactAndAllowWhileIdle for: " + midnight.getTime());
                } else {
                    // For older versions, use setExact
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        midnight.getTimeInMillis(),
                        pendingIntent
                    );
                    Log.d(TAG, "🕛 Midnight reset scheduled using setExact for: " + midnight.getTime());
                }
                
                // ADDITIONAL BACKUP: Schedule multiple backup checks around midnight
                scheduleBackupResetChecks();
                
                // HOURLY BACKUP: Also schedule a repeating alarm every hour to check if we missed midnight
                scheduleHourlyBackupCheck();
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to schedule enhanced midnight reset: " + e.getMessage());
                // Try fallback scheduling
                scheduleReliableFallbackAlarm(midnight, pendingIntent, alarmManager);
            }
        } else {
            Log.e(TAG, "❌ AlarmManager is null, cannot schedule midnight reset");
        }
    }
    
    /**
     * Fallback alarm scheduling if main method fails
     */
    private void scheduleReliableFallbackAlarm(Calendar midnight, PendingIntent pendingIntent, AlarmManager alarmManager) {
        try {
            // Use setRepeating as a fallback
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                midnight.getTimeInMillis(),
                24 * 60 * 60 * 1000, // 24 hours
                pendingIntent
            );
            Log.d(TAG, "🕛 Fallback midnight reset scheduled using setRepeating");
        } catch (Exception e) {
            Log.e(TAG, "❌ Even fallback alarm scheduling failed: " + e.getMessage());
        }
    }
    
    /**
     * Schedule multiple backup checks around midnight (11:58 PM, 12:02 AM, 12:05 AM)
     */
    private void scheduleBackupResetChecks() {
        try {
            // Check at 11:58 PM (2 minutes before midnight)
            scheduleBackupCheck(-2, "PRE_MIDNIGHT_CHECK");
            
            // Check at 12:02 AM (2 minutes after midnight)
            scheduleBackupCheck(2, "POST_MIDNIGHT_CHECK_1");
            
            // Check at 12:05 AM (5 minutes after midnight)
            scheduleBackupCheck(5, "POST_MIDNIGHT_CHECK_2");
            
            Log.d(TAG, "✅ Backup reset checks scheduled around midnight");
        } catch (Exception e) {
            Log.e(TAG, "Failed to schedule backup reset checks: " + e.getMessage());
        }
    }
    
    /**
     * Schedule a backup check at specific minutes relative to midnight
     */
    private void scheduleBackupCheck(int minutesFromMidnight, String action) {
        Calendar checkTime = Calendar.getInstance();
        checkTime.add(Calendar.DAY_OF_YEAR, 1);
        checkTime.set(Calendar.HOUR_OF_DAY, 0);
        checkTime.set(Calendar.MINUTE, 0);
        checkTime.set(Calendar.SECOND, 0);
        checkTime.set(Calendar.MILLISECOND, 0);
        checkTime.add(Calendar.MINUTE, minutesFromMidnight);
        
        Intent intent = new Intent(this, DailyTimerResetService.class);
        intent.setAction(action);
        
        PendingIntent pendingIntent = PendingIntent.getService(
            this,
            1001 + minutesFromMidnight + 10, // Unique ID for each backup
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    checkTime.getTimeInMillis(),
                    pendingIntent
                );
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    checkTime.getTimeInMillis(),
                    pendingIntent
                );
            }
        }
    }
    
    /**
     * Schedule an hourly backup check to ensure we don't miss midnight resets
     * This runs every hour and checks if any timers/limits need reset
     */
    private void scheduleHourlyBackupCheck() {
        Intent backupIntent = new Intent(this, DailyTimerResetService.class);
        backupIntent.setAction("HOURLY_BACKUP_CHECK");
        
        PendingIntent backupPendingIntent = PendingIntent.getService(
            this, 
            1002, // Different ID from midnight reset
            backupIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            try {
                // Cancel existing backup alarm
                alarmManager.cancel(backupPendingIntent);
                
                // Schedule to run every hour
                long nextHour = System.currentTimeMillis() + (60 * 60 * 1000); // 1 hour from now
                alarmManager.setInexactRepeating(
                    AlarmManager.RTC_WAKEUP,
                    nextHour,
                    AlarmManager.INTERVAL_HOUR, // Every hour
                    backupPendingIntent
                );
                
                Log.d(TAG, "⏰ Hourly backup check scheduled");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to schedule hourly backup check: " + e.getMessage());
            }
        }
    }

    private void checkAndResetExpiredTimers() {
        if (parentTimersRef == null) {
            Log.w(TAG, "⚠️ parentTimersRef is null, skipping timer reset check");
            return;
        }
        
        long currentTime = System.currentTimeMillis();
        
        try {
            parentTimersRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                for (DataSnapshot timerSnapshot : dataSnapshot.getChildren()) {
                    try {
                        Map<String, Object> timerData = (Map<String, Object>) timerSnapshot.getValue();
                        if (timerData == null) continue;
                        
                        Boolean isRecurring = (Boolean) timerData.get("isRecurring");
                        Boolean resetAtMidnight = (Boolean) timerData.get("resetAtMidnight");
                        Long fromDate = (Long) timerData.get("fromDate");
                        Long toDate = (Long) timerData.get("toDate");
                        Long dailyDurationMs = (Long) timerData.get("dailyDurationMs");
                        String deviceId = (String) timerData.get("deviceId");
                        
                        if (Boolean.TRUE.equals(isRecurring) && 
                            Boolean.TRUE.equals(resetAtMidnight) &&
                            fromDate != null && toDate != null && 
                            dailyDurationMs != null && deviceId != null) {
                            
                            // Check if current date is within the timer range
                            Calendar today = Calendar.getInstance();
                            today.set(Calendar.HOUR_OF_DAY, 0);
                            today.set(Calendar.MINUTE, 0);
                            today.set(Calendar.SECOND, 0);
                            today.set(Calendar.MILLISECOND, 0);
                            
                            long todayStart = today.getTimeInMillis();
                            
                            // Create calendar instances for from/to dates for better comparison
                            Calendar fromCal = Calendar.getInstance();
                            fromCal.setTimeInMillis(fromDate);
                            fromCal.set(Calendar.HOUR_OF_DAY, 0);
                            fromCal.set(Calendar.MINUTE, 0);
                            fromCal.set(Calendar.SECOND, 0);
                            fromCal.set(Calendar.MILLISECOND, 0);
                            
                            Calendar toCal = Calendar.getInstance();
                            toCal.setTimeInMillis(toDate);
                            toCal.set(Calendar.HOUR_OF_DAY, 23);
                            toCal.set(Calendar.MINUTE, 59);
                            toCal.set(Calendar.SECOND, 59);
                            toCal.set(Calendar.MILLISECOND, 999);
                            
                            long fromDateStart = fromCal.getTimeInMillis();
                            long toDateEnd = toCal.getTimeInMillis();
                            
                            Log.d(TAG, "⏰ Timer range check - Today: " + today.getTime() + 
                                  ", From: " + fromCal.getTime() + ", To: " + toCal.getTime());
                            
                            if (todayStart >= fromDateStart && todayStart <= toDateEnd) {
                                // Timer should be active today, reset it for daily use
                                Log.d(TAG, "✅ Timer is within multi-day range - resetting daily time for device: " + deviceId);
                                resetTimerForDevice(deviceId, dailyDurationMs, timerData);
                                Log.d(TAG, "🔄 Reset recurring timer for device: " + deviceId + 
                                      " (Daily Duration: " + (dailyDurationMs / (1000 * 60)) + " minutes)");
                            } else if (todayStart > toDateEnd) {
                                // Timer period has COMPLETELY ended (after last day), deactivate it
                                Log.d(TAG, "⏰ Multi-day timer period completely ended - deactivating timer: " + timerSnapshot.getKey());
                                
                                // Calculate how many days the timer was active
                                long durationDays = (toDateEnd - fromDateStart) / (24 * 60 * 60 * 1000) + 1;
                                
                                // 🔔 SEND END-DATE EXPIRY NOTIFICATION to parent
                                sendEndDateExpiryNotification(deviceId, timerSnapshot.getKey(), durationDays, toCal.getTime());
                                
                                deactivateExpiredTimer(timerSnapshot.getKey());
                                Log.d(TAG, "❌ Deactivated completed multi-day timer: " + timerSnapshot.getKey());
                            } else {
                                Log.d(TAG, "⏰ Timer not yet active (future date) for device: " + deviceId);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing timer: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error checking timers: " + databaseError.getMessage());
            }
            });
        } catch (Exception e) {
            Log.e(TAG, "❌ Error setting up timer reset check: " + e.getMessage());
        }
    }

    private void performMidnightReset() {
        Calendar now = Calendar.getInstance();
        Log.d(TAG, "🌙 PERFORMING MIDNIGHT RESET at " + now.getTime());
        
        try {
            // Check and reset all recurring timers
            Log.d(TAG, "🔄 Step 1: Resetting recurring timers...");
            checkAndResetExpiredTimers();
            
            // Reset daily usage limits for all devices
            Log.d(TAG, "📊 Step 2: Resetting daily usage limits...");
            resetAllDailyUsageLimits();
            
            Log.d(TAG, "✅ Midnight reset completed successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error during midnight reset: " + e.getMessage());
        } finally {
            // Always schedule the next midnight reset
            Log.d(TAG, "🕛 Step 3: Scheduling next midnight reset...");
            scheduleReliableMidnightReset();
            Log.d(TAG, "🌙 MIDNIGHT RESET PROCESS COMPLETED");
        }
    }

    private void resetTimerForDevice(String deviceId, Long dailyDurationMs, Map<String, Object> originalTimerData) {
        try {
            // Check for a cleared-by-parent tombstone. If present, skip resetting this device's timer.
            DatabaseReference flagsRef = FirebaseDatabase.getInstance().getReference()
                    .child("parent_cleared_flags").child(deviceId);

            flagsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot flagSnapshot) {
                    if (flagSnapshot.exists()) {
                        try {
                            Map<String, Object> flagData = (Map<String, Object>) flagSnapshot.getValue();
                            Log.d(TAG, "Found parent_cleared_flags for device " + deviceId + ": " + flagData);
                            
                            // Check if the cleared flag is recent (within last 7 days to be safe)
                            if (flagData != null && flagData.containsKey("clearedAt")) {
                                Object clearedAtObj = flagData.get("clearedAt");
                                if (clearedAtObj instanceof Long) {
                                    long clearedAt = (Long) clearedAtObj;
                                    long currentTime = System.currentTimeMillis();
                                    long sevenDaysMs = 7 * 24 * 60 * 60 * 1000L; // 7 days in milliseconds
                                    
                                    if (currentTime - clearedAt < sevenDaysMs) {
                                        Log.d(TAG, "Recent parent clear flag found for device " + deviceId + ", respecting tombstone");
                                        return; // Honor the tombstone - don't reset
                                    } else {
                                        Log.d(TAG, "Old parent clear flag found for device " + deviceId + ", proceeding with reset");
                                        // Remove old flag and proceed with reset
                                        flagsRef.removeValue();
                                    }
                                } else {
                                    Log.d(TAG, "Invalid clearedAt timestamp, respecting tombstone anyway");
                                    return; // Be safe and honor the tombstone
                                }
                            } else {
                                Log.d(TAG, "Missing clearedAt timestamp, respecting tombstone anyway");
                                return; // Be safe and honor the tombstone
                            }
                        } catch (Exception e) {
                            Log.d(TAG, "parent_cleared_flags exists for device " + deviceId + ", skipping reset due to parse error");
                            return; // Be safe and honor the tombstone even if we can't parse it
                        }
                    }

                    // No cleared flag found - proceed to create active timer
                    Map<String, Object> activeTimerData = new HashMap<>();
                    activeTimerData.put("remainingTime", dailyDurationMs);
                    activeTimerData.put("isTimerActive", true);
                    activeTimerData.put("isTimerRunning", false); // Will start when monitored app opens
                    activeTimerData.put("lastResetTime", System.currentTimeMillis());
                    activeTimerData.put("dailyAllowedTime", dailyDurationMs);
                    activeTimerData.put("selectedApps", originalTimerData.get("selectedApps"));
                    activeTimerData.put("currentMonitoredApp", null);

                    // Update active timer in Firebase
                    activeTimersRef.child(deviceId).setValue(activeTimerData)
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "Successfully reset timer for device: " + deviceId + 
                                      " with duration: " + (dailyDurationMs / (1000 * 60)) + " minutes");
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Failed to reset timer for device: " + deviceId + " - " + e.getMessage());
                            });
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "Error reading parent_cleared_flags for device " + deviceId + ": " + databaseError.getMessage());
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error in resetTimerForDevice: " + e.getMessage());
        }
    }

    private void deactivateExpiredTimer(String timerKey) {
        parentTimersRef.child(timerKey).child("isActive").setValue(false)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Successfully deactivated expired timer: " + timerKey);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to deactivate timer: " + timerKey + " - " + e.getMessage());
                });
    }
    
    /**
     * 🔔 Send notification to parent when timer date range has expired
     * This is the "Timer expired for X days" notification
     */
    private void sendEndDateExpiryNotification(String deviceId, String timerKey, long durationDays, Date endDate) {
        try {
            Log.d(TAG, "🔔 Sending end-date expiry notification for timer: " + timerKey);
            
            // Create notification data in Firebase for parent app to read
            DatabaseReference expiryNotifRef = FirebaseDatabase.getInstance()
                .getReference("timer_expiry_notifications")
                .child(deviceId)
                .child(timerKey);
            
            SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
            
            Map<String, Object> notificationData = new HashMap<>();
            notificationData.put("type", "timer_date_range_expired");
            notificationData.put("deviceId", deviceId);
            notificationData.put("timerKey", timerKey);
            notificationData.put("durationDays", durationDays);
            notificationData.put("endDate", dateFormat.format(endDate));
            notificationData.put("timestamp", System.currentTimeMillis());
            notificationData.put("read", false);
            notificationData.put("message", "Timer expired after " + durationDays + " days (ended: " + dateFormat.format(endDate) + ")");
            notificationData.put("title", "⏰ Timer Period Completed");
            
            expiryNotifRef.setValue(notificationData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "🔔 End-date expiry notification saved for device: " + deviceId);
                    Log.d(TAG, "🔔 Timer was active for " + durationDays + " days");
                    
                    // Also show local notification if we're on a parent device
                    showLocalExpiryNotification(deviceId, durationDays, endDate);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to save expiry notification: " + e.getMessage());
                });
                
        } catch (Exception e) {
            Log.e(TAG, "❌ Error sending expiry notification: " + e.getMessage());
        }
    }
    
    /**
     * 📱 Show local notification about timer expiry
     */
    private void showLocalExpiryNotification(String deviceId, long durationDays, Date endDate) {
        try {
            // Check if we're on a parent device
            SessionManager sessionManager = new SessionManager(this);
            if (!"parent".equals(sessionManager.getUserType())) {
                return; // Only show notifications on parent devices
            }
            
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) return;
            
            // Create notification channel if needed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    "timer_expiry_channel",
                    "Timer Expiry Notifications",
                    NotificationManager.IMPORTANCE_HIGH
                );
                channel.setDescription("Notifications when timer date ranges expire");
                notificationManager.createNotificationChannel(channel);
            }
            
            SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd", Locale.getDefault());
            
            // Build notification
            Intent intent = new Intent(this, ParentDashboardActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            Notification notification = new NotificationCompat.Builder(this, "timer_expiry_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⏰ Timer Period Completed!")
                .setContentText("Timer was active for " + durationDays + " days (ended: " + dateFormat.format(endDate) + ")")
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText("Your app usage timer for a child device has completed its scheduled period.\n\n" +
                            "📅 Duration: " + durationDays + " days\n" +
                            "🏁 End Date: " + dateFormat.format(endDate) + "\n\n" +
                            "Tap to set a new timer or extend the existing one."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build();
            
            notificationManager.notify((int) System.currentTimeMillis() % 10000, notification);
            Log.d(TAG, "📱 Local expiry notification shown");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing local notification: " + e.getMessage());
        }
    }

    /**
     * Check and reset daily usage limits for all devices that need reset with enhanced 7-day rolling window
     */
    private void checkAndResetDailyUsageLimits() {
        if (dailyUsageLimitsRef == null) {
            Log.w(TAG, "⚠️ dailyUsageLimitsRef is null, skipping usage limits reset check");
            return;
        }
        
        Log.d(TAG, "🔍 Checking daily usage limits for reset with enhanced 7-day rolling window...");
        
        try {
            dailyUsageLimitsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                int devicesChecked = 0;
                int devicesReset = 0;
                int devicesEnhanced = 0;
                
                for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                    try {
                        String deviceId = deviceSnapshot.getKey();
                        DailyUsageLimit usageLimit = deviceSnapshot.getValue(DailyUsageLimit.class);
                        
                        if (usageLimit != null && deviceId != null) {
                            devicesChecked++;
                            
                            // Check if this device's limits need reset
                            if (usageLimit.needsReset()) {
                                Log.d(TAG, "🔄 Resetting daily usage limits for device: " + deviceId + " (" + usageLimit.deviceName + ") with 7-day rolling window");
                                
                                // Enhanced reset with 7-day rolling window
                                usageLimit.resetDailyUsage();
                                
                                // Sync current usage data to ensure consistency
                                usageLimit.syncCurrentUsageWithToday();
                                
                                // Save back to Firebase
                                dailyUsageLimitsRef.child(deviceId).setValue(usageLimit)
                                        .addOnSuccessListener(aVoid -> {
                                            Log.d(TAG, "✅ Successfully reset daily usage limits for device: " + deviceId + " with enhanced 7-day tracking");
                                        })
                                        .addOnFailureListener(e -> {
                                            Log.e(TAG, "❌ Failed to reset daily usage limits for device: " + deviceId + " - " + e.getMessage());
                                        });
                                
                                devicesReset++;
                                devicesEnhanced++;
                            } else {
                                Log.d(TAG, "✅ Device " + deviceId + " (" + usageLimit.deviceName + ") does not need reset - 7-day data maintained");
                                
                                // Even if no reset needed, sync current usage to ensure data consistency
                                usageLimit.syncCurrentUsageWithToday();
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing daily usage limits for device: " + e.getMessage());
                    }
                }
                
                Log.d(TAG, "📊 Enhanced daily usage limits check completed. Devices checked: " + devicesChecked + 
                      ", devices reset: " + devicesReset + ", enhanced with 7-day rolling: " + devicesEnhanced);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error checking daily usage limits: " + databaseError.getMessage());
            }
            });
        } catch (Exception e) {
            Log.e(TAG, "❌ Error setting up enhanced daily usage limits reset check: " + e.getMessage());
        }
    }

    /**
     * Reset daily usage limits for all devices at midnight with enhanced 7-day rolling window
     */
    private void resetAllDailyUsageLimits() {
        Log.d(TAG, "🌙 Performing enhanced midnight reset for all daily usage limits with 7-day rolling window...");
        
        dailyUsageLimitsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                int totalDevices = 0;
                int resetDevices = 0;
                int enhancedDevices = 0;
                
                for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                    try {
                        String deviceId = deviceSnapshot.getKey();
                        DailyUsageLimit usageLimit = deviceSnapshot.getValue(DailyUsageLimit.class);
                        
                        if (usageLimit != null && deviceId != null) {
                            totalDevices++;
                            
                            Log.d(TAG, "🌙 Enhanced midnight reset for device: " + deviceId + " (" + usageLimit.deviceName + ")");
                            
                            // Enhanced reset with 7-day rolling window maintenance
                            usageLimit.resetDailyUsage();
                            
                            // Sync current usage data to ensure consistency
                            usageLimit.syncCurrentUsageWithToday();
                            
                            // Save back to Firebase with enhanced data
                            dailyUsageLimitsRef.child(deviceId).setValue(usageLimit)
                                    .addOnSuccessListener(aVoid -> {
                                        Log.d(TAG, "✅ Enhanced midnight reset completed for device: " + deviceId + " with 7-day rolling window");
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.e(TAG, "❌ Enhanced midnight reset failed for device: " + deviceId + " - " + e.getMessage());
                                    });
                            
                            resetDevices++;
                            enhancedDevices++;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during enhanced midnight reset for device: " + e.getMessage());
                    }
                }
                
                Log.d(TAG, "🌙 Enhanced midnight reset completed for daily usage limits. Total devices: " + totalDevices + 
                      ", reset: " + resetDevices + ", enhanced with 7-day rolling: " + enhancedDevices);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error during enhanced midnight reset of daily usage limits: " + databaseError.getMessage());
            }
        });
    }

    public static void startService(Context context) {
        Intent intent = new Intent(context, DailyTimerResetService.class);
        context.startService(intent);
        Log.d("DailyTimerResetService", "Service start requested");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "DailyTimerResetService destroyed");
        
        // Release wake lock
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                Log.d(TAG, "🔋 Wake lock released");
            } catch (Exception e) {
                Log.e(TAG, "Error releasing wake lock: " + e.getMessage());
            }
        }
    }
    
    /**
     * Enhanced midnight reset with better error handling and logging
     */
    private void performEnhancedMidnightReset() {
        Calendar now = Calendar.getInstance();
        Log.d(TAG, "🌙 PERFORMING ENHANCED MIDNIGHT RESET at " + now.getTime());
        
        // Acquire temporary wake lock for reset process
        PowerManager.WakeLock tempWakeLock = null;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                tempWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Master2:MidnightReset"
                );
                tempWakeLock.acquire(10 * 60 * 1000L); // 10 minutes max
                Log.d(TAG, "🔋 Temporary wake lock acquired for midnight reset");
            }
            
            // Check and reset all recurring timers
            Log.d(TAG, "🔄 Step 1: Resetting recurring timers...");
            checkAndResetExpiredTimers();
            
            // Reset daily usage limits for all devices
            Log.d(TAG, "📊 Step 2: Resetting daily usage limits...");
            resetAllDailyUsageLimits();
            
            // Update 7-day usage tracking for all connected devices
            Log.d(TAG, "📈 Step 3: Updating 7-day usage data for all devices...");
            updateAll7DayUsageData();
            
            // Reset usage limiters for all devices with smart multi-day detection
            Log.d(TAG, "⏱️ Step 4: Resetting usage limiters for multi-day timers...");
            resetAllUsageLimiters();
            
            Log.d(TAG, "✅ Enhanced midnight reset completed successfully");

            // 🔔 STEP 5: Start persistent notification service for parent devices
            Log.d(TAG, "🔔 Step 5: Starting persistent timer notification service...");
            startPersistentNotificationService();

        } catch (Exception e) {
            Log.e(TAG, "❌ Error during enhanced midnight reset: " + e.getMessage());
        } finally {
            // Always schedule the next midnight reset
            Log.d(TAG, "🕛 Step 6: Scheduling next enhanced midnight reset...");
            scheduleReliableMidnightReset();

            // Release temporary wake lock
            if (tempWakeLock != null && tempWakeLock.isHeld()) {
                try {
                    tempWakeLock.release();
                    Log.d(TAG, "🔋 Temporary wake lock released");
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing temporary wake lock: " + e.getMessage());
                }
            }

            Log.d(TAG, "🌙 ENHANCED MIDNIGHT RESET PROCESS COMPLETED");
        }
    }
    
    /**
     * Log comprehensive system status for debugging timer issues
     */
    private void logSystemStatus() {
        try {
            Calendar now = Calendar.getInstance();
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            
            StringBuilder status = new StringBuilder();
            status.append("=== ENHANCED TIMER SERVICE STATUS ===\n");
            status.append("Current Time: ").append(now.getTime()).append("\n");
            status.append("Service Running: ").append(true).append("\n");
            
            if (powerManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    status.append("Battery Optimization Ignored: ")
                          .append(powerManager.isIgnoringBatteryOptimizations(getPackageName())).append("\n");
                    status.append("Device Idle Mode: ").append(powerManager.isDeviceIdleMode()).append("\n");
                }
                status.append("Power Save Mode: ").append(powerManager.isPowerSaveMode()).append("\n");
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
                if (alarmManager != null) {
                    status.append("Can Schedule Exact Alarms: ").append(alarmManager.canScheduleExactAlarms()).append("\n");
                }
            }
            
            status.append("Wake Lock Held: ").append(wakeLock != null && wakeLock.isHeld()).append("\n");
            status.append("Android Version: ").append(Build.VERSION.SDK_INT).append("\n");
            status.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            status.append("=====================================");
            
            Log.d(TAG, status.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error logging system status: " + e.getMessage());
        }
    }
    
    /**
     * 📈 Update 7-day usage data for all connected devices automatically
     * This runs at midnight to ensure calendar-based daily resets work without app interaction
     * ENHANCED: Now uses DateAwareUsageDataManager for proper daily separation
     */
    private void updateAll7DayUsageData() {
        try {
            Log.d(TAG, "🎯 Starting ENHANCED automatic usage data midnight reset for all devices...");
            
            // Get all connection data to find connected devices
            DatabaseReference connectionsRef = FirebaseDatabase.getInstance().getReference("device_connections");
            connectionsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    int totalDevices = 0;
                    int resetDevices = 0;
                    
                    for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                        String deviceId = deviceSnapshot.getKey();
                        if (deviceId != null) {
                            totalDevices++;
                            
                            try {
                                // 🌅 CREATE DATE-AWARE MANAGER for this device
                                DateAwareUsageDataManager dataManager = new DateAwareUsageDataManager(
                                    DailyTimerResetService.this, deviceId
                                );
                                
                                // 🕛 Trigger midnight reset - this will:
                                // 1. Preserve yesterday's data under yesterday's date
                                // 2. Create fresh empty data structure for today
                                // 3. Ensure no data contamination between days
                                dataManager.triggerMidnightReset();
                                resetDevices++;
                                
                                Log.d(TAG, "🌅 Midnight reset completed for device: " + deviceId + 
                                      " (old data preserved, new day started fresh)");
                                
                            } catch (Exception e) {
                                Log.e(TAG, "❌ Error performing midnight reset for device " + deviceId + ": " + e.getMessage());
                            }
                        }
                    }
                    
                    Log.d(TAG, "🎯 ENHANCED midnight usage reset completed. " +
                          "Total devices: " + totalDevices + ", Reset: " + resetDevices);
                    Log.d(TAG, "✅ All devices now have fresh daily data separation - no more data carry-over!");
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "❌ Failed to get device connections for midnight reset: " + databaseError.getMessage());
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error during enhanced midnight usage data reset: " + e.getMessage());
        }
    }
    
    /**
     * 🎯 Reset usage limiters for all devices with smart multi-day timer functionality
     * Automatically restores timer values to parent-set amounts each day at midnight
     */
    private void resetAllUsageLimiters() {
        if (usageLimitersRef == null) {
            Log.w(TAG, "⚠️ usageLimitersRef is null, skipping usage limiter reset");
            return;
        }
        
        Log.d(TAG, "🎯 Performing smart multi-day usage limiter reset at midnight...");
        
        try {
            usageLimitersRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    int totalDevices = 0;
                    int resetDevices = 0;
                    int skippedDevices = 0;
                    
                    String currentDate = getCurrentDateString();
                    
                    for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                        String deviceId = deviceSnapshot.getKey();
                        totalDevices++;
                        
                        try {
                            // Check if limiter is active and needs reset
                            Boolean isActive = deviceSnapshot.child("isActive").getValue(Boolean.class);
                            String lastResetDate = deviceSnapshot.child("lastResetDate").getValue(String.class);
                            
                            // Respect parent-cleared state: if node missing or inactive, do nothing
                            if (isActive != null && isActive && !currentDate.equals(lastResetDate)) {
                                // Check if limiter should be active today
                                @SuppressWarnings("unchecked")
                                List<String> activeDays = (List<String>) deviceSnapshot.child("activeDays").getValue();
                                
                                if (activeDays != null && isLimiterActiveForToday(activeDays)) {
                                    resetLimiterForDevice(deviceId, deviceSnapshot, currentDate);
                                    resetDevices++;
                                    Log.d(TAG, "✅ Reset usage limiter for device: " + deviceId);
                                } else {
                                    Log.d(TAG, "📅 Skipping device " + deviceId + " - not active for today");
                                    skippedDevices++;
                                }
                            } else {
                                Log.d(TAG, "⏭️ Skipping device " + deviceId + " - inactive or already reset today");
                                skippedDevices++;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error processing reset for device " + deviceId + ": " + e.getMessage());
                            skippedDevices++;
                        }
                    }
                    
                    Log.d(TAG, "🎯 Smart usage limiter reset completed. " +
                          "Total devices: " + totalDevices + 
                          ", Reset: " + resetDevices + 
                          ", Skipped: " + skippedDevices);
                }
                
                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "❌ Failed to reset usage limiters: " + databaseError.getMessage());
                }
            });
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error during usage limiter reset: " + e.getMessage());
        }
    }
    
    /**
     * 🔄 Reset limiter for a specific device with smart multi-day detection
     * FIXED: Properly resets timer at midnight to parent-set values
     */
    private void resetLimiterForDevice(String deviceId, DataSnapshot deviceSnapshot, String currentDate) {
        try {
            // Get original timer duration set by parent
            Integer hours = deviceSnapshot.child("hours").getValue(Integer.class);
            Integer minutes = deviceSnapshot.child("minutes").getValue(Integer.class);
            
            if (hours != null || minutes != null) {
                // Handle null values safely
                int h = hours != null ? hours : 0;
                int m = minutes != null ? minutes : 0;
                
                // Calculate new remaining time from original parent-set values
                long totalTimeMs = ((long) h * 60 * 60 * 1000) + ((long) m * 60 * 1000);
                
                Log.d(TAG, "🔄 ═══════════════════════════════════════");
                Log.d(TAG, "🔄 MIDNIGHT RESET for device: " + deviceId);
                Log.d(TAG, "🔄 Resetting to original: " + h + "h " + m + "m (" + totalTimeMs + "ms)");
                Log.d(TAG, "🔄 ═══════════════════════════════════════");
                
                // Update Firebase with reset values - COMPLETE RESET
                Map<String, Object> resetData = new HashMap<>();
                resetData.put("remainingTimeMs", totalTimeMs);
                resetData.put("lastResetDate", currentDate);
                resetData.put("startTime", System.currentTimeMillis());
                resetData.put("isActive", true); // Ensure timer stays active
                resetData.put("midnightReset", true); // Flag indicating midnight reset
                resetData.put("resetTimestamp", System.currentTimeMillis());
                
                usageLimitersRef.child(deviceId).updateChildren(resetData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ MIDNIGHT RESET SUCCESS for device: " + deviceId);
                        Log.d(TAG, "✅ Timer restored to: " + h + "h " + m + "m");
                        
                        // Send broadcast to child device to refresh timer
                        notifyChildDeviceOfReset(deviceId, totalTimeMs);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ MIDNIGHT RESET FAILED for device " + deviceId + ": " + e.getMessage());
                    });
            } else {
                Log.w(TAG, "⚠️ Missing hours/minutes for device " + deviceId + " - skipping reset");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error resetting limiter for device " + deviceId + ": " + e.getMessage());
        }
    }
    
    /**
     * 📢 Notify child device that timer has been reset at midnight
     */
    private void notifyChildDeviceOfReset(String deviceId, long newTimeMs) {
        try {
            // Write to a notification path that child device listens to
            DatabaseReference notifyRef = FirebaseDatabase.getInstance()
                .getReference("timer_notifications")
                .child(deviceId);
            
            Map<String, Object> notification = new HashMap<>();
            notification.put("type", "midnight_reset");
            notification.put("newTimeMs", newTimeMs);
            notification.put("timestamp", System.currentTimeMillis());
            notification.put("message", "Timer reset at midnight");
            
            notifyRef.setValue(notification)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "📢 Midnight reset notification sent to device: " + deviceId);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "⚠️ Failed to send notification: " + e.getMessage());
                });
        } catch (Exception e) {
            Log.w(TAG, "⚠️ Error sending notification: " + e.getMessage());
        }
    }
    
    /**
     * 📅 Check if limiter should be active for today based on activeDays
     */
    private boolean isLimiterActiveForToday(List<String> activeDays) {
        if (activeDays == null || activeDays.isEmpty()) {
            return false;
        }
        
        Calendar calendar = Calendar.getInstance();
        String currentDay = getDayName(calendar.get(Calendar.DAY_OF_WEEK));
        
        // Check for both lowercase and proper case to ensure compatibility
        boolean isActiveToday = activeDays.contains(currentDay.toLowerCase()) || activeDays.contains(currentDay);
        Log.d(TAG, "📅 Today: " + currentDay + ", Active days: " + activeDays + ", Active today: " + isActiveToday);
        
        return isActiveToday;
    }
    
    /**
     * 🗓️ Get day name from Calendar.DAY_OF_WEEK
     */
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
    
    /**
     * 📆 Get current date string in yyyy-MM-dd format
     */
    private String getCurrentDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    /**
     * 🔔 Start persistent notification service for parent devices
     * This will show notifications asking parents to reset or remove expired timers
     */
    private void startPersistentNotificationService() {
        try {
            // Check if we're on a parent device
            SessionManager sessionManager = new SessionManager(this);
            String userType = sessionManager.getUserType();

            if ("parent".equals(userType)) {
                Log.d(TAG, "🔔 Starting persistent timer notification service for parent device");

                // Start the notification service
                Intent notificationServiceIntent = new Intent(this, PersistentTimerNotificationService.class);
                startService(notificationServiceIntent);

                Log.d(TAG, "✅ Persistent timer notification service started successfully");
            } else {
                Log.d(TAG, "ℹ️ Not a parent device - skipping notification service start");
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting persistent notification service: " + e.getMessage());
        }
    }
}