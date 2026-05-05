package com.example.master2.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.master2.ParentDashboardActivity;
import com.example.master2.R;
import com.example.master2.SessionManager;
import com.example.master2.models.PermissionEvent;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Background service that listens for child device permission changes
 * and shows Android system notifications on parent device
 */
public class PermissionEventListener extends Service {
    private static final String TAG = "PermissionEventListener";
    private static final String CHANNEL_ID = "child_service_alerts";
    private static final String FOREGROUND_CHANNEL_ID = "permission_listener_fg";
    private static final int FOREGROUND_NOTIFICATION_ID = 9002;

    private SessionManager sessionManager;
    private DatabaseReference permissionEventsRef;
    private Map<String, ChildEventListener> childListeners = new HashMap<>();
    private Map<String, String> childNames = new HashMap<>(); // deviceId -> childName
    private int notificationId = 10000; // Counter for unique notification IDs

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🔔 PermissionEventListener service starting...");

        sessionManager = new SessionManager(this);

        // Check if parent is logged in
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) {
            Log.w(TAG, "⚠️ Parent not logged in, stopping service");
            stopSelf();
            return;
        }

        createNotificationChannels();
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification());

        String parentUserId = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid()
                : sessionManager.getParentUserId();

        if (parentUserId == null || parentUserId.isEmpty()) {
            Log.e(TAG, "❌ No parent user ID, cannot listen for events");
            stopSelf();
            return;
        }

        setupFirebaseListeners(parentUserId);
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);

            // Channel for foreground service
            NotificationChannel fgChannel = new NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    "Service Monitor",
                    NotificationManager.IMPORTANCE_LOW);
            fgChannel.setDescription("Keeps service running in background");
            manager.createNotificationChannel(fgChannel);

            // Channel for child device alerts
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Child Device Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("Notifications when child device services are activated/deactivated");
            alertChannel.enableVibration(true);
            manager.createNotificationChannel(alertChannel);
        }
    }

    private Notification buildForegroundNotification() {
        return new NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                .setContentTitle("Monitoring Child Devices")
                .setContentText("Listening for service status changes")
                .setSmallIcon(R.drawable.ic_child)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void setupFirebaseListeners(String parentUserId) {
        Log.d(TAG, "📡 Setting up Firebase listeners for parent: " + parentUserId);

        permissionEventsRef = FirebaseDatabase.getInstance()
                .getReference("permission_events")
                .child(parentUserId);

        // First, load child names
        loadChildNames(parentUserId);

        // Listen for all children under this parent
        permissionEventsRef.addChildEventListener(new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot childSnapshot, String previousChildKey) {
                String childDeviceId = childSnapshot.getKey();
                Log.d(TAG, "🆕 Detected child device: " + childDeviceId);
                listenToChildEvents(childDeviceId);
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildName) {
                // Not needed
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
                String childDeviceId = snapshot.getKey();
                Log.d(TAG, "❌ Child device removed: " + childDeviceId);
                stopListeningToChild(childDeviceId);
            }

            @Override
            public void onChildMoved(DataSnapshot snapshot, String previousChildName) {
                // Not needed
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Firebase listener cancelled: " + error.getMessage());
            }
        });
    }

    private void loadChildNames(String parentUserId) {
        DatabaseReference connectedDevicesRef = FirebaseDatabase.getInstance()
                .getReference("connected_devices")
                .child(parentUserId);

        connectedDevicesRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                childNames.clear();
                for (DataSnapshot deviceSnapshot : snapshot.getChildren()) {
                    String deviceId = deviceSnapshot.getKey();
                    String userName = deviceSnapshot.child("userName").getValue(String.class);
                    String deviceName = deviceSnapshot.child("deviceName").getValue(String.class);

                    // Prefer userName, fallback to deviceName
                    String displayName = (userName != null && !userName.isEmpty()) ? userName : deviceName;
                    if (displayName != null) {
                        childNames.put(deviceId, displayName);
                        Log.d(TAG, "📝 Loaded child name: " + deviceId + " -> " + displayName);
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to load child names: " + error.getMessage());
            }
        });
    }

    private void listenToChildEvents(String childDeviceId) {
        if (childListeners.containsKey(childDeviceId)) {
            Log.d(TAG, "Already listening to child: " + childDeviceId);
            return;
        }

        DatabaseReference eventsRef = permissionEventsRef
                .child(childDeviceId)
                .child("events");

        ChildEventListener listener = new ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot eventSnapshot, String previousChildKey) {
                PermissionEvent event = eventSnapshot.getValue(PermissionEvent.class);
                if (event != null) {
                    Log.d(TAG, "🔔 New permission event: " + event.getPermissionName() + " -> " + event.getAction());
                    showSystemNotification(childDeviceId, event);
                }
            }

            @Override
            public void onChildChanged(DataSnapshot snapshot, String previousChildName) {
                // Not needed
            }

            @Override
            public void onChildRemoved(DataSnapshot snapshot) {
                // Not needed
            }

            @Override
            public void onChildMoved(DataSnapshot snapshot, String previousChildName) {
                // Not needed
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Events listener cancelled: " + error.getMessage());
            }
        };

        eventsRef.addChildEventListener(listener);
        childListeners.put(childDeviceId, listener);
        Log.d(TAG, "✅ Now listening to events for: " + childDeviceId);
    }

    private void stopListeningToChild(String childDeviceId) {
        ChildEventListener listener = childListeners.remove(childDeviceId);
        if (listener != null) {
            permissionEventsRef.child(childDeviceId).child("events").removeEventListener(listener);
            Log.d(TAG, "🛑 Stopped listening to: " + childDeviceId);
        }
    }

    private void showSystemNotification(String childDeviceId, PermissionEvent event) {
        String childName = childNames.getOrDefault(childDeviceId, "Child Device");
        boolean isActivated = "ACTIVATED".equals(event.getAction());

        // Build notification
        Intent intent = new Intent(this, ParentDashboardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Choose icon and color
        int icon = isActivated ? R.drawable.ic_child : R.drawable.ic_refresh;
        int color = isActivated ? 0xFF4CAF50 : 0xFFF44336; // Green or Red

        String title = childName + ": " + event.getPermissionName() + " " + event.getAction();

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(event.getEffect())
                .setSmallIcon(icon)
                .setColor(color)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(event.getEffect()))
                .setGroup("child_" + childDeviceId); // Group by child device

        // Show notification
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(notificationId++, builder.build());

        Log.d(TAG, "📲 Notification shown: " + title);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand called");
        return START_STICKY; // Restart if killed
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // Clean up all listeners
        for (Map.Entry<String, ChildEventListener> entry : childListeners.entrySet()) {
            permissionEventsRef.child(entry.getKey()).child("events").removeEventListener(entry.getValue());
        }
        childListeners.clear();

        Log.d(TAG, "PermissionEventListener destroyed");
    }
}
