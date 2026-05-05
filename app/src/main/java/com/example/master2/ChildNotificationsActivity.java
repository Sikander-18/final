package com.example.master2;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.master2.adapters.PermissionEventAdapter;
import com.example.master2.models.PermissionEvent;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Activity that displays permission change notifications for a specific child
 * device.
 * Shows current permission status and historical events.
 */
public class ChildNotificationsActivity extends AppCompatActivity {
    private static final String TAG = "ChildNotifications";

    public static final String EXTRA_CHILD_DEVICE_ID = "childDeviceId";
    public static final String EXTRA_CHILD_NAME = "childName";
    public static final String EXTRA_PARENT_USER_ID = "parentUserId";

    private String childDeviceId;
    private String childName;
    private String parentUserId;

    private RecyclerView rvEvents;
    private LinearLayout emptyState;
    private PermissionEventAdapter adapter;

    private ImageView ivAccessibility, ivUsageStats, ivNotifications, ivBattery;
    private TextView tvStatusSummary;

    private DatabaseReference eventsRef;
    private DatabaseReference statusRef;
    private ValueEventListener eventsListener;
    private ValueEventListener statusListener;

    // 📦 APP STATUS FILTER
    private MaterialButton btnPermissionStatus, btnAppStatus;
    private LinearLayout containerPermissionStatus, containerAppStatus;
    private RecyclerView rvAppStatus;
    private LinearLayout emptyStateAppStatus;
    private AppStatusAdapter appStatusAdapter;
    private List<AppStatusEvent> appEvents = new ArrayList<>();
    private DatabaseReference appEventsRef;
    private ValueEventListener appEventsListener;

    // 🔔 BADGE COUNTERS
    private TextView tvPermissionBadge, tvAppStatusBadge;
    private int permissionUnreadCount = 0;
    private int appUnreadCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_notifications);

        // Get intent extras
        childDeviceId = getIntent().getStringExtra(EXTRA_CHILD_DEVICE_ID);
        childName = getIntent().getStringExtra(EXTRA_CHILD_NAME);
        parentUserId = getIntent().getStringExtra(EXTRA_PARENT_USER_ID);

        if (childDeviceId == null || parentUserId == null) {
            Log.e(TAG, "Missing required extras!");
            finish();
            return;
        }

        Log.d(TAG, "Opening notifications for: " + childName + " (" + childDeviceId + ")");

        setupToolbar();
        initializeViews();
        setupRecyclerView();
        setupFilterToggle();
        setupFirebaseListeners();
        setupBadgeCounters(); // Count and display unread notifications
        markNotificationsAsRead(); // Mark all as read when page opens
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            String title = (childName != null) ? childName + " - Notifications" : "Device Notifications";
            getSupportActionBar().setTitle(title);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void initializeViews() {
        rvEvents = findViewById(R.id.rvEvents);
        emptyState = findViewById(R.id.emptyState);

        ivAccessibility = findViewById(R.id.ivAccessibility);
        ivUsageStats = findViewById(R.id.ivUsageStats);
        ivNotifications = findViewById(R.id.ivNotifications);
        ivBattery = findViewById(R.id.ivBattery);
        tvStatusSummary = findViewById(R.id.tvStatusSummary);

        // 📦 APP STATUS VIEWS
        btnPermissionStatus = findViewById(R.id.btnPermissionStatus);
        btnAppStatus = findViewById(R.id.btnAppStatus);
        containerPermissionStatus = findViewById(R.id.containerPermissionStatus);
        containerAppStatus = findViewById(R.id.containerAppStatus);
        rvAppStatus = findViewById(R.id.rvAppStatus);
        emptyStateAppStatus = findViewById(R.id.emptyStateAppStatus);

        // 🔔 BADGE VIEWS
        tvPermissionBadge = findViewById(R.id.tvPermissionBadge);
        tvAppStatusBadge = findViewById(R.id.tvAppStatusBadge);
    }

    private void setupRecyclerView() {
        adapter = new PermissionEventAdapter(this);
        rvEvents.setLayoutManager(new LinearLayoutManager(this));
        rvEvents.setAdapter(adapter);

        // Setup app status recycler view
        appStatusAdapter = new AppStatusAdapter(this, appEvents);
        rvAppStatus.setLayoutManager(new LinearLayoutManager(this));
        rvAppStatus.setAdapter(appStatusAdapter);
    }

    /**
     * 🔄 Setup filter toggle between Permission Status and App Status
     */
    private void setupFilterToggle() {
        if (btnPermissionStatus == null || btnAppStatus == null)
            return;

        // Set initial state - Permission Status selected
        btnPermissionStatus.setBackgroundColor(getResources().getColor(R.color.primary_600));
        btnPermissionStatus.setTextColor(getResources().getColor(android.R.color.white));

        btnPermissionStatus.setOnClickListener(v -> {
            // Update button states
            btnPermissionStatus.setBackgroundColor(getResources().getColor(R.color.primary_600));
            btnPermissionStatus.setTextColor(getResources().getColor(android.R.color.white));
            btnAppStatus.setBackgroundColor(getResources().getColor(android.R.color.transparent));
            btnAppStatus.setTextColor(getResources().getColor(R.color.primary_600));

            showPermissionStatusView();
        });

        btnAppStatus.setOnClickListener(v -> {
            // Update button states
            btnAppStatus.setBackgroundColor(getResources().getColor(R.color.primary_600));
            btnAppStatus.setTextColor(getResources().getColor(android.R.color.white));
            btnPermissionStatus.setBackgroundColor(getResources().getColor(android.R.color.transparent));
            btnPermissionStatus.setTextColor(getResources().getColor(R.color.primary_600));

            showAppStatusView();
        });
    }

    /**
     * Show Permission Status view
     */
    private void showPermissionStatusView() {
        containerPermissionStatus.setVisibility(View.VISIBLE);
        containerAppStatus.setVisibility(View.GONE);
    }

    /**
     * Show App Status view
     */
    private void showAppStatusView() {
        containerPermissionStatus.setVisibility(View.GONE);
        containerAppStatus.setVisibility(View.VISIBLE);

        // Setup app events listener if not already done
        if (appEventsRef == null) {
            setupAppEventsListener();
        }
    }

    private void setupFirebaseListeners() {
        // 🔧 FIX: Try main path first, fallback to alternative if empty
        String mainPath = "permission_events/" + parentUserId + "/" + childDeviceId;
        String fallbackPath = "child_permission_events/" + childDeviceId;

        Log.d(TAG, "Primary path: " + mainPath);
        Log.d(TAG, "Fallback path: " + fallbackPath);

        // Try main path first
        tryMainPath(mainPath, fallbackPath);
    }

    private void tryMainPath(String mainPath, String fallbackPath) {
        DatabaseReference mainRef = FirebaseDatabase.getInstance().getReference(mainPath);

        // Check if main path has data
        mainRef.get().addOnSuccessListener(snapshot -> {
            if (snapshot.exists() && snapshot.child("events").exists()) {
                Log.d(TAG, "✅ Using main path: " + mainPath);
                setupListenersForPath(mainPath);
            } else {
                Log.d(TAG, "⚠️ Main path empty, trying fallback: " + fallbackPath);
                setupListenersForPath(fallbackPath);
            }
        }).addOnFailureListener(e -> {
            Log.e(TAG, "❌ Failed to check main path, using fallback");
            setupListenersForPath(fallbackPath);
        });
    }

    private void setupListenersForPath(String basePath) {
        Log.d(TAG, "📡 Listening to: " + basePath);

        // Events listener (sorted by timestamp, newest first)
        eventsRef = FirebaseDatabase.getInstance().getReference(basePath).child("events");
        eventsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<PermissionEvent> events = new ArrayList<>();
                for (DataSnapshot eventSnapshot : snapshot.getChildren()) {
                    PermissionEvent event = eventSnapshot.getValue(PermissionEvent.class);
                    if (event != null) {
                        events.add(event);
                    }
                }

                // Sort by timestamp (newest first)
                Collections.sort(events, (e1, e2) -> Long.compare(e2.getTimestamp(), e1.getTimestamp()));

                adapter.setEvents(events);
                updateEmptyState(events.isEmpty());
                Log.d(TAG, "Loaded " + events.size() + " events");
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Events listener cancelled: " + error.getMessage());
            }
        };
        eventsRef.addValueEventListener(eventsListener);

        // Current status listener
        statusRef = FirebaseDatabase.getInstance().getReference(basePath).child("currentStatus");
        statusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    Log.d(TAG, "No current status available");
                    return;
                }

                Boolean accessibility = snapshot.child("accessibility").getValue(Boolean.class);
                Boolean usageStats = snapshot.child("usageStats").getValue(Boolean.class);
                Boolean notifications = snapshot.child("notifications").getValue(Boolean.class);
                Boolean batteryOpt = snapshot.child("batteryOptimization").getValue(Boolean.class);

                updateStatusIcons(
                        accessibility != null ? accessibility : false,
                        usageStats != null ? usageStats : false,
                        notifications != null ? notifications : false,
                        batteryOpt != null ? batteryOpt : false);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Status listener cancelled: " + error.getMessage());
            }
        };
        statusRef.addValueEventListener(statusListener);
    }

    private void updateStatusIcons(boolean accessibility, boolean usageStats,
            boolean notifications, boolean batteryOpt) {
        int greenColor = ContextCompat.getColor(this, android.R.color.holo_green_dark);
        int redColor = ContextCompat.getColor(this, android.R.color.holo_red_dark);

        ivAccessibility.setColorFilter(accessibility ? greenColor : redColor);
        ivUsageStats.setColorFilter(usageStats ? greenColor : redColor);
        ivNotifications.setColorFilter(notifications ? greenColor : redColor);
        ivBattery.setColorFilter(batteryOpt ? greenColor : redColor);

        int activeCount = 0;
        if (accessibility)
            activeCount++;
        if (usageStats)
            activeCount++;
        if (notifications)
            activeCount++;
        if (batteryOpt)
            activeCount++;

        if (activeCount == 4) {
            tvStatusSummary.setText("All permissions active ✓");
            tvStatusSummary.setTextColor(greenColor);
        } else if (activeCount == 0) {
            tvStatusSummary.setText("⚠️ All permissions disabled!");
            tvStatusSummary.setTextColor(redColor);
        } else {
            tvStatusSummary.setText("⚠️ " + (4 - activeCount) + " permission(s) disabled");
            tvStatusSummary.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        }
    }

    private void updateEmptyState(boolean isEmpty) {
        emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvEvents.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /**
     * 📦 Setup Firebase listener for app installation/uninstallation events
     */
    private void setupAppEventsListener() {
        String firebasePath = "child_devices/" + childDeviceId + "/app_events";
        Log.d(TAG, "========================================");
        Log.d(TAG, "📱 PARENT: Setting up App Events Listener");
        Log.d(TAG, "Child Device ID: " + childDeviceId);
        Log.d(TAG, "Firebase Path: " + firebasePath);
        Log.d(TAG, "========================================");

        appEventsRef = FirebaseDatabase.getInstance()
                .getReference("child_devices")
                .child(childDeviceId)
                .child("app_events");

        appEventsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(TAG, "📦 App Events Data Changed!");
                Log.d(TAG, "Snapshot exists: " + snapshot.exists());
                Log.d(TAG, "Snapshot children count: " + snapshot.getChildrenCount());

                appEvents.clear();
                for (DataSnapshot eventSnap : snapshot.getChildren()) {
                    AppStatusEvent event = eventSnap.getValue(AppStatusEvent.class);
                    if (event != null) {
                        appEvents.add(event);
                        Log.d(TAG, "✅ Loaded event: " + event.getAction() + " - " + event.getAppName());
                    } else {
                        Log.w(TAG, "⚠️ Event is null for snapshot: " + eventSnap.getKey());
                    }
                }

                // Sort by timestamp (newest first)
                Collections.sort(appEvents, (e1, e2) -> Long.compare(e2.getTimestamp(), e1.getTimestamp()));

                appStatusAdapter.notifyDataSetChanged();
                updateAppStatusEmptyState(appEvents.isEmpty());
                Log.d(TAG, "📊 TOTAL app events loaded: " + appEvents.size());
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "❌❌❌ App events listener CANCELLED!");
                Log.e(TAG, "Error: " + error.getMessage());
                Log.e(TAG, "Code: " + error.getCode());
            }
        };

        appEventsRef.addValueEventListener(appEventsListener);
        Log.d(TAG, "✅ Listener attached to: " + firebasePath);
    }

    /**
     * 🔔 Setup badge counters to show unread notifications count
     */
    private void setupBadgeCounters() {
        Log.d(TAG, "🔔 Setting up badge counters for device: " + childDeviceId);

        // Count permission events
        countPermissionEvents();

        // Count app events
        countAppEvents();
    }

    /**
     * Count permission events and update badge
     */
    private void countPermissionEvents() {
        // First, get lastReadTimestamp
        DatabaseReference lastReadRef = FirebaseDatabase.getInstance()
                .getReference("child_devices")
                .child(childDeviceId)
                .child("notifications")
                .child("lastReadTimestamp");

        lastReadRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot lastReadSnapshot) {
                Long lastRead = lastReadSnapshot.getValue(Long.class);
                long lastReadTime = lastRead != null ? lastRead : 0;

                Log.d(TAG, "🔔 Last read timestamp: " + lastReadTime);

                // Now count events AFTER lastReadTime
                String mainPath = "permission_events/" + parentUserId + "/" + childDeviceId + "/events";
                DatabaseReference mainRef = FirebaseDatabase.getInstance().getReference(mainPath);

                mainRef.addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        int count = 0;
                        for (DataSnapshot event : snapshot.getChildren()) {
                            Long timestamp = event.child("timestamp").getValue(Long.class);
                            if (timestamp != null && timestamp > lastReadTime) {
                                count++;
                            }
                        }
                        Log.d(TAG, "🔔 Permission UNREAD events count: " + count);
                        permissionUnreadCount = count;
                        updatePermissionBadge(count);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to count permission events: " + error.getMessage());
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to get lastReadTimestamp: " + error.getMessage());
            }
        });
    }

    /**
     * Count app events and update badge
     */
    private void countAppEvents() {
        // First, get lastReadTimestamp
        DatabaseReference lastReadRef = FirebaseDatabase.getInstance()
                .getReference("child_devices")
                .child(childDeviceId)
                .child("notifications")
                .child("lastReadTimestamp");

        lastReadRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot lastReadSnapshot) {
                Long lastRead = lastReadSnapshot.getValue(Long.class);
                long lastReadTime = lastRead != null ? lastRead : 0;

                // Now count app events AFTER lastReadTime
                DatabaseReference appEventsCountRef = FirebaseDatabase.getInstance()
                        .getReference("child_devices")
                        .child(childDeviceId)
                        .child("app_events");

                appEventsCountRef.addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        int count = 0;
                        for (DataSnapshot event : snapshot.getChildren()) {
                            Long timestamp = event.child("timestamp").getValue(Long.class);
                            if (timestamp != null && timestamp > lastReadTime) {
                                count++;
                            }
                        }
                        Log.d(TAG, "🔔 App UNREAD events count: " + count);
                        appUnreadCount = count;
                        updateAppStatusBadge(count);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Failed to count app events: " + error.getMessage());
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Failed to get lastReadTimestamp: " + error.getMessage());
            }
        });
    }

    /**
     * Update permission status badge
     */
    private void updatePermissionBadge(int count) {
        runOnUiThread(() -> {
            if (tvPermissionBadge != null) {
                if (count > 0) {
                    tvPermissionBadge.setVisibility(View.VISIBLE);
                    tvPermissionBadge.setText(count > 99 ? "99+" : String.valueOf(count));
                    Log.d(TAG, "🔔 Permission badge updated: " + count);
                } else {
                    tvPermissionBadge.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * Update app status badge
     */
    private void updateAppStatusBadge(int count) {
        runOnUiThread(() -> {
            if (tvAppStatusBadge != null) {
                if (count > 0) {
                    tvAppStatusBadge.setVisibility(View.VISIBLE);
                    tvAppStatusBadge.setText(count > 99 ? "99+" : String.valueOf(count));
                    Log.d(TAG, "🔔 App status badge updated: " + count);
                } else {
                    tvAppStatusBadge.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * Update app status empty state
     */
    private void updateAppStatusEmptyState(boolean isEmpty) {
        emptyStateAppStatus.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        rvAppStatus.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    /**
     * 🔔 Mark notifications as read by saving current timestamp
     */
    private void markNotificationsAsRead() {
        if (childDeviceId == null)
            return;

        long currentTime = System.currentTimeMillis();

        FirebaseDatabase.getInstance()
                .getReference("child_devices")
                .child(childDeviceId)
                .child("notifications")
                .child("lastReadTimestamp")
                .setValue(currentTime)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "🔔 Marked notifications as read at: " + currentTime);
                    // Badges will automatically update through listeners
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to mark as read: " + e.getMessage());
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Remove listeners to prevent memory leaks
        if (eventsRef != null && eventsListener != null) {
            eventsRef.removeEventListener(eventsListener);
        }
        if (statusRef != null && statusListener != null) {
            statusRef.removeEventListener(statusListener);
        }
        if (appEventsRef != null && appEventsListener != null) {
            appEventsRef.removeEventListener(appEventsListener);
        }
    }
}
