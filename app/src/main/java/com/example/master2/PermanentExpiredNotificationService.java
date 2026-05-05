package com.example.master2;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.annotation.Nullable;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import androidx.annotation.NonNull;
import java.util.Calendar;
import java.util.List;
import java.util.ArrayList;

/**
 * 🔥 PERMANENT EXPIRED TIMER NOTIFICATION SERVICE
 * 
 * This service ensures that when a timer expires, a notification appears and
 * stays
 * PERMANENTLY visible until:
 * 1. Parent removes the timer (isActive = false)
 * 2. Timer resets at midnight (new day starts)
 * 
 * The notification CANNOT be dismissed by the child and survives:
 * - App kills
 * - Device reboots
 * - System optimizations
 * - User attempts to dismiss
 */
public class PermanentExpiredNotificationService extends Service {

    private static final String TAG = "PermanentExpiredNotif";
    private static final String CHANNEL_ID = "permanent_expired_timer";
    private static final int NOTIFICATION_ID = 88888;

    private String childDeviceId;
    private DatabaseReference limiterRef;
    private ValueEventListener timerListener;
    private Handler midnightHandler;
    private Runnable midnightChecker;

    // Notification state tracking
    private boolean isNotificationActive = false;
    private boolean isTimerExpired = false;
    private List<String> limitedApps = new ArrayList<>();

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🔥 Permanent Expired Notification Service Created");

        // Get device ID
        SessionManager sessionManager = new SessionManager(this);
        childDeviceId = sessionManager.getChildDeviceId();

        if (childDeviceId != null) {
            setupFirebaseListener();
            setupMidnightResetChecker();
            createNotificationChannel();
        } else {
            Log.e(TAG, "❌ No child device ID - stopping service");
            stopSelf();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "🔥 Service started - IMMEDIATELY promoting to foreground");

        // 🚨 CRITICAL: Call startForeground() IMMEDIATELY to avoid crash
        // Android requires this within 5 seconds of startForegroundService()
        // We show a dummy notification first, then replace it if timer is actually
        // expired
        try {
            Notification dummyNotification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Monitoring Timer Status")
                    .setContentText("Checking for expired timers...")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .build();

            startForeground(NOTIFICATION_ID, dummyNotification);
            Log.d(TAG, "✅ Service promoted to foreground successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to promote to foreground: " + e.getMessage());
        }

        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // This is a started service, not bound
    }

    /**
     * Setup Firebase listener to monitor timer state
     */
    private void setupFirebaseListener() {
        if (childDeviceId == null)
            return;

        limiterRef = FirebaseDatabase.getInstance()
                .getReference("usage_limiters")
                .child(childDeviceId);

        timerListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    try {
                        Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                        Long remainingTimeMs = dataSnapshot.child("remainingTimeMs").getValue(Long.class);
                        List<String> selectedApps = (List<String>) dataSnapshot.child("selectedApps").getValue();

                        Log.d(TAG, "🔥 Timer data - isActive: " + isActive + ", remainingTimeMs: " + remainingTimeMs);

                        if (Boolean.TRUE.equals(isActive)) {
                            if (remainingTimeMs != null && remainingTimeMs <= 0) {
                                // Timer expired but still active - SHOW PERMANENT NOTIFICATION
                                limitedApps = selectedApps != null ? selectedApps : new ArrayList<>();
                                showPermanentExpiredNotification();
                            } else {
                                // Timer is active and running - HIDE NOTIFICATION
                                hidePermanentExpiredNotification();
                            }
                        } else {
                            // Timer removed by parent - HIDE NOTIFICATION
                            hidePermanentExpiredNotification();
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error processing timer data: " + e.getMessage());
                    }
                } else {
                    // No timer data - HIDE NOTIFICATION
                    hidePermanentExpiredNotification();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "❌ Firebase listener cancelled: " + databaseError.getMessage());
            }
        };

        limiterRef.addValueEventListener(timerListener);
    }

    /**
     * Setup midnight reset checker to automatically hide notification at new day
     */
    private void setupMidnightResetChecker() {
        midnightHandler = new Handler(Looper.getMainLooper());

        midnightChecker = new Runnable() {
            @Override
            public void run() {
                checkForMidnightReset();
                // Check every minute for midnight
                midnightHandler.postDelayed(this, 60000);
            }
        };

        // Start checking
        midnightHandler.post(midnightChecker);
    }

    /**
     * Check if it's past midnight and reset notification
     */
    private void checkForMidnightReset() {
        Calendar now = Calendar.getInstance();
        int hour = now.get(Calendar.HOUR_OF_DAY);
        int minute = now.get(Calendar.MINUTE);

        // If it's midnight (00:00), hide notification for fresh day
        if (hour == 0 && minute == 0) {
            Log.d(TAG, "🌅 Midnight detected - hiding expired notification for fresh day");
            hidePermanentExpiredNotification();
        }
    }

    /**
     * Create notification channel for permanent notifications
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "🔥 TIMER EXPIRED - PERMANENT ALERT",
                    NotificationManager.IMPORTANCE_MAX);

            channel.setDescription("Critical permanent alert when daily timer expires - Cannot be dismissed");
            channel.enableLights(true);
            channel.setLightColor(Color.RED);
            channel.enableVibration(false); // No continuous vibration
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(true);
            channel.setBypassDnd(true); // Always shows even in Do Not Disturb

            // Make channel non-blockable
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                channel.setBlockable(false);
            }

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "🔥 Permanent notification channel created");
            }
        }
    }

    /**
     * 🔥 SHOW PERMANENT EXPIRED TIMER NOTIFICATION
     * This notification CANNOT be dismissed and stays until midnight or parent
     * removes timer
     */
    private void showPermanentExpiredNotification() {
        if (isNotificationActive) {
            Log.d(TAG, "🔥 Permanent notification already active - skipping");
            return;
        }

        try {
            Log.d(TAG, "🔥 SHOWING PERMANENT EXPIRED TIMER NOTIFICATION");

            // Create app names list
            StringBuilder appsList = new StringBuilder();
            if (limitedApps != null && !limitedApps.isEmpty()) {
                for (int i = 0; i < Math.min(limitedApps.size(), 3); i++) {
                    if (i > 0)
                        appsList.append(", ");
                    appsList.append(getAppName(limitedApps.get(i)));
                }
                if (limitedApps.size() > 3) {
                    appsList.append(" and ").append(limitedApps.size() - 3).append(" more");
                }
            } else {
                appsList.append("Monitored apps");
            }

            // Create intent to open child dashboard
            Intent intent = new Intent(this, ChildDashboardActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Create the PERMANENT notification
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("⏰ DAILY TIMER EXPIRED")
                    .setContentText("Time limit reached for: " + appsList.toString())
                    .setSubText("⚠️ PERMANENT UNTIL MIDNIGHT OR PARENT REMOVAL")
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setLargeIcon(android.graphics.BitmapFactory.decodeResource(getResources(),
                            android.R.drawable.ic_dialog_alert))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(false) // 🔥 CANNOT be dismissed by swipe
                    .setOngoing(true) // 🔥 PERMANENT - shows in ongoing section
                    .setPriority(NotificationCompat.PRIORITY_MAX) // 🔥 HIGHEST priority
                    .setCategory(NotificationCompat.CATEGORY_ALARM) // 🔥 ALARM category
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 🔥 Shows on lock screen
                    .setColor(Color.RED) // 🔥 Red color for attention
                    .setDefaults(NotificationCompat.DEFAULT_LIGHTS) // Only lights, no sound/vibration spam
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("⏰ YOUR DAILY TIMER HAS EXPIRED\n\n" +
                                    "Limited apps: " + appsList.toString() + "\n\n" +
                                    "🚨 This notification will remain permanently visible until:\n" +
                                    "• Your parent removes the timer, OR\n" +
                                    "• Midnight (new day starts)\n\n" +
                                    "Apps remain accessible but please be mindful of screen time."))
                    .addAction(android.R.drawable.ic_menu_view, "Open Dashboard", pendingIntent)
                    .build();

            // Start as foreground service to keep notification alive PERMANENTLY
            startForeground(NOTIFICATION_ID, notification);

            // Mark notification as active
            isNotificationActive = true;

            // Save state to SharedPreferences for persistence across reboots
            SharedPreferences prefs = getSharedPreferences("permanent_expired_notif", MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("notification_active", true)
                    .putLong("notification_created_time", System.currentTimeMillis())
                    .putString("device_id", childDeviceId)
                    .apply();

            Log.d(TAG, "🔥 PERMANENT EXPIRED NOTIFICATION IS NOW ACTIVE AND UNDISMISSIBLE");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing permanent notification: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Hide permanent notification when timer is removed or midnight resets
     */
    private void hidePermanentExpiredNotification() {
        // ✅ FIX: Removed the early-return guard `if (!isNotificationActive) return;`
        // That guard prevented cleanup when the service restarts (e.g. after OEM kill)
        // and
        // isNotificationActive starts as false even though a stale notification is
        // still visible.
        // Calling cancel is idempotent — safe to call even if notification isn’t
        // showing.

        try {
            Log.d(TAG, "🔥 HIDING PERMANENT EXPIRED NOTIFICATION");

            // Stop foreground and remove notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }

            // Belt-and-suspenders: also cancel by ID in case stopForeground didn’t remove
            // it
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null)
                nm.cancel(NOTIFICATION_ID);

            // Mark as inactive
            isNotificationActive = false;

            // Clear saved state
            SharedPreferences prefs = getSharedPreferences("permanent_expired_notif", MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("notification_active", false)
                    .remove("notification_created_time")
                    .apply();

            Log.d(TAG, "🔥 Permanent notification hidden successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error hiding permanent notification: " + e.getMessage());
        }
    }

    /**
     * Get app name from package name
     */
    private String getAppName(String packageName) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(packageName, 0)).toString();
        } catch (Exception e) {
            return packageName; // Return package name if app name not found
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🔥 Permanent Expired Notification Service Destroyed");

        // Clean up listeners
        if (timerListener != null && limiterRef != null) {
            limiterRef.removeEventListener(timerListener);
        }

        if (midnightHandler != null && midnightChecker != null) {
            midnightHandler.removeCallbacks(midnightChecker);
        }

        // Note: Don't hide notification on destroy - service might restart
        // Notification should only be hidden by timer removal or midnight reset
    }
}