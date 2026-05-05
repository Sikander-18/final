package com.example.master2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

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
 * 🔔 PERSISTENT TIMER NOTIFICATION SERVICE
 * Shows non-dismissible notification after midnight 00:00 asking parent to reset or remove timer
 * This service runs on parent devices only and monitors timer expiration status
 */
public class PersistentTimerNotificationService extends Service {
    private static final String TAG = "PersistentTimerNotif";
    private static final String CHANNEL_ID = "persistent_timer_reset";
    private static final int NOTIFICATION_ID = 3001;

    private static final String ACTION_RESET_TIMER = "com.example.master2.RESET_TIMER";
    private static final String ACTION_REMOVE_TIMER = "com.example.master2.REMOVE_TIMER";
    private static final String ACTION_DISMISS_NOTIFICATION = "com.example.master2.DISMISS_NOTIFICATION";

    private DatabaseReference usageLimitersRef;
    private String myDeviceId;
    private SessionManager sessionManager;
    private boolean hasActiveTimers = false;
    private boolean notificationShown = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🔔 PersistentTimerNotificationService created");

        // Get device ID and session manager
        myDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        sessionManager = new SessionManager(this);

        // Only run on parent devices
        if (!"parent".equals(sessionManager.getUserType())) {
            Log.d(TAG, "❌ Not a parent device, stopping service");
            stopSelf();
            return;
        }

        // Initialize Firebase reference
        usageLimitersRef = FirebaseDatabase.getInstance().getReference("usage_limiters");

        // Create notification channel
        createNotificationChannel();

        // Monitor all usage limiters for this parent's connected devices
        monitorConnectedDeviceTimers();

        Log.d(TAG, "✅ PersistentTimerNotificationService initialized for parent device");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            String deviceId = intent.getStringExtra("deviceId");

            if (ACTION_RESET_TIMER.equals(action) && deviceId != null) {
                Log.d(TAG, "🔄 Parent chose to RESET timer for device: " + deviceId);
                resetTimerForDevice(deviceId);
                dismissNotification();
            } else if (ACTION_REMOVE_TIMER.equals(action) && deviceId != null) {
                Log.d(TAG, "🗑️ Parent chose to REMOVE timer for device: " + deviceId);
                removeTimerForDevice(deviceId);
                dismissNotification();
            } else if (ACTION_DISMISS_NOTIFICATION.equals(action)) {
                Log.d(TAG, "❌ Parent dismissed notification");
                dismissNotification();
            }
        }

        return START_STICKY; // Restart if killed
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 📺 Create high priority notification channel that cannot be easily dismissed
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Daily Timer Reset Notifications",
                NotificationManager.IMPORTANCE_HIGH
            );

            channel.setDescription("Important notifications about daily timer resets");
            channel.enableLights(true);
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(true);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "✅ High priority notification channel created");
            }
        }
    }

    /**
     * 👀 Monitor all connected devices for timer status
     */
    private void monitorConnectedDeviceTimers() {
        // Get parent's connected devices
        DatabaseReference connectionsRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(myDeviceId)
                .child("connected_devices");

        connectionsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Log.d(TAG, "👀 Checking connected devices for timer status...");
                boolean foundExpiredTimer = false;

                for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                    String childDeviceId = deviceSnapshot.getKey();
                    if (childDeviceId != null) {
                        // Check if this device has an active timer
                        checkDeviceTimerStatus(childDeviceId);
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Failed to monitor connected devices: " + databaseError.getMessage());
            }
        });
    }

    /**
     * 🔍 Check specific device timer status
     */
    private void checkDeviceTimerStatus(String deviceId) {
        usageLimitersRef.child(deviceId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    return;
                }

                try {
                    Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                    Long remainingTimeMs = dataSnapshot.child("remainingTimeMs").getValue(Long.class);
                    String lastResetDate = dataSnapshot.child("lastResetDate").getValue(String.class);

                    String currentDate = getCurrentDateString();
                    Calendar now = Calendar.getInstance();
                    int currentHour = now.get(Calendar.HOUR_OF_DAY);

                    // Check if timer needs attention (active, time expired or new day, and after midnight)
                    if (Boolean.TRUE.equals(isActive)) {
                        boolean needsReset = false;
                        String reason = "";

                        // Check if it's a new day and we're past midnight
                        if (!currentDate.equals(lastResetDate) && currentHour >= 0) {
                            needsReset = true;
                            reason = "New day detected - timer needs reset";
                        }
                        // Also check if remaining time is 0 or negative
                        else if (remainingTimeMs != null && remainingTimeMs <= 0) {
                            needsReset = true;
                            reason = "Timer expired - needs reset or removal";
                        }

                        if (needsReset && !notificationShown) {
                            Log.d(TAG, "🚨 Timer needs attention for device " + deviceId + ": " + reason);
                            showPersistentNotification(deviceId, reason);
                        }
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error checking timer status for device " + deviceId + ": " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Failed to check timer for device " + deviceId + ": " + databaseError.getMessage());
            }
        });
    }

    /**
     * 🔔 Show persistent notification with Reset/Remove options
     */
    private void showPersistentNotification(String deviceId, String reason) {
        if (notificationShown) {
            return; // Don't show multiple notifications
        }

        Log.d(TAG, "🔔 Showing persistent timer notification for device: " + deviceId);

        // Get device name for display
        DatabaseReference deviceRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(myDeviceId)
                .child("connected_devices")
                .child(deviceId);

        deviceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                String deviceName = "Child Device";
                if (dataSnapshot.exists()) {
                    String name = dataSnapshot.child("deviceName").getValue(String.class);
                    if (name != null && !name.isEmpty()) {
                        deviceName = name;
                    }
                }

                // Create action buttons
                Intent resetIntent = new Intent(PersistentTimerNotificationService.this, PersistentTimerNotificationService.class);
                resetIntent.setAction(ACTION_RESET_TIMER);
                resetIntent.putExtra("deviceId", deviceId);
                PendingIntent resetPendingIntent = PendingIntent.getService(
                    PersistentTimerNotificationService.this,
                    1001,
                    resetIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                Intent removeIntent = new Intent(PersistentTimerNotificationService.this, PersistentTimerNotificationService.class);
                removeIntent.setAction(ACTION_REMOVE_TIMER);
                removeIntent.putExtra("deviceId", deviceId);
                PendingIntent removePendingIntent = PendingIntent.getService(
                    PersistentTimerNotificationService.this,
                    1002,
                    removeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                // Create main notification intent
                Intent mainIntent = new Intent(PersistentTimerNotificationService.this, ParentDashboardActivity.class);
                PendingIntent mainPendingIntent = PendingIntent.getActivity(
                    PersistentTimerNotificationService.this,
                    0,
                    mainIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                // Build the persistent notification
                NotificationCompat.Builder builder = new NotificationCompat.Builder(PersistentTimerNotificationService.this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_agenda)
                    .setContentTitle("⏰ Daily Timer Reset Required")
                    .setContentText("Set new time for " + deviceName + " today")
                    .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText("Time's up for " + deviceName + "!\n\n" +
                                "🔄 RESET: Start fresh timer for today\n" +
                                "🗑️ REMOVE: Stop daily timer completely\n\n" +
                                "Choose an option to continue."))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(false) // Cannot be dismissed by swipe
                    .setOngoing(true) // Persistent notification
                    .setContentIntent(mainPendingIntent)
                    .addAction(android.R.drawable.ic_menu_revert, "🔄 RESET", resetPendingIntent)
                    .addAction(android.R.drawable.ic_menu_delete, "🗑️ REMOVE", removePendingIntent)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setVibrate(new long[]{1000, 1000, 1000})
                    .setLights(0xFF0000FF, 3000, 3000);

                // Show the notification
                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                if (notificationManager != null) {
                    notificationManager.notify(NOTIFICATION_ID, builder.build());
                    notificationShown = true;
                    Log.d(TAG, "🔔 Persistent notification shown for device: " + deviceName);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Failed to get device name: " + databaseError.getMessage());
            }
        });
    }

    /**
     * 🔄 Reset timer to original parent-set duration
     */
    private void resetTimerForDevice(String deviceId) {
        Log.d(TAG, "🔄 Resetting timer for device: " + deviceId);

        usageLimitersRef.child(deviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    Log.w(TAG, "⚠️ No timer data found for device: " + deviceId);
                    return;
                }

                try {
                    // Get original parent-set duration
                    Integer hours = dataSnapshot.child("hours").getValue(Integer.class);
                    Integer minutes = dataSnapshot.child("minutes").getValue(Integer.class);

                    if (hours != null && minutes != null) {
                        // Calculate fresh timer duration
                        long totalTimeMs = ((long) hours * 60 * 60 * 1000) + ((long) minutes * 60 * 1000);
                        String currentDate = getCurrentDateString();

                        // Reset timer with original duration
                        Map<String, Object> resetData = new HashMap<>();
                        resetData.put("remainingTimeMs", totalTimeMs);
                        resetData.put("lastResetDate", currentDate);
                        resetData.put("startTime", System.currentTimeMillis());
                        resetData.put("isActive", true);

                        usageLimitersRef.child(deviceId).updateChildren(resetData)
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "✅ Successfully reset timer for device: " + deviceId +
                                      " to " + hours + "h " + minutes + "m");

                                // Show success notification
                                showSuccessNotification("Timer Reset",
                                    "Timer reset to " + hours + "h " + minutes + "m for today");
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "❌ Failed to reset timer: " + e.getMessage());
                                showErrorNotification("Reset Failed", "Could not reset timer. Please try again.");
                            });
                    } else {
                        Log.w(TAG, "⚠️ Missing hours/minutes data for device: " + deviceId);
                        showErrorNotification("Reset Failed", "Timer configuration is missing.");
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error resetting timer: " + e.getMessage());
                    showErrorNotification("Reset Failed", "An error occurred while resetting timer.");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Failed to read timer data: " + databaseError.getMessage());
                showErrorNotification("Reset Failed", "Could not access timer data.");
            }
        });
    }

    /**
     * 🗑️ Remove timer completely
     */
    private void removeTimerForDevice(String deviceId) {
        Log.d(TAG, "🗑️ Removing timer for device: " + deviceId);

        // Deactivate the timer
        Map<String, Object> removeData = new HashMap<>();
        removeData.put("isActive", false);
        removeData.put("remainingTimeMs", 0);
        removeData.put("removedAt", System.currentTimeMillis());
        removeData.put("removedBy", "parent");

        usageLimitersRef.child(deviceId).updateChildren(removeData)
            .addOnSuccessListener(aVoid -> {
                Log.d(TAG, "✅ Successfully removed timer for device: " + deviceId);
                showSuccessNotification("Timer Removed", "Daily timer has been completely removed");
            })
            .addOnFailureListener(e -> {
                Log.e(TAG, "❌ Failed to remove timer: " + e.getMessage());
                showErrorNotification("Remove Failed", "Could not remove timer. Please try again.");
            });
    }

    /**
     * ❌ Dismiss the persistent notification
     */
    private void dismissNotification() {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
            notificationShown = false;
            Log.d(TAG, "❌ Persistent notification dismissed");
        }
    }

    /**
     * ✅ Show success notification
     */
    private void showSuccessNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("✅ " + title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(5000); // Auto dismiss after 5 seconds

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID + 1, builder.build());
        }
    }

    /**
     * ❌ Show error notification
     */
    private void showErrorNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("❌ " + title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(10000); // Auto dismiss after 10 seconds

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager != null) {
            notificationManager.notify(NOTIFICATION_ID + 2, builder.build());
        }
    }

    /**
     * 📅 Get current date string
     */
    private String getCurrentDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "🔔 PersistentTimerNotificationService destroyed");

        // Clean up notification
        dismissNotification();
    }
}