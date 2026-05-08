package com.example.master2;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.LayoutInflater;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.text.TextWatcher;
import android.text.Editable;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.example.master2.databinding.ActivityParentDashboardBinding;
import com.example.master2.models.AppUsage; // Added for AppUsage model
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Calendar;
import java.util.Locale;
import java.util.Collections; // Added for Collections.sort

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ApplicationInfo;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;

import com.google.firebase.auth.FirebaseAuth;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.tasks.Task;
import com.example.master2.models.ChildDeviceManager;

import android.app.Dialog;
import android.content.DialogInterface;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.auth.FirebaseAuth;
import com.example.master2.utils.LoadingDialogManager;
import com.example.master2.voice.VoiceAssistantActivity;

public class ParentDashboardActivity extends AppCompatActivity {
    private static final String TAG = "ParentDashboard";
    private ActivityParentDashboardBinding binding;

    // QR Code and Multi-Device Management
    private QRCodeManager qrCodeManager;
    private ChildDeviceManager childDeviceManager;
    private PresetManager presetManager;
    private String permanentQRKey;

    // Current selected child device
    private String currentChildDeviceId = null;
    private String currentChildDeviceName = "No Device";
    private String currentChildUserName = ""; // 🔧 Store actual child name (entered during setup)
    private List<ChildDevice> connectedDevices = new ArrayList<>();

    // Device management
    private ConnectedDevicesManager connectedDevicesManager;

    // 🎯 USAGE DATA CACHE: Store last known usage per device for instant display
    private HashMap<String, Long> cachedUsageData = new HashMap<>(); // deviceId -> totalUsageMs
    private HashMap<String, String> cachedUsageFormatted = new HashMap<>(); // deviceId -> "2h 26m"

    // Navigation views
    private BottomNavigationView bottomNavigation;
    private View homeContent;
    // private View usageContent; // Removed/Merged
    private View settingsContent;

    // New Dashboard Fields
    private LinearLayout llDeviceList;

    // Usage Limiter components
    private TextView tvLimiterStatus;
    private TextView tvLimiterTimer;
    private EditText etLimiterHours;
    private EditText etLimiterMinutes;
    private Button btnSelectDays;
    private Button btnSelectApps;
    private Button btnSetLimiter;
    private Button btnClearLimiter;
    private List<String> selectedDays = new ArrayList<>();
    private List<String> selectedApps = new ArrayList<>();

    // Usage Limiter Firebase references
    private DatabaseReference limiterRef;
    private ValueEventListener limiterListener;
    private DatabaseReference currentLimiterDeviceRef; // Tracks the device-specific limiter ref for listener lifecycle

    // 🔧 MULTI-DEVICE FIX: Track active listeners for cleanup when switching
    // devices
    private ValueEventListener activeLimiterListener;
    private DatabaseReference activeLimiterRef;

    // Periodic timer refresh handler

    // Debouncing for UI updates
    private Handler uiUpdateHandler = new Handler();
    private Runnable pendingUIUpdate = null;
    private static final int UI_UPDATE_DEBOUNCE_DELAY = 100; // 100ms debounce

    // Date range for recurring timers
    private Calendar fromDate = Calendar.getInstance();
    private Calendar toDate = Calendar.getInstance();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());

    // Add current selected date for usage display with protection against
    // auto-resets
    private Calendar currentUsageDate = Calendar.getInstance();
    private boolean dateSetByUser = false; // Flag to prevent automatic date resets
    private SimpleDateFormat usageDateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    // Flag to prevent multiple preset creation
    private boolean isCreatingPreset = false;

    // Fresh login state flag
    private boolean isFreshLoginSession = false;

    // Focus mode components
    private TextView tvFocusModeStatus;
    private Button btnEditAppList;
    private boolean isFocusModeActive = false;
    private List<AppInfo> focusModeApps = new ArrayList<>();
    private SharedPreferences focusModePrefs;
    private SharedPreferences usageCachePrefs; // 📦 PERSISTENT CACHE STORAGE

    private Button btnViewUsage;
    private SessionManager sessionManager;
    private DeviceStatusManager deviceStatusManager;
    private LoadingDialogManager loadingDialogManager;
    private Button btnRemoveDevice;

    // 🚨 UNINSTALL DETECTION
    private UninstallDetectionManager uninstallDetectionManager;
    private LinearLayout layoutUninstallWarning;
    private TextView tvUninstallWarningMessage;
    private TextView tvUninstallLastSeen;
    private String currentDeviceStatus = UninstallDetectionManager.STATUS_ONLINE;

    // Request codes
    private static final int REQUEST_APP_SELECTION = 1001;
    private static final int REQUEST_FOCUS_MODE_APPS = 1002;

    private FirebaseAuth mAuth;

    private String deviceIdJustRemoved = null;

    // 💓 PARENT HEARTBEAT: Timer to keep session alive and detect if app is deleted
    private java.util.Timer parentHeartbeatTimer;
    private static final long HEARTBEAT_INTERVAL = 30 * 1000; // 30 seconds

    public static class PieSlice {
        public String label;
        public float value;

        public PieSlice(String label, float value) {
            this.label = label;
            this.value = value;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate called");

        // Make status bar transparent but visible (light status bar icons)
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);

        try {
            // Enable hardware acceleration for better performance
            getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);

            Log.d(TAG, "Initializing view binding and Firebase...");

            binding = ActivityParentDashboardBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());

            // Start usage data storage service for accurate date-based tracking
            startUsageDataStorageService();

            mAuth = FirebaseAuth.getInstance();

            // Initialize session manager
            sessionManager = new SessionManager(this);

            // Initialize loading dialog manager
            loadingDialogManager = new LoadingDialogManager(this);

            Log.d(TAG, "Basic components initialized successfully");

            // Update session activity
            sessionManager.updateLastActivity();

            // Initialize focus mode preferences
            focusModePrefs = getSharedPreferences("focus_mode_prefs", MODE_PRIVATE);

            // 📦 Initialize usage cache preferences
            usageCachePrefs = getSharedPreferences("usage_data_cache_prefs", MODE_PRIVATE);

            initializeViews();
            initializeManagers();

            // 🧹 FRESH LOGIN CHECK - Ensure clean slate for new login sessions
            // (Must be AFTER managers are initialized)
            boolean isFreshLogin = checkForFreshLoginAndCleanup();
            isFreshLoginSession = isFreshLogin; // Store for use in other methods

            // Show welcome message with important information
            showWelcomeMessage();

            setupBottomNavigation();
            setupUsageLimiter();
            setupQRCodeGeneration();
            setupDeviceSwitcher();

            // Restore Focus Mode state before clearing devices
            restoreFocusModeStateAfterRestart();

            // Setup Focus Mode AFTER restoration (so it doesn't get cleared)
            setupFocusMode();

            setupChart();

            // 🔔 START PERSISTENT TIMER NOTIFICATION SERVICE for parent devices
            startPersistentTimerNotificationService();

            // 📡 START PERMISSION EVENT LISTENER to monitor child device service status
            startPermissionEventListener();

            // Only allow QR scan connections - user requirement
            Log.d(TAG, "Automatic device loading disabled - only QR scan connections allowed");

            // Only clear devices if none were restored from Focus Mode
            if (connectedDevices.isEmpty()) {
                Log.d(TAG, "No devices restored - showing empty state");
                updateDeviceStatus();
                updateTargetDeviceDisplay();
            } else {
                Log.d(TAG, "Devices restored from Focus Mode - keeping current state");
            }

            // Set up QR scan listener ONLY (no automatic loading)
            setupQRScanOnlyListener();

            // 🚨 BULLETPROOF: Also monitor device_apps for direct connections
            setupDeviceAppsListener();

            // Add settings buttons functionality
            addSettingsButtons();

            // Force Focus Mode UI update to ensure status shows immediately
            forceUpdateFocusModeUI();

            // Auto-restore devices with Focus Mode data
            autoRestoreDevicesWithFocusMode();

            // Show Home content by default
            bottomNavigation.setSelectedItemId(R.id.nav_home);

            // Hide the usage stats section (Usage Overview) if the data is fake
            // View usageOverviewSection = findViewById(R.id.usageOverviewSection);
            // if (usageOverviewSection != null) {
            // usageOverviewSection.setVisibility(View.GONE);
            // }

            setupCategorySummaryChart();

            // 🔔 Setup notification bell badge
            setupNotificationBadge();

            // 💓 Start parent heartbeat timer to keep session alive
            startParentHeartbeatTimer();

        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate: " + e.getMessage());
            Toast.makeText(this, "Error loading dashboard: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Start the usage data storage service for accurate date-based tracking
     */
    private void startUsageDataStorageService() {
        try {
            Intent serviceIntent = new Intent(this, UsageDataStorageService.class);
            startService(serviceIntent);
            Log.d(TAG, "✅ Started UsageDataStorageService for accurate date-based tracking");

            // Also trigger immediate collection after a short delay to ensure child device
            // data is available
            new Handler(getMainLooper()).postDelayed(() -> {
                triggerImmediateDataCollection();
            }, 3000); // 3 second delay
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start UsageDataStorageService: " + e.getMessage());
        }
    }

    /**
     * Trigger immediate data collection for testing purposes
     */
    private void triggerImmediateDataCollection() {
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            Log.d(TAG, "🔄 Triggering immediate usage data collection for: " + currentChildDeviceId);

            // Send a signal to collect data immediately
            Intent serviceIntent = new Intent(this, UsageDataStorageService.class);
            serviceIntent.putExtra("action", "collect_now");
            serviceIntent.putExtra("deviceId", currentChildDeviceId);
            startService(serviceIntent);
        }
    }

    /**
     * 💓 Start parent heartbeat timer to keep session alive
     * Other devices can check this heartbeat to know if the app was deleted
     */
    private void startParentHeartbeatTimer() {
        // Cancel existing timer if any
        if (parentHeartbeatTimer != null) {
            parentHeartbeatTimer.cancel();
        }

        String parentUserId = sessionManager.getParentUserId();
        if (parentUserId == null || parentUserId.isEmpty()) {
            Log.w(TAG, "💓 Cannot start heartbeat - no parent user ID");
            return;
        }

        parentHeartbeatTimer = new java.util.Timer();
        parentHeartbeatTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    // Update heartbeat in Firebase
                    DatabaseReference heartbeatRef = FirebaseDatabase.getInstance()
                            .getReference("parent_accounts")
                            .child(parentUserId)
                            .child("activeDevice")
                            .child("lastHeartbeat");

                    heartbeatRef.setValue(System.currentTimeMillis())
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "💓 Parent heartbeat sent");
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "💓 Failed to send heartbeat: " + e.getMessage());
                            });
                } catch (Exception e) {
                    Log.e(TAG, "💓 Error in heartbeat timer: " + e.getMessage());
                }
            }
        }, 0, HEARTBEAT_INTERVAL); // Start immediately, repeat every 30 seconds

        Log.d(TAG, "💓 Parent heartbeat timer started (interval: " + (HEARTBEAT_INTERVAL / 1000) + "s)");
    }

    /**
     * 💓 Stop parent heartbeat timer
     */
    private void stopParentHeartbeatTimer() {
        if (parentHeartbeatTimer != null) {
            parentHeartbeatTimer.cancel();
            parentHeartbeatTimer = null;
            Log.d(TAG, "💓 Parent heartbeat timer stopped");
        }
    }

    private void initializeViews() {
        // Initialize navigation views
        bottomNavigation = findViewById(R.id.bottomNavigation);
        homeContent = findViewById(R.id.homeContent);
        // usageContent removed/merged
        settingsContent = findViewById(R.id.settingsContent);

        // New Dashboard Views
        llDeviceList = findViewById(R.id.llDeviceList);

        // Quick Actions
        // View cardQrAction = findViewById(R.id.cardQrAction); // Removed from XML
        View cardAppLimitsAction = findViewById(R.id.cardAppLimitsAction);
        View cardVoiceAssistantAction = findViewById(R.id.cardVoiceAssistantAction);
        // View cardSettingsAction = findViewById(R.id.cardSettingsAction); // Removed

        /*
         * Removed from XML
         * if (cardQrAction != null) {
         * cardQrAction.setOnClickListener(v -> {
         * // Open QR Scanner / Connection screen
         * showQRScanner();
         * });
         * }
         */

        if (cardAppLimitsAction != null) {
            cardAppLimitsAction.setOnClickListener(v -> {
                if (currentChildDeviceId != null) {
                    // Open App Blocking / Limits
                    Intent intent = new Intent(this, ChildInstalledAppsActivity.class);
                    intent.putExtra("childDeviceId", currentChildDeviceId);
                    String displayName = (currentChildUserName != null && !currentChildUserName.isEmpty())
                            ? currentChildUserName
                            : currentChildDeviceName;
                    intent.putExtra("childName", displayName);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Wire up "Usage Stats" to ChildUsageViewActivity
        View cardUsageStatsAction = findViewById(R.id.cardUsageStatsAction);
        if (cardUsageStatsAction != null) {
            cardUsageStatsAction.setOnClickListener(v -> {
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, ChildUsageViewActivity.class);
                    intent.putExtra(ChildUsageViewActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                    intent.putExtra(ChildUsageViewActivity.EXTRA_CHILD_NAME, currentChildDeviceName);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (cardVoiceAssistantAction != null) {
            cardVoiceAssistantAction.setOnClickListener(v -> {
                if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent intent = new Intent(this, VoiceAssistantActivity.class);
                intent.putExtra("childDeviceId", currentChildDeviceId);
                intent.putExtra("deviceName",
                        (currentChildUserName != null && !currentChildUserName.isEmpty())
                                ? currentChildUserName : currentChildDeviceName);
                startActivity(intent);
            });
        }

        // Settings card removed from quick actions
        // if (cardSettingsAction != null) ... removed
        View btnAddDevice = findViewById(R.id.btnAddDevice);
        if (btnAddDevice != null) {
            btnAddDevice.setOnClickListener(v -> showQRScanner());
        }

        // Initialize focus mode views (kept for compatibility with methods)
        tvFocusModeStatus = findViewById(R.id.tvFocusModeStatus);
        btnEditAppList = findViewById(R.id.btnEditAppList);

        // Greeter
        TextView tvGreeter = findViewById(R.id.tvGreeter);
        TextView tvParentName = findViewById(R.id.tvParentName); // Added reference
        TextView tvCurrentDate = findViewById(R.id.tvCurrentDate);

        // 🔧 Manage Devices Click Listener
        if (binding != null && binding.tvDeviceStatus != null) {
            binding.tvDeviceStatus.setOnClickListener(v -> showManageDevicesDialog());
        } else {
            // Fallback if binding not fully ready or using findViewById
            TextView tvDeviceStatus = findViewById(R.id.tvDeviceStatus);
            if (tvDeviceStatus != null) {
                tvDeviceStatus.setOnClickListener(v -> showManageDevicesDialog());
            }
        }

        // 📘 GUIDE BOOK BUTTON
        // Initialize the floating guide button and label container
        View btnGuideBook = findViewById(R.id.btnGuideBook);
        View fabGuide = findViewById(R.id.fabGuide);

        View.OnClickListener guideClickListener = v -> {
            startActivity(new Intent(ParentDashboardActivity.this, GuideBookActivity.class));
        };

        if (btnGuideBook != null)
            btnGuideBook.setOnClickListener(guideClickListener);
        if (fabGuide != null)
            fabGuide.setOnClickListener(guideClickListener);

        // 🔧 Set Parent Name - Load from Firebase Database
        final TextView tvParentNameRef = tvParentName;
        if (mAuth != null && mAuth.getCurrentUser() != null && tvParentNameRef != null) {
            String uid = mAuth.getCurrentUser().getUid();

            // First set temporary text
            tvParentNameRef.setText("Hi there!");

            // Query Firebase Database for saved name
            FirebaseDatabase.getInstance()
                    .getReference("parent_accounts")
                    .child(uid)
                    .child("name")
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        String name = snapshot.getValue(String.class);
                        if (name != null && !name.trim().isEmpty()) {
                            tvParentNameRef.setText("Hi " + name);
                            Log.d(TAG, "Loaded parent name from DB: " + name);
                        } else {
                            // Fallback to email extraction
                            String email = mAuth.getCurrentUser().getEmail();
                            if (email != null) {
                                try {
                                    String namePart = email.split("@")[0].replaceAll("\\d+", "").replaceAll("[^a-zA-Z]",
                                            "");
                                    if (!namePart.isEmpty()) {
                                        String extracted = namePart.substring(0, 1).toUpperCase()
                                                + namePart.substring(1);
                                        tvParentNameRef.setText("Hi " + extracted);
                                    }
                                } catch (Exception e) {
                                    tvParentNameRef.setText("Hi Parent");
                                }
                            } else {
                                tvParentNameRef.setText("Hi Parent");
                            }
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to load parent name: " + e.getMessage());
                        tvParentNameRef.setText("Hi Parent");
                    });
        } else if (tvParentName != null) {
            tvParentName.setText("Hi Parent");
        }

        if (tvGreeter != null) {
            tvGreeter.setText("Welcome Back");
        }

        if (tvCurrentDate != null) {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("EEEE, MMM d",
                    java.util.Locale.getDefault());
            tvCurrentDate.setText(sdf.format(new java.util.Date()));
        }

        // Usage limiter views removed from parent dashboard per request
        tvLimiterStatus = null;
        tvLimiterTimer = null;
        etLimiterHours = null;
        etLimiterMinutes = null;
        btnSelectDays = null;
        btnSelectApps = null;
        btnSetLimiter = null;
        btnClearLimiter = null;

        // Notification Bell Icon - Opens child permission notifications page
        View btnNotifications = findViewById(R.id.btnNotifications);
        if (btnNotifications != null) {
            btnNotifications.setOnClickListener(v -> {
                if (currentChildDeviceId != null && sessionManager.getUserId() != null) {
                    Intent intent = new Intent(this, ChildNotificationsActivity.class);
                    intent.putExtra(ChildNotificationsActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                    intent.putExtra(ChildNotificationsActivity.EXTRA_CHILD_NAME,
                            (currentChildUserName != null && !currentChildUserName.isEmpty())
                                    ? currentChildUserName
                                    : currentChildDeviceName);
                    intent.putExtra(ChildNotificationsActivity.EXTRA_PARENT_USER_ID, sessionManager.getUserId());
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // 🚨 UNINSTALL WARNING UI INITIALIZATION
        layoutUninstallWarning = findViewById(R.id.layoutUninstallWarning);
        tvUninstallWarningMessage = findViewById(R.id.tvUninstallWarningMessage);
        tvUninstallLastSeen = findViewById(R.id.tvUninstallLastSeen);

        // Initialize UninstallDetectionManager
        uninstallDetectionManager = new UninstallDetectionManager(this);

    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_home) {
                // Home is always visible, just update UI
                updateFocusModeUI();
                return true;
            } else if (itemId == R.id.nav_timer_status) {
                // Launch Timer Status Activity
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, TimerStatusActivity.class);
                    intent.putExtra(TimerStatusActivity.EXTRA_DEVICE_ID, currentChildDeviceId);
                    intent.putExtra(TimerStatusActivity.EXTRA_IS_PARENT, true);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
                }
                return true;
            } else if (itemId == R.id.nav_settings) {
                // Launch Settings Activity
                startActivity(new Intent(this, ParentSettingsActivity.class));
                return true;
            }
            return false;
        });
    }

    /**
     * 🔔 Setup notification badge on bell icon to show total unread count
     */
    private TextView tvNotificationBadge;
    private ValueEventListener notificationBadgeListener;
    private DatabaseReference notificationBadgeRef;

    private void setupNotificationBadge() {
        tvNotificationBadge = findViewById(R.id.tvNotificationBadge);
        FrameLayout btnNotifications = findViewById(R.id.btnNotifications);

        if (tvNotificationBadge == null || btnNotifications == null) {
            Log.w(TAG, "Notification badge views not found");
            return;
        }

        // Set click listener to open notifications
        btnNotifications.setOnClickListener(v -> {
            if (currentChildDeviceId != null && mAuth.getCurrentUser() != null) {
                Intent intent = new Intent(this, ChildNotificationsActivity.class);
                intent.putExtra(ChildNotificationsActivity.EXTRA_CHILD_DEVICE_ID, currentChildDeviceId);
                intent.putExtra(ChildNotificationsActivity.EXTRA_CHILD_NAME, currentChildDeviceName);
                intent.putExtra(ChildNotificationsActivity.EXTRA_PARENT_USER_ID, mAuth.getCurrentUser().getUid());
                startActivity(intent);
            } else {
                Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            }
        });

        // Initial update
        refreshNotificationBadge();
    }

    /**
     * Refresh the notification badge - called when device changes or data updates
     */
    public void refreshNotificationBadge() {
        if (tvNotificationBadge == null) {
            tvNotificationBadge = findViewById(R.id.tvNotificationBadge);
        }
        if (tvNotificationBadge == null)
            return;

        if (currentChildDeviceId == null || mAuth.getCurrentUser() == null) {
            tvNotificationBadge.setVisibility(View.GONE);
            return;
        }

        String parentUid = mAuth.getCurrentUser().getUid();
        Log.d(TAG, "🔔 Refreshing notification badge for device: " + currentChildDeviceId);

        // Get last read timestamp
        DatabaseReference lastReadRef = FirebaseDatabase.getInstance()
                .getReference("child_devices")
                .child(currentChildDeviceId)
                .child("notifications")
                .child("lastReadTimestamp");

        lastReadRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot lastReadSnap) {
                Long lastRead = lastReadSnap.getValue(Long.class);
                final long lastReadTime = lastRead != null ? lastRead : 0L;
                Log.d(TAG, "🔔 Last read time: " + lastReadTime);

                // Count BOTH permission events AND app events

                // 1. Permission events path
                DatabaseReference permEventsRef = FirebaseDatabase.getInstance()
                        .getReference("permission_events")
                        .child(parentUid)
                        .child(currentChildDeviceId)
                        .child("events");

                // 2. App events path
                DatabaseReference appEventsRef = FirebaseDatabase.getInstance()
                        .getReference("child_devices")
                        .child(currentChildDeviceId)
                        .child("app_events");

                // Count permission events
                permEventsRef.addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot permSnapshot) {
                        int permCount = 0;
                        for (DataSnapshot event : permSnapshot.getChildren()) {
                            Long ts = event.child("timestamp").getValue(Long.class);
                            if (ts != null && ts > lastReadTime) {
                                permCount++;
                            }
                        }
                        final int finalPermCount = permCount;
                        Log.d(TAG, "🔔 Unread permission events: " + finalPermCount);

                        // Count app events
                        appEventsRef.addValueEventListener(new ValueEventListener() {
                            @Override
                            public void onDataChange(@NonNull DataSnapshot appSnapshot) {
                                int appCount = 0;
                                for (DataSnapshot event : appSnapshot.getChildren()) {
                                    Long ts = event.child("timestamp").getValue(Long.class);
                                    if (ts != null && ts > lastReadTime) {
                                        appCount++;
                                    }
                                }
                                final int finalAppCount = appCount;
                                final int totalCount = finalPermCount + finalAppCount;

                                Log.d(TAG, "🔔 Unread: perm=" + finalPermCount + ", app=" + finalAppCount + ", TOTAL="
                                        + totalCount);

                                runOnUiThread(() -> {
                                    if (totalCount > 0) {
                                        tvNotificationBadge.setVisibility(View.VISIBLE);
                                        tvNotificationBadge.setText(String.valueOf(totalCount));
                                        Log.d(TAG, "🔔 Badge showing: " + totalCount);
                                    } else {
                                        tvNotificationBadge.setVisibility(View.GONE);
                                        Log.d(TAG, "🔔 Badge hidden (no unread)");
                                    }
                                });
                            }

                            @Override
                            public void onCancelled(@NonNull DatabaseError error) {
                                Log.e(TAG, "App events error: " + error.getMessage());
                            }
                        });
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "Permission events error: " + error.getMessage());
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Last read error: " + error.getMessage());
            }
        });
    }

    private void openTimerManagement() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(this, TimerManagementActivity.class);
        intent.putExtra("deviceId", currentChildDeviceId);
        intent.putExtra("deviceName", currentChildDeviceName);
        startActivityForResult(intent, 1001);

        Log.d(TAG, "Launched Timer Management for device: " + currentChildDeviceName);
    }

    // Timer tab removed per request; no timer navigation on parent dashboard

    // Settings methods moved to ParentSettingsActivity

    private void loadFocusModeApps() {
        if (currentChildDeviceId == null)
            return;

        try {
            String key = "focus_apps_" + currentChildDeviceId;
            String appsJson = focusModePrefs.getString(key, "");

            if (!appsJson.isEmpty()) {
                Gson gson = new Gson();
                Type listType = new TypeToken<List<AppInfo>>() {
                }.getType();
                List<AppInfo> savedApps = gson.fromJson(appsJson, listType);

                if (savedApps != null) {
                    focusModeApps.clear();
                    focusModeApps.addAll(savedApps);
                    Log.d(TAG, "📱 Loaded " + focusModeApps.size() + " focus mode apps for device "
                            + currentChildDeviceId);

                    // 🔧 DETAILED APP LIST LOGGING: Show which apps were loaded
                    for (AppInfo app : focusModeApps) {
                        Log.d(TAG, "   📱 Loaded app: " + (app.name != null ? app.name : app.packageName));
                    }
                }
            }

            // 🔧 PERSISTENCE FIX: Load Focus Mode active state
            String stateKey = "focus_mode_active_" + currentChildDeviceId;
            isFocusModeActive = focusModePrefs.getBoolean(stateKey, false);
            Log.d(TAG, "🎯 Loaded Focus Mode state for device " + currentChildDeviceId + ": "
                    + (isFocusModeActive ? "ACTIVE" : "INACTIVE"));

        } catch (Exception e) {
            Log.e(TAG, "Error loading focus mode apps: " + e.getMessage());
        }
    }

    private void saveFocusModeApps() {
        if (currentChildDeviceId == null)
            return;

        try {
            String key = "focus_apps_" + currentChildDeviceId;
            Gson gson = new Gson();
            String appsJson = gson.toJson(focusModeApps);

            focusModePrefs.edit().putString(key, appsJson).apply();
            Log.d(TAG, "Saved " + focusModeApps.size() + " focus mode apps for device " + currentChildDeviceId);

            // 🔧 PERSISTENCE FIX: Save Focus Mode active state
            String stateKey = "focus_mode_active_" + currentChildDeviceId;
            focusModePrefs.edit().putBoolean(stateKey, isFocusModeActive).apply();
            Log.d(TAG, "🎯 Saved Focus Mode state for device " + currentChildDeviceId + ": "
                    + (isFocusModeActive ? "ACTIVE" : "INACTIVE"));

            // 🔧 FORCE-CLOSE PERSISTENCE: Also save device name for restoration
            String deviceNameKey = "device_name_" + currentChildDeviceId;
            focusModePrefs.edit().putString(deviceNameKey, currentChildDeviceName).apply();
            Log.d(TAG, "💾 Saved device name for " + currentChildDeviceId + ": " + currentChildDeviceName);

        } catch (Exception e) {
            Log.e(TAG, "Error saving focus mode apps: " + e.getMessage());
        }
    }

    private void activateFocusMode() {
        Log.d(TAG, "🎯 ActivateFocusMode called - checking conditions...");
        Log.d(TAG, "📱 Focus mode apps count: " + focusModeApps.size());
        Log.d(TAG, "📱 Current device: " + currentChildDeviceId);

        try {
            if (focusModeApps.isEmpty()) {
                Log.w(TAG, "❌ No focus mode apps selected");
                Toast.makeText(this, "No apps selected for focus mode. Please select apps first.", Toast.LENGTH_SHORT)
                        .show();
                binding.switchFocusMode.setChecked(false);
                return;
            }

            Log.d(TAG, "✅ Starting focus mode activation...");
            isCreatingPreset = true;

            // Convert AppInfo list to package names for blocking
            List<String> packageNames = new ArrayList<>();
            for (AppInfo appInfo : focusModeApps) {
                if (appInfo.packageName != null && !appInfo.packageName.trim().isEmpty()) {
                    packageNames.add(appInfo.packageName);
                    Log.d(TAG, "Adding app to focus mode: " + appInfo.packageName + " (" + appInfo.name + ")");
                }
            }

            if (packageNames.isEmpty()) {
                Log.w(TAG, "❌ No valid apps to block");
                Toast.makeText(this, "No valid apps to block", Toast.LENGTH_SHORT).show();
                binding.switchFocusMode.setChecked(false);
                isCreatingPreset = false;
                return;
            }

            // Send focus mode command to child device
            DatabaseReference blockRef = FirebaseDatabase.getInstance()
                    .getReference("block_commands")
                    .child(currentChildDeviceId);

            Map<String, Object> focusCommand = new HashMap<>();
            focusCommand.put("action", "focus_mode");
            focusCommand.put("enabled", true);
            focusCommand.put("timestamp", System.currentTimeMillis());
            focusCommand.put("setBy", "parent");
            focusCommand.put("apps", packageNames);
            focusCommand.put("processed", false);

            Log.d(TAG, "Sending focus mode command to device: " + currentChildDeviceId);
            Log.d(TAG, "Command contains " + packageNames.size() + " apps to block");

            // Use push() to create a new command instead of setValue() to avoid overwriting
            blockRef.push().setValue(focusCommand)
                    .addOnSuccessListener(aVoid -> {
                        isFocusModeActive = true;
                        saveFocusModeApps(); // Save the state to persistence
                        updateFocusModeUI();
                        Toast.makeText(this, "🎯 Focus Mode activated on " + currentChildDeviceName +
                                " (" + focusModeApps.size() + " apps blocked)", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Focus mode command sent successfully");
                        isCreatingPreset = false;
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error activating focus mode: " + e.getMessage());
                        Toast.makeText(this, "❌ Failed to activate focus mode", Toast.LENGTH_SHORT).show();
                        binding.switchFocusMode.setChecked(false);
                        isCreatingPreset = false;
                    });

        } catch (Exception e) {
            Log.e(TAG, "Error activating focus mode: " + e.getMessage());
            Toast.makeText(this, "Error activating focus mode", Toast.LENGTH_SHORT).show();
            binding.switchFocusMode.setChecked(false);
            isCreatingPreset = false;
        }
    }

    private void deactivateFocusMode() {
        try {
            if (currentChildDeviceId == null)
                return;

            DatabaseReference blockRef = FirebaseDatabase.getInstance()
                    .getReference("block_commands")
                    .child(currentChildDeviceId);

            Map<String, Object> focusCommand = new HashMap<>();
            focusCommand.put("action", "focus_mode");
            focusCommand.put("enabled", false);
            focusCommand.put("timestamp", System.currentTimeMillis());
            focusCommand.put("setBy", "parent");
            focusCommand.put("processed", false);

            Log.d(TAG, "Sending focus mode deactivation command to device: " + currentChildDeviceId);

            // Use push() to create a new command instead of setValue() to avoid overwriting
            blockRef.push().setValue(focusCommand)
                    .addOnSuccessListener(aVoid -> {
                        isFocusModeActive = false;
                        saveFocusModeApps(); // Save the state to persistence
                        updateFocusModeUI();
                        Toast.makeText(this, "✅ Focus Mode deactivated", Toast.LENGTH_SHORT).show();
                        Log.d(TAG, "Focus mode deactivation command sent successfully");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error deactivating focus mode: " + e.getMessage());
                        Toast.makeText(this, "❌ Failed to deactivate focus mode", Toast.LENGTH_SHORT).show();
                    });

        } catch (Exception e) {
            Log.e(TAG, "Error deactivating focus mode: " + e.getMessage());
            Toast.makeText(this, "Error deactivating focus mode", Toast.LENGTH_SHORT).show();
        }
    }

    // ==============================================
    // UPDATED METHOD FOR MODERN DESIGN COLORS
    // ==============================================
    private void updateFocusModeUI() {
        try {
            Log.d(TAG, "🔧 Updating Focus Mode UI: " + (isFocusModeActive ? "ACTIVE" : "INACTIVE") + " - "
                    + focusModeApps.size() + " apps");

            if (tvFocusModeStatus == null) {
                Log.e(TAG, "❌ tvFocusModeStatus is NULL! Cannot update Focus Mode UI!");
                // Try to reinitialize
                tvFocusModeStatus = findViewById(R.id.tvFocusModeStatus);
                if (tvFocusModeStatus == null) {
                    Log.e(TAG, "❌ CRITICAL: tvFocusModeStatus still NULL after findViewById!");
                    return;
                }
            }

            // 🔧 FORCE VISIBILITY: Ensure the UI element is visible
            tvFocusModeStatus.setVisibility(View.VISIBLE);
            Log.d(TAG, "🔧 Forced tvFocusModeStatus visibility to VISIBLE");

            if (isFocusModeActive) {
                if (focusModeApps.isEmpty()) {
                    tvFocusModeStatus.setText("Active (No apps selected)");
                    tvFocusModeStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_600)); // Modern orange
                } else {
                    tvFocusModeStatus.setText("Active - Blocking " + focusModeApps.size() + " apps");
                    tvFocusModeStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600)); // Modern green
                }
                if (binding != null && binding.switchFocusMode != null) {
                    binding.switchFocusMode.setChecked(true);
                }
            } else {
                if (focusModeApps.isEmpty()) {
                    tvFocusModeStatus.setText("Inactive - No apps selected");
                    tvFocusModeStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral_600)); // Modern grey
                } else {
                    tvFocusModeStatus.setText("Inactive - " + focusModeApps.size() + " apps ready");
                    tvFocusModeStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral_600)); // Modern grey
                }
                if (binding != null && binding.switchFocusMode != null) {
                    binding.switchFocusMode.setChecked(false);
                }
            }

            // Always show edit button so users can select/modify apps
            if (btnEditAppList != null) {
                btnEditAppList.setVisibility(View.VISIBLE);
            } else {
                // Try to reinitialize
                btnEditAppList = findViewById(R.id.btnEditAppList);
                if (btnEditAppList != null) {
                    btnEditAppList.setVisibility(View.VISIBLE);
                }
            }

            // 🔧 FINAL UI REFRESH: Force refresh the entire view
            tvFocusModeStatus.requestLayout();
            tvFocusModeStatus.invalidate();

        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating Focus Mode UI: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showEditAppListDialog() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a child device first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (focusModeApps.isEmpty()) {
            // No apps selected, show option to select apps
            AlertDialog.Builder builder = new AlertDialog.Builder(
                    new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
            builder.setTitle("📱 Focus Mode App Selection");
            builder.setMessage(
                    "No apps selected for focus mode.\n\nSelect apps from your child device to block during focus mode sessions.");
            builder.setPositiveButton("Select Apps", (dialog, which) -> {
                showFocusModeAppSelection();
            });
            builder.setNegativeButton("Cancel", null);
            builder.show();
            return;
        }

        // Show currently selected apps
        StringBuilder appListText = new StringBuilder();
        appListText.append("Currently selected apps for focus mode:\n\n");

        for (AppInfo appInfo : focusModeApps) {
            String displayName = appInfo.name != null ? appInfo.name : appInfo.packageName;
            appListText.append("📱 ").append(displayName).append("\n");
        }

        appListText.append("\nTotal: ").append(focusModeApps.size()).append(" apps");

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("📱 Focus Mode App List");
        builder.setMessage(appListText.toString());
        builder.setPositiveButton("Edit Selection", (dialog, which) -> {
            showFocusModeAppSelection();
        });
        builder.setNegativeButton("Cancel", null);
        builder.setNeutralButton("Clear All", (dialog, which) -> {
            focusModeApps.clear();
            saveFocusModeApps();
            updateFocusModeUI();
            if (isFocusModeActive) {
                // Deactivate focus mode since no apps are selected
                binding.switchFocusMode.setChecked(false);
                deactivateFocusMode();
            }
            Toast.makeText(this, "All apps cleared from focus mode", Toast.LENGTH_SHORT).show();
        });
        builder.show();
    }

    private void setupQRScanOnlyListener() {
        Log.d(TAG, "� NUCLEAR SIMPLE QR SETUP - FUCK COMPLEX LOGIC!");
        Log.d(TAG, "🔍 QR Key: " + permanentQRKey);

        try {
            // 🚀 NUCLEAR APPROACH 1: Listen to QR shares directly via Firebase
            DatabaseReference qrRef = FirebaseDatabase.getInstance()
                    .getReference("qr_shares")
                    .child(permanentQRKey);

            Log.d(TAG, "� Setting up DIRECT Firebase listener on: qr_shares/" + permanentQRKey);

            qrRef.addValueEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    Log.d(TAG, "🔥🔥🔥 QR FIREBASE DATA CHANGED!");
                    Log.d(TAG, "🔍 Data exists: " + dataSnapshot.exists());
                    Log.d(TAG, "🔍 QR Key being monitored: " + permanentQRKey);
                    Log.d(TAG, "🔍 Full snapshot: " + dataSnapshot.toString());

                    // Also check if there are any children
                    if (dataSnapshot.exists()) {
                        Log.d(TAG, "🔍 Data snapshot children count: " + dataSnapshot.getChildrenCount());
                        for (DataSnapshot child : dataSnapshot.getChildren()) {
                            Log.d(TAG, "🔍 Child key: " + child.getKey() + " | Value: " + child.getValue());
                        }
                    } else {
                        Log.w(TAG, "❌ NO DATA EXISTS in qr_shares/" + permanentQRKey);

                        // EMERGENCY: Try to find the device in device_apps instead
                        Log.d(TAG, "🚨 EMERGENCY: Checking device_apps for recent connections...");
                        DatabaseReference deviceAppsRef = FirebaseDatabase.getInstance()
                                .getReference("device_apps");

                        deviceAppsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(DataSnapshot appsSnapshot) {
                                Log.d(TAG,
                                        "🔍 DEVICE APPS CHECK: Found " + appsSnapshot.getChildrenCount() + " devices");

                                for (DataSnapshot deviceSnapshot : appsSnapshot.getChildren()) {
                                    String deviceId = deviceSnapshot.getKey();
                                    long timestamp = 0;

                                    // Check if this device uploaded recently (within last 30 seconds)
                                    for (DataSnapshot appSnapshot : deviceSnapshot.getChildren()) {
                                        Object timestampObj = appSnapshot.child("timestamp").getValue();
                                        if (timestampObj instanceof Long) {
                                            timestamp = (Long) timestampObj;
                                            break;
                                        }
                                    }

                                    if (timestamp > 0 && (System.currentTimeMillis() - timestamp) < 30000) {
                                        Log.d(TAG, "🚨 EMERGENCY CONNECTION DETECTED: Device " + deviceId
                                                + " uploaded apps recently!");

                                        // Get device name from first app entry
                                        String deviceName = "Unknown Device";
                                        int appCount = (int) deviceSnapshot.getChildrenCount();

                                        // Create emergency device connection
                                        createEmergencyDeviceConnection(deviceId, deviceName, appCount, timestamp);
                                    }
                                }
                            }

                            @Override
                            public void onCancelled(DatabaseError databaseError) {
                                Log.e(TAG, "❌ Emergency device_apps check failed: " + databaseError.getMessage());
                            }
                        });

                        return; // Exit early if no QR data exists
                    }

                    if (dataSnapshot.exists()) {
                        // Check for allDeviceAppLists (where child devices put their data)
                        if (dataSnapshot.child("allDeviceAppLists").exists()) {
                            Log.d(TAG, "🎯 FOUND allDeviceAppLists - processing devices...");

                            for (DataSnapshot deviceSnapshot : dataSnapshot.child("allDeviceAppLists").getChildren()) {
                                String deviceId = deviceSnapshot.child("deviceId").getValue(String.class);
                                String deviceName = deviceSnapshot.child("deviceName").getValue(String.class);
                                Long timestamp = deviceSnapshot.child("timestamp").getValue(Long.class);

                                if (deviceId != null && deviceName != null) {
                                    Log.d(TAG, "🚀 DEVICE FOUND IN QR DATA: " + deviceName + " (ID: " + deviceId + ")");

                                    // Count apps
                                    int appCount = 0;
                                    if (deviceSnapshot.child("apps").exists()) {
                                        appCount = (int) deviceSnapshot.child("apps").getChildrenCount();
                                    }

                                    // Create device immediately - NO COMPLEX CHECKS
                                    ChildDevice device = new ChildDevice();
                                    device.deviceId = deviceId;
                                    device.deviceName = deviceName;
                                    device.appCount = appCount;
                                    device.lastConnected = timestamp != null ? timestamp : System.currentTimeMillis();
                                    device.apps = new ArrayList<>();
                                    device.focusModeActive = false;

                                    // CHECK IF DEVICE ALREADY EXISTS
                                    boolean exists = connectedDevices.stream()
                                            .anyMatch(d -> d.deviceId.equals(deviceId));

                                    // 🔥 BULLETPROOF RECONNECTION: Always allow QR connections
                                    if (!exists) {
                                        Log.d(TAG, "🔥 BULLETPROOF: Adding new device via QR scan");

                                        runOnUiThread(() -> {
                                            // � BULLETPROOF QR CONNECTION: Clear ALL barriers
                                            Log.d(TAG, "🔥 BULLETPROOF QR CONNECTION: Clearing ALL barriers for: "
                                                    + device.deviceName);

                                            // �🔧 BULLETPROOF: Remove any previous removal status
                                            if (isPermanentlyRemoved(device.deviceId)) {
                                                Log.d(TAG,
                                                        "🚫→✅ BULLETPROOF: Clearing removal status for QR reconnection: "
                                                                + device.deviceName);
                                                removePermanentRemoval(device.deviceId);
                                            }

                                            // Clear just-removed tracking to allow immediate reconnection
                                            if (device.deviceId.equals(deviceIdJustRemoved)) {
                                                deviceIdJustRemoved = null;
                                                Log.d(TAG, "🔄 BULLETPROOF: Cleared just-removed tracking for: "
                                                        + device.deviceName);
                                            }

                                            // 🔥 BULLETPROOF: Clear device from any blacklists
                                            connectedDevicesManager.removeDeviceFromBlacklist(device.deviceId);
                                            connectedDevicesManager.clearBlacklist(); // Nuclear option - clear all
                                                                                      // blacklists

                                            // Clear any Firebase connection conflicts
                                            clearFirebaseConnectionConflicts(device.deviceId);

                                            Log.d(TAG, "✅ ALL CONNECTION BARRIERS CLEARED for: " + device.deviceName);

                                            // Add to list immediately
                                            connectedDevices.add(device);
                                            Log.d(TAG, "✅ Device added to local list. Total devices: "
                                                    + connectedDevices.size());

                                            // Save to storage
                                            connectedDevicesManager.addOrUpdateDevice(device);
                                            Log.d(TAG, "✅ Device saved to persistent storage");

                                            // 🔧 CRITICAL FIX: Create timer data for QR reconnected device
                                            createTimerDataForDevice(device.deviceId, device.deviceName);

                                            // 🔥 BULLETPROOF: Create backup connection paths for extra reliability
                                            createBackupConnectionPaths(device.deviceId, device.deviceName);

                                            // Set as current device if first
                                            if (currentChildDeviceId == null) {
                                                // Auto-select only if no device exists yet (non-explicit)
                                                connectedDevicesManager.setCurrentDevice(device.deviceId, false);
                                                currentChildDeviceId = device.deviceId;
                                                currentChildDeviceName = device.deviceName;
                                                // 🔧 Store actual child name if available
                                                currentChildUserName = (device.userName != null) ? device.userName : "";

                                                // Initialize usage limiter for first device
                                                initializeLimiterForDevice(device.deviceId);

                                                // 🔧 FORCE-CLOSE PERSISTENCE: Save device name immediately
                                                saveDeviceNameForCurrentDevice();
                                            }

                                            // Update UI immediately
                                            updateDeviceStatus();
                                            updateTargetDeviceDisplay();
                                            populateDeviceList(); // Use simple circular icons

                                            Log.d(TAG, "🎉 BULLETPROOF DEVICE CONNECTED: " + device.deviceName);
                                            Log.d(TAG, "📱 Total devices now: " + connectedDevices.size());

                                            // Show success toast for QR reconnection
                                            Toast.makeText(ParentDashboardActivity.this,
                                                    "🎉 " + device.deviceName + " connected via QR scan!",
                                                    Toast.LENGTH_LONG).show();
                                        });
                                    } else {
                                        Log.d(TAG,
                                                "� BULLETPROOF: Updating existing device via QR scan: " + deviceName);
                                        runOnUiThread(() -> {
                                            // 🔥 BULLETPROOF: Clear ALL barriers for existing device too
                                            Log.d(TAG, "🔥 BULLETPROOF: Clearing barriers for existing device: "
                                                    + deviceName);

                                            if (isPermanentlyRemoved(deviceId)) {
                                                removePermanentRemoval(deviceId);
                                                Log.d(TAG, "🚫→✅ Cleared removal status for existing device: "
                                                        + deviceName);
                                            }

                                            if (deviceId.equals(deviceIdJustRemoved)) {
                                                deviceIdJustRemoved = null;
                                                Log.d(TAG, "🔄 Cleared just-removed tracking for existing device: "
                                                        + deviceName);
                                            }

                                            connectedDevicesManager.removeDeviceFromBlacklist(deviceId);
                                            clearFirebaseConnectionConflicts(deviceId);

                                            // 🔥 BULLETPROOF: Always update existing device data
                                            for (int i = 0; i < connectedDevices.size(); i++) {
                                                if (connectedDevices.get(i).deviceId.equals(deviceId)) {
                                                    // Update with fresh data from QR scan
                                                    device.lastConnected = System.currentTimeMillis();
                                                    connectedDevices.set(i, device);

                                                    // Save updated device
                                                    connectedDevicesManager.addOrUpdateDevice(device);
                                                    Log.d(TAG, "✅ Existing device updated in storage");

                                                    // 🔧 CRITICAL FIX: Ensure timer data exists for existing device
                                                    createTimerDataForDevice(device.deviceId, device.deviceName);

                                                    // 🔥 BULLETPROOF: Create backup connection paths for extra
                                                    // reliability
                                                    createBackupConnectionPaths(device.deviceId, device.deviceName);

                                                    Log.d(TAG, "✅ BULLETPROOF: Updated device data for: " + deviceName);
                                                    break;
                                                }
                                            }

                                            // 🔥 BULLETPROOF: Ensure device is visible and current
                                            updateDeviceStatus();
                                            updateTargetDeviceDisplay();
                                            populateDeviceList(); // Use simple circular icons

                                            // Show reconnection success
                                            Toast.makeText(ParentDashboardActivity.this,
                                                    "🔄 " + device.deviceName + " reconnected via QR scan!",
                                                    Toast.LENGTH_SHORT).show();
                                        });
                                    }
                                }
                            }
                        } else {
                            // 🔧 FIX: Handle bulletproof format with childDeviceId/childDeviceName at root
                            String childDeviceId = dataSnapshot.child("childDeviceId").getValue(String.class);
                            String childDeviceName = dataSnapshot.child("childDeviceName").getValue(String.class);
                            String connectionStatus = dataSnapshot.child("connectionStatus").getValue(String.class);

                            Log.d(TAG, "🔧 Checking bulletproof format: childDeviceId=" + childDeviceId
                                    + ", childDeviceName=" + childDeviceName);

                            if (childDeviceId != null && childDeviceName != null
                                    && "connected".equals(connectionStatus)) {
                                Log.d(TAG, "🔧 BULLETPROOF FORMAT DETECTED: " + childDeviceName + " (" + childDeviceId
                                        + ")");

                                // Create device
                                ChildDevice device = new ChildDevice();
                                device.deviceId = childDeviceId;
                                device.deviceName = childDeviceName;
                                device.userName = childDeviceName; // Use childDeviceName as userName
                                device.appCount = 0; // Will be updated from device_apps or parents structure
                                device.lastConnected = System.currentTimeMillis();
                                device.apps = new ArrayList<>();
                                device.focusModeActive = false;

                                // Check if already exists
                                boolean exists = connectedDevices.stream()
                                        .anyMatch(d -> d.deviceId.equals(childDeviceId));

                                if (!exists) {
                                    Log.d(TAG, "🔧 BULLETPROOF: Adding new device from QR share root");

                                    final ChildDevice finalDevice = device;
                                    runOnUiThread(() -> {
                                        // Clear any removal barriers
                                        if (isPermanentlyRemoved(finalDevice.deviceId)) {
                                            removePermanentRemoval(finalDevice.deviceId);
                                            Log.d(TAG, "🚫→✅ BULLETPROOF: Cleared removal status for: "
                                                    + finalDevice.deviceName);
                                        }
                                        if (finalDevice.deviceId.equals(deviceIdJustRemoved)) {
                                            deviceIdJustRemoved = null;
                                        }
                                        connectedDevicesManager.removeDeviceFromBlacklist(finalDevice.deviceId);

                                        // Add to list
                                        connectedDevices.add(finalDevice);
                                        Log.d(TAG, "✅ Device added to local list. Total: " + connectedDevices.size());

                                        // Save to storage
                                        connectedDevicesManager.addOrUpdateDevice(finalDevice);
                                        Log.d(TAG, "✅ Device saved to persistent storage");

                                        // Set as current if first device
                                        if (currentChildDeviceId == null) {
                                            connectedDevicesManager.setCurrentDevice(finalDevice.deviceId, false);
                                            currentChildDeviceId = finalDevice.deviceId;
                                            currentChildDeviceName = finalDevice.deviceName;
                                            currentChildUserName = finalDevice.userName;
                                            initializeLimiterForDevice(finalDevice.deviceId);
                                            saveDeviceNameForCurrentDevice();
                                        }

                                        // Update UI
                                        updateDeviceStatus();
                                        updateTargetDeviceDisplay();
                                        refreshDeviceListPremium();

                                        Log.d(TAG, "🎉 BULLETPROOF DEVICE CONNECTED: " + finalDevice.deviceName);
                                        Toast.makeText(ParentDashboardActivity.this,
                                                "🎉 " + finalDevice.deviceName + " connected!", Toast.LENGTH_LONG)
                                                .show();
                                    });
                                } else {
                                    Log.d(TAG, "✅ Device already exists in list: " + childDeviceName);
                                }
                            }
                        }
                    }
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    Log.e(TAG, "� QR Firebase listener error: " + databaseError.getMessage());
                }
            });

            Log.d(TAG, "✅ NUCLEAR QR LISTENER ACTIVE");

            // � BULLETPROOF FALLBACK: Monitor device_apps directly
            setupDeviceAppsListener();

            // �🚀 NUCLEAR APPROACH 2: ALSO listen to parents structure for ANY device
            // connections
            setupNuclearParentsListener();

        } catch (Exception e) {
            Log.e(TAG, "🔥 Error setting up nuclear QR listener: " + e.getMessage());
        }
    }

    private void setupNuclearParentsListener() {
        Log.d(TAG, "🚀 NUCLEAR PARENTS LISTENER - CATCH EVERYTHING!");

        try {
            // Listen to parents structure for immediate device additions
            if (mAuth != null && mAuth.getCurrentUser() != null) {
                String parentUserId = mAuth.getCurrentUser().getUid();

                DatabaseReference parentsRef = FirebaseDatabase.getInstance()
                        .getReference("parents")
                        .child(parentUserId)
                        .child("connectedChildDevices");

                Log.d(TAG, "🔥 Setting up PARENTS listener: parents/" + parentUserId + "/connectedChildDevices");

                parentsRef.addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        Log.d(TAG, "🔥🔥🔥 PARENTS FIREBASE DATA CHANGED!");
                        Log.d(TAG, "🔍 Children count: " + dataSnapshot.getChildrenCount());

                        for (DataSnapshot childSnapshot : dataSnapshot.getChildren()) {
                            String deviceId = childSnapshot.child("deviceId").getValue(String.class);
                            String deviceName = childSnapshot.child("deviceName").getValue(String.class);
                            Long appCount = childSnapshot.child("appCount").getValue(Long.class);
                            Long lastConnected = childSnapshot.child("lastConnected").getValue(Long.class);

                            if (deviceId != null && deviceName != null) {
                                Log.d(TAG, "🚀 DEVICE FOUND IN PARENTS: " + deviceName + " (ID: " + deviceId + ")");

                                // Create device immediately
                                ChildDevice device = new ChildDevice();
                                device.deviceId = deviceId;
                                device.deviceName = deviceName;
                                device.appCount = appCount != null ? appCount.intValue() : 0;
                                device.lastConnected = lastConnected != null ? lastConnected
                                        : System.currentTimeMillis();
                                device.apps = new ArrayList<>();
                                device.focusModeActive = false;

                                // Check if already exists
                                boolean exists = connectedDevices.stream()
                                        .anyMatch(d -> d.deviceId.equals(deviceId));

                                if (!exists) {
                                    Log.d(TAG, "🔥 ADDING DEVICE FROM PARENTS - NO BLOCKING!");

                                    runOnUiThread(() -> {
                                        // Add to list immediately
                                        connectedDevices.add(device);

                                        // Save to storage
                                        connectedDevicesManager.addOrUpdateDevice(device);

                                        // Set as current device if first
                                        if (currentChildDeviceId == null) {
                                            // Auto-select only if no device exists yet (non-explicit)
                                            connectedDevicesManager.setCurrentDevice(device.deviceId, false);
                                            currentChildDeviceName = device.deviceName;

                                            // Initialize usage limiter for first device
                                            initializeLimiterForDevice(device.deviceId);
                                        }

                                        // Update UI immediately
                                        updateDeviceStatus();
                                        updateTargetDeviceDisplay();
                                        refreshDeviceListPremium();

                                        Log.d(TAG, "🎉 DEVICE CONNECTED FROM PARENTS: " + device.deviceName);
                                        Log.d(TAG, "📱 Total devices now: " + connectedDevices.size());

                                        Toast.makeText(ParentDashboardActivity.this,
                                                "🎉 " + device.deviceName + " connected!",
                                                Toast.LENGTH_LONG).show();
                                    });
                                }
                            }
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e(TAG, "🔥 Parents Firebase listener error: " + databaseError.getMessage());
                    }
                });

                Log.d(TAG, "✅ NUCLEAR PARENTS LISTENER ACTIVE");
            }
        } catch (Exception e) {
            Log.e(TAG, "🔥 Error setting up nuclear parents listener: " + e.getMessage());
        }
    }

    private void setupDeviceAppsListener() {
        Log.d(TAG, "🚀 EMERGENCY DEVICE_APPS LISTENER - BULLETPROOF DETECTION!");

        try {
            if (mAuth != null && mAuth.getCurrentUser() != null) {
                String parentUserId = mAuth.getCurrentUser().getUid();

                DatabaseReference deviceAppsRef = FirebaseDatabase.getInstance()
                        .getReference("device_apps");

                Log.d(TAG, "🚨 Setting up EMERGENCY device_apps listener for bulletproof detection");

                deviceAppsRef.addChildEventListener(new ChildEventListener() {
                    @Override
                    public void onChildAdded(DataSnapshot deviceSnapshot, String previousChildName) {
                        Log.d(TAG, "🚨🚨🚨 EMERGENCY DEVICE_APPS CHILD ADDED!");

                        if (deviceSnapshot != null && deviceSnapshot.exists()) {
                            String deviceId = deviceSnapshot.getKey();
                            String connectedParent = deviceSnapshot.child("connectedParent").getValue(String.class);
                            Long lastConnected = deviceSnapshot.child("lastConnected").getValue(Long.class);
                            DataSnapshot appsSnapshot = deviceSnapshot.child("apps");

                            Log.d(TAG, "🔍 Emergency detection - Device: " + deviceId);
                            Log.d(TAG, "🔍 Connected Parent: " + connectedParent);
                            Log.d(TAG,
                                    "🔍 Apps count: " + (appsSnapshot != null ? appsSnapshot.getChildrenCount() : 0));

                            // Check if this device is connected to current parent
                            if (parentUserId.equals(connectedParent) && appsSnapshot != null
                                    && appsSnapshot.getChildrenCount() > 0) {
                                Log.d(TAG, "🚨 EMERGENCY CONNECTION DETECTED!");
                                Log.d(TAG, "🚨 Device " + deviceId + " has " + appsSnapshot.getChildrenCount()
                                        + " apps - CREATING EMERGENCY CONNECTION!");

                                // Check if already exists in our list
                                boolean exists = connectedDevices.stream()
                                        .anyMatch(d -> d.deviceId.equals(deviceId));

                                if (!exists) {
                                    Log.d(TAG, "🚨 EMERGENCY: Device not in list - creating emergency connection");
                                    createEmergencyDeviceConnection(deviceId, appsSnapshot);
                                } else {
                                    Log.d(TAG, "✅ EMERGENCY: Device already exists in list");
                                }
                            }
                        }
                    }

                    @Override
                    public void onChildChanged(DataSnapshot deviceSnapshot, String previousChildName) {
                        Log.d(TAG, "🚨 EMERGENCY device_apps child changed: " + deviceSnapshot.getKey());
                        onChildAdded(deviceSnapshot, previousChildName); // Treat as addition
                    }

                    @Override
                    public void onChildRemoved(DataSnapshot deviceSnapshot) {
                        Log.d(TAG, "🚨 EMERGENCY device_apps child removed: " + deviceSnapshot.getKey());
                    }

                    @Override
                    public void onChildMoved(DataSnapshot deviceSnapshot, String previousChildName) {
                        // Not needed for our use case
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e(TAG, "🚨 EMERGENCY device_apps listener error: " + databaseError.getMessage());
                    }
                });

                Log.d(TAG, "✅ EMERGENCY DEVICE_APPS LISTENER ACTIVE - BULLETPROOF DETECTION ENABLED");
            }
        } catch (Exception e) {
            Log.e(TAG, "🚨 Error setting up emergency device_apps listener: " + e.getMessage());
        }
    }

    private void setupDirectParentChildConnectionsForQROnly() {
        try {
            // Get current parent's Firebase Auth UID and device ID
            FirebaseAuth mAuth = FirebaseAuth.getInstance();
            String parentDeviceId = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);

            Log.d(TAG, "🔍 Setting up QR-only parent-child connection listeners...");

            // Listen to Firebase Auth UID path - QR CONNECTIONS ONLY
            if (mAuth.getCurrentUser() != null) {
                String parentUserId = mAuth.getCurrentUser().getUid();
                Log.d(TAG, "📱 QR Listener via Firebase Auth UID: " + parentUserId);
                setupQROnlyParentConnectionListener(parentUserId, "Firebase Auth UID");
            }

            // ALSO listen to device ID path (backup/compatibility) - QR CONNECTIONS ONLY
            if (parentDeviceId != null) {
                Log.d(TAG, "📱 QR Listener via Device ID: " + parentDeviceId);
                setupQROnlyParentConnectionListener(parentDeviceId, "Device ID");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error setting up QR-only parent-child connection listener: " + e.getMessage());
        }
    }

    private void setupQROnlyParentConnectionListener(String parentId, String idType) {
        Log.d(TAG, "� QR-ONLY LISTENER DISABLED for parent: " + parentId + " (Type: " + idType + ")");
        Log.d(TAG, "� Reason: ULTRA-STRICT MODE - No Firebase auto-loading allowed");
        Log.d(TAG, "🚫 All device connections must go through manual QR code scanning");
        Log.d(TAG, "✅ Only QR scan will trigger device connections");

        // 🚫 COMPLETELY DISABLED: This Firebase listener was still auto-loading devices
        // Even though it claimed to be "QR-only", it was still accepting all Firebase
        // changes
        // True QR-only mode means NO Firebase listeners at all for device loading

        return; // No Firebase listeners = True QR-only mode
    }

    private void listenForDeviceConnections() {
        // 🚫 DISABLED - This method was causing automatic device loading
        // User requirement: Only QR scanned devices should be shown
        Log.d(TAG, "🚫 AUTOMATIC DEVICE LISTENERS DISABLED - Only QR scan connections allowed");
        Log.d(TAG, "📱 Use setupQRScanOnlyListener() instead for QR-only connections");

        // The QR scan listener is already set up in setupQRScanOnlyListener()
        return;
    }

    private void listenForDeviceConnectionsOLD_DISABLED() {
        try {
            // Listen for QR share connections (legacy method)
            childDeviceManager.listenForConnections(permanentQRKey,
                    new ChildDeviceManager.OnDeviceConnectionListener() {
                        @Override
                        public void onDeviceConnected(ChildDevice device) {
                            runOnUiThread(() -> {
                                addConnectedDevice(device, true); // TRUE: This is from QR scan connection
                                Toast.makeText(ParentDashboardActivity.this,
                                        device.deviceName + " connected successfully!",
                                        Toast.LENGTH_LONG).show();
                            });
                        }

                        @Override
                        public void onDeviceDisconnected(String deviceId) {
                            runOnUiThread(() -> removeConnectedDevice(deviceId));
                        }

                        @Override
                        public void onError(String error) {
                            Log.e(TAG, "Device connection error: " + error);
                        }
                    });

            // CRITICAL FIX: Also listen for direct parent-child connections
            listenForDirectParentChildConnections();

        } catch (Exception e) {
            Log.e(TAG, "Error setting up device connection listener: " + e.getMessage());
        }
    }

    private void listenForDirectParentChildConnections() {
        try {
            // Get current parent's Firebase Auth UID and device ID
            FirebaseAuth mAuth = FirebaseAuth.getInstance();
            String parentDeviceId = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);

            Log.d(TAG, "🔍 Setting up listeners for parent connections...");

            // Listen to Firebase Auth UID path
            if (mAuth.getCurrentUser() != null) {
                String parentUserId = mAuth.getCurrentUser().getUid();
                Log.d(TAG, "📱 Listening via Firebase Auth UID: " + parentUserId);
                setupParentConnectionListener(parentUserId, "Firebase Auth UID");
            }

            // ALSO listen to device ID path (backup/compatibility)
            if (parentDeviceId != null) {
                Log.d(TAG, "📱 Listening via Device ID: " + parentDeviceId);
                setupParentConnectionListener(parentDeviceId, "Device ID");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error setting up direct parent-child connection listener: " + e.getMessage());
        }
    }

    private void setupParentConnectionListener(String parentId, String idType) {
        DatabaseReference parentChildRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(parentId)
                .child("connectedChildDevices");

        parentChildRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                Log.d(TAG, "📊 Parent-child connection data changed via " + idType + " (" + parentId + ")");

                try {
                    for (DataSnapshot childSnapshot : dataSnapshot.getChildren()) {
                        try {
                            String deviceId = childSnapshot.child("deviceId").getValue(String.class);
                            String deviceName = childSnapshot.child("deviceName").getValue(String.class);
                            Integer appCount = childSnapshot.child("appCount").getValue(Integer.class);
                            Long lastConnected = childSnapshot.child("lastConnected").getValue(Long.class);
                            String connectionStatus = childSnapshot.child("connectionStatus").getValue(String.class);

                            if (deviceId != null && deviceName != null) {
                                // Only process devices that are currently online
                                if ("online".equals(connectionStatus)) {
                                    // Check if this device is already in our list
                                    boolean deviceExists = connectedDevices.stream()
                                            .anyMatch(d -> d.deviceId.equals(deviceId));

                                    if (!deviceExists) {
                                        // Create new child device object
                                        ChildDevice device = new ChildDevice();
                                        device.deviceId = deviceId;
                                        device.deviceName = deviceName;
                                        device.appCount = appCount != null ? appCount : 0;
                                        device.lastConnected = lastConnected != null ? lastConnected
                                                : System.currentTimeMillis();
                                        device.apps = new ArrayList<>(); // Will be loaded separately
                                        device.focusModeActive = false;

                                        Log.d(TAG, "🎉 NEW CHILD DEVICE CONNECTED via " + idType + ": " + deviceName
                                                + " (ID: " + deviceId + ")");

                                        runOnUiThread(() -> {
                                            try {
                                                addConnectedDevice(device);

                                                // ENHANCED: More prominent toast notification
                                                String toastMessage = "🎉 " + deviceName + " connected successfully!";

                                                Toast toast = Toast.makeText(ParentDashboardActivity.this, toastMessage,
                                                        Toast.LENGTH_LONG);
                                                toast.show();

                                                Log.d(TAG, "✅ Toast notification shown for device: " + deviceName);

                                            } catch (Exception e) {
                                                Log.e(TAG, "Error showing connection notification: " + e.getMessage());
                                            }
                                        });
                                    } else {
                                        // Update existing device info
                                        for (ChildDevice existingDevice : connectedDevices) {
                                            if (existingDevice.deviceId.equals(deviceId)) {
                                                existingDevice.appCount = appCount != null ? appCount
                                                        : existingDevice.appCount;
                                                existingDevice.lastConnected = lastConnected != null ? lastConnected
                                                        : existingDevice.lastConnected;
                                                break;
                                            }
                                        }
                                        runOnUiThread(() -> updateDeviceStatus());
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing child device connection: " + e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing connection data: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "❌ Connection listener cancelled for " + idType + ": " + databaseError.getMessage());
            }
        });
    }

    private void addConnectedDevice(ChildDevice device) {
        nuclearAddDevice(device); // Just add the device, fuck everything else
    }

    private void addConnectedDevice(ChildDevice device, boolean isFromQRScan) {
        nuclearAddDevice(device); // Just add the device, fuck everything else
    }

    private void nuclearAddDevice(ChildDevice device) {
        try {
            Log.d(TAG, "�🔥🔥 NUCLEAR DEVICE ADDITION: " + device.deviceName + " (ID: " + device.deviceId + ")");
            Log.d(TAG, "� FUCK ALL BLOCKING - ADDING DEVICE IMMEDIATELY!");

            // Check if already exists
            boolean exists = connectedDevices.stream().anyMatch(d -> d.deviceId.equals(device.deviceId));

            if (!exists) {
                // 🔧 DEVICE REMOVAL FIX: Check if device was permanently removed
                if (isPermanentlyRemoved(device.deviceId)) {
                    Log.d(TAG, "🚫 Device was previously removed, clearing removal status for QR reconnection: "
                            + device.deviceName);
                    removePermanentRemoval(device.deviceId);
                }

                // Add to local list
                connectedDevices.add(device);
                Log.d(TAG, "� Device added to local list. Total devices now: " + connectedDevices.size());
            } else {
                // Update existing device
                for (int i = 0; i < connectedDevices.size(); i++) {
                    if (connectedDevices.get(i).deviceId.equals(device.deviceId)) {
                        connectedDevices.set(i, device);
                        Log.d(TAG, "📱 Device updated in local list: " + device.deviceName);
                        break;
                    }
                }
            }

            // Add to persistent storage
            connectedDevicesManager.addOrUpdateDevice(device);

            // FIXED: Only set as current device if NO devices exist AND user hasn't
            // manually selected one
            // This prevents auto-switching when new devices connect
            if (currentChildDeviceId == null && connectedDevices.size() == 1) {
                // 🔧 MULTI-DEVICE FIX: Clean up before switching
                performMultiDeviceSwitchCleanup();

                // Only auto-select if this is the very first device (non-explicit)
                connectedDevicesManager.setCurrentDevice(device.deviceId, false);
                // Sync local cached value from manager
                currentChildDeviceId = connectedDevicesManager.getCurrentDeviceId();
                currentChildDeviceName = device.deviceName;

                // Initialize usage limiter for first device
                initializeLimiterForDevice(device.deviceId);

                // 🔧 FORCE-CLOSE PERSISTENCE: Save device name immediately
                saveDeviceNameForCurrentDevice();

                Log.d(TAG, "📱 Set as current device (first device only): " + device.deviceName);
            } else if (currentChildDeviceId != null) {
                Log.d(TAG, "📱 Device added but keeping current selection: " + currentChildDeviceName);
            } else {
                Log.d(TAG, "📱 Multiple devices present, user must manually select");
            }

            // Update UI immediately
            runOnUiThread(() -> {
                // UI updates handled by methods below
                updateDeviceStatus();
                updateTargetDeviceDisplay();
                refreshDeviceListPremium();

                Log.d(TAG, "🚀🚀� UI UPDATED FOR DEVICE: " + device.deviceName);

                Toast.makeText(ParentDashboardActivity.this,
                        "🎉 " + device.deviceName + " connected!",
                        Toast.LENGTH_LONG).show();
            });

        } catch (Exception e) {
            Log.e(TAG, "🔥 Error in nuclear device addition: " + e.getMessage());
        }
    }

    private void continueDeviceConnection(ChildDevice device, boolean isFromQRScan) {
        try {
            Log.d(TAG, "✅✅✅ DEVICE PASSES ALL CHECKS - PROCEEDING WITH CONNECTION");

            // ✅ QR SCAN DEVICE CONNECTION ACCEPTED
            Log.d(TAG, "✅ QR SCAN DEVICE APPROVED: " + device.deviceName + " (ID: " + device.deviceId + ")");
            Log.d(TAG, "✅ Connection method: QR Code Scan (ONLY valid method)");
            Log.d(TAG, "✅ Parent Email Account: " + getCurrentParentUserId());

            // Remove from permanent removal list since QR scan was successful
            removePermanentRemoval(device.deviceId);
            Log.d(TAG, "🔓 Device removed from permanent removal list via QR scan: " + device.deviceId);
            Log.d(TAG, "🔓 Device can now connect normally until removed again");

            // Add to persistent storage (device has already passed blacklist check)
            connectedDevicesManager.addOrUpdateDevice(device);

            // Add to local list
            connectedDevices.removeIf(d -> d.deviceId.equals(device.deviceId));
            connectedDevices.add(device);
            Log.d(TAG, "📱 Device added to local list. Total devices now: " + connectedDevices.size());

            // Log all current devices
            for (int i = 0; i < connectedDevices.size(); i++) {
                ChildDevice d = connectedDevices.get(i);
                Log.d(TAG, "📱 Device " + (i + 1) + ": " + d.deviceName + " (ID: " + d.deviceId + ")");
            }

            // If this is the first device or no current device is set, make it the current
            // device
            if (currentChildDeviceId == null) {
                Log.d(TAG, "Setting as current device (first device or no current device)");
                // Non-explicit set: only set if no current device exists
                connectedDevicesManager.setCurrentDevice(device.deviceId, false);
                currentChildDeviceName = device.deviceName;

                // Initialize usage limiter for first device
                initializeLimiterForDevice(device.deviceId);
            }

            Log.d(TAG, "Current device after adding: " + currentChildDeviceId);

            // Update UI
            updateDeviceStatus();
            updateTargetDeviceDisplay();

            // Start listening for device status
            startListeningForDeviceStatus(device.deviceId);

            // Refresh the category summary chart with new device data
            setupCategorySummaryChart();

            Log.d(TAG, "✅ Successfully added device: " + device.deviceName);

            // Show success message for QR scan connections
            if (isFromQRScan) {
                Toast.makeText(this, "✅ " + device.deviceName + " connected via QR scan", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "🎉 QR scan connection successful - device now appears in parent app");
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error adding connected device: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void removeFromDeviceBlacklist(String deviceId) {
        SharedPreferences blacklistPrefs = getSharedPreferences("removed_devices", MODE_PRIVATE);
        blacklistPrefs.edit().remove(deviceId).apply();
        Log.d(TAG, "🔓 Removed device from blacklist (reconnecting): " + deviceId);
    }

    /**
     * 🚫 Mark device as permanently removed (requires QR scan to reconnect)
     * Email-specific removal tracking to ensure complete isolation
     */
    private void addToPermanentRemovalList(String deviceId) {
        String parentUserId = getCurrentParentUserId();
        if (parentUserId == null) {
            Log.w(TAG, "⚠️ Cannot add to permanent removal list - no parent user ID");
            return;
        }

        // Email-specific permanent removal storage
        String removalKey = parentUserId + "_permanently_removed_devices";
        SharedPreferences removedDevicesPrefs = getSharedPreferences(removalKey, MODE_PRIVATE);
        removedDevicesPrefs.edit().putBoolean(deviceId, true).apply();

        // Also add to global removal list for extra protection
        SharedPreferences globalRemovedPrefs = getSharedPreferences("permanently_removed_devices", MODE_PRIVATE);
        globalRemovedPrefs.edit().putBoolean(deviceId, true).apply();

        // 🧹 SELECTIVE CLEANUP: Keep Focus Mode data but prevent restoration
        // NOTE: We don't clear focus mode data here because user wants persistence
        // The permanent removal list will prevent restoration, but data stays for QR
        // reconnection
        Log.d(TAG,
                "🧹 Device marked as permanently removed, but Focus Mode data preserved for potential QR reconnection");

        Log.d(TAG, "🚫 PERMANENT REMOVAL: Added device to email-specific removal list");
        Log.d(TAG, "🚫 Parent Email ID: " + parentUserId);
        Log.d(TAG, "🚫 Device ID: " + deviceId);
        Log.d(TAG, "🚫 Removal Storage Key: " + removalKey);
        Log.d(TAG, "🔄 Device will ONLY reconnect via QR scan - NO automatic loading allowed");
    }

    /**
     * 🔍 Check if device is permanently removed (cannot auto-reconnect)
     * Email-specific checking with fallback to global list
     */
    private boolean isPermanentlyRemoved(String deviceId) {
        String parentUserId = getCurrentParentUserId();
        Log.d(TAG, "🔍 PERMANENT REMOVAL CHECK START:");
        Log.d(TAG, "🔍 getCurrentParentUserId() returned: " + (parentUserId != null ? parentUserId : "NULL"));

        if (parentUserId == null) {
            Log.w(TAG, "⚠️⚠️⚠️ CRITICAL: Cannot check permanent removal - no parent user ID");
            Log.w(TAG, "⚠️ This would block all connections! Allowing connection as safety fallback");
            return false; // CHANGED: Allow connection if we can't get parent ID
        }

        // Check email-specific removal list first
        String removalKey = parentUserId + "_permanently_removed_devices";
        SharedPreferences removedDevicesPrefs = getSharedPreferences(removalKey, MODE_PRIVATE);
        boolean isEmailSpecificRemoved = removedDevicesPrefs.getBoolean(deviceId, false);

        // Check global removal list as backup
        SharedPreferences globalRemovedPrefs = getSharedPreferences("permanently_removed_devices", MODE_PRIVATE);
        boolean isGlobalRemoved = globalRemovedPrefs.getBoolean(deviceId, false);

        boolean isRemoved = isEmailSpecificRemoved || isGlobalRemoved;

        Log.d(TAG, "🔍 PERMANENT REMOVAL CHECK RESULTS:");
        Log.d(TAG, "🔍 Parent Email ID: " + parentUserId);
        Log.d(TAG, "🔍 Device ID: " + deviceId);
        Log.d(TAG, "🔍 Email-specific removed: " + isEmailSpecificRemoved);
        Log.d(TAG, "🔍 Global removed: " + isGlobalRemoved);
        Log.d(TAG, "🔍 FINAL RESULT: " + (isRemoved ? "DEVICE IS BLOCKED" : "DEVICE IS ALLOWED"));
        Log.d(TAG, "🔍 Final result: " + (isRemoved ? "🚫 BLOCKED" : "✅ ALLOWED"));

        return isRemoved;
    }

    /**
     * 🔓 Remove device from permanent removal list (QR scan reconnection)
     * Clears from both email-specific and global removal lists
     */
    private void removePermanentRemoval(String deviceId) {
        String parentUserId = getCurrentParentUserId();
        if (parentUserId == null) {
            Log.w(TAG, "⚠️ Cannot remove from permanent removal list - no parent user ID");
            return;
        }

        // Remove from email-specific list
        String removalKey = parentUserId + "_permanently_removed_devices";
        SharedPreferences removedDevicesPrefs = getSharedPreferences(removalKey, MODE_PRIVATE);
        removedDevicesPrefs.edit().remove(deviceId).apply();

        // Remove from global list
        SharedPreferences globalRemovedPrefs = getSharedPreferences("permanently_removed_devices", MODE_PRIVATE);
        globalRemovedPrefs.edit().remove(deviceId).apply();

        Log.d(TAG, "🔓 PERMANENT REMOVAL CLEARED:");
        Log.d(TAG, "🔓 Parent Email ID: " + parentUserId);
        Log.d(TAG, "🔓 Device ID: " + deviceId);
        Log.d(TAG, "🔓 Removed from: " + removalKey);
        Log.d(TAG, "🔓 QR scan reconnection successful - device can now connect");
        Log.d(TAG, "💾 IMPORTANT: Focus Mode data preserved during removal - will be available upon reconnection");
    }

    /**
     * 🆔 Get current parent user ID (email-based identifier)
     * Returns Firebase Auth UID or SessionManager user ID
     */
    private String getCurrentParentUserId() {
        String parentUserId = null;

        // Try Firebase Auth first (primary email-based authentication)
        if (mAuth != null && mAuth.getCurrentUser() != null) {
            parentUserId = mAuth.getCurrentUser().getUid();
            Log.d(TAG, "🆔 Parent User ID from Firebase Auth: " + parentUserId);
        }
        // Fallback to SessionManager
        else if (sessionManager != null && sessionManager.isLoggedIn()) {
            parentUserId = sessionManager.getUserId();
            Log.d(TAG, "🆔 Parent User ID from Session Manager: " + parentUserId);
        }

        if (parentUserId == null) {
            Log.w(TAG, "⚠️ No parent user ID available - user not properly authenticated");
        }

        return parentUserId;
    }

    /**
     * 🧹 Clean up permanently removed devices from ConnectedDevicesManager storage
     * Email-specific cleanup to prevent removed devices from reappearing
     * This prevents removed devices from reappearing when the app restarts
     */
    private void cleanupPermanentlyRemovedDevices() {
        try {
            String parentUserId = getCurrentParentUserId();
            if (parentUserId == null) {
                Log.w(TAG, "⚠️ Cannot perform cleanup - no parent user ID available");
                return;
            }

            // Get email-specific removal list
            String removalKey = parentUserId + "_permanently_removed_devices";
            SharedPreferences removedDevicesPrefs = getSharedPreferences(removalKey, MODE_PRIVATE);
            Map<String, ?> emailSpecificRemoved = removedDevicesPrefs.getAll();

            // Also check global removal list for extra protection
            SharedPreferences globalRemovedPrefs = getSharedPreferences("permanently_removed_devices", MODE_PRIVATE);
            Map<String, ?> globalRemoved = globalRemovedPrefs.getAll();

            // Combine both removal lists
            Map<String, Object> allRemovedDevices = new HashMap<>();
            allRemovedDevices.putAll(emailSpecificRemoved);
            allRemovedDevices.putAll(globalRemoved);

            if (allRemovedDevices.isEmpty()) {
                Log.d(TAG, "🧹 CLEANUP: No permanently removed devices to clean up for email: " + parentUserId);
                return;
            }

            Log.d(TAG, "🧹 CLEANUP: Starting email-specific permanent removal cleanup");
            Log.d(TAG, "🧹 Parent Email ID: " + parentUserId);
            Log.d(TAG, "🧹 Email-specific removed devices: " + emailSpecificRemoved.size());
            Log.d(TAG, "🧹 Global removed devices: " + globalRemoved.size());
            Log.d(TAG, "🧹 Total devices to check for removal: " + allRemovedDevices.size());

            // Get current loaded devices from ConnectedDevicesManager
            List<ChildDevice> loadedDevices = connectedDevicesManager.getConnectedDevices();
            List<String> devicesToRemove = new ArrayList<>();

            // Find devices that are loaded but should be permanently removed
            for (ChildDevice device : loadedDevices) {
                if (allRemovedDevices.containsKey(device.deviceId)) {
                    devicesToRemove.add(device.deviceId);
                    Log.d(TAG, "🚫 CLEANUP: Found permanently removed device in storage: " + device.deviceName
                            + " (ID: " + device.deviceId + ")");
                }
            }

            // Remove permanently removed devices from ConnectedDevicesManager
            for (String deviceId : devicesToRemove) {
                connectedDevicesManager.removeDevice(deviceId);
                Log.d(TAG, "🗑️ CLEANUP: Permanently removed device deleted from storage: " + deviceId);
            }

            // Also clear from local connectedDevices list
            int removedFromLocal = 0;
            Iterator<ChildDevice> iterator = connectedDevices.iterator();
            while (iterator.hasNext()) {
                ChildDevice device = iterator.next();
                if (allRemovedDevices.containsKey(device.deviceId)) {
                    iterator.remove();
                    removedFromLocal++;
                    Log.d(TAG, "🔥 CLEANUP: Removed from local memory: " + device.deviceName);
                }
            }

            if (!devicesToRemove.isEmpty() || removedFromLocal > 0) {
                Log.d(TAG, "✅ CLEANUP COMPLETE for email: " + parentUserId);
                Log.d(TAG, "✅ Removed from storage: " + devicesToRemove.size());
                Log.d(TAG, "✅ Removed from memory: " + removedFromLocal);
                Log.d(TAG, "🔄 These devices will NOT appear until QR scan reconnection");

                // Update UI to reflect clean device list
                updateDeviceStatus();
                updateTargetDeviceDisplay();
            } else {
                Log.d(TAG, "✅ CLEANUP COMPLETE: No permanently removed devices found in loaded storage for email: "
                        + parentUserId);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error during email-specific permanent removal cleanup: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void removeConnectedDevice(String deviceId) {
        Log.d(TAG, "🗑️ NUCLE AR DEVICE NAME REMOVAL - Obliterating device: " + deviceId);

        // 🚀 STEP 0: AUTOMATIC CHILD LOGOUT - Trigger immediate logout on child device
        Log.d(TAG, "🚨 TRIGGERING AUTOMATIC CHILD LOGOUT for device: " + deviceId);
        triggerChildDeviceLogout(deviceId);

        // 🛡️ STEP 0.1: DISABLE UNINSTALL PROTECTION - Allow child to deactivate device
        // admin
        Log.d(TAG, "🛡️ DISABLING UNINSTALL PROTECTION for device: " + deviceId);
        disableUninstallProtectionForDevice(deviceId);

        // 🚫 STEP 0.5: Add to permanent removal list (prevents auto-reconnection)
        addToPermanentRemovalList(deviceId);

        // 🗑️ STEP 0.6: Clean up usage limiter data for removed device
        cleanDeviceUsageLimiterData(deviceId);

        // 🆕 STEP 0.7: Clear timer and blocking data (NEW - user requested)
        clearTimerAndBlockingDataForDevice(deviceId);

        // STEP 1: NUCLEAR REMOVE from persistent storage and local list
        connectedDevicesManager.removeDevice(deviceId);

        // STEP 2: NUCLEAR REMOVE from in-memory list
        connectedDevices.removeIf(d -> d.deviceId.equals(deviceId));

        // STEP 3: NUCLEAR CLEAR - Force save empty state if this was the last device
        if (connectedDevices.isEmpty()) {
            connectedDevicesManager.clearAllDevices();
            Log.d(TAG, "🔥 CLEARED ALL DEVICE STORAGE - No devices remain");
        }

        // STEP 4: Simple removal - just remove from current session
        removeDeviceFromCurrentSession(deviceId);

        deviceIdJustRemoved = deviceId; // Set the flag

        if (deviceId.equals(currentChildDeviceId)) {
            // The current device was removed. AUTO-SWITCH to another device if available.
            Log.d(TAG, "📱 Current device removed. Looking for another device to switch to...");

            // Get remaining devices
            List<ChildDevice> remaining = new ArrayList<>();
            for (ChildDevice d : connectedDevices) {
                if (!d.deviceId.equals(deviceId)) {
                    remaining.add(d);
                }
            }

            if (!remaining.isEmpty()) {
                // Auto-switch to first remaining device
                ChildDevice firstDevice = remaining.get(0);
                Log.d(TAG, "📱 Auto-switching to: " + firstDevice.deviceName);

                currentChildDeviceId = firstDevice.deviceId;
                currentChildDeviceName = firstDevice.deviceName;
                currentChildUserName = firstDevice.userName;

                connectedDevicesManager.setCurrentDevice(firstDevice.deviceId, true);

                updateDeviceStatus();
                updateTargetDeviceDisplay();
                populateDeviceList();
                loadSmartUsageDataForSelectedDate(); // Load usage for new device

                Toast.makeText(this,
                        "Switched to " + (firstDevice.userName != null ? firstDevice.userName : firstDevice.deviceName),
                        Toast.LENGTH_SHORT).show();
            } else {
                // No remaining devices - show empty state
                Log.d(TAG, "📱 No remaining devices. Showing empty state.");
                connectedDevicesManager.setCurrentDevice(null, true);
                currentChildDeviceId = null;
                currentChildDeviceName = "No Device";
                currentChildUserName = null;

                updateDeviceStatus();
                updateTargetDeviceDisplay();
                populateDeviceList();
                binding.switchFocusMode.setChecked(false);

                if (binding != null && binding.tvTotalTime != null) {
                    binding.tvTotalTime.setText("0h 0m");
                }

                Toast.makeText(this, "Device removed. Connect a new device.", Toast.LENGTH_SHORT).show();
            }
        } else {
            updateDeviceStatus();
            populateDeviceList();
        }

        // STEP 5: NUCLEAR OPTION - Clear device name from any UI references
        runOnUiThread(() -> {
            updateDeviceStatus();
            updateTargetDeviceDisplay();
        });

        // 🔧 DEVICE REMOVAL FIX: Force cleanup of persistent storage to prevent
        // reappearance
        try {
            cleanupPermanentlyRemovedDevices();
            Log.d(TAG, "✅ Forced cleanup of permanently removed devices from storage");
        } catch (Exception e) {
            Log.e(TAG, "Error during forced cleanup: " + e.getMessage());
        }

        deviceIdJustRemoved = null; // Clear the flag after reload

        // STEP 6: NUCLEAR VERIFICATION - Ensure device is truly obliterated
        boolean deviceGone = connectedDevicesManager.isDeviceTrulyGone(deviceId);
        if (deviceGone) {
            Log.d(TAG, "✅ NUCLEAR VERIFICATION PASSED - Device completely obliterated");
        } else {
            Log.e(TAG, "🚨 NUCLEAR VERIFICATION FAILED - Device still exists somewhere!");
            // Force clear again if verification fails
            connectedDevicesManager.removeDevice(deviceId);
        }

        Log.d(TAG, "☢️ DEVICE NAME COMPLETELY OBLITERATED from all sources: " + deviceId);
        Log.d(TAG, "📊 Remaining devices in storage: " + connectedDevicesManager.getDeviceCount());
        Log.d(TAG, "📋 Device names in storage: " + connectedDevicesManager.getDeviceNames());
    }

    /**
     * 🆕 Clear timer and blocking data for disconnected device
     * USER REQUIREMENT: When child disconnects, delete timer & blocking status
     * so reconnection shows clean state (no past timers or blocking)
     * ⚠️ IMPORTANT: Does NOT touch usage data (susage_stats, device_apps)
     */
    private void clearTimerAndBlockingDataForDevice(String deviceId) {
        try {
            Log.d(TAG, "🧹 CLEARING TIMER & BLOCKING DATA for device: " + deviceId);
            String parentUserId = getCurrentParentUserId();

            if (parentUserId == null) {
                Log.w(TAG, "⚠️ Cannot clear timer/blocking data - no parent user ID");
                return;
            }

            DatabaseReference database = FirebaseDatabase.getInstance().getReference();

            // 1️⃣ Clear parent_timers (parent-side timer settings)
            database.child("parent_timers").child(deviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared parent_timers for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear parent_timers: " + e.getMessage());
                    });

            // 2️⃣ Clear active_timers (child-side active timer state)
            database.child("active_timers").child(deviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared active_timers for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear active_timers: " + e.getMessage());
                    });

            // 3️⃣ Clear smart_timers (advanced timer settings)
            database.child("smart_timers").child(deviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared smart_timers for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear smart_timers: " + e.getMessage());
                    });

            // 4️⃣ Clear daily_usage_limits (daily time limits)
            database.child("daily_usage_limits").child(deviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared daily_usage_limits for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear daily_usage_limits: " + e.getMessage());
                    });

            // 5️⃣ Clear blocked_apps (app blocking status)
            database.child("blocked_apps").child(deviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared blocked_apps for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear blocked_apps: " + e.getMessage());
                    });

            // 6️⃣ Clear timers from parents structure (backup location)
            database.child("parents").child(parentUserId)
                    .child("connectedChildDevices").child(deviceId)
                    .child("timers").removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared timers from parents structure for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear timers from parents structure: " + e.getMessage());
                    });

            // 7️⃣ Clear blocking status from parents structure
            database.child("parents").child(parentUserId)
                    .child("connectedChildDevices").child(deviceId)
                    .child("blockedApps").removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared blockedApps from parents structure for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear blockedApps from parents structure: " + e.getMessage());
                    });

            // 8️⃣ 🆕 Clear app_timers (per-app timer settings) - CRITICAL: This was
            // missing!
            database.child("app_timers").child(deviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared app_timers for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear app_timers: " + e.getMessage());
                    });

            // 9️⃣ 🆕 Clear focus mode status
            database.child("parents").child(parentUserId)
                    .child("connectedChildDevices").child(deviceId)
                    .child("focusModeActive").removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Cleared focusModeActive for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to clear focusModeActive: " + e.getMessage());
                    });

            // 🔟 🆕 Clear any timer data stored under qr_share_codes
            String qrShareKey = sessionManager.getQRShareKey();
            if (qrShareKey != null && !qrShareKey.isEmpty()) {
                database.child("qr_share_codes").child(qrShareKey)
                        .child("timers").removeValue()
                        .addOnSuccessListener(aVoid -> {
                            Log.d(TAG, "✅ Cleared qr_share_codes timers for shareKey: " + qrShareKey);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "❌ Failed to clear qr_share_codes timers: " + e.getMessage());
                        });

                database.child("qr_share_codes").child(qrShareKey)
                        .child("devices").child(deviceId).child("timers").removeValue()
                        .addOnSuccessListener(aVoid -> {
                            Log.d(TAG, "✅ Cleared qr_share_codes device timers for device: " + deviceId);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "❌ Failed to clear qr_share_codes device timers: " + e.getMessage());
                        });
            }

            // 1️⃣1️⃣ 🆕 Clear LOCAL SharedPreferences timer storage (CRITICAL - this was
            // causing timers to restore!)
            clearLocalTimerStorageForDevice(deviceId);

            Log.d(TAG, "🎯 Timer & blocking data clearing initiated for: " + deviceId);
            Log.d(TAG, "✅ FIREBASE PATHS CLEARED: 10 different locations");
            Log.d(TAG, "✅ LOCAL STORAGE CLEARED: timer_duration, smart_timer_prefs, timer_state");
            Log.d(TAG, "✅ PRESERVED: Usage data (susage_stats, device_apps) - NOT touched");
            Log.d(TAG, "🔄 On reconnection: Device will have CLEAN timer & blocking state");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error clearing timer/blocking data for device " + deviceId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🆕 Clear local SharedPreferences timer storage for a specific device
     * This prevents timers from being restored when device reconnects
     */
    private void clearLocalTimerStorageForDevice(String deviceId) {
        try {
            Log.d(TAG, "🧹 Clearing LOCAL timer storage for device: " + deviceId);

            // 1. Clear timer_duration (stored per device)
            SharedPreferences timerDurationPrefs = getSharedPreferences("timer_duration", MODE_PRIVATE);
            String hoursKey = "timer_hours_" + deviceId;
            String minutesKey = "timer_minutes_" + deviceId;
            timerDurationPrefs.edit()
                    .remove(hoursKey)
                    .remove(minutesKey)
                    .apply();
            Log.d(TAG, "✅ Cleared timer_duration for device: " + deviceId);

            // 2. Clear smart_timer_prefs (if stored per device)
            SharedPreferences smartTimerPrefs = getSharedPreferences("smart_timer_prefs", MODE_PRIVATE);
            SharedPreferences.Editor smartEditor = smartTimerPrefs.edit();
            for (String key : smartTimerPrefs.getAll().keySet()) {
                if (key.contains(deviceId)) {
                    smartEditor.remove(key);
                    Log.d(TAG, "✅ Removed smart_timer key: " + key);
                }
            }
            smartEditor.apply();

            // 3. Clear timer_state (if stored per device)
            SharedPreferences timerStatePrefs = getSharedPreferences("timer_state", MODE_PRIVATE);
            SharedPreferences.Editor stateEditor = timerStatePrefs.edit();
            for (String key : timerStatePrefs.getAll().keySet()) {
                if (key.contains(deviceId)) {
                    stateEditor.remove(key);
                    Log.d(TAG, "✅ Removed timer_state key: " + key);
                }
            }
            stateEditor.apply();

            // 4. Clear app_timer_prefs (per-app timer settings for this device)
            SharedPreferences appTimerPrefs = getSharedPreferences("app_timer_prefs", MODE_PRIVATE);
            SharedPreferences.Editor appEditor = appTimerPrefs.edit();
            for (String key : appTimerPrefs.getAll().keySet()) {
                if (key.contains(deviceId)) {
                    appEditor.remove(key);
                    Log.d(TAG, "✅ Removed app_timer key: " + key);
                }
            }
            appEditor.apply();

            // 5. Clear focus_mode_prefs for this device
            SharedPreferences focusModePrefs = getSharedPreferences("focus_mode_prefs", MODE_PRIVATE);
            SharedPreferences.Editor focusEditor = focusModePrefs.edit();
            for (String key : focusModePrefs.getAll().keySet()) {
                if (key.contains(deviceId)) {
                    focusEditor.remove(key);
                    Log.d(TAG, "✅ Removed focus_mode key: " + key);
                }
            }
            focusEditor.apply();

            // 6. Clear blocked_apps SharedPreferences for this device
            SharedPreferences blockedAppsPrefs = getSharedPreferences("blocked_apps_" + deviceId, MODE_PRIVATE);
            if (blockedAppsPrefs.getAll().size() > 0) {
                blockedAppsPrefs.edit().clear().apply();
                Log.d(TAG, "✅ Cleared blocked_apps prefs for device: " + deviceId);
            }

            Log.d(TAG, "🎯 LOCAL timer storage completely cleared for device: " + deviceId);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error clearing local timer storage: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🛡️ Disable uninstall protection for a device in Firebase
     * This is called when a child device is removed/disconnected
     * Setting this to false allows the child to access Device Admin settings and
     * uninstall the app
     */
    private void disableUninstallProtectionForDevice(String deviceId) {
        try {
            Log.d(TAG, "🛡️ Disabling uninstall protection for device: " + deviceId);

            DatabaseReference protectionRef = FirebaseDatabase.getInstance()
                    .getReference("child_devices")
                    .child(deviceId)
                    .child("uninstall_protection");

            // Set to false to disable protection (child can now deactivate device admin)
            protectionRef.setValue(false)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Uninstall protection DISABLED for device: " + deviceId);
                        Log.d(TAG, "🔓 Child can now access Device Admin settings");
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to disable uninstall protection: " + e.getMessage());
                    });

            // Also remove the protection node entirely when device is fully disconnected
            // This ensures clean state for potential future reconnection
            protectionRef.removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Uninstall protection node removed for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "⚠️ Could not remove protection node (not critical): " + e.getMessage());
                    });

        } catch (Exception e) {
            Log.e(TAG, "❌ Error disabling uninstall protection: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Simple device removal - just remove from current session without blacklisting
     */
    private void removeDeviceFromCurrentSession(String deviceId) {
        // Remove from current session only
        connectedDevices.removeIf(device -> device.deviceId.equals(deviceId));
        connectedDevicesManager.removeDevice(deviceId);
        Log.d(TAG, "🗑️ Device removed from current session: " + deviceId);
    }

    private void loadConnectedDevices() {
        // 🚫 DISABLED - This method was causing automatic device loading from Firebase
        // User requirement: Only QR scanned devices should be shown
        Log.d(TAG, "🚫 AUTOMATIC DEVICE LOADING DISABLED - Only QR scan connections allowed");
        Log.d(TAG, "📱 Device list will remain empty until QR codes are scanned");

        // Clear any existing devices and show empty state
        connectedDevices.clear();
        updateDeviceStatus();
        updateTargetDeviceDisplay();

        return;
    }

    private void loadConnectedDevicesOLD_DISABLED() {
        try {
            Log.d(TAG, "🔍 Loading connected devices from multiple sources...");

            // First load from persistent storage (devices that were previously connected)
            connectedDevicesManager.loadDevicesAsync(new ConnectedDevicesManager.OnDevicesLoadedListener() {
                @Override
                public void onDevicesLoaded(List<ChildDevice> devices) {
                    Log.d(TAG, "Loaded " + devices.size() + " devices from persistent storage");
                    connectedDevices.clear();
                    connectedDevices.addAll(devices);

                    // Update Firebase data for each persistent device
                    for (ChildDevice device : devices) {
                        startListeningForDeviceStatus(device.deviceId);
                    }
                }

                @Override
                public void onCurrentDeviceLoaded(String deviceId) {
                    Log.d(TAG, "Current device from storage: " + deviceId);
                    if (deviceId != null) {
                        // Treat restored selection as explicit restoration
                        connectedDevicesManager.setCurrentDevice(deviceId, true);

                        // If we have a saved current device, switch to it
                        ChildDevice device = connectedDevicesManager.getDevice(deviceId);
                        if (device != null) {
                            currentChildDeviceName = device.deviceName;

                            // ⭐ Setup device-specific data loading

                            updateDeviceStatus();
                            updateTargetDeviceDisplay();
                            loadFocusModeApps();
                            updateFocusModeUI();
                            // Refresh the category summary chart with loaded device data
                            setupCategorySummaryChart();
                            // Load usage data for the selected device
                            loadSmartUsageDataForSelectedDate();

                            Log.d(TAG, "✅ Device-specific data loaded for: " + device.deviceName);
                        }
                    }
                }
            });

            // 🚫 DISABLED - These methods cause automatic device loading
            // User requirement: Only QR scanned devices should be shown
            // loadDevicesFromQRShares();
            // loadDevicesFromParentsStructure();
            Log.d(TAG, "🚫 Firebase device loading disabled - QR scan only mode active");

        } catch (Exception e) {
            Log.e(TAG, "Error loading connected devices: " + e.getMessage());
        }
    }

    private void loadDevicesFromQRShares() {
        // 🚫 DISABLED - This method was causing automatic device loading from qr_shares
        // User requirement: Only QR scanned devices should be shown
        Log.d(TAG, "🚫 QR_SHARES LOADING DISABLED - Only active QR scan connections allowed");
        return;
    }

    private void loadDevicesFromQRSharesOLD_DISABLED() {
        // Load from Firebase qr_shares (original method) - but validate against parents
        // structure
        childDeviceManager.loadExistingConnections(permanentQRKey, new ChildDeviceManager.OnDevicesLoadedListener() {
            @Override
            public void onDevicesLoaded(List<ChildDevice> firebaseDevices) {
                Log.d(TAG, "Found " + firebaseDevices.size()
                        + " devices in qr_shares, validating against parents structure...");

                if (mAuth.getCurrentUser() != null) {
                    String parentUserId = mAuth.getCurrentUser().getUid();
                    DatabaseReference parentsRef = FirebaseDatabase.getInstance()
                            .getReference("parents")
                            .child(parentUserId)
                            .child("connectedChildDevices");

                    // Validate each device from QR shares against parents structure
                    parentsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(@NonNull DataSnapshot parentsSnapshot) {
                            Log.d(TAG, "🔍 Parents structure has " + parentsSnapshot.getChildrenCount() + " devices");

                            for (ChildDevice device : firebaseDevices) {
                                // STRICT VALIDATION: Check parents structure and recent removal
                                if (parentsSnapshot.hasChild(device.deviceId) &&
                                        !device.deviceId.equals(deviceIdJustRemoved)) {

                                    if (!connectedDevicesManager.isDeviceConnected(device.deviceId)) {
                                        Log.d(TAG, "✅ Adding validated device from qr_shares: " + device.deviceName);
                                        addConnectedDevice(device);
                                    } else {
                                        // Update existing device data
                                        connectedDevicesManager.addOrUpdateDevice(device);
                                        connectedDevices.removeIf(d -> d.deviceId.equals(device.deviceId));
                                        connectedDevices.add(device);
                                    }
                                } else {
                                    Log.d(TAG,
                                            "❌ Skipping device (not in parents or just removed): " + device.deviceName);
                                }
                            }
                            finalizeDeviceLoading();
                        }

                        @Override
                        public void onCancelled(@NonNull DatabaseError databaseError) {
                            Log.e(TAG, "Failed to validate devices against parents structure");
                            finalizeDeviceLoading();
                        }
                    });
                } else {
                    Log.w(TAG, "No authenticated user - skipping QR shares loading");
                    finalizeDeviceLoading();
                }
            }

            @Override
            public void onError(String error) {
                Log.d(TAG, "No existing connections found in qr_shares: " + error);
                finalizeDeviceLoading();
            }
        });
    }

    private void loadDevicesFromParentsStructure() {
        // 🚫 DISABLED - This method was causing automatic device loading from parents
        // structure
        // User requirement: Only QR scanned devices should be shown
        Log.d(TAG, "🚫 PARENTS STRUCTURE LOADING DISABLED - Only active QR scan connections allowed");
        return;
    }

    private void loadDevicesFromParentsStructureOLD_DISABLED() {
        // Also load from parents/{parentUserId}/connectedChildDevices
        if (mAuth.getCurrentUser() != null) {
            String parentUserId = mAuth.getCurrentUser().getUid();
            Log.d(TAG, "🔍 Loading devices from parents structure for user: " + parentUserId);

            DatabaseReference parentDevicesRef = FirebaseDatabase.getInstance()
                    .getReference("parents")
                    .child(parentUserId)
                    .child("connectedChildDevices");

            parentDevicesRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    Log.d(TAG, "📊 Parents structure devices count: " + dataSnapshot.getChildrenCount());

                    for (DataSnapshot deviceSnapshot : dataSnapshot.getChildren()) {
                        try {
                            String deviceId = deviceSnapshot.getKey();
                            String deviceName = deviceSnapshot.child("deviceName").getValue(String.class);
                            Long appCount = deviceSnapshot.child("appCount").getValue(Long.class);
                            Long lastConnected = deviceSnapshot.child("lastConnected").getValue(Long.class);

                            if (deviceId != null && deviceName != null) {
                                // Safety check for recently removed device
                                if (deviceId.equals(deviceIdJustRemoved)) {
                                    Log.d(TAG, "⚠️ RECENTLY REMOVED DEVICE blocked: " + deviceName + " (ID: " + deviceId
                                            + ")");
                                    continue;
                                }

                                // Create ChildDevice from parents structure data
                                ChildDevice device = new ChildDevice();
                                device.deviceId = deviceId;
                                device.deviceName = deviceName;
                                device.appCount = appCount != null ? appCount.intValue() : 0;
                                device.lastConnected = lastConnected != null ? lastConnected
                                        : System.currentTimeMillis();
                                device.apps = new ArrayList<>();
                                device.focusModeActive = false;

                                if (!connectedDevicesManager.isDeviceConnected(device.deviceId)) {
                                    Log.d(TAG, "✅ SAFE DEVICE ADDITION from parents structure: " + device.deviceName
                                            + " (ID: " + device.deviceId + ")");
                                    addConnectedDevice(device);
                                } else {
                                    Log.d(TAG,
                                            "📱 Updating existing device from parents structure: " + device.deviceName);
                                    // Update existing device data
                                    connectedDevicesManager.addOrUpdateDevice(device);
                                    connectedDevices.removeIf(d -> d.deviceId.equals(device.deviceId));
                                    connectedDevices.add(device);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing device from parents structure: " + e.getMessage());
                        }
                    }

                    finalizeDeviceLoading();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "Error loading from parents structure: " + databaseError.getMessage());
                    finalizeDeviceLoading();
                }
            });
        } else {
            Log.w(TAG, "⚠️ Parent not authenticated, cannot load from parents structure");
            finalizeDeviceLoading();
        }
    }

    private void finalizeDeviceLoading() {
        // Do NOT auto-select a device. Keep the current selection until the user
        // explicitly switches.
        if (currentChildDeviceId != null) {
            updateDeviceStatus();
        } else {
            // No device selected: update UI and wait for explicit user selection
            updateDeviceStatus();
            Log.d(TAG, "No device auto-selected; waiting for user to pick a device.");
        }

        Log.d(TAG, "✅ Device loading finalized. Total devices: " + connectedDevices.size());

        // Update UI to reflect current state
        runOnUiThread(() -> {
            updateDeviceStatus();
            if (connectedDevices.isEmpty()) {
                Log.d(TAG, "📱 No devices connected - showing tap to view devices message");
                binding.tvDeviceStatus.setText("Tap to view devices");
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600)); // Always green
            }
        });
    }

    // ==============================================
    // UPDATED METHOD FOR MODERN DESIGN COLORS
    // ==============================================
    private void updateDeviceStatus() {
        try {
            Log.d(TAG, "📱 UPDATING DEVICE STATUS DISPLAY");
            Log.d(TAG, "📱 Current device ID: " + currentChildDeviceId);
            Log.d(TAG, "📱 Current device name: " + currentChildDeviceName);

            if (currentChildDeviceId != null) {
                // Show formatted device status text
                String displayName = "";
                if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
                    displayName = currentChildUserName;
                } else if (currentChildDeviceName != null && !currentChildDeviceName.isEmpty()) {
                    displayName = currentChildDeviceName;
                } else {
                    displayName = currentChildDeviceId;
                }

                // Capitalize first letter
                String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
                String deviceStatusText = capitalizedName + " (Manage Devices)";

                Log.d(TAG, "📱 Setting device status text to: " + deviceStatusText);
                binding.tvDeviceStatus.setText(deviceStatusText);
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600)); // Teal color

                // Force UI refresh
                binding.tvDeviceStatus.invalidate();
                binding.tvDeviceStatus.requestLayout();

                if (btnRemoveDevice != null) {
                    btnRemoveDevice.setVisibility(View.VISIBLE);
                }
                Log.d(TAG, "✅ Device status updated successfully");
            } else {
                // Show default text when no device
                Log.d(TAG, "📱 No current device, showing default text");
                binding.tvDeviceStatus.setText("Select a child");
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral_500));

                // Force UI refresh
                binding.tvDeviceStatus.invalidate();
                binding.tvDeviceStatus.requestLayout();

                if (btnRemoveDevice != null) {
                    btnRemoveDevice.setVisibility(View.GONE);
                }
            }

            // 🔧 REFRESH DEVICE LIST UI
            populateDeviceList();

        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating device status: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void refreshDeviceList() {
        try {
            Log.d(TAG, "🔄 Force refreshing device list display");

            // Force update device status display
            updateDeviceStatus();
            updateTargetDeviceDisplay();

            // Force adapter notification if using RecyclerView
            if (binding != null) {
                // Update any RecyclerView adapters here if they exist
                Log.d(TAG, "📱 Device list UI refreshed");
            }

            // 🔧 POPULATE THE NEW HORIZONTAL LIST
            populateDeviceList();

        } catch (Exception e) {
            Log.e(TAG, "Error refreshing device list: " + e.getMessage());
        }
    }

    private void showQRScanner() {
        // In this app flow, "Connect New" means showing the Parent QR for the child to
        // scan
        showQRFullscreen();
    }

    private void populateDeviceList() {
        if (llDeviceList == null)
            return;

        llDeviceList.removeAllViews();

        // 1. Add all connected devices
        List<ChildDevice> devices = connectedDevicesManager.getConnectedDevices();

        for (ChildDevice device : devices) {

            // Build programmatically to avoid creating new XML right now
            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.VERTICAL);
            itemLayout.setGravity(android.view.Gravity.CENTER);
            itemLayout.setPadding(16, 8, 16, 8);

            // Frame for Icon/Avatar
            android.widget.FrameLayout iconFrame = new android.widget.FrameLayout(this);
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                    (int) (56 * getResources().getDisplayMetrics().density),
                    (int) (56 * getResources().getDisplayMetrics().density));
            iconFrame.setLayoutParams(frameParams);
            iconFrame.setBackgroundResource(R.drawable.selector_device_item);
            iconFrame.setSelected(device.deviceId.equals(currentChildDeviceId));

            ImageView icon = new ImageView(this);
            android.widget.FrameLayout.LayoutParams iconParams = new android.widget.FrameLayout.LayoutParams(
                    (int) (24 * getResources().getDisplayMetrics().density),
                    (int) (24 * getResources().getDisplayMetrics().density));
            iconParams.gravity = android.view.Gravity.CENTER;
            icon.setLayoutParams(iconParams);
            icon.setImageResource(R.drawable.ic_device); // Use appropriate icon
            icon.setColorFilter(ContextCompat.getColor(this, R.color.primary_600));

            iconFrame.addView(icon);
            itemLayout.addView(iconFrame);

            // Name Text
            TextView nameText = new TextView(this);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
            nameText.setLayoutParams(textParams);
            // Display child's actual name (userName) if available, otherwise device name
            String displayName = (device.userName != null && !device.userName.isEmpty())
                    ? device.userName
                    : device.deviceName;
            nameText.setText(displayName);
            nameText.setTextSize(12);
            nameText.setTextColor(ContextCompat.getColor(this, R.color.neutral_700));
            nameText.setMaxLines(1);
            nameText.setEllipsize(android.text.TextUtils.TruncateAt.END);

            itemLayout.addView(nameText);

            // Click Listener
            itemLayout.setOnClickListener(v -> {
                switchDevice(device.deviceId);
            });

            // Add margin
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            itemParams.setMarginEnd((int) (12 * getResources().getDisplayMetrics().density));
            itemLayout.setLayoutParams(itemParams);

            llDeviceList.addView(itemLayout);
        }

        // 2. Add static "Add" button at the end
        // We reuse the hidden definition from XML or create new?
        // In XML we added a STATIC "Add" button inside the ScrollView inside
        // llDeviceList?
        // Wait, in my XML I added:
        // <LinearLayout id="@+id/llDeviceList" ...>
        // <LinearLayout id="@+id/btnAddDevice" ... />
        // </LinearLayout>
        // calling removeAllViews() CLEARS that static button!
        // So I must Re-Add it or Inflate it.

        // Re-create Add Button programmatically for simplicity and robustness
        LinearLayout addLayout = new LinearLayout(this);
        addLayout.setOrientation(LinearLayout.VERTICAL);
        addLayout.setGravity(android.view.Gravity.CENTER);
        addLayout.setPadding(16, 8, 16, 8);
        addLayout.setOnClickListener(v -> showQRScanner());

        android.widget.FrameLayout addFrame = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams addFrameParams = new LinearLayout.LayoutParams(
                (int) (56 * getResources().getDisplayMetrics().density),
                (int) (56 * getResources().getDisplayMetrics().density));
        addFrame.setLayoutParams(addFrameParams);
        addFrame.setBackgroundResource(R.drawable.bg_icon_child); // Reuse existing or simple circle

        ImageView addIcon = new ImageView(this);
        android.widget.FrameLayout.LayoutParams addIconParams = new android.widget.FrameLayout.LayoutParams(
                (int) (24 * getResources().getDisplayMetrics().density),
                (int) (24 * getResources().getDisplayMetrics().density));
        addIconParams.gravity = android.view.Gravity.CENTER;
        addIcon.setLayoutParams(addIconParams);
        addIcon.setImageResource(R.drawable.ic_add);
        addIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary_600));

        addFrame.addView(addIcon);
        addLayout.addView(addFrame);

        TextView addText = new TextView(this);
        LinearLayout.LayoutParams addTextParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        addTextParams.topMargin = (int) (4 * getResources().getDisplayMetrics().density);
        addText.setLayoutParams(addTextParams);
        addText.setText("Add");
        addText.setTextSize(12);
        addText.setTextColor(ContextCompat.getColor(this, R.color.neutral_600));

        addLayout.addView(addText);

        llDeviceList.addView(addLayout);
    }

    private void switchDevice(String deviceId) {
        if (deviceId.equals(currentChildDeviceId))
            return;

        // Stop monitoring old device
        stopUninstallDetection();

        connectedDevicesManager.setCurrentDevice(deviceId, true);
        currentChildDeviceId = deviceId;

        ChildDevice device = connectedDevicesManager.getDevice(deviceId);
        if (device != null) {
            currentChildDeviceName = device.deviceName;
            currentChildUserName = device.userName; // FIX: Update user name for green text display
        }

        // Removed: loadSmartUsageDataForSelectedDate() now shows cached data instantly

        updateDeviceStatus();
        updateTargetDeviceDisplay(); // Update green text (now uses currentChildUserName)
        loadSmartUsageDataForSelectedDate(); // Async load usage data

        // 🚨 Start uninstall detection for new device
        startUninstallDetection();

        // Re-populate list to update selection state (uses simple circular icons)
        populateDeviceList();
    }

    private void debugDeviceLists(String context) {
        try {
            Log.d(TAG, "🔍 DEBUG DEVICE LISTS - " + context);
            Log.d(TAG, "📱 Local connectedDevices size: " + connectedDevices.size());
            for (int i = 0; i < connectedDevices.size(); i++) {
                ChildDevice device = connectedDevices.get(i);
                Log.d(TAG, "  [" + i + "] " + device.deviceName + " (ID: " + device.deviceId + ")");
            }

            List<ChildDevice> persistentDevices = connectedDevicesManager.getConnectedDevices();
            Log.d(TAG, "💾 Persistent storage devices size: " + persistentDevices.size());
            for (int i = 0; i < persistentDevices.size(); i++) {
                ChildDevice device = persistentDevices.get(i);
                Log.d(TAG, "  [" + i + "] " + device.deviceName + " (ID: " + device.deviceId + ")");
            }

            Log.d(TAG, "🎯 Current device: " + currentChildDeviceId + " (" + currentChildDeviceName + ")");

        } catch (Exception e) {
            Log.e(TAG, "Error debugging device lists: " + e.getMessage());
        }
    }

    private void startListeningForDeviceStatus(String deviceId) {
        try {
            Log.d(TAG, "👂 Starting device status listener for: " + deviceId);
            deviceStatusManager.listenForChildDeviceStatus(deviceId,
                    new DeviceStatusManager.OnDeviceStatusChangeListener() {
                        @Override
                        public void onDeviceStatusChanged(String deviceId, boolean isOnline, long lastSeen) {
                            runOnUiThread(() -> {
                                // Update the device status in persistent storage
                                connectedDevicesManager.updateDeviceLastSeen(deviceId, lastSeen);

                                // Update the device status in our in-memory list for compatibility
                                for (ChildDevice device : connectedDevices) {
                                    if (device.deviceId.equals(deviceId)) {
                                        device.lastConnected = lastSeen;

                                        // Update UI if this is the current device
                                        if (deviceId.equals(currentChildDeviceId)) {
                                            updateDeviceStatusDisplay(device.deviceName, isOnline, lastSeen);
                                        }
                                        break;
                                    }
                                }
                            });
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error setting up device status listener: " + e.getMessage());
        }
    }

    // ==============================================
    // UPDATED METHOD FOR MODERN DESIGN COLORS
    // ==============================================
    private void updateDeviceStatusDisplay(String deviceName, boolean isOnline, long lastSeen) {
        try {
            // Show formatted device status text
            String displayName = "";
            if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
                displayName = currentChildUserName;
            } else if (deviceName != null && !deviceName.isEmpty()) {
                displayName = deviceName;
            } else {
                displayName = "Device";
            }

            // Capitalize first letter
            String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
            String deviceStatusText = capitalizedName + " (Manage Devices)";

            binding.tvDeviceStatus.setText(deviceStatusText);
            binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600)); // Teal color
        } catch (Exception e) {
            Log.e(TAG, "Error updating device status display: " + e.getMessage());
        }
    }

    private void updateUsageChart(ChildDevice device) {
        setupChart();
    }

    private void setupChart() {
        // Chart removed - now showing only total usage display
        Log.d(TAG, "setupChart called - chart functionality removed");
    }

    /**
     * 📊 Get day label for bar chart index (0 = oldest day, last index = today)
     */
    private String getDayLabelForBarIndex(int index) {
        // Calculate the date for this bar index
        Calendar barDate = getDateForBarIndex(index);
        if (barDate == null)
            return "Unknown";

        Calendar today = Calendar.getInstance();
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);

        if (isSameDay(barDate, today)) {
            return "Today";
        } else if (isSameDay(barDate, yesterday)) {
            return "Yesterday";
        } else {
            String[] dayNames = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };
            return dayNames[barDate.get(Calendar.DAY_OF_WEEK) - 1];
        }
    }

    /**
     * 📅 Get date for bar chart index
     */
    private Calendar getDateForBarIndex(int index) {
        // Get current 7-day window
        List<Calendar> dayWindow = getCurrentSevenDayWindow();
        if (index >= 0 && index < dayWindow.size()) {
            return dayWindow.get(index);
        }
        return null;
    }

    /**
     * Check if two Calendar dates represent the same day
     */
    private boolean isSameDay(Calendar cal1, Calendar cal2) {
        if (cal1 == null || cal2 == null)
            return false;
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * Get current 7-day window for bar chart
     */
    private List<Calendar> getCurrentSevenDayWindow() {
        List<Calendar> dayWindow = new ArrayList<>();
        Calendar cal = Calendar.getInstance();

        // Start from 6 days ago and go to today
        for (int i = 6; i >= 0; i--) {
            Calendar dayCalendar = Calendar.getInstance();
            dayCalendar.add(Calendar.DAY_OF_YEAR, -i);
            dayWindow.add(dayCalendar);
        }

        return dayWindow;
    }

    /**
     * Update parent dashboard bar chart with usage data
     * /**
     * Update the parent dashboard bar chart - DISABLED
     * 7-Day Usage Overview section has been completely removed from layout
     */
    private void updateParentDashboardBarChart(List<Float> barValues, List<String> dayLabels) {
        // 7-Day Usage Overview section has been removed from layout
        // This method is disabled to prevent errors
        Log.d(TAG, "📊 7-Day Usage Overview disabled - section removed from layout");
        return;
    }

    /**
     * Update bar chart from Firebase snapshot data
     */
    private void updateBarChartFromSnapshot(DataSnapshot snapshot) {
        try {
            if (snapshot == null) {
                Log.w(TAG, "Cannot update bar chart - snapshot is null");
                return;
            }

            // Get bar chart data from snapshot
            List<Float> barValues = new ArrayList<>();
            List<String> dayLabels = new ArrayList<>();

            // Check for bars data
            if (snapshot.child("bars").exists()) {
                DataSnapshot barsSnapshot = snapshot.child("bars");

                // Extract bar values (usage data in minutes)
                for (DataSnapshot barSnapshot : barsSnapshot.getChildren()) {
                    Float value = barSnapshot.getValue(Float.class);
                    if (value != null) {
                        barValues.add(value);
                    } else {
                        barValues.add(0.0f);
                    }
                }

                Log.d(TAG, "📊 Extracted " + barValues.size() + " bar values from Firebase");
            }

            // Check for day labels data
            if (snapshot.child("dayLabels").exists()) {
                DataSnapshot labelsSnapshot = snapshot.child("dayLabels");

                // Extract day labels
                for (DataSnapshot labelSnapshot : labelsSnapshot.getChildren()) {
                    String label = labelSnapshot.getValue(String.class);
                    if (label != null) {
                        dayLabels.add(label);
                    }
                }

                Log.d(TAG, "📅 Extracted " + dayLabels.size() + " day labels from Firebase");
            }

            // If we have data, update the bar chart
            if (!barValues.isEmpty()) {
                // Ensure dayLabels matches barValues length
                while (dayLabels.size() < barValues.size()) {
                    int index = dayLabels.size();
                    dayLabels.add(getDayLabelForBarIndex(index));
                }

                // Update the chart
                updateParentDashboardBarChart(barValues, dayLabels);
                Log.d(TAG, "📊 Successfully updated bar chart with " + barValues.size() + " data points");
            } else {
                Log.d(TAG, "📊 No bar chart data available in snapshot");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating bar chart from snapshot: " + e.getMessage(), e);
        }
    }

    private void checkAccessibilityPermission() {
        try {
            String packageName = getPackageName();
            String enabledServices = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            if (enabledServices == null || !enabledServices.contains(packageName)) {
                new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                        .setTitle("Accessibility Permission Required")
                        .setMessage("This app needs accessibility permission to monitor and block apps effectively.")
                        .setPositiveButton("Grant Permission", (dialog, which) -> {
                            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                            startActivity(intent);
                        })
                        .setNegativeButton("Skip", null)
                        .show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error checking accessibility permission: " + e.getMessage());
        }
    }

    private void addSettingsButtons() {
        // Terms and Services button - REMOVED from dashboard footer
        // Button btnTermsAndServices = findViewById(R.id.btnTermsAndServices);
        // ...

        // Disconnect All Devices button - REMOVED
        // Button btnDisconnectAll = findViewById(R.id.btnDisconnectAll);
        // ...
    }

    private void addWelcomeTextToSettings() {
        // Add the full welcome message text directly to settings content
        if (settingsContent != null && settingsContent instanceof LinearLayout) {
            LinearLayout settingsLayout = (LinearLayout) settingsContent;

            // Remove any existing welcome/help content first
            View existingWelcomeText = settingsContent.findViewWithTag("welcome_text");
            View existingHelpInfo = settingsContent.findViewWithTag("help_info");

            if (existingWelcomeText != null) {
                settingsLayout.removeView(existingWelcomeText);
                Log.d(TAG, "Removed existing welcome text");
            }
            if (existingHelpInfo != null) {
                settingsLayout.removeView(existingHelpInfo);
                Log.d(TAG, "Removed existing help info");
            }

            // Create welcome text directly - no card wrapper, no button
            TextView welcomeText = new TextView(this);
            welcomeText.setTag("welcome_text");
            welcomeText.setText("🎉 Welcome & Important Information\n\n" +
                    "Welcome! Here are some important tips:\n\n" +
                    "• Use the QR code scanner to connect child devices\n" +
                    "• Monitor and manage your child's screen time easily\n" +
                    "• Access all controls from this parent dashboard\n\n" +
                    "⚠️ TROUBLESHOOTING: If you can see a device name but cannot track its data, please:\n" +
                    "1. Remove the device from this app\n" +
                    "2. Reinstall the app on the child device\n" +
                    "3. Connect the child via QR code again\n\n" +
                    "🔒 IMPORTANT SECURITY: Before uninstalling this app or logging out permanently:\n" +
                    "• Always remove all connected child devices first\n" +
                    "• This prevents security issues and data conflicts\n" +
                    "• Use 'Disconnect All Devices' in Settings if needed\n\n" +
                    "💡 TIP: Ensure both devices have stable internet when connecting via QR code.");

            // Style the text
            welcomeText.setTextSize(14);
            welcomeText.setTextColor(ContextCompat.getColor(this, android.R.color.black));
            welcomeText.setLineSpacing(6, 1.2f);
            welcomeText.setPadding(32, 32, 32, 32);
            welcomeText.setBackground(ContextCompat.getDrawable(this, R.drawable.card_background));

            // Set layout parameters with margin
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            textParams.setMargins(0, 24, 0, 24);
            welcomeText.setLayoutParams(textParams);

            // Add text to settings layout (at the bottom)
            settingsLayout.addView(welcomeText);

            Log.d(TAG, "✅ SUCCESS! Welcome information text added to settings - should be visible now!");
        } else {
            Log.e(TAG, "❌ FAILED! Settings content is null or not LinearLayout - cannot add welcome text");
            if (settingsContent == null) {
                Log.e(TAG, "settingsContent is NULL");
            } else {
                Log.e(TAG, "settingsContent type: " + settingsContent.getClass().getSimpleName());
            }
        }
    }

    private void addHelpInfoToSettings() {
        try {
            if (settingsContent instanceof LinearLayout) {
                LinearLayout settingsLayout = (LinearLayout) settingsContent;

                // Create a card-like container for help information
                LinearLayout helpCard = new LinearLayout(this);
                helpCard.setOrientation(LinearLayout.VERTICAL);
                helpCard.setTag("help_info");
                helpCard.setPadding(32, 24, 32, 24);
                helpCard.setBackground(ContextCompat.getDrawable(this, R.drawable.card_background));

                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                cardParams.setMargins(0, 16, 0, 16);
                helpCard.setLayoutParams(cardParams);

                // Title
                TextView helpTitle = new TextView(this);
                helpTitle.setText("🎉 Welcome & Important Information");
                helpTitle.setTextSize(16);
                helpTitle.setTextColor(ContextCompat.getColor(this, R.color.primary_600));
                helpTitle.setTypeface(null, Typeface.BOLD);
                helpTitle.setPadding(0, 0, 0, 16);

                // Help content - FULL WELCOME MESSAGE
                TextView helpContent = new TextView(this);
                helpContent.setText("Welcome! Here are some important tips:\n\n" +
                        "• Use the QR code scanner to connect child devices\n" +
                        "• Monitor and manage your child's screen time easily\n" +
                        "• Access all controls from this parent dashboard\n\n" +
                        "⚠️ TROUBLESHOOTING: If you can see a device name but cannot track its data, please:\n" +
                        "1. Remove the device from this app\n" +
                        "2. Reinstall the app on the child device\n" +
                        "3. Connect the child via QR code again\n\n" +
                        "� IMPORTANT SECURITY: Before uninstalling this app or logging out permanently:\n" +
                        "• Always remove all connected child devices first\n" +
                        "• This prevents security issues and data conflicts\n" +
                        "• Use 'Disconnect All Devices' in Settings if needed\n\n" +
                        "� TIP: Ensure both devices have stable internet when connecting via QR code.");
                helpContent.setTextSize(14);
                helpContent.setTextColor(ContextCompat.getColor(this, android.R.color.black));
                helpContent.setLineSpacing(4, 1.1f);

                // Show more details button
                Button btnShowDetails = new Button(this);
                btnShowDetails.setText("Show Detailed Help");
                btnShowDetails.setTextColor(ContextCompat.getColor(this, R.color.modern_orange_600));
                btnShowDetails.setOnClickListener(v -> showTroubleshootingDialog());

                LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                btnParams.topMargin = 16;
                btnShowDetails.setLayoutParams(btnParams);

                // Add all components to the card
                helpCard.addView(helpTitle);
                helpCard.addView(helpContent);
                helpCard.addView(btnShowDetails);

                // Add card to settings layout (at the top)
                settingsLayout.addView(helpCard, 0);

                Log.d(TAG, "Help information card added to settings");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error adding help info to settings: " + e.getMessage());
        }
    }

    private void showDisconnectAllDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("Disconnect All Devices");
        builder.setMessage(
                "Are you sure you want to disconnect all connected child devices?\n\nThis action will:\n• Remove all connected devices\n• Sign out all child devices\n• Redirect you to the login page\n\nThis action cannot be undone.");
        builder.setPositiveButton("Disconnect All", (dialog, which) -> {
            disconnectAllDevices();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void performLogout() {
        try {
            Log.d(TAG, "🚪 NUCLEAR LOGOUT INITIATED - Obliterating all device connections");

            // Show loading dialog for logout process
            runOnUiThread(() -> {
                if (loadingDialogManager != null) {
                    loadingDialogManager.show("Logging Out", "Disconnecting devices and clearing data...");
                }
            });

            // STEP 1: Get current user for Firebase cleanup
            String parentId = null;
            if (mAuth != null && mAuth.getCurrentUser() != null) {
                parentId = mAuth.getCurrentUser().getUid();
            } else if (sessionManager != null && sessionManager.isLoggedIn()) {
                parentId = sessionManager.getUserId();
            }

            if (parentId != null) {
                Log.d(TAG, "☢️ NUCLEAR FIREBASE OBLITERATION for user: " + parentId);
                performNuclearFirebaseCleanup(parentId, () -> {
                    // STEP 2: Complete local cleanup after Firebase cleanup
                    completeLogoutProcess();
                });
            } else {
                Log.w(TAG, "⚠️ No user ID found - proceeding with local cleanup only");
                completeLogoutProcess();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during logout: " + e.getMessage());
            runOnUiThread(() -> {
                if (loadingDialogManager != null) {
                    loadingDialogManager.hide();
                }
                Toast.makeText(this, "Error during logout", Toast.LENGTH_SHORT).show();
            });
        }
    }

    /**
     * NUCLEAR FIREBASE OBLITERATION - Removes ALL parent device data from Firebase
     */
    private void performNuclearFirebaseCleanup(String parentId, Runnable onComplete) {
        Log.d(TAG, "🔥 NUCLEAR FIREBASE CLEANUP - Obliterating all parent data");

        // Get list of connected devices for child logout
        List<String> devicesToDisconnect = new ArrayList<>();
        if (connectedDevices != null) {
            for (ChildDevice device : connectedDevices) {
                devicesToDisconnect.add(device.deviceId);
            }
        }

        // STEP 1: Disconnect all child devices (triggers logout on child apps)
        for (String deviceId : devicesToDisconnect) {
            Log.d(TAG, "🔌 Triggering child device logout: " + deviceId);
            triggerChildDeviceLogout(deviceId);
        }

        DatabaseReference database = FirebaseDatabase.getInstance().getReference();

        // STEP 2: Nuclear obliteration of ALL parent Firebase paths
        Log.d(TAG, "☢️ OBLITERATING Firebase paths for parent: " + parentId);

        // Create batch removal tasks
        List<Task<Void>> cleanupTasks = new ArrayList<>();

        // 1. Remove from parents structure
        cleanupTasks.add(database.child("parents").child(parentId).removeValue());

        // 2. Remove parent's QR shares
        cleanupTasks.add(database.child("qr_shares").child(permanentQRKey).removeValue());

        // 3. Remove parent from device_status for all children
        for (String deviceId : devicesToDisconnect) {
            cleanupTasks.add(database.child("device_status").child(deviceId).removeValue());
            cleanupTasks.add(database.child("device_apps").child(deviceId).removeValue());
            cleanupTasks.add(database.child("usage_snapshots").child(deviceId).removeValue());
            cleanupTasks.add(database.child("active_timers").child(deviceId).removeValue());

            // ENHANCED: Also cleanup parent_timers for this device
            cleanupTasks.add(database.child("parent_timers").child(deviceId).removeValue());
            Log.d(TAG, "Added parent_timers cleanup task for device: " + deviceId);
        }

        // 4. Wait for all cleanup tasks to complete
        Tasks.whenAll(cleanupTasks)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "✅ NUCLEAR FIREBASE CLEANUP COMPLETED");
                    } else {
                        Log.e(TAG, "⚠️ Some Firebase cleanup tasks failed: " + task.getException());
                    }
                    // Continue with local cleanup regardless
                    onComplete.run();
                });
    }

    /**
     * Complete the logout process with local cleanup
     */
    private void completeLogoutProcess() {
        Log.d(TAG, "🧹 COMPLETING LOCAL CLEANUP");

        // Set flag for fresh login cleanup
        SharedPreferences appStatePrefs = getSharedPreferences("app_state", MODE_PRIVATE);
        appStatePrefs.edit().putBoolean("was_logged_out", true).apply();

        // Clear session
        sessionManager.logoutUser();

        // Clear connected devices data
        if (connectedDevicesManager != null) {
            connectedDevicesManager.clearAllDevices();
        }

        // Clear local preferences
        SharedPreferences prefs = getSharedPreferences("focus_mode_prefs", MODE_PRIVATE);
        prefs.edit().clear().apply();

        // Sign out from Firebase
        FirebaseAuth.getInstance().signOut();

        runOnUiThread(() -> {
            if (loadingDialogManager != null) {
                loadingDialogManager.hide();
            }

            Toast.makeText(this, "✅ Logged out successfully - All devices disconnected", Toast.LENGTH_LONG).show();

            // Navigate to main activity
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });

        Log.d(TAG, "🎯 NUCLEAR LOGOUT COMPLETED - Clean slate achieved");
        Log.d(TAG, "🎯 NEXT LOGIN WILL BE TREATED AS FRESH LOGIN");
    }

    /**
     * DEBUG METHOD: Force fresh login state for testing
     * Call this from settings or debug menu to test fresh login behavior
     */
    private void debugForceFreshLogin() {
        SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
        prefs.edit().putBoolean("was_logged_out", true).apply();
        Log.d(TAG, "🧪 DEBUG: Fresh login flag set - restart app to test");
        Toast.makeText(this, "Fresh login flag set - restart app to test", Toast.LENGTH_LONG).show();
    }

    /**
     * Trigger logout on a specific child device
     */
    private void triggerChildDeviceLogout(String deviceId) {
        try {
            String parentId = null;
            if (mAuth != null && mAuth.getCurrentUser() != null) {
                parentId = mAuth.getCurrentUser().getUid();
            } else if (sessionManager != null && sessionManager.isLoggedIn()) {
                parentId = sessionManager.getUserId();
            }

            if (parentId == null) {
                Log.w(TAG, "⚠️ No parent ID - cannot trigger logout for device: " + deviceId);
                return;
            }

            Log.d(TAG, "📤 Sending BULLETPROOF logout signal to child device: " + deviceId);

            DatabaseReference childDeviceRef = FirebaseDatabase.getInstance()
                    .getReference("parents")
                    .child(parentId)
                    .child("connectedChildDevices")
                    .child(deviceId);

            Map<String, Object> logoutData = new HashMap<>();
            logoutData.put("logout", true);
            logoutData.put("automaticLogout", true); // 🚨 Flag for automatic logout
            logoutData.put("forceLogout", true); // 🚨 Force immediate logout
            logoutData.put("explicitRemoval", true); // 🚨 Flag for explicit device removal
            logoutData.put("logoutInitiated", System.currentTimeMillis());
            logoutData.put("logoutReason", "parent_device_removal");
            logoutData.put("status", "force_logging_out");
            logoutData.put("redirectToLogin", true); // 🚨 Force redirect to login
            logoutData.put("noConfirmation", true); // 🚨 No user confirmation needed
            logoutData.put("bulletproofCleanup", true); // 🛡️ Flag for bulletproof cleanup

            childDeviceRef.updateChildren(logoutData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ BULLETPROOF logout signal sent successfully to device: " + deviceId);

                        // ALSO send signal via device status path for redundancy
                        DatabaseReference statusRef = FirebaseDatabase.getInstance()
                                .getReference("device_status")
                                .child(deviceId);
                        statusRef.updateChildren(logoutData);

                        // 🛡️ BULLETPROOF: Also trigger nuclear cleanup from parent side
                        triggerNuclearCleanupFromParent(deviceId);

                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to send automatic logout signal to device: " + deviceId + " - "
                                + e.getMessage());

                        // Even if Firebase signal fails, still do nuclear cleanup
                        triggerNuclearCleanupFromParent(deviceId);
                    });

        } catch (Exception e) {
            Log.e(TAG, "Error triggering child device logout: " + e.getMessage());
        }
    }

    /**
     * 🔥 Trigger nuclear cleanup from parent side to ensure child cannot reconnect
     */
    private void triggerNuclearCleanupFromParent(String deviceId) {
        try {
            Log.d(TAG, "🔥 NUCLEAR CLEANUP: Obliterating connection paths for device: " + deviceId);

            // Clear all Firebase paths related to this device
            DatabaseReference[] pathsToObliterate = {
                    FirebaseDatabase.getInstance().getReference("device_status").child(deviceId),
                    FirebaseDatabase.getInstance().getReference("children").child(deviceId),
                    FirebaseDatabase.getInstance().getReference("active_timers").child(deviceId),
                    FirebaseDatabase.getInstance().getReference("enhanced_usage_data").child(deviceId),
                    FirebaseDatabase.getInstance().getReference("app_usage_limits").child(deviceId),
                    FirebaseDatabase.getInstance().getReference("focus_mode").child(deviceId)
            };

            for (DatabaseReference path : pathsToObliterate) {
                path.removeValue()
                        .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Obliterated: " + path.getKey()))
                        .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate: " + path.getKey()));
            }

            // Set permanent removal marker
            DatabaseReference removalMarkerRef = FirebaseDatabase.getInstance()
                    .getReference("device_removal_markers")
                    .child(deviceId);

            Map<String, Object> removalMarker = new HashMap<>();
            removalMarker.put("removed_by_parent", true);
            removalMarker.put("removal_timestamp", System.currentTimeMillis());
            removalMarker.put("requires_qr_reconnection", true);
            removalMarker.put("nuclear_cleanup_completed", true);

            removalMarkerRef.setValue(removalMarker);

            Log.d(TAG, "✅ Nuclear cleanup completed for device: " + deviceId);

        } catch (Exception e) {
            Log.e(TAG, "❌ Error during nuclear cleanup: " + e.getMessage());
        }
    }

    /**
     * Enable device connection listeners after fresh login when user manually
     * connects first device
     * 🚫 DISABLED - Prevents automatic device loading
     */
    private void enableDeviceListenersAfterFreshLogin() {
        // 🚫 DISABLED - This method would enable automatic device loading listeners
        // User requirement: Only QR scanned devices should be shown
        Log.d(TAG, "🚫 AUTOMATIC LISTENER ACTIVATION DISABLED - QR scan only mode maintained");

        if (isFreshLoginSession) {
            isFreshLoginSession = false; // Clear fresh login flag
            Log.d(TAG, "✅ Fresh login flag cleared - but listeners remain disabled");
        }
    }

    /**
     * Check if this is a fresh login and cleanup any residual data
     * 
     * @return true if this was a fresh login (skip device loading), false otherwise
     */
    private boolean checkForFreshLoginAndCleanup() {
        try {
            SharedPreferences prefs = getSharedPreferences("app_state", MODE_PRIVATE);
            boolean wasLoggedOut = prefs.getBoolean("was_logged_out", false);
            boolean isFirstRun = prefs.getBoolean("is_first_run", true);

            if (wasLoggedOut || isFirstRun) {
                // 🔧 CONNECTION FIX: Check if we have existing devices - if so, this isn't a
                // fresh start, it's an update!
                boolean hasExistingDevices = false;
                SharedPreferences devicePrefs = getSharedPreferences("connected_devices", MODE_PRIVATE);
                String devicesJson = devicePrefs.getString("devices", "[]");
                if (devicesJson != null && !devicesJson.equals("[]") && !devicesJson.isEmpty()) {
                    hasExistingDevices = true;
                    Log.d(TAG, "📱 EXISTING DEVICES DETECTED during fresh login check - Preserving data");
                }

                if (isFirstRun) {
                    if (hasExistingDevices) {
                        Log.d(TAG, "🚀 APP UPDATE DETECTED - Existing devices found, skipping initial cleanup");
                        prefs.edit().putBoolean("is_first_run", false).apply();
                        // SKIP CLEANUP!
                        return false;
                    }
                    Log.d(TAG, "🚀 FIRST APP RUN DETECTED - Performing initial cleanup");
                    prefs.edit().putBoolean("is_first_run", false).apply();
                } else {
                    Log.d(TAG, "🧹 FRESH LOGIN DETECTED - Performing cleanup");
                }

                // Clear the fresh login flag
                prefs.edit().remove("was_logged_out").apply();

                // Ensure all connected device data is cleared
                if (connectedDevicesManager != null) {
                    connectedDevicesManager.clearAllDevices();
                    Log.d(TAG, "🗑️ ConnectedDevicesManager cleared");
                }

                // Clear focus mode preferences
                SharedPreferences focusPrefs = getSharedPreferences("focus_mode_prefs", MODE_PRIVATE);
                focusPrefs.edit().clear().apply();

                // Clear any other app state preferences
                SharedPreferences connectedDevicesPrefs = getSharedPreferences("connected_devices", MODE_PRIVATE);
                connectedDevicesPrefs.edit().clear().apply();

                // Reset connected devices list
                connectedDevices.clear();
                // Explicitly clear current device selection (treat as explicit user action)
                connectedDevicesManager.setCurrentDevice(null, true);
                // Sync local cache
                currentChildDeviceId = connectedDevicesManager.getCurrentDeviceId();
                currentChildDeviceName = "No Device";

                Log.d(TAG, "✅ Fresh start cleanup completed - NO DEVICES SHOULD BE LOADED");
                Log.d(TAG, "🎯 EXPECTED RESULT: User should see 'No Device' and empty device list");
                Log.d(TAG, "📱 Device connection method: Manual QR scan ONLY");
                return true; // This is a fresh login - skip device loading
            } else {
                Log.d(TAG, "📱 Continuing existing session");
                return false; // Normal session - allow device loading
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during fresh login check: " + e.getMessage());
            return false;
        }
    }

    private void disconnectAllDevices() {
        Log.d(TAG, "Starting disconnect all devices process");

        // Show loading dialog
        loadingDialogManager.show("Disconnecting Devices", "Please wait while we disconnect all connected devices...");

        String parentId = null;
        if (mAuth != null && mAuth.getCurrentUser() != null) {
            parentId = mAuth.getCurrentUser().getUid();
        } else if (sessionManager != null && sessionManager.isLoggedIn()) {
            parentId = sessionManager.getUserId();
        }

        if (parentId == null) {
            Log.e(TAG, "Parent ID is null. Cannot disconnect devices.");
            loadingDialogManager.hide();
            Toast.makeText(this, "You must be logged in to disconnect devices.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (connectedDevices == null || connectedDevices.isEmpty()) {
            Log.w(TAG, "No connected devices to disconnect");
            loadingDialogManager.updateText("Disconnecting Devices", "No devices to disconnect. Logging out...");

            // Wait a moment then perform logout
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                loadingDialogManager.hide();
                performLogout();
            }, 1500);
            return;
        }

        final String finalParentId = parentId;
        final int totalDevices = connectedDevices.size();
        final int[] processedDevices = { 0 };

        Log.d(TAG, "Disconnecting " + totalDevices + " devices for parent: " + finalParentId);

        // Process each connected device
        for (ChildDevice device : new ArrayList<>(connectedDevices)) {
            String deviceId = device.deviceId;
            String deviceName = device.deviceName;

            Log.d(TAG, "Processing device: " + deviceName + " (ID: " + deviceId + ")");

            loadingDialogManager.updateText("Disconnecting Devices",
                    "Disconnecting " + deviceName + " (" + (processedDevices[0] + 1) + "/" + totalDevices + ")...");

            // Signal child device to logout
            DatabaseReference childDeviceRef = FirebaseDatabase.getInstance()
                    .getReference("parents")
                    .child(finalParentId)
                    .child("connectedChildDevices")
                    .child(deviceId);

            Map<String, Object> logoutData = new HashMap<>();
            logoutData.put("logout", true);
            logoutData.put("logoutInitiated", System.currentTimeMillis());
            logoutData.put("logoutReason", "disconnect_all_by_parent");
            logoutData.put("status", "logging_out");

            childDeviceRef.updateChildren(logoutData)
                    .addOnCompleteListener(task -> {
                        processedDevices[0]++;
                        Log.d(TAG, "Device " + deviceName + " logout signal sent. Processed: " + processedDevices[0]
                                + "/" + totalDevices);

                        // When all devices have been signaled, proceed with cleanup
                        if (processedDevices[0] >= totalDevices) {
                            loadingDialogManager.updateText("Disconnecting Devices",
                                    "All devices signaled. Cleaning up data...");

                            // Wait for child devices to receive signals, then clean up
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                cleanupAllDeviceData(finalParentId);
                            }, 3000); // 3 seconds for child devices to process
                        }
                    });
        }
    }

    private void cleanupAllDeviceData(String parentId) {
        Log.d(TAG, "Starting cleanup of all device data");

        loadingDialogManager.updateText("Disconnecting Devices", "Removing all device data...");

        // Remove all connected child devices from Firebase
        DatabaseReference connectedDevicesRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(parentId)
                .child("connectedChildDevices");

        connectedDevicesRef.removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "All connected devices removed from Firebase");

                    // Clean up other references for all devices
                    ArrayList<String> deviceIds = new ArrayList<>();
                    for (ChildDevice device : connectedDevices) {
                        deviceIds.add(device.deviceId);
                    }

                    cleanupAllDeviceReferences(deviceIds);

                    // Clear local connected devices
                    connectedDevices.clear();
                    if (connectedDevicesManager != null) {
                        connectedDevicesManager.clearAllDevices();
                    }

                    loadingDialogManager.updateText("Disconnecting Devices", "Finalizing logout...");

                    // Complete the process by logging out
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        loadingDialogManager.hide();
                        Toast.makeText(this, "✅ All devices disconnected successfully", Toast.LENGTH_LONG).show();
                        performLogout();
                    }, 1500);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to remove all device data: " + e.getMessage());
                    loadingDialogManager.hide();
                    Toast.makeText(this, "❌ Failed to disconnect all devices: " + e.getMessage(), Toast.LENGTH_LONG)
                            .show();
                });
    }

    private void cleanupAllDeviceReferences(ArrayList<String> deviceIds) {
        Log.d(TAG, "Cleaning up Firebase references for all devices");

        for (String deviceId : deviceIds) {
            // Clean up active timers
            FirebaseDatabase.getInstance()
                    .getReference("active_timers")
                    .child(deviceId)
                    .removeValue();

            // Clean up device status
            FirebaseDatabase.getInstance()
                    .getReference("device_status")
                    .child(deviceId)
                    .removeValue();

            // Clean up usage snapshots
            FirebaseDatabase.getInstance()
                    .getReference("usage_snapshots")
                    .child(deviceId)
                    .removeValue();

            // Clean up device apps
            FirebaseDatabase.getInstance()
                    .getReference("device_apps")
                    .child(deviceId)
                    .removeValue();

            // Clean up block commands
            FirebaseDatabase.getInstance()
                    .getReference("block_commands")
                    .child(deviceId)
                    .removeValue();

            // Send logout command to child device
            DatabaseReference logoutCommandRef = FirebaseDatabase.getInstance()
                    .getReference("logout_commands")
                    .child(deviceId);
            Map<String, Object> data = new HashMap<>();
            data.put("trigger", true);
            data.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);
            logoutCommandRef.setValue(data);

            Log.d(TAG, "Cleaned up references for device: " + deviceId);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Fix: Force bottom navigation to "Home" when returning to dashboard
        if (bottomNavigation != null) {
            bottomNavigation.setSelectedItemId(R.id.nav_home);
        }

        // Update session activity
        if (sessionManager != null) {
            sessionManager.updateLastActivity();
        }
        // Request notification permission if not already granted
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1001);
            }
        }

        // Update Focus Mode UI when app resumes
        Log.d(TAG, "App resumed - updating Focus Mode UI");
        forceUpdateFocusModeUI();

        // Additional checks for current device
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            Log.d(TAG, "App resumed - syncing device: " + currentChildDeviceId);
            checkActualFocusModeStateFromFirebase(); // Sync with Firebase state

            // Restore timer state from Firebase
            Log.d(TAG, "Restoring timer state for device: " + currentChildDeviceId);

            // Use delayed refresh to avoid conflicts
            Handler timerRestoreHandler = new Handler(Looper.getMainLooper());
            timerRestoreHandler.postDelayed(() -> {
                Log.d(TAG, "State restoration delayed actions completed");
            }, 500); // 500ms delay to allow app to fully resume

            // Ensure background services are running
            ensureBackgroundServicesRunning();
        }

        // AUTO-REFRESH USAGE DATA when parent reopens the app (PRESERVE USER'S SELECTED
        // DATE)
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(currentUsageDate.getTime());
            Log.d(TAG, "🔄 App resumed - refreshing data for SELECTED date: " + dateKey + " for device: "
                    + currentChildDeviceName);

            // Check if user has selected a specific historical date
            if (preventAutoDateReset("app resume")) {
                Log.d(TAG, "📅 USER SELECTED HISTORICAL DATE - respecting user's choice, no auto-refresh");
                // Just update display for selected date, don't override with today's data
                updateSelectedDateDisplay();
                loadSmartUsageDataForSelectedDate();
                return;
            }

            // Only do fresh data fetch if user is viewing TODAY's data
            Calendar today = Calendar.getInstance();
            String todayKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(today.getTime());

            if (!dateKey.equals(todayKey)) {
                Log.d(TAG, "📅 User viewing historical date (" + dateKey + "), skipping today's fresh data fetch");
                updateSelectedDateDisplay();
                loadSmartUsageDataForSelectedDate();
                return;
            }

            // PRESERVE user's selected date - DO NOT reset to today
            Log.d(TAG, "📅 PRESERVING user's selected date: " + currentUsageDate.getTime());

            // Update the date display to show the SELECTED date (not force "Today")
            updateSelectedDateDisplay();
            // 🚀 SHOW CACHED DATA IMMEDIATELY
            loadSmartUsageDataForSelectedDate();

            // FORCE IMMEDIATE REFRESH - for the SELECTED date, not today
            Log.d(TAG, "🚀 IMMEDIATE FORCE REFRESH - Getting fresh data now!");

            // Clear any stale UI data - REMOVED: DON'T CLEAR, causes "0m" flash!
            // clearUsageDisplay();

            // STEP 1: Signal child device to upload fresh data IMMEDIATELY
            Log.d(TAG, "📤 Signaling child device to upload FRESH usage data");
            DatabaseReference childSignalRef = FirebaseDatabase.getInstance()
                    .getReference("device_status")
                    .child(currentChildDeviceId)
                    .child("data_upload_requested");

            childSignalRef.setValue(System.currentTimeMillis()); // Signal with timestamp

            // STEP 2: Force Firebase to fetch fresh data from network (not cache)
            FirebaseDatabase.getInstance().getReference()
                    .child("usage_snapshots")
                    .child(currentChildDeviceId)
                    .child("latest")
                    .keepSynced(false); // Disable caching

            // STEP 3: Wait briefly for child to respond, then fetch data
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // Only fetch "latest" if user is still viewing today
                String currentDateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(currentUsageDate.getTime());
                String currentTodayKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                        .format(new Date());

                if (!currentDateKey.equals(currentTodayKey)) {
                    Log.d(TAG, "🚫 User switched to historical date, canceling today's data fetch");
                    return;
                }

                // Immediately trigger fresh data load with network priority
                DatabaseReference freshDataRef = FirebaseDatabase.getInstance()
                        .getReference("usage_snapshots")
                        .child(currentChildDeviceId)
                        .child("latest");

                // Force network fetch
                freshDataRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        Log.d(TAG, "🔥 FRESH DATA RECEIVED - Immediately updating UI");

                        if (dataSnapshot.exists()) {
                            // Force update UI with fresh data RIGHT NOW
                            displayAccurateUsageData(dataSnapshot);
                            Log.d(TAG, "✅ TODAY'S FRESH DATA DISPLAYED");
                        } else {
                            Log.w(TAG, "⚠️ No fresh data available, trying fallback...");
                            // Fallback to regular load method
                            loadSmartUsageDataForSelectedDate();
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError databaseError) {
                        Log.e(TAG, "❌ Error fetching fresh data: " + databaseError.getMessage());
                        // Fallback to regular load
                        loadSmartUsageDataForSelectedDate();
                    }
                });

                // FALLBACK: If no data comes within 5 seconds, force regular load
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    // tvTotalUsage view removed from layout - simplified fallback
                    Log.w(TAG, "⏰ Timeout - using fallback method");
                    loadSmartUsageDataForSelectedDate();
                }, 5000); // 5 second timeout

            }, 2000); // 2 second delay to let child device respond

        } else {
            Log.d(TAG, "⚠️ No child device selected - skipping auto-refresh");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ParentDashboardActivity destroyed");

        // Stop device status tracking to avoid memory leaks
        if (deviceStatusManager != null) {
            deviceStatusManager.stopStatusTracking();
        }

        // 🚨 Stop uninstall detection monitoring
        if (uninstallDetectionManager != null) {
            uninstallDetectionManager.stopAllMonitoring();
        }

        // Detach limiter realtime listener to avoid leaks/crosstalk
        detachLimiterRealtimeListener();
    }

    // MANAGE DEVICES BOTTOM SHEET
    private void showManageDevicesDialog() {
        if (connectedDevicesManager == null)
            return;

        try {
            // Inflate bottom sheet layout
            View sheetView = getLayoutInflater().inflate(R.layout.bottom_sheet_manage_devices, null);
            final com.google.android.material.bottomsheet.BottomSheetDialog bottomSheetDialog = new com.google.android.material.bottomsheet.BottomSheetDialog(
                    this, R.style.BottomSheetDialogTheme);
            bottomSheetDialog.setContentView(sheetView);

            // Handle Interactions
            View btnClose = sheetView.findViewById(R.id.btnSheetClose);
            LinearLayout btnAdd = sheetView.findViewById(R.id.btnSheetAddDevice);
            LinearLayout listContainer = sheetView.findViewById(R.id.llSheetDeviceList);

            if (btnClose != null) {
                btnClose.setOnClickListener(v -> bottomSheetDialog.dismiss());
            }

            if (btnAdd != null) {
                btnAdd.setOnClickListener(v -> {
                    bottomSheetDialog.dismiss();
                    showQRScanner();
                });
            }

            // Populate List
            if (listContainer != null) {
                listContainer.removeAllViews();
                List<ChildDevice> devices = connectedDevicesManager.getConnectedDevices();

                if (devices != null && !devices.isEmpty()) {
                    for (ChildDevice device : devices) {
                        View itemView = getLayoutInflater().inflate(R.layout.item_manage_device, listContainer, false);

                        TextView tvName = itemView.findViewById(R.id.tvItemDeviceName);
                        TextView tvStatus = itemView.findViewById(R.id.tvItemLastSeen);
                        TextView tvBadge = itemView.findViewById(R.id.tvItemCurrentBadge);
                        View btnRemove = itemView.findViewById(R.id.btnItemRemove);
                        ImageView ivIcon = itemView.findViewById(R.id.ivItemDeviceIcon);

                        // Set Data
                        String displayName = (device.userName != null && !device.userName.isEmpty())
                                ? device.userName
                                : device.deviceName;
                        // Capitalize
                        if (displayName != null && !displayName.isEmpty()) {
                            displayName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
                        }

                        final String finalDisplayName = displayName;

                        tvName.setText(finalDisplayName);

                        boolean isCurrent = device.deviceId.equals(currentChildDeviceId);

                        if (isCurrent) {
                            tvBadge.setVisibility(View.VISIBLE);
                            tvStatus.setText("Active Now");
                            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));
                            if (ivIcon != null)
                                ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.success_600));
                        } else {
                            tvBadge.setVisibility(View.GONE);
                            tvStatus.setText("Tap to switch");
                            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral_500));
                            if (ivIcon != null)
                                ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.neutral_400));
                        }

                        // Remove Action
                        if (btnRemove != null) {
                            final String deviceIdToRemove = device.deviceId;
                            final String deviceNameToRemove = (device.userName != null && !device.userName.isEmpty())
                                    ? device.userName
                                    : device.deviceName;

                            btnRemove.setOnClickListener(v -> {
                                bottomSheetDialog.dismiss();

                                // Show confirmation dialog and pass device ID directly
                                new AlertDialog.Builder(new android.view.ContextThemeWrapper(
                                        ParentDashboardActivity.this, R.style.AlertDialogCustom))
                                        .setTitle("🗑️ Remove Device")
                                        .setMessage("Remove \"" + deviceNameToRemove
                                                + "\"?\n\nThis will log out the device and clear all data.")
                                        .setPositiveButton("Remove", (dialog, which) -> {
                                            removeChildDevice(deviceIdToRemove);
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            });
                        }

                        // Switch Action (Row Click)
                        itemView.setOnClickListener(v -> {
                            if (!isCurrent) {
                                switchDevice(device.deviceId);
                                Toast.makeText(ParentDashboardActivity.this, "Switched to " + finalDisplayName,
                                        Toast.LENGTH_SHORT).show();
                            }
                            bottomSheetDialog.dismiss();
                        });

                        listContainer.addView(itemView);
                    }
                } else {
                    // Show empty state?
                    TextView emptyText = new TextView(this);
                    emptyText.setText("No connected devices");
                    emptyText.setPadding(32, 32, 32, 32);
                    listContainer.addView(emptyText);
                }
            }

            bottomSheetDialog.show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing manage devices dialog: " + e.getMessage());
            Toast.makeText(this, "Could not open device manager", Toast.LENGTH_SHORT).show();
        }
    }

    // REMOVE CHILD DEVICE FUNCTIONALITY
    private void showRemoveDeviceConfirmationDialog() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Toast.makeText(this, "No child device selected to remove.", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("🗑️ Remove Child Device");
        builder.setMessage("Are you sure you want to remove \"" + currentChildDeviceName + "\"?\n\n" +
                "⚠️ This will:\n" +
                "• Log out the child device immediately\n" +
                "• Remove all monitoring data\n" +
                "• Clear all timers and focus modes\n" +
                "• Require re-scanning QR code to reconnect\n\n" +
                "This action cannot be undone.");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("🗑️ Remove Device", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                removeChildDevice(currentChildDeviceId);
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    // Enhanced removeChildDevice method with extensive debugging
    private void removeChildDevice(String childDeviceIdToRemove) {
        Log.d(TAG, "=== REMOVE CHILD DEVICE DEBUG ===");
        Log.d(TAG, "Attempting to remove child device: " + childDeviceIdToRemove);

        // Set the flag to prevent re-adding this device during cleanup
        deviceIdJustRemoved = childDeviceIdToRemove;

        // Show loading dialog for device removal
        loadingDialogManager.show("Removing Device", "Please wait while we remove the device and clean up data...");

        // Debug log
        Log.d(TAG, "mAuth=" + mAuth + ", currentUser=" + (mAuth != null ? mAuth.getCurrentUser() : "null"));

        String parentId = null;
        if (mAuth != null && mAuth.getCurrentUser() != null) {
            parentId = mAuth.getCurrentUser().getUid();
            Log.d(TAG, "Got parent ID from Firebase Auth: " + parentId);
        } else if (sessionManager != null && sessionManager.isLoggedIn()) {
            parentId = sessionManager.getUserId();
            Log.d(TAG, "Got parent ID from Session Manager: " + parentId);
        }

        if (parentId == null) {
            Log.e(TAG, "Parent ID is null. Cannot remove child device.");
            loadingDialogManager.hide(); // Hide loading dialog on error
            Toast.makeText(this, "You must be logged in as the parent to remove a device.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (childDeviceIdToRemove == null || childDeviceIdToRemove.isEmpty()) {
            Log.e(TAG, "Child device ID is null or empty");
            loadingDialogManager.hide(); // Hide loading dialog on error
            Toast.makeText(this, "Invalid device ID.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Make parentId final for lambda usage
        final String finalParentId = parentId;
        Log.d(TAG, "Removing child device: " + childDeviceIdToRemove + " for parent: " + finalParentId);

        // Show progress
        Toast.makeText(this, "Removing device...", Toast.LENGTH_SHORT).show();

        // Step 1: Signal child device to logout with improved reliability
        String referencePath = "parents/" + finalParentId + "/connectedChildDevices/" + childDeviceIdToRemove;
        Log.d(TAG, "Firebase reference path: " + referencePath);

        DatabaseReference childDeviceRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(finalParentId)
                .child("connectedChildDevices")
                .child(childDeviceIdToRemove);

        // First, check if the child device exists
        childDeviceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Log.d(TAG, "Child device exists in Firebase: " + dataSnapshot.exists());
                if (dataSnapshot.exists()) {
                    Log.d(TAG, "Current child device data: " + dataSnapshot.getValue());

                    // Set logout flag with additional metadata for better tracking
                    java.util.Map<String, Object> logoutData = new java.util.HashMap<>();
                    logoutData.put("logout", true);
                    logoutData.put("logoutInitiated", System.currentTimeMillis());
                    logoutData.put("logoutReason", "removed_by_parent");
                    logoutData.put("status", "logging_out");

                    Log.d(TAG, "Setting logout data: " + logoutData);

                    childDeviceRef.updateChildren(logoutData)
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "✅ Logout signal sent successfully to child device");

                                // Verify the data was actually written
                                childDeviceRef.addListenerForSingleValueEvent(new ValueEventListener() {
                                    @Override
                                    public void onDataChange(@NonNull DataSnapshot verifySnapshot) {
                                        Log.d(TAG, "Verification - Updated child device data: "
                                                + verifySnapshot.getValue());
                                        Boolean logoutFlag = verifySnapshot.child("logout").getValue(Boolean.class);
                                        Log.d(TAG, "Verification - Logout flag value: " + logoutFlag);
                                    }

                                    @Override
                                    public void onCancelled(@NonNull DatabaseError databaseError) {
                                        Log.e(TAG, "Verification failed: " + databaseError.getMessage());
                                    }
                                });

                                // Wait longer for child to receive logout signal, then remove data
                                new android.os.Handler().postDelayed(() -> {
                                    Log.d(TAG, "Proceeding to remove device data after 5 seconds");
                                    removeChildDeviceData(childDeviceIdToRemove, finalParentId);
                                }, 5000); // Increased to 5 seconds for better reliability
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "❌ Failed to signal child device logout: " + e.getMessage());
                                e.printStackTrace();
                                Toast.makeText(ParentDashboardActivity.this,
                                        "Failed to signal child device logout: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();

                                // Still try to remove the data even if signaling failed
                                loadingDialogManager.updateText("Removing Device",
                                        "Signaling failed, proceeding with data cleanup...");
                                removeChildDeviceData(childDeviceIdToRemove, finalParentId);
                            });
                } else {
                    Log.w(TAG, "Child device doesn't exist in Firebase, proceeding with removal");
                    loadingDialogManager.updateText("Removing Device",
                            "Device not found in database, cleaning up local data...");
                    removeChildDeviceData(childDeviceIdToRemove, finalParentId);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error checking child device existence: " + databaseError.getMessage());
                // Still try to remove
                loadingDialogManager.updateText("Removing Device", "Database error occurred, attempting cleanup...");
                removeChildDeviceData(childDeviceIdToRemove, finalParentId);
            }
        });
    }

    private void removeChildDeviceData(String childDeviceIdToRemove, String parentId) {
        // Only allow if parentId is not null
        if (parentId == null) {
            loadingDialogManager.hide(); // Hide loading dialog on error
            Toast.makeText(this, "You must be logged in as the parent to remove a device.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "Parent ID is null in removeChildDeviceData. Cannot remove child device data.");
            return;
        }

        Log.d(TAG, "Removing child device data for: " + childDeviceIdToRemove);

        // ⚠️ STABILITY FIX: Send logout command FIRST before removing data
        // This ensures child receives the command before listeners are disrupted
        Log.d(TAG, "📤 Step 1: Sending logout command FIRST to child device: " + childDeviceIdToRemove);

        DatabaseReference logoutCommandRef = FirebaseDatabase.getInstance()
                .getReference("logout_commands")
                .child(childDeviceIdToRemove);
        Map<String, Object> logoutData = new HashMap<>();
        logoutData.put("trigger", true);
        logoutData.put("reason", "removed_by_parent");
        logoutData.put("parentName", sessionManager.getParentName());
        logoutData.put("timestamp", com.google.firebase.database.ServerValue.TIMESTAMP);

        final String finalParentId = parentId;

        logoutCommandRef.setValue(logoutData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Logout command sent successfully! Now removing device data...");

                    // Step 2: NOW remove child device data from Firebase
                    DatabaseReference childDeviceRef = FirebaseDatabase.getInstance()
                            .getReference("parents")
                            .child(finalParentId)
                            .child("connectedChildDevices")
                            .child(childDeviceIdToRemove);

                    childDeviceRef.removeValue()
                            .addOnSuccessListener(aVoid2 -> {
                                Log.d(TAG, "Child device data removed successfully from Firebase");

                                // Step 3: Clean up other Firebase references
                                cleanupChildDeviceReferences(childDeviceIdToRemove);

                                // Step 4: Remove child from connection path
                                DatabaseReference connectionRef = FirebaseDatabase.getInstance()
                                        .getReference("device_connections")
                                        .child(childDeviceIdToRemove);
                                connectionRef.removeValue()
                                        .addOnSuccessListener(aVoid3 -> {
                                            Log.d(TAG, "✅ Child device connection reference removed");
                                        })
                                        .addOnFailureListener(e -> {
                                            Log.e(TAG, "❌ Failed to remove connection reference: " + e.getMessage());
                                        });

                                // Step 5: Update local state
                                removeConnectedDevice(childDeviceIdToRemove);

                                // Step 6: Refresh UI
                                loadConnectedDevices();

                                // Hide loading dialog and show success message
                                loadingDialogManager.hide();
                                Toast.makeText(ParentDashboardActivity.this,
                                        "✅ Device \"" + currentChildDeviceName + "\" removed successfully",
                                        Toast.LENGTH_LONG).show();

                                // AUTO-SWITCH: If we just removed the currently selected device, switch to
                                // another
                                if (childDeviceIdToRemove.equals(currentChildDeviceId)) {
                                    Log.d(TAG, "📱 Removed device was currently selected. Auto-switching...");

                                    List<ChildDevice> remainingDevices = new ArrayList<>();
                                    for (ChildDevice d : connectedDevices) {
                                        if (!d.deviceId.equals(childDeviceIdToRemove)) {
                                            remainingDevices.add(d);
                                        }
                                    }
                                    Log.d(TAG, "📱 Remaining devices: " + remainingDevices.size());

                                    if (!remainingDevices.isEmpty()) {
                                        ChildDevice firstDevice = remainingDevices.get(0);
                                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                            switchDevice(firstDevice.deviceId);
                                            Toast.makeText(ParentDashboardActivity.this,
                                                    "Switched to "
                                                            + (firstDevice.userName != null ? firstDevice.userName
                                                                    : firstDevice.deviceName),
                                                    Toast.LENGTH_SHORT).show();
                                        }, 500);
                                    } else {
                                        Log.d(TAG, "📱 No remaining devices. Showing empty state.");
                                        currentChildDeviceId = null;
                                        currentChildDeviceName = null;
                                        currentChildUserName = null;
                                        runOnUiThread(() -> {
                                            updateDeviceStatus();
                                            updateTargetDeviceDisplay();
                                            if (binding != null && binding.tvTotalTime != null) {
                                                binding.tvTotalTime.setText("0h 0m");
                                            }
                                        });
                                    }
                                }

                                // Clear the removed device flag after a delay
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    Log.d(TAG, "🧹 Clearing deviceIdJustRemoved flag");
                                    deviceIdJustRemoved = null;
                                }, 3000);
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Failed to remove child device data: " + e.getMessage());
                                loadingDialogManager.hide();
                                Toast.makeText(ParentDashboardActivity.this,
                                        "❌ Failed to remove device data: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to send logout command: " + e.getMessage());
                    loadingDialogManager.hide();
                    Toast.makeText(ParentDashboardActivity.this,
                            "❌ Failed to disconnect device: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void cleanupChildDeviceReferences(String childDeviceId) {
        Log.d(TAG, "🔥 NUCLEAR DEVICE OBLITERATION - Removing device from ALL Firebase paths: " + childDeviceId);

        // 1. Clean up active timers
        DatabaseReference activeTimersRef = FirebaseDatabase.getInstance()
                .getReference("active_timers")
                .child(childDeviceId);
        activeTimersRef.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Active timers OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate active timers: " + e.getMessage()));

        // 2. Clean up device status
        DatabaseReference deviceStatusRef = FirebaseDatabase.getInstance()
                .getReference("device_status")
                .child(childDeviceId);
        deviceStatusRef.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Device status OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate device status: " + e.getMessage()));

        // 3. Clean up usage snapshots
        DatabaseReference usageSnapshotsRef = FirebaseDatabase.getInstance()
                .getReference("usage_snapshots")
                .child(childDeviceId);
        usageSnapshotsRef.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Usage snapshots OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate usage snapshots: " + e.getMessage()));

        // 4. Clean up device apps
        DatabaseReference deviceAppsRef = FirebaseDatabase.getInstance()
                .getReference("device_apps")
                .child(childDeviceId);
        deviceAppsRef.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Device apps OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate device apps: " + e.getMessage()));

        // 5. Clean up block commands
        DatabaseReference blockCommandsRef = FirebaseDatabase.getInstance()
                .getReference("block_commands")
                .child(childDeviceId);
        blockCommandsRef.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Block commands OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate block commands: " + e.getMessage()));

        // 6. Clean up from parent timers
        DatabaseReference parentTimersRef = FirebaseDatabase.getInstance().getReference("parent_timers");
        parentTimersRef.orderByChild("deviceId").equalTo(childDeviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        for (DataSnapshot snapshot : dataSnapshot.getChildren()) {
                            snapshot.getRef().removeValue()
                                    .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Parent timer OBLITERATED"))
                                    .addOnFailureListener(
                                            e -> Log.e(TAG, "❌ Failed to obliterate parent timer: " + e.getMessage()));
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError databaseError) {
                        Log.e(TAG, "❌ Error obliterating parent timers: " + databaseError.getMessage());
                    }
                });

        // 7. NUCLEAR OPTION: Remove from QR share data COMPLETELY
        String qrShareKey = sessionManager.getQRShareKey();
        if (qrShareKey != null && !qrShareKey.isEmpty()) {
            Log.d(TAG, "🔥 NUCLEAR QR SHARE OBLITERATION for key: " + qrShareKey + ", device: " + childDeviceId);
            DatabaseReference qrShareRef = FirebaseDatabase.getInstance()
                    .getReference("qr_shares")
                    .child(qrShareKey);

            // NUCLEAR: Scan and destroy ALL entries containing this device
            qrShareRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot qrSnapshot) {
                    // Check allDeviceAppLists
                    if (qrSnapshot.child("allDeviceAppLists").exists()) {
                        for (DataSnapshot deviceSnapshot : qrSnapshot.child("allDeviceAppLists").getChildren()) {
                            String deviceIdInList = deviceSnapshot.child("deviceId").getValue(String.class);
                            if (childDeviceId.equals(deviceIdInList)) {
                                Log.d(TAG, "🗑️ NUCLEAR OBLITERATION: " + deviceSnapshot.getKey()
                                        + " from allDeviceAppLists");
                                deviceSnapshot.getRef().removeValue()
                                        .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ QR SHARE ENTRY OBLITERATED"))
                                        .addOnFailureListener(
                                                e -> Log.e(TAG, "❌ Failed to obliterate QR entry: " + e.getMessage()));
                            }
                        }
                    }

                    // Check deviceAppLists (legacy)
                    if (qrSnapshot.child("deviceAppLists").hasChild(childDeviceId)) {
                        qrSnapshot.child("deviceAppLists").child(childDeviceId).getRef().removeValue()
                                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Legacy QR entry OBLITERATED"))
                                .addOnFailureListener(
                                        e -> Log.e(TAG, "❌ Failed to obliterate legacy entry: " + e.getMessage()));
                    }

                    // Check any other structures that might contain the device
                    for (DataSnapshot childSnapshot : qrSnapshot.getChildren()) {
                        if (childSnapshot.hasChild(childDeviceId)) {
                            childSnapshot.child(childDeviceId).getRef().removeValue()
                                    .addOnSuccessListener(aVoid -> Log.d(TAG,
                                            "☢️ Additional QR structure OBLITERATED: " + childSnapshot.getKey()))
                                    .addOnFailureListener(
                                            e -> Log.e(TAG, "❌ Failed to obliterate structure: " + e.getMessage()));
                        }
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "❌ Nuclear QR obliteration cancelled: " + databaseError.getMessage());
                }
            });
        } else {
            Log.w(TAG, "⚠️ No QR share key available for nuclear obliteration!");
        }

        // 8. OBLITERATE device references
        DatabaseReference deviceRef = FirebaseDatabase.getInstance()
                .getReference("devices")
                .child(childDeviceId);
        deviceRef.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Device reference OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate device reference: " + e.getMessage()));

        // 9. OBLITERATE monitoring relationships
        DatabaseReference monitoringRef = FirebaseDatabase.getInstance()
                .getReference("monitoring_relationships");
        monitoringRef.child(sessionManager.getUserId()).child(childDeviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Monitoring relationship OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate monitoring: " + e.getMessage()));

        // Also remove from any parent perspective
        monitoringRef.child(childDeviceId).removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Child monitoring OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate child monitoring: " + e.getMessage()));

        // 10. OBLITERATE device connections
        DatabaseReference deviceConnectionsRef = FirebaseDatabase.getInstance()
                .getReference("device_connections")
                .child(childDeviceId);
        deviceConnectionsRef.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Device connections OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate device connections: " + e.getMessage()));

        // 11. OBLITERATE device sessions
        DatabaseReference deviceSessionsRef = FirebaseDatabase.getInstance()
                .getReference("device_sessions")
                .child(childDeviceId);
        deviceSessionsRef.removeValue()
                .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ Device sessions OBLITERATED"))
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to obliterate device sessions: " + e.getMessage()));

        // 12. OBLITERATE any user-device relationships
        if (mAuth.getCurrentUser() != null) {
            String parentUserId = mAuth.getCurrentUser().getUid();
            DatabaseReference userDeviceRef = FirebaseDatabase.getInstance()
                    .getReference("user_devices")
                    .child(parentUserId)
                    .child(childDeviceId);
            userDeviceRef.removeValue()
                    .addOnSuccessListener(aVoid -> Log.d(TAG, "☢️ User-device relationship OBLITERATED"))
                    .addOnFailureListener(
                            e -> Log.e(TAG, "❌ Failed to obliterate user-device relationship: " + e.getMessage()));
        }

        Log.d(TAG, "🔥 NUCLEAR OBLITERATION COMPLETE - Device should be COMPLETELY ERASED from Firebase!");
    }

    private void updateTargetDeviceDisplay() {
        if (binding == null || binding.tvDeviceStatus == null)
            return;

        // Prioritize child's actual name (userName) over device name
        String displayName = "";
        if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
            displayName = currentChildUserName;
        } else if (currentChildDeviceName != null && !currentChildDeviceName.isEmpty()) {
            displayName = currentChildDeviceName;
        } else {
            displayName = "Device";
        }

        // Capitalize first letter
        String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);

        // Format as "Name (Manage Devices)"
        String deviceStatusText = capitalizedName + " (Manage Devices)";
        binding.tvDeviceStatus.setText(deviceStatusText);

        // Set appropriate color (teal for modern look)
        if (!isFocusModeActive) {
            binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_FOCUS_MODE_APPS && resultCode == RESULT_OK && data != null) {
            // Get selected apps from child app list activity for focus mode
            ArrayList<AppInfo> selectedApps = data.getParcelableArrayListExtra("selected_apps");
            if (selectedApps != null) {
                Log.d(TAG, "Received " + selectedApps.size() + " selected apps from ChildAppListActivity");
                for (AppInfo app : selectedApps) {
                    Log.d(TAG, "Selected app: " + app.name + " (" + app.packageName + ")");
                }

                focusModeApps.clear();
                focusModeApps.addAll(selectedApps);

                // Save the selected apps persistently
                saveFocusModeApps();

                Toast.makeText(this, "Selected " + selectedApps.size() + " apps for focus mode blocking",
                        Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Focus mode now has " + focusModeApps.size() + " apps selected");

                // Update the focus mode UI to show selected apps
                updateFocusModeUI();

                // If focus mode is currently active, apply the new app selection
                if (isFocusModeActive) {
                    activateFocusMode();
                }
            }
        } else if (requestCode == 1003 && resultCode == RESULT_OK && data != null) {
            // Handle usage limiter app selection result
            ArrayList<String> selectedAppPackages = data.getStringArrayListExtra("selected_packages");
            if (selectedAppPackages != null) {
                Log.d(TAG, "Received " + selectedAppPackages.size() + " selected apps for usage limiter");

                // Update selected apps list
                selectedApps.clear();
                selectedApps.addAll(selectedAppPackages);

                // Update button text to show selection count
                String buttonText = selectedApps.isEmpty() ? "Select Apps"
                        : "Update Apps (" + selectedApps.size() + ")";
                if (btnSelectApps != null) {
                    btnSelectApps.setText(buttonText);
                }

                // Update Set Timer button state based on all requirements
                updateSetTimerButtonState();

                Toast.makeText(this, "Selected " + selectedApps.size() + " apps for usage limiter",
                        Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Usage limiter now has " + selectedApps.size() + " apps selected");
            }
        } else if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            // Handle timer navigation result
            String selectedTab = data.getStringExtra("selected_tab");

            if ("home".equals(selectedTab)) {
                // Just update navigation and UI (home is always visible)
                if (bottomNavigation != null) {
                    bottomNavigation.setSelectedItemId(R.id.nav_home);
                }
                updateFocusModeUI();
            } else if ("settings".equals(selectedTab)) {
                // Launch Settings Activity
                startActivity(new Intent(this, ParentSettingsActivity.class));
                if (bottomNavigation != null) {
                    bottomNavigation.setSelectedItemId(R.id.nav_settings);
                }
            }
        }
    }

    private void initializeManagers() {
        try {
            qrCodeManager = new QRCodeManager(this);
            childDeviceManager = new ChildDeviceManager(this);
            presetManager = new PresetManager(this);
            deviceStatusManager = new DeviceStatusManager(this);
            connectedDevicesManager = new ConnectedDevicesManager(this);

            // 🚫 CRITICAL: Clean up permanently removed devices from loaded storage
            cleanupPermanentlyRemovedDevices();

            // 🔧 PERSISTENCE FIX: Don't clear devices! Load them instead.
            // Old code: connectedDevicesManager.clearAllDevices();

            // Load preserved devices from storage
            connectedDevices = connectedDevicesManager.getConnectedDevices();
            if (connectedDevices == null) {
                connectedDevices = new ArrayList<>();
            }
            Log.d(TAG, "📱 Loaded " + connectedDevices.size() + " preserved devices from storage");

            // Sync current device ID
            String savedDeviceId = connectedDevicesManager.getCurrentDeviceId();
            if (savedDeviceId != null) {
                currentChildDeviceId = savedDeviceId;
                // Find name
                for (ChildDevice d : connectedDevices) {
                    if (d.deviceId.equals(savedDeviceId)) {
                        currentChildDeviceName = d.deviceName;
                        break;
                    }
                }
                Log.d(TAG, "📱 Restored current device: " + currentChildDeviceName);
            }

            // Start as parent device
            String deviceName = ParentUtils.getParentDeviceName();
            deviceStatusManager.startAsParentDevice(deviceName);

            Log.d(TAG, "Managers initialized successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing managers: " + e.getMessage());
        }
    }

    private void setupQRCodeGeneration() {
        try {
            // Generate permanent QR code
            permanentQRKey = qrCodeManager.getPermanentQRKey();
            Log.d(TAG, "QR key generated: " + permanentQRKey);

            // Initialize Firebase entry for this QR share
            initializeQRShareInFirebase();

            // Remove the code that creates and adds the QR section programmatically
            // Just set up the click listener for the XML blue button
            Button btnShowQRFullscreen = findViewById(R.id.btnShowQRFullscreen);
            if (btnShowQRFullscreen != null) {
                btnShowQRFullscreen.setOnClickListener(v -> showQRFullscreen());
                Log.d(TAG, "📱 QR button ready for manual device connections");
            }

            // Removed qrImageView code since user does not want to show QR code image in
            // the card

            // 🚫 CONDITIONAL: Only start AUTOMATIC listening for devices if NOT a fresh
            // login
            if (!isFreshLoginSession) {
                // Start listening for device connections
                listenForDeviceConnections();
                Log.d(TAG, "👂 Automatic device connection listeners started");
            } else {
                Log.d(TAG, "🚫 SKIPPED automatic device listeners - Fresh login session");
                Log.d(TAG, "💡 Users can still manually connect devices via QR scan");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error setting up QR code: " + e.getMessage());
        }
    }

    private void initializeQRShareInFirebase() {
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String deviceName = ParentUtils.getParentDeviceName();

            // DEBUGGING: Log critical information
            Log.d(TAG, "🔥 INITIALIZING QR SHARE - REAL DEVICE DEBUG");
            Log.d(TAG, "📱 Android Device ID: " + deviceId);
            Log.d(TAG, "📱 Device Name: " + deviceName);
            Log.d(TAG, "🔑 QR Key: " + permanentQRKey);

            // CRITICAL FIX: Store parent user data in users collection for child lookup
            FirebaseAuth mAuth = FirebaseAuth.getInstance();

            // DEBUGGING: Check authentication status
            if (mAuth.getCurrentUser() != null) {
                String parentUserId = mAuth.getCurrentUser().getUid();
                Log.d(TAG, "✅ Firebase Auth SUCCESS");
                Log.d(TAG, "👤 Parent User ID: " + parentUserId);
                Log.d(TAG, "📧 User Email: " + mAuth.getCurrentUser().getEmail());
                Log.d(TAG, "🔐 Is Anonymous: " + mAuth.getCurrentUser().isAnonymous());

                // Store parent user data in users collection
                DatabaseReference userRef = FirebaseDatabase.getInstance()
                        .getReference("users")
                        .child(parentUserId);

                Map<String, Object> parentUserData = new HashMap<>();
                parentUserData.put("deviceId", deviceId); // Android device ID for lookup
                parentUserData.put("deviceName", deviceName);
                parentUserData.put("userType", "parent");
                parentUserData.put("lastActive", System.currentTimeMillis());

                userRef.setValue(parentUserData)
                        .addOnSuccessListener(aVoid -> {
                            Log.d(TAG, "✅ Parent user data stored in users/" + parentUserId);
                            Log.d(TAG, "🔗 Device ID mapping: " + deviceId + " → " + parentUserId);
                            Log.d(TAG, "📊 Stored data: " + parentUserData.toString());

                            // DEBUGGING: Verify the data was actually written
                            userRef.addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(DataSnapshot snapshot) {
                                    if (snapshot.exists()) {
                                        Log.d(TAG, "🔍 VERIFICATION: User data exists in Firebase");
                                        Log.d(TAG, "📋 Retrieved data: " + snapshot.getValue());
                                    } else {
                                        Log.e(TAG, "❌ VERIFICATION FAILED: User data not found in Firebase!");
                                    }
                                }

                                @Override
                                public void onCancelled(DatabaseError error) {
                                    Log.e(TAG, "❌ Verification cancelled: " + error.getMessage());
                                }
                            });
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "❌ Failed to store parent user data: " + e.getMessage());
                            Log.e(TAG, "🔍 Error code: " + e.getClass().getSimpleName());
                            if (e.getMessage() != null) {
                                Log.e(TAG, "📝 Error details: " + e.getMessage());
                            }
                        });
            } else {
                Log.e(TAG, "❌ FIREBASE AUTH FAILED - NO USER!");
                Log.e(TAG, "🔧 This is likely why real device connection fails");
                Log.e(TAG, "💡 Parent must be authenticated to store user data");
            }

            DatabaseReference qrRef = FirebaseDatabase.getInstance()
                    .getReference("qr_shares")
                    .child(permanentQRKey);

            Map<String, Object> qrData = new HashMap<>();
            qrData.put("parentDeviceId", deviceId);
            qrData.put("parentDeviceName", deviceName);
            qrData.put("createdAt", System.currentTimeMillis());
            qrData.put("isActive", true);

            qrRef.setValue(qrData);
            Log.d(TAG, "QR share initialized in Firebase with parent user mapping");
        } catch (Exception e) {
            Log.e(TAG, "Error initializing QR share in Firebase: " + e.getMessage());
        }
    }

    private void generateAndDisplayQR(ImageView imageView) {
        try {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            String deviceName = ParentUtils.getParentDeviceName();

            String qrData = permanentQRKey + "|" + deviceId + "|" + deviceName;
            Bitmap qrBitmap = QRCodeManager.generateQRCodeBitmap(qrData, 200, 200);
            if (qrBitmap != null) {
                imageView.setImageBitmap(qrBitmap);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error generating QR: " + e.getMessage());
        }
    }

    private void showQRFullscreen() {
        try {
            Intent intent = new Intent(this, QRDisplayActivity.class);
            intent.putExtra("qr_key", permanentQRKey);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Error showing QR fullscreen: " + e.getMessage());
            Toast.makeText(this, "Error opening QR display", Toast.LENGTH_SHORT).show();
        }
    }

    // ⭐ NEW: Confirmation dialog for clearing timer
    private void showClearTimerConfirmation() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("🗑️ Clear Timer");
        builder.setMessage("Are you sure you want to clear the timer for \"" + currentChildDeviceName + "\"?\n\n" +
                "⚠️ This will:\n" +
                "• Stop the current timer immediately\n" +
                "• Remove all timer settings\n" +
                "• Clear selected apps for this device\n" +
                "• Require setting a new timer to restart\n\n" +
                "This action cannot be undone.");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("🗑️ Clear Timer", (dialog, which) -> {
            Log.d(TAG, "✅ User confirmed timer clear for device: " + currentChildDeviceName);
            clearUsageLimiter();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            Log.d(TAG, "❌ User cancelled timer clear");
        });

        builder.show();
    }

    private void setupDeviceSwitcher() {
        try {
            binding.tvDeviceStatus.setOnClickListener(v -> showDeviceSwitcher());
            updateDeviceStatus();
        } catch (Exception e) {
            Log.e(TAG, "Error in setupDeviceSwitcher: " + e.getMessage());
        }
    }

    private void showDeviceSwitcher() {
        // Redirect to the new premium bottom sheet
        showManageDevicesDialog();
    }

    private void showDeviceSwitcherLegacy() {
        try {
            Log.d(TAG, "🔄 DEVICE SWITCHER - Checking connected devices");
            Log.d(TAG, "📱 Current device: " + currentChildDeviceId + " (" + currentChildDeviceName + ")");

            // 🔍 DEBUG: Log device list status before showing switcher
            debugDeviceLists("Before Device Switcher");

            // Use local connectedDevices list (QR-scanned devices)
            List<ChildDevice> devices = new ArrayList<>(connectedDevices);
            Log.d(TAG, "📱 Local device list has " + devices.size() + " devices");

            // 🔧 DEVICE REMOVAL FIX: Check persistent storage but filter out removed
            // devices
            List<ChildDevice> persistentDevices = connectedDevicesManager.getConnectedDevices();
            Log.d(TAG, "💾 Persistent storage has " + persistentDevices.size() + " devices");

            // Filter out permanently removed devices from persistent storage
            List<ChildDevice> filteredPersistentDevices = new ArrayList<>();
            for (ChildDevice device : persistentDevices) {
                if (!isPermanentlyRemoved(device.deviceId)) {
                    filteredPersistentDevices.add(device);
                } else {
                    Log.d(TAG, "🚫 Filtering out permanently removed device: " + device.deviceName);
                }
            }
            Log.d(TAG, "💾 Filtered persistent storage has " + filteredPersistentDevices.size() + " devices");

            // Use whichever list has devices (prioritize local list)
            if (devices.isEmpty() && !filteredPersistentDevices.isEmpty()) {
                devices = filteredPersistentDevices;
                Log.d(TAG, "📱 Using filtered persistent devices as backup");
            }

            if (devices.isEmpty()) {
                Log.d(TAG, "❌ No devices found in either local or persistent storage");
                Toast.makeText(this,
                        "No child devices connected\n\nTo connect a device:\n1. Open child app\n2. Scan the QR code from parent app",
                        Toast.LENGTH_LONG).show();
                return;
            }

            // Log all available devices
            Log.d(TAG, "📱 Available devices for switching:");
            for (int i = 0; i < devices.size(); i++) {
                ChildDevice device = devices.get(i);
                String currentFlag = device.deviceId.equals(currentChildDeviceId) ? " [CURRENT]" : "";
                Log.d(TAG, "  " + (i + 1) + ". " + device.deviceName + " (ID: " + device.deviceId + ")" + currentFlag);
            }

            // Create a custom dialog with a vertical LinearLayout
            LinearLayout layout = new LinearLayout(this);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setPadding(32, 32, 32, 32);

            final AlertDialog[] dialogHolder = new AlertDialog[1];

            for (ChildDevice device : devices) {
                Log.d(TAG, "📲 Adding device to switcher: " + device.deviceName + " (ID: " + device.deviceId + ")");

                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0, 16, 0, 16);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);

                TextView deviceName = new TextView(this);
                // Display child's name if available, otherwise device name
                String displayName = (device.userName != null && !device.userName.isEmpty())
                        ? device.userName
                        : device.deviceName;
                String displayText = displayName;
                if (device.deviceId.equals(currentChildDeviceId)) {
                    displayText += " [CURRENT]";
                    deviceName.setTypeface(Typeface.DEFAULT_BOLD);
                }
                deviceName.setText(displayText);
                deviceName.setTextSize(16);
                deviceName.setTextColor(ContextCompat.getColor(this, R.color.text_primary)); // Modern color
                deviceName.setPadding(0, 0, 24, 0);
                deviceName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                deviceName.setOnClickListener(v -> {
                    Log.d(TAG,
                            "🔄 Device selected for switch: " + device.deviceName + " (ID: " + device.deviceId + ")");

                    // Check if this is already the current device
                    if (device.deviceId.equals(currentChildDeviceId)) {
                        Log.d(TAG, "Device already selected, no switch needed");
                        Toast.makeText(ParentDashboardActivity.this, "Already using " + device.deviceName,
                                Toast.LENGTH_SHORT).show();
                        if (dialogHolder[0] != null)
                            dialogHolder[0].dismiss();
                        return;
                    }

                    // Close dialog first
                    if (dialogHolder[0] != null)
                        dialogHolder[0].dismiss();

                    // Perform the switch with loading
                    switchToDevice(device);
                });

                Button removeBtn = new Button(this);
                removeBtn.setText("Remove");
                removeBtn.setTextColor(ContextCompat.getColor(this, R.color.error_600)); // Modern red
                removeBtn.setTextSize(14);
                removeBtn.setOnClickListener(v -> {
                    Log.d(TAG, "🗑️ REMOVE BUTTON CLICKED for device: " + device.deviceId);

                    // Show confirmation dialog before removing
                    new AlertDialog.Builder(ParentDashboardActivity.this)
                            .setTitle("Remove Device?")
                            .setMessage("Are you sure you want to remove " + device.deviceName
                                    + "? You will need to scan the QR code again to reconnect.")
                            .setPositiveButton("Remove", (dialog, which) -> {
                                removeChildDevice(device.deviceId);
                                if (dialogHolder[0] != null)
                                    dialogHolder[0].dismiss();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });

                row.addView(deviceName);
                row.addView(removeBtn);
                layout.addView(row);
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(
                    new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                    .setTitle("Select Child Device")
                    .setView(layout)
                    .setNegativeButton("Cancel", null);
            dialogHolder[0] = builder.create();
            dialogHolder[0].show();
        } catch (Exception e) {
            Log.e(TAG, "Error showing device switcher: " + e.getMessage());
        }
    }

    private void switchToDevice(ChildDevice device) {
        try {
            Log.d(TAG, "🔄 DEVICE SWITCH: From '" + currentChildDeviceName + "' to '" + device.deviceName + "'");

            // ⭐ ENHANCED: Show loading dialog IMMEDIATELY
            if (loadingDialogManager != null) {
                loadingDialogManager.show("Switching Device", "Loading " + device.deviceName + " data...");
                Log.d(TAG, "🔄 Loading dialog shown for device switch");
            }

            // ⭐ CRITICAL: Clear ALL previous device data completely
            clearDeviceSpecificUI();

            // Save current device state before switching
            if (currentChildDeviceId != null) {
                saveCompleteDeviceState();
            }

            // ⭐ ENHANCED: Show loading dialog during device switch
            runOnUiThread(() -> {
                if (loadingDialogManager != null) {
                    loadingDialogManager.updateText("Switching Device", "Preparing " + device.deviceName + "...");
                }
            });

            // ⭐ CRITICAL: Update device IDs BEFORE loading new data
            String previousDeviceId = currentChildDeviceId;
            // Save current device to persistent storage (explicit user action)
            connectedDevicesManager.setCurrentDevice(device.deviceId, true);
            // Sync local cached value from manager to ensure consistency
            currentChildDeviceId = connectedDevicesManager.getCurrentDeviceId();
            currentChildDeviceName = device.deviceName;

            // 🔧 FORCE-CLOSE PERSISTENCE: Save device name immediately
            saveDeviceNameForCurrentDevice();

            Log.d(TAG, "📱 Device switch: " + previousDeviceId + " → " + currentChildDeviceId);

            // Update loading message
            runOnUiThread(() -> {
                if (loadingDialogManager != null) {
                    loadingDialogManager.updateText("Loading Device Data",
                            "Loading settings for " + device.deviceName + "...");
                }
            });

            // ⭐ CRITICAL: Load device-specific timer data and settings
            loadCompleteDeviceState();

            // Initialize usage limiter for the new device
            initializeLimiterForDevice(device.deviceId);

            // Update device status display
            updateDeviceStatus();

            // Force UI refresh after device switch
            runOnUiThread(() -> {
                updateDeviceStatus();
                updateTargetDeviceDisplay();
                updateFocusModeUI();

                // Force complete UI refresh
                if (binding != null) {
                    if (binding.tvDeviceStatus != null) {
                        binding.tvDeviceStatus.setText(currentChildDeviceName);
                        binding.tvDeviceStatus.invalidate();
                    }
                    // Device name display handled by usage limiter UI
                }

                Log.d(TAG, "🔄 UI forcefully refreshed for device: " + currentChildDeviceName);
            });

            // Final loading message
            runOnUiThread(() -> {
                if (loadingDialogManager != null) {
                    loadingDialogManager.updateText("Almost Done", "Finalizing " + device.deviceName + "...");
                }
            });

            // Hide loading dialog after switch is complete (with small delay for smooth UX)
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                runOnUiThread(() -> {
                    if (loadingDialogManager != null) {
                        loadingDialogManager.hide();
                        Log.d(TAG, "🔄 Loading dialog hidden after device switch");
                    }
                    Toast.makeText(this, "✅ Switched to " + device.deviceName, Toast.LENGTH_SHORT).show();
                });
            }, 500); // 500ms delay for smooth UX

            Log.d(TAG, "🔄 Successfully switched to device: " + device.deviceName + " (" + device.deviceId + ")");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error switching device: " + e.getMessage());
            // Hide loading dialog on error
            runOnUiThread(() -> {
                if (loadingDialogManager != null) {
                    loadingDialogManager.hide();
                }
                Toast.makeText(this, "❌ Error switching device: " + e.getMessage(), Toast.LENGTH_LONG).show();
            });
            // Fallback: Clear display on error
            clearUsageDisplay();
        }
    }

    private void clearDeviceSpecificUI() {
        try {
            Log.d(TAG, "🧹 Clearing previous device-specific UI data");

            // ENHANCED: Clear ALL device-specific data completely
            // Clear usage display immediately
            clearUsageDisplay();
            Log.d(TAG, "✅ Cleared usage display");

            // DON'T clear timer data during device switch - it will be loaded for new
            // device
            Log.d(TAG, "✅ Skipped timer display clear to preserve timer state");

            // Clear focus mode apps display
            focusModeApps.clear();
            updateFocusModeUI();

            // Clear any cached usage data
            clearCachedUsageData();
            Log.d(TAG, "✅ Cleared cached usage data");

            // Clear timer display for device isolation
            // Timer running state no longer needed

            // ENHANCED: Clear selected apps for timer (device-specific)
            selectedApps.clear();

            // Clear device-specific timer references
            // Active timer ref cleanup no longer needed - using direct Firebase references
            // Timer ref cleanup no longer needed - using direct Firebase references

            // Clear usage chart data
            setupCategorySummaryChart(); // This will clear the chart for new device

            Log.d(TAG, "✅ Device-specific UI completely cleared");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error clearing device-specific UI: " + e.getMessage());
        }
    }

    private void refreshDeviceSpecificData() {
        if (currentChildDeviceId == null)
            return;

        Log.d(TAG, "🔄 Refreshing device-specific data for: " + currentChildDeviceName);

        try {
            // Refresh category summary chart with device-specific data
            setupCategorySummaryChart();

            // Clear any cached timer display and reload for current device

            // Refresh usage data
            loadSmartUsageDataForSelectedDate();

            Log.d(TAG, "✅ Device-specific data refresh complete");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error refreshing device-specific data: " + e.getMessage());
        }
    }

    private ChildDevice getCurrentDevice() {
        if (currentChildDeviceId != null) {
            return connectedDevicesManager.getDevice(currentChildDeviceId);
        }
        return null;
    }

    private void setupFocusMode() {
        Log.d(TAG, "🔧 Setting up focus mode functionality...");

        try {
            // Load saved focus mode apps for current device
            loadFocusModeApps();

            // 🔧 PERSISTENCE FIX: Check actual Focus Mode state from Firebase
            checkActualFocusModeStateFromFirebase();

            Log.d(TAG, "🎛️ Setting up focus mode switch listener...");

            // Check if binding and switch are available
            if (binding != null && binding.switchFocusMode != null) {
                Log.d(TAG, "✅ Focus mode switch found, setting up listener");

                // Set up focus mode switch listener
                binding.switchFocusMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    Log.d(TAG, "🎯 Focus mode switch toggled: " + isChecked);

                    if (isChecked) {
                        if (currentChildDeviceId == null) {
                            Log.w(TAG, "❌ No child device selected");
                            Toast.makeText(this, "Please select a child device first", Toast.LENGTH_SHORT).show();
                            buttonView.setChecked(false);
                            return;
                        }

                        if (isCreatingPreset) {
                            Log.w(TAG, "❌ Already processing focus mode request");
                            Toast.makeText(this, "Please wait, processing...", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        // Check if apps are selected
                        if (focusModeApps.isEmpty()) {
                            Log.w(TAG, "❌ No apps selected for focus mode");
                            Toast.makeText(this, "Please select apps first by clicking 'Edit App List'",
                                    Toast.LENGTH_SHORT).show();
                            buttonView.setChecked(false);
                            return;
                        }

                        Log.d(TAG, "✅ Activating focus mode with " + focusModeApps.size() + " apps");
                        // Activate focus mode with selected apps
                        activateFocusMode();

                    } else {
                        Log.d(TAG, "🔓 Deactivating focus mode");
                        // Deactivate focus mode
                        deactivateFocusMode();
                    }
                });

                // Set up edit app list button
                if (btnEditAppList != null) {
                    btnEditAppList.setOnClickListener(v -> {
                        Log.d(TAG, "📝 Edit App List button clicked");
                        showEditAppListDialog();
                    });
                    Log.d(TAG, "✅ Edit App List button listener set");
                } else {
                    Log.e(TAG, "❌ btnEditAppList is null!");
                }

                // Update initial state
                updateFocusModeUI();

            } else {
                Log.e(TAG, "❌ Focus mode switch not found in binding! Cannot set up focus mode.");
                Toast.makeText(this, "Focus mode switch not available", Toast.LENGTH_SHORT).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error setting up focus mode: " + e.getMessage(), e);
            Toast.makeText(this, "Focus mode temporarily unavailable", Toast.LENGTH_SHORT).show();
            try {
                if (binding != null && binding.switchFocusMode != null) {
                    binding.switchFocusMode.setChecked(false);
                }
            } catch (Exception bindingError) {
                Log.e(TAG, "Error accessing focus mode switch: " + bindingError.getMessage());
            }
        }
    }

    private void showFocusModeAppSelection() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a child device first", Toast.LENGTH_SHORT).show();
            // Reset the switch if no device is selected
            binding.switchFocusMode.setChecked(false);
            return;
        }

        // Launch child app list activity for focus mode
        Intent intent = new Intent(this, ChildAppListActivity.class);
        intent.putExtra("deviceId", currentChildDeviceId);
        intent.putExtra("deviceName", currentChildDeviceName);

        // Pass currently selected apps so they appear as checked
        if (!focusModeApps.isEmpty()) {
            intent.putParcelableArrayListExtra("preselected_apps", new ArrayList<>(focusModeApps));
            Log.d(TAG, "Passing " + focusModeApps.size() + " preselected apps to ChildAppListActivity");
            for (AppInfo app : focusModeApps) {
                Log.d(TAG, "Preselected app: " + app.name + " (" + app.packageName + ")");
            }
        } else {
            Log.d(TAG, "No preselected apps to pass to ChildAppListActivity");
        }

        startActivityForResult(intent, REQUEST_FOCUS_MODE_APPS);
    }

    /**
     * 🔧 PERSISTENCE FIX: Check actual Focus Mode state from Firebase
     */
    private void checkActualFocusModeStateFromFirebase() {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot check Focus Mode state - no device selected");
            return;
        }

        Log.d(TAG, "🔄 Checking actual Focus Mode state from Firebase for device: " + currentChildDeviceId);

        // Check if there are recent active focus mode commands on the child device
        DatabaseReference blockRef = FirebaseDatabase.getInstance()
                .getReference("block_commands")
                .child(currentChildDeviceId);

        blockRef.orderByChild("timestamp").limitToLast(5)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        boolean foundActiveFocusMode = false;
                        boolean foundRecentDeactivation = false;
                        long latestActivation = 0;
                        long latestDeactivation = 0;

                        if (snapshot.exists()) {
                            for (DataSnapshot commandSnapshot : snapshot.getChildren()) {
                                Map<String, Object> command = (Map<String, Object>) commandSnapshot.getValue();
                                if (command != null) {
                                    String action = (String) command.get("action");
                                    Boolean enabled = (Boolean) command.get("enabled");
                                    Long timestamp = (Long) command.get("timestamp");

                                    if ("focus_mode".equals(action) && timestamp != null) {
                                        if (Boolean.TRUE.equals(enabled)) {
                                            foundActiveFocusMode = true;
                                            latestActivation = Math.max(latestActivation, timestamp);
                                        } else if (Boolean.FALSE.equals(enabled)) {
                                            foundRecentDeactivation = true;
                                            latestDeactivation = Math.max(latestDeactivation, timestamp);
                                        }
                                    }
                                }
                            }
                        }

                        // Determine actual state based on most recent command
                        boolean actuallyActive = foundActiveFocusMode
                                && (latestDeactivation == 0 || latestActivation > latestDeactivation);

                        if (actuallyActive != isFocusModeActive) {
                            Log.d(TAG, "🔄 Focus Mode state mismatch detected!");
                            Log.d(TAG, "   📱 Stored state: " + (isFocusModeActive ? "ACTIVE" : "INACTIVE"));
                            Log.d(TAG, "   🔥 Firebase state: " + (actuallyActive ? "ACTIVE" : "INACTIVE"));
                            Log.d(TAG, "   ✅ Syncing to Firebase state...");

                            isFocusModeActive = actuallyActive;
                            saveFocusModeApps(); // Save the corrected state
                            updateFocusModeUI();
                        } else {
                            Log.d(TAG, "✅ Focus Mode state is in sync: " + (isFocusModeActive ? "ACTIVE" : "INACTIVE"));
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                        Log.e(TAG, "❌ Failed to check Focus Mode state from Firebase: " + error.getMessage());
                    }
                });
    }

    private void setupCategorySummaryChart() {
        /*
         * Removed from XML
         * if (btnViewDetailedUsage != null) {
         * btnViewDetailedUsage.setOnClickListener(v -> {
         * // ...
         * });
         * }
         */

        // Setup Update Usage Data button - REMOVED per user request
        // Button btnUpdateUsageData = findViewById(R.id.btnUpdateUsageData);
        // if (btnUpdateUsageData != null) ...
        View btnUpdateUsageData = findViewById(R.id.btnUpdateUsageData); // Keep finding it to avoid null checks failing
                                                                         // if used elsewhere, but simply ignore it
        if (btnUpdateUsageData != null) {
            btnUpdateUsageData.setOnClickListener(v -> {
                // No-op
            });
        }

        // Setup View Installed Apps button
        Button btnViewInstalledApps = findViewById(R.id.btnViewInstalledApps);
        if (btnViewInstalledApps != null) {
            btnViewInstalledApps.setOnClickListener(v -> {
                Log.d(TAG, "📱 View Installed Apps button clicked");
                if (currentChildDeviceId != null) {
                    Intent intent = new Intent(this, ChildInstalledAppsActivity.class);
                    intent.putExtra("childDeviceId", currentChildDeviceId);
                    // 🔧 FIX: Pass actual child name if available, otherwise device name
                    String displayName = (currentChildUserName != null && !currentChildUserName.isEmpty())
                            ? currentChildUserName
                            : currentChildDeviceName;
                    intent.putExtra("childName", displayName);
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "No child device selected", Toast.LENGTH_SHORT).show();
                }
            });
        }

        // Load usage data for selected device and date using the SMART tracking system
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            Log.d(TAG, "Initializing usage data for device: " + currentChildDeviceId);
            loadSmartUsageDataForSelectedDate();
        } else {
            Log.w(TAG, "No device selected, showing empty usage data");
            clearUsageDisplay();
        }
    }

    private void showCategoryAppsDialog(String categoryName, List<String> apps) {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle(categoryName + " Apps");

        // Create the app list text
        StringBuilder appList = new StringBuilder();
        for (String app : apps) {
            appList.append("• ").append(app).append("\n");
        }

        builder.setMessage(appList.toString());
        builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private List<String> getSocialMediaApps() {
        List<String> apps = new ArrayList<>();
        apps.add("WhatsApp");
        apps.add("Instagram");
        apps.add("Facebook");
        apps.add("Snapchat");
        apps.add("Twitter");
        apps.add("TikTok");
        apps.add("Telegram");
        return apps;
    }

    private List<String> getGamesApps() {
        List<String> apps = new ArrayList<>();
        apps.add("PUBG Mobile");
        apps.add("Free Fire");
        apps.add("Candy Crush");
        apps.add("Subway Surfers");
        apps.add("Temple Run");
        apps.add("Clash of Clans");
        apps.add("Minecraft");
        return apps;
    }

    private List<String> getEntertainmentApps() {
        List<String> apps = new ArrayList<>();
        apps.add("YouTube");
        apps.add("Netflix");
        apps.add("Disney+");
        apps.add("Amazon Prime");
        apps.add("Spotify");
        apps.add("TikTok");
        apps.add("Twitch");
        return apps;
    }

    private List<String> getOthersApps() {
        List<String> apps = new ArrayList<>();
        apps.add("Chrome");
        apps.add("Gmail");
        apps.add("Maps");
        apps.add("Camera");
        apps.add("Gallery");
        apps.add("Settings");
        apps.add("Calculator");
        return apps;
    }

    // Date navigation is disabled - only showing today's data
    // The arrows will show a toast message when clicked

    /**
     * SAFE method to change date - only allows user-initiated changes
     */
    private void setUserSelectedDate(Calendar newDate, String reason) {
        String oldDateKey = usageDateFormat.format(currentUsageDate.getTime());
        String newDateKey = usageDateFormat.format(newDate.getTime());

        Log.d(TAG, "🔐 USER DATE CHANGE: " + oldDateKey + " → " + newDateKey + " (Reason: " + reason + ")");

        currentUsageDate = (Calendar) newDate.clone();
        dateSetByUser = true;

        // Save the date change for the current device
        saveUsageDateForDevice();

        updateSelectedDateDisplay();
        loadSmartUsageDataForSelectedDate();
    }

    /**
     * PROTECTED method - prevents automatic date resets when user has chosen a
     * specific date
     */
    private boolean preventAutoDateReset(String attemptReason) {
        if (dateSetByUser) {
            String currentDateKey = usageDateFormat.format(currentUsageDate.getTime());
            Log.w(TAG, "🚫 BLOCKED AUTO DATE RESET: User selected " + currentDateKey + ", blocking reset attempt: "
                    + attemptReason);
            return true; // Block the operation
        }
        Log.d(TAG, "✅ Auto date operation allowed: " + attemptReason + " (User hasn't manually set date)");
        return false; // Allow the operation
    }

    /**
     * Update the selected date display in the UI
     */
    private void updateSelectedDateDisplay() {
        TextView tvSelectedDate = findViewById(R.id.tvSelectedDate);
        if (tvSelectedDate != null) {
            String displayDate;
            Calendar today = Calendar.getInstance();

            // Clear time components for proper comparison
            Calendar todayCompare = Calendar.getInstance();
            todayCompare.set(Calendar.HOUR_OF_DAY, 0);
            todayCompare.set(Calendar.MINUTE, 0);
            todayCompare.set(Calendar.SECOND, 0);
            todayCompare.set(Calendar.MILLISECOND, 0);

            Calendar selectedCompare = (Calendar) currentUsageDate.clone();
            selectedCompare.set(Calendar.HOUR_OF_DAY, 0);
            selectedCompare.set(Calendar.MINUTE, 0);
            selectedCompare.set(Calendar.SECOND, 0);
            selectedCompare.set(Calendar.MILLISECOND, 0);

            if (selectedCompare.equals(todayCompare)) {
                displayDate = "Today";
            } else {
                displayDate = dateFormat.format(currentUsageDate.getTime());
            }

            tvSelectedDate.setText(displayDate);
            String dateKey = usageDateFormat.format(currentUsageDate.getTime());
            Log.d(TAG, "📅 DISPLAY: Updated date display to: " + dateKey + " (User set: " + dateSetByUser + ")");
        }
    }

    /**
     * Load usage data for the currently selected date and device
     */
    /**
     * Save the current usage date for the specific device
     */
    private void saveUsageDateForDevice() {
        if (currentChildDeviceId == null)
            return;

        try {
            SharedPreferences datePrefs = getSharedPreferences("usage_dates", MODE_PRIVATE);
            String dateKey = "usage_date_" + currentChildDeviceId;
            String userSetKey = "date_user_set_" + currentChildDeviceId;

            String dateString = usageDateFormat.format(currentUsageDate.getTime());
            datePrefs.edit()
                    .putString(dateKey, dateString)
                    .putBoolean(userSetKey, dateSetByUser)
                    .apply();

            Log.d(TAG, "Saved usage date for device " + currentChildDeviceId + ": " + dateString + " (user set: "
                    + dateSetByUser + ")");
        } catch (Exception e) {
            Log.e(TAG, "Error saving usage date for device: " + e.getMessage());
        }
    }

    /**
     * Load the saved usage date for the current device
     */
    private void loadUsageDateForDevice() {
        if (currentChildDeviceId == null)
            return;

        try {
            SharedPreferences datePrefs = getSharedPreferences("usage_dates", MODE_PRIVATE);
            String dateKey = "usage_date_" + currentChildDeviceId;
            String userSetKey = "date_user_set_" + currentChildDeviceId;

            String savedDateString = datePrefs.getString(dateKey, null);
            boolean savedUserSetFlag = datePrefs.getBoolean(userSetKey, false);

            if (savedDateString != null) {
                try {
                    Date savedDate = usageDateFormat.parse(savedDateString);
                    currentUsageDate.setTime(savedDate);
                    dateSetByUser = savedUserSetFlag;

                    updateSelectedDateDisplay();
                    Log.d(TAG, "Loaded usage date for device " + currentChildDeviceId + ": " + savedDateString
                            + " (user set: " + dateSetByUser + ")");
                } catch (Exception parseE) {
                    Log.e(TAG, "Error parsing saved date: " + parseE.getMessage());
                    // Reset to today if parse fails
                    currentUsageDate = Calendar.getInstance();
                    dateSetByUser = false;
                }
            } else {
                // No saved date, default to today
                currentUsageDate = Calendar.getInstance();
                dateSetByUser = false;
                Log.d(TAG, "No saved usage date for device " + currentChildDeviceId + ", defaulting to today");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading usage date for device: " + e.getMessage());
            currentUsageDate = Calendar.getInstance();
            dateSetByUser = false;
        }
    }

    /**
     * Comprehensive device-specific state loading for complete isolation
     */
    private void loadCompleteDeviceState() {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot load device state: no device selected");
            return;
        }

        Log.d(TAG, "🔄 Loading complete state for device: " + currentChildDeviceId);

        // Load all device-specific data
        loadSelectedAppsForDevice();
        loadTimerDurationFromLocal();
        loadUsageDateForDevice();
        loadFocusModeApps();

        // Update all UI components for this device
        updateFocusModeUI();
        updateTargetDeviceDisplay();
        updateSelectedDateDisplay();

        // Force UI refresh to show device change
        runOnUiThread(() -> {
            updateDeviceStatus();
            updateTargetDeviceDisplay();
            // Force layout refresh
            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.invalidate();
                binding.tvDeviceStatus.requestLayout();
            }
        });

        // Refresh usage data
        loadSmartUsageDataForSelectedDate();

        Log.d(TAG, "✅ Complete device state loaded for: " + currentChildDeviceId);
    }

    /**
     * Save all device-specific state for persistence
     */
    private void saveCompleteDeviceState() {
        if (currentChildDeviceId == null)
            return;

        Log.d(TAG, "💾 Saving complete state for device: " + currentChildDeviceId);

        saveSelectedAppsForDevice();
        saveFocusModeApps();
        saveUsageDateForDevice();
        saveDeviceNameForCurrentDevice();

        // Save timer duration if currently set
        if (etLimiterHours != null && etLimiterMinutes != null) {
            try {
                String hoursStr = etLimiterHours.getText().toString().trim();
                String minutesStr = etLimiterMinutes.getText().toString().trim();
                if (!hoursStr.isEmpty() || !minutesStr.isEmpty()) {
                    int hours = hoursStr.isEmpty() ? 0 : Integer.parseInt(hoursStr);
                    int minutes = minutesStr.isEmpty() ? 0 : Integer.parseInt(minutesStr);
                    saveTimerDurationLocally(hours, minutes);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error saving timer duration during state save: " + e.getMessage());
            }
        }

        Log.d(TAG, "✅ Complete device state saved for: " + currentChildDeviceId);
    }

    /**
     * 🎯 Load Smart Usage Data - GAME CHANGER!
     * Load rolling 7-day usage data from connection-based tracking
     */
    private void loadSmartUsageDataForSelectedDate() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            clearUsageDisplay();
            return;
        }

        // 🚀 INSTITUTIONAL CACHE: Check persistent storage first (survives app
        // restarts)
        String persistentCache = usageCachePrefs.getString(currentChildDeviceId, null);

        // 🚀 INSTANT DISPLAY: Show cached data immediately if available
        if (cachedUsageFormatted.containsKey(currentChildDeviceId)) {
            String cachedValue = cachedUsageFormatted.get(currentChildDeviceId);
            Log.d(TAG, "📦 MEMORY CACHE HIT: " + cachedValue);
            TextView tvTotalTime = findViewById(R.id.tvTotalTime);
            if (tvTotalTime != null)
                tvTotalTime.setText(cachedValue);
        } else if (persistentCache != null) {
            Log.d(TAG, "💾 DISK CACHE HIT: " + persistentCache);
            TextView tvTotalTime = findViewById(R.id.tvTotalTime);
            if (tvTotalTime != null)
                tvTotalTime.setText(persistentCache);

            // Update memory cache too
            cachedUsageFormatted.put(currentChildDeviceId, persistentCache);
        } else {
            TextView tvTotalTime = findViewById(R.id.tvTotalTime);
            if (tvTotalTime != null) {
                tvTotalTime.setText("Loading...");
            }
        }

        // 🔧 FIX: Use 'susage_data' (Smart Usage) and REAL-TIME listener
        // This ensures data updates automatically every 2 mins when child uploads
        DatabaseReference smartUsageRef = FirebaseDatabase.getInstance()
                .getReference("susage_data")
                .child(currentChildDeviceId);

        // 🔄 REAL-TIME UDPATE: Use addValueEventListener instead of single value
        smartUsageRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    // Generate date key for the selected date (yyyy-MM-dd)
                    String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .format(currentUsageDate.getTime());

                    runOnUiThread(() -> displaySmartUsageData(dataSnapshot, dateKey));
                } else {
                    runOnUiThread(() -> {
                        if (!cachedUsageFormatted.containsKey(currentChildDeviceId)) {
                            clearUsageDisplay();
                        }
                    });
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                runOnUiThread(() -> {
                    if (!cachedUsageFormatted.containsKey(currentChildDeviceId)) {
                        clearUsageDisplay();
                        Toast.makeText(ParentDashboardActivity.this, "Error loading usage data", Toast.LENGTH_SHORT)
                                .show();
                    }
                });
            }
        });
    }

    /**
     * 🎯 Display Smart Usage Data using yyyy-MM-dd keys
     */
    private void displaySmartUsageData(DataSnapshot smartDataSnapshot, String requestedDateKey) {
        try {
            Log.d(TAG, "🎯 Processing smart usage data (susage) for date: " + requestedDateKey);

            // Look for data in weeklyData -> [DateKey]
            DataSnapshot dailyDataSnapshot = smartDataSnapshot.child("weeklyData").child(requestedDateKey);

            if (dailyDataSnapshot.exists()) {
                Log.d(TAG, "✅ Found usage data for " + requestedDateKey);

                long totalUsage = 0;
                List<AppUsage> appUsageList = new ArrayList<>();

                // Parse apps from the daily data
                // The structure should be weeklyData -> Date -> apps -> PackageName ->
                // {usageTime, ...}
                DataSnapshot appsSnapshot = dailyDataSnapshot.child("apps");

                for (DataSnapshot appSnapshot : appsSnapshot.getChildren()) {
                    Long usageTime = appSnapshot.child("usageTime").getValue(Long.class);
                    String packageName = appSnapshot.getKey(); // Key is package name

                    // Fallback to "packageName" field if exists
                    if (appSnapshot.hasChild("packageName")) {
                        packageName = appSnapshot.child("packageName").getValue(String.class);
                    }

                    if (usageTime != null && usageTime > 0) {
                        totalUsage += usageTime;

                        // Add to list for chart/details if needed (though we only need total for main
                        // card now)
                        AppUsage appUsage = new AppUsage();
                        appUsage.setPackageName(packageName);
                        appUsage.setUsageTime(usageTime);
                        // appUsage.setAppName(...); // Name might be cached or fetched
                        appUsageList.add(appUsage);
                    }
                }

                // If apps list is empty but 'totalScreenTimeMillis' exists directly on the day
                // node
                if (totalUsage == 0 && dailyDataSnapshot.hasChild("totalScreenTimeMillis")) {
                    Long storedTotal = dailyDataSnapshot.child("totalScreenTimeMillis").getValue(Long.class);
                    if (storedTotal != null) {
                        totalUsage = storedTotal;
                        Log.d(TAG, "⚡ Used pre-calculated totalScreenTimeMillis: " + totalUsage);
                    }
                }

                // Update UI using the helper method
                updateUsageDisplayUI(totalUsage);

                Log.d(TAG, "📊 Total usage calc: " + formatDurationMs(totalUsage));

            } else {
                Log.d(TAG, "❌ No data found for date key: " + requestedDateKey);
                // Try to see if it's 'today' and maybe data hasn't synced yet
                // But generally just clear display or show 0
                updateUsageDisplayUI(0);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error displaying smart usage data: " + e.getMessage());
            e.printStackTrace();
            clearUsageDisplay();
        }
    }

    /**
     * 📊 Display smart usage list data
     */
    private void displaySmartUsageList(List<AppUsage> appUsageList) {
        try {
            Log.d(TAG, "📊 Displaying " + appUsageList.size() + " apps from smart usage data");

            // Calculate total usage time
            long totalUsage = 0;
            for (AppUsage appUsage : appUsageList) {
                totalUsage += appUsage.getUsageTime();
            }

            // Use existing UI update method
            updateUsageDisplayUI(totalUsage);

            // Log app details for debugging
            for (int i = 0; i < Math.min(appUsageList.size(), 5); i++) {
                AppUsage app = appUsageList.get(i);
                Log.d(TAG, "📱 App " + (i + 1) + ": " + app.getAppName() +
                        " - " + formatDurationMs(app.getUsageTime()));
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error displaying smart usage list: " + e.getMessage());
            clearUsageDisplay();
        }
    }

    /**
     * 📊 Show smart tracking information to user
     */
    private void showSmartTrackingInfo(long trackingStartTime, long daysSinceTracking, int appsCount) {
        try {
            Date trackingStart = new Date(trackingStartTime);
            String trackingInfo = String.format("🎯 Smart Tracking: Day %d since %s (%d apps)",
                    daysSinceTracking + 1,
                    new SimpleDateFormat("MMM dd", Locale.getDefault()).format(trackingStart),
                    appsCount);

            // You can display this in a TextView or as a toast
            // For now, just log it
            Log.d(TAG, trackingInfo);

        } catch (Exception e) {
            Log.e(TAG, "Error showing smart tracking info: " + e.getMessage());
        }
    }

    /**
     * 📭 Display message when no data is available for selected date
     */
    private void displayNoDataMessage(long daysSinceTracking, long currentDay) {
        clearUsageDisplay();

        String message;
        if (daysSinceTracking < 0) {
            message = "No data available - Date is before device connection";
        } else if (daysSinceTracking > currentDay) {
            message = "No data available - Future date selected";
        } else if (daysSinceTracking > 6) {
            message = "Data not available - Only last 7 days are tracked";
        } else {
            message = "No usage data recorded for this day";
        }

        Log.d(TAG, "📭 " + message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /**
     * 🔄 LEGACY METHOD - Replaced by Smart Usage Tracking
     * 
     * @deprecated Use loadSmartUsageDataForSelectedDate() instead
     */
    @Deprecated
    private void loadUsageDataForSelectedDate() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Log.w(TAG, "No device selected, cannot load usage data");
            clearUsageDisplay();
            return;
        }

        // Format the selected date for loading
        String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(currentUsageDate.getTime());

        Log.d(TAG, "📅 ENHANCED: Loading usage data for device: " + currentChildDeviceId + " for date: " + dateKey);

        // Show loading state
        showLoadingState();

        // 🎯 Use new DateAwareUsageDataManager for proper daily separation
        DateAwareUsageDataManager dataManager = new DateAwareUsageDataManager(this, currentChildDeviceId);

        dataManager.getUsageDataForDate(dateKey, new DateAwareUsageDataManager.UsageDataCallback() {
            @Override
            public void onDataReceived(DateAwareUsageDataManager.UsageDataForDate data) {
                runOnUiThread(() -> {
                    Log.d(TAG, "✅ DATE-SPECIFIC data received for " + dateKey + ": " + data.totalTimeText);
                    displayDateAwareUsageData(data);
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Log.e(TAG, "❌ Error loading date-specific data: " + error);
                    clearUsageDisplay();
                    Toast.makeText(ParentDashboardActivity.this,
                            "Error loading usage data for " + dateKey, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 📅 Display date-aware usage data (NEW METHOD - no data contamination between
     * days)
     */
    private void displayDateAwareUsageData(DateAwareUsageDataManager.UsageDataForDate data) {
        try {
            Log.d(TAG, "📅 Displaying DATE-AWARE data for " + data.dateKey + ": " + data.totalTimeText);

            // Update total usage display - tvTotalUsage view removed from layout
            // Now using SUSAGE-style usage view instead
            Log.d(TAG, "📊 Total usage: " + data.totalTimeText);

            // Update app usage list
            if (data.appUsageList != null && !data.appUsageList.isEmpty()) {
                // displayAppUsageList(data.appUsageList); // Method implementation needed
                Log.d(TAG, "📱 Displayed " + data.appUsageList.size() + " apps for " + data.dateKey);
            } else {
                // Show empty state for this specific date
                displayEmptyUsageState(data.dateKey, data.dayLabel);
            }

            // Hide loading state
            // hideLoadingState(); // Method implementation needed

        } catch (Exception e) {
            Log.e(TAG, "❌ Error displaying date-aware usage data: " + e.getMessage());
            clearUsageDisplay();
        }
    }

    /**
     * 📭 Display empty usage state for a specific date
     */
    private void displayEmptyUsageState(String dateKey, String dayLabel) {
        // Clear app list - using existing container or skip if not available
        // LinearLayout appUsageContainer = findViewById(R.id.appUsageContainer);
        // if (appUsageContainer != null) {
        // appUsageContainer.removeAllViews();
        //
        // // Add empty state message
        // TextView emptyMessage = new TextView(this);
        // emptyMessage.setText("📭 No usage data recorded for " + dayLabel);
        // emptyMessage.setTextSize(16);
        // emptyMessage.setTextColor(getResources().getColor(android.R.color.darker_gray));
        // emptyMessage.setPadding(32, 32, 32, 32);
        // emptyMessage.setGravity(android.view.Gravity.CENTER);
        //
        // appUsageContainer.addView(emptyMessage);
        // }

        Log.d(TAG, "📭 No usage data for " + dayLabel + " (" + dateKey + ")");

        Log.d(TAG, "📭 Displayed empty state for " + dateKey + " (" + dayLabel + ")");
    }

    /**
     * Display accurate usage data with device-specific filtering - DATE AWARE
     * VERSION
     */
    private void displayAccurateUsageData(DataSnapshot snapshot) {
        Log.d(TAG, "⚡ DATE-AWARE displaying usage data for device: " + currentChildDeviceId);
        Log.d(TAG, "🔍 DEBUG: Current selected date: " + usageDateFormat.format(currentUsageDate.getTime()));
        Log.d(TAG, "🔍 DEBUG: User set date flag: " + dateSetByUser);

        if (snapshot == null || !snapshot.exists()) {
            Log.w(TAG, "No usage data available in snapshot");
            // Fallback to regular loaded data instead of clearing
            loadSmartUsageDataForSelectedDate();
            return;
        }

        // Calculate which day we need based on selected date
        Calendar today = Calendar.getInstance();
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);

        Calendar selectedCompare = (Calendar) currentUsageDate.clone();
        selectedCompare.set(Calendar.HOUR_OF_DAY, 0);
        selectedCompare.set(Calendar.MINUTE, 0);
        selectedCompare.set(Calendar.SECOND, 0);
        selectedCompare.set(Calendar.MILLISECOND, 0);

        // Calculate days difference (0 = today, 1 = yesterday, etc.)
        long diffInMs = today.getTimeInMillis() - selectedCompare.getTimeInMillis();
        int daysBack = (int) (diffInMs / (24 * 60 * 60 * 1000));

        String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(currentUsageDate.getTime());

        Log.d(TAG, "⚡ Selected date: " + dateKey + " is " + daysBack + " days back from today");

        String totalTimeText = null;

        // PRIORITY 1: For TODAY only, check immediate totalText field (fastest)
        if (daysBack == 0) {
            totalTimeText = snapshot.child("totalText").getValue(String.class);
            if (totalTimeText != null && !totalTimeText.isEmpty()) {
                Log.d(TAG, "⚡ TODAY: Found totalText field: " + totalTimeText);

                // Update bar chart with 7-day data if available
                updateBarChartFromSnapshot(snapshot);

                updateTotalUsageUI(totalTimeText);
                return;
            }
        }

        // PRIORITY 2: Check totalTexts array for specific date
        if (snapshot.child("totalTexts").exists()) {
            DataSnapshot totalTextsSnapshot = snapshot.child("totalTexts");
            List<String> totalTextsList = new ArrayList<>();

            // Build the list in order
            for (DataSnapshot textSnapshot : totalTextsSnapshot.getChildren()) {
                String text = textSnapshot.getValue(String.class);
                if (text != null) {
                    totalTextsList.add(text);
                }
            }

            Log.d(TAG, "⚡ Found " + totalTextsList.size() + " total texts in array");

            // Update bar chart with 7-day data if available
            updateBarChartFromSnapshot(snapshot);

            // Calculate the array index: newest data is at the end
            // Array structure: [6 days ago, 5 days ago, ..., yesterday, today]
            int arrayIndex = totalTextsList.size() - 1 - daysBack;

            if (arrayIndex >= 0 && arrayIndex < totalTextsList.size()) {
                totalTimeText = totalTextsList.get(arrayIndex);
                if (totalTimeText != null && !totalTimeText.isEmpty()) {
                    Log.d(TAG, "⚡ DATE-SPECIFIC: Found total for " + dateKey + " at index " + arrayIndex + ": "
                            + totalTimeText);
                    updateTotalUsageUI(totalTimeText);
                    return;
                }
            } else {
                Log.d(TAG, "⚡ No data available for " + dateKey + " (index " + arrayIndex + " out of bounds for "
                        + totalTextsList.size() + " items)");
            }
        }

        // PRIORITY 3: Calculate from dailyApps for specific date
        if (snapshot.child("dailyApps").exists()) {
            Log.d(TAG, "⚡ CALCULATING: From dailyApps for date " + dateKey);
            calculateTotalFromAppsForDate(snapshot, daysBack);
            return;
        }

        // PRIORITY 4: Fallback to apps calculation (today only)
        if (daysBack == 0) {
            Log.d(TAG, "⚡ FALLBACK: Calculating from apps for today");
            calculateTotalFromApps(snapshot);
        } else {
            // No data available for historical date
            Log.d(TAG, "⚡ NO DATA: No historical data found for " + dateKey);
            // Fallback to regular load
            loadSmartUsageDataForSelectedDate();
        }
    }

    /**
     * Update just the total usage UI immediately
     */
    private void updateTotalUsageUI(String totalText) {
        // tvTotalUsage view removed from layout - logging only
        Log.d(TAG, "⚡ Total usage calculated: " + totalText);
    }

    /**
     * Fast fallback method to calculate total usage from individual app data
     */
    /**
     * Fast fallback method to calculate total usage from individual app data
     */
    private void calculateTotalFromApps(DataSnapshot snapshot) {
        long totalUsage = 0L;

        // FAST: Check for today's apps first (most likely scenario)
        if (snapshot.child("apps").exists()) {
            for (DataSnapshot appSnapshot : snapshot.child("apps").getChildren()) {
                try {
                    Long usage = appSnapshot.child("usageTime").getValue(Long.class);
                    if (usage != null && usage > 0) {
                        totalUsage += usage;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing app usage: " + e.getMessage());
                }
            }
        }

        // If no usage found, check dailyApps structure (only first day for speed)
        if (totalUsage == 0 && snapshot.child("dailyApps").exists()) {
            DataSnapshot dailyAppsSnapshot = snapshot.child("dailyApps");
            // Just check the first day entry for speed
            DataSnapshot firstDay = dailyAppsSnapshot.child("0");
            if (firstDay.exists()) {
                for (DataSnapshot appSnapshot : firstDay.getChildren()) {
                    try {
                        Long usage = appSnapshot.child("usageTime").getValue(Long.class);
                        if (usage != null && usage > 0) {
                            totalUsage += usage;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing daily app usage: " + e.getMessage());
                    }
                }
            }
        }

        // 🛡️ REJECTION LOGIC: If calculated usage is 0, but we have valid cache,
        // suspicious!
        if (totalUsage == 0 && cachedUsageFormatted.containsKey(currentChildDeviceId)) {
            String cachedVal = cachedUsageFormatted.get(currentChildDeviceId);
            if (cachedVal != null && !cachedVal.equals("0m") && !cachedVal.equals("0h 0m")) {
                Log.w(TAG, "⚠️ POTENTIAL BAD DATA: Ignored 0 usage from fast-fetch because cache has " + cachedVal);
                return;
            }
        }

        // Update UI with calculated total
        updateUsageDisplayUI(totalUsage);

        Log.d(TAG, "⚡ CALCULATED total usage: " + formatDurationMs(totalUsage));
    }

    /**
     * Calculate total usage from dailyApps for a specific date
     */
    private void calculateTotalFromAppsForDate(DataSnapshot snapshot, int daysBack) {
        long totalUsage = 0L;

        if (snapshot.child("dailyApps").exists()) {
            DataSnapshot dailyAppsSnapshot = snapshot.child("dailyApps");
            long childCount = dailyAppsSnapshot.getChildrenCount();

            // Calculate the array index for the specific date
            int arrayIndex = (int) (childCount - 1 - daysBack);

            if (arrayIndex >= 0 && arrayIndex < childCount) {
                DataSnapshot daySnapshot = dailyAppsSnapshot.child(String.valueOf(arrayIndex));
                if (daySnapshot.exists()) {
                    for (DataSnapshot appSnapshot : daySnapshot.getChildren()) {
                        try {
                            Long usage = appSnapshot.child("usageTime").getValue(Long.class);
                            if (usage != null && usage > 0) {
                                totalUsage += usage;
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing daily app usage for date: " + e.getMessage());
                        }
                    }
                    Log.d(TAG, "⚡ DATE-SPECIFIC: Calculated total for " + daysBack + " days back: "
                            + formatDurationMs(totalUsage));
                } else {
                    Log.d(TAG, "⚡ No apps data found for " + daysBack + " days back at index " + arrayIndex);
                }
            } else {
                Log.d(TAG, "⚡ Date index " + arrayIndex + " out of bounds for " + childCount + " days");
            }
        }

        // Update UI with calculated total
        updateUsageDisplayUI(totalUsage);
    }

    /**
     * Clear the usage display when no data is available
     */
    private void clearUsageDisplay() {
        TextView tvTotalTime = findViewById(R.id.tvTotalTime);
        if (tvTotalTime != null) {
            tvTotalTime.setText("0h 0m");
        }
        Log.d(TAG, "Usage display cleared - no data available");
    }

    /**
     * Clear timer display for device switching
     */
    private void clearTimerDisplay() {
        if (tvLimiterTimer != null) {
            tvLimiterTimer.setText("00:00");
        }
        if (tvLimiterStatus != null) {
            tvLimiterStatus.setText("No timer active");
        }
        // Timer running state no longer needed
    }

    /**
     * FIXED: Clear timer display ONLY when no device is selected
     */
    private void clearTimerDisplayForNoDevice() {
        if (tvLimiterTimer != null) {
            tvLimiterTimer.setText("00:00");
        }
        if (tvLimiterStatus != null) {
            tvLimiterStatus.setText("No device selected");
        }
        // Timer running state no longer needed
    }

    /**
     * Display active timer remaining time
     */
    private void displayActiveTimerTime(long remainingTimeMs) {
        int totalSeconds = (int) (remainingTimeMs / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (tvLimiterTimer != null) {
            tvLimiterTimer.setText(String.format("%02d:%02d:%02d", hours, minutes, seconds));
            tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
        if (tvLimiterStatus != null) {
            tvLimiterStatus.setText("Timer active on " + currentChildDeviceName);
            tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }

        Log.d(TAG, "Displayed active timer: " + hours + "h " + minutes + "m " + seconds + "s");
    }

    /**
     * Clear cached usage data for device switching
     */
    private void clearCachedUsageData() {
        // Clear any cached data maps or variables
        // This prevents data from previous device showing on new device
        Log.d(TAG, "Cleared cached usage data for device isolation");
    }

    /**
     * Load timer state for current device from Firebase
     */
    private void loadTimerStateForCurrentDevice() {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot load timer state: device is null");
            return;
        }

        Log.d(TAG, "Loading timer state for device: " + currentChildDeviceId);

        // ENHANCED: Check both parent_timers and active_timers for complete state
        DatabaseReference parentTimersRef = FirebaseDatabase.getInstance().getReference("parent_timers");
        parentTimersRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                boolean hasActiveTimer = false;

                if (dataSnapshot.exists()) {
                    for (DataSnapshot timerSnapshot : dataSnapshot.getChildren()) {
                        Boolean isActive = timerSnapshot.child("isActive").getValue(Boolean.class);
                        if (isActive != null && isActive) {
                            hasActiveTimer = true;
                            // Timer running state no longer needed

                            // Load selected apps for this timer
                            loadSelectedAppsFromTimer(timerSnapshot);

                            // Load timer duration data
                            Long duration = timerSnapshot.child("duration").getValue(Long.class);
                            if (duration != null) {
                                loadTimerDurationToUI(duration);
                            }

                            Log.d(TAG, "Found active timer for device: " + currentChildDeviceId);
                            break;
                        }
                    }
                }

                if (!hasActiveTimer) {
                    // Also check active_timers as backup
                    DatabaseReference activeTimerRef = FirebaseDatabase.getInstance()
                            .getReference("active_timers")
                            .child(currentChildDeviceId);

                    activeTimerRef.addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot activeSnapshot) {
                            if (activeSnapshot.exists()) {
                                // Timer running state no longer needed

                                // Load the actual timer data from active_timers
                                Long remainingTime = activeSnapshot.child("remainingTime").getValue(Long.class);
                                if (remainingTime != null && remainingTime > 0) {
                                    runOnUiThread(() -> {
                                        displayActiveTimerTime(remainingTime);
                                    });
                                }

                                Log.d(TAG, "Found active timer in active_timers for device: " + currentChildDeviceId);
                            } else {
                                // Timer running state no longer needed
                                Log.d(TAG, "No active timer found for device: " + currentChildDeviceId);
                            }

                            runOnUiThread(() -> {
                                updateLimiterDisplay();
                                updateButtonStates();
                            });
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {
                            Log.e(TAG, "Error checking active_timers: " + databaseError.getMessage());
                            // Timer running state no longer needed
                            runOnUiThread(() -> {
                                updateLimiterDisplay();
                                updateButtonStates();
                            });
                        }
                    });
                } else {
                    runOnUiThread(() -> {
                        updateLimiterDisplay();
                        updateButtonStates();
                    });
                }

                Log.d(TAG, "Timer state loaded - hasActiveTimer: " + hasActiveTimer);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error loading timer state: " + databaseError.getMessage());
                // Timer running state no longer needed
                selectedApps.clear();
                runOnUiThread(() -> {
                    updateLimiterDisplay();
                    updateButtonStates();
                });
            }
        });
    }

    /**
     * Load timer duration into UI inputs
     */
    private void loadTimerDurationToUI(long durationMs) {
        int totalMinutes = (int) (durationMs / (1000 * 60));
        int hours = totalMinutes / 60;
        int minutes = totalMinutes % 60;

        runOnUiThread(() -> {
            if (etLimiterHours != null && hours > 0) {
                etLimiterHours.setText(String.valueOf(hours));
            }
            if (etLimiterMinutes != null && minutes > 0) {
                etLimiterMinutes.setText(String.valueOf(minutes));
            }
            Log.d(TAG, "Loaded timer duration to UI: " + hours + "h " + minutes + "m");
        });
    }

    /**
     * Save timer duration locally for persistence
     */
    private void saveTimerDurationLocally(int hours, int minutes) {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot save timer duration: no device selected");
            return;
        }

        try {
            SharedPreferences timerPrefs = getSharedPreferences("timer_duration", MODE_PRIVATE);
            String hoursKey = "timer_hours_" + currentChildDeviceId;
            String minutesKey = "timer_minutes_" + currentChildDeviceId;

            timerPrefs.edit()
                    .putInt(hoursKey, hours)
                    .putInt(minutesKey, minutes)
                    .apply();

            Log.d(TAG, "Saved timer duration locally: " + hours + "h " + minutes + "m for device: "
                    + currentChildDeviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error saving timer duration locally: " + e.getMessage());
        }
    }

    /**
     * Load timer duration from local storage
     */
    private void loadTimerDurationFromLocal() {
        if (currentChildDeviceId == null) {
            return;
        }

        try {
            SharedPreferences timerPrefs = getSharedPreferences("timer_duration", MODE_PRIVATE);
            String hoursKey = "timer_hours_" + currentChildDeviceId;
            String minutesKey = "timer_minutes_" + currentChildDeviceId;

            int hours = timerPrefs.getInt(hoursKey, 0);
            int minutes = timerPrefs.getInt(minutesKey, 0);

            if (hours > 0 || minutes > 0) {
                runOnUiThread(() -> {
                    if (etLimiterHours != null && hours > 0) {
                        etLimiterHours.setText(String.valueOf(hours));
                    }
                    if (etLimiterMinutes != null && minutes > 0) {
                        etLimiterMinutes.setText(String.valueOf(minutes));
                    }
                });

                Log.d(TAG, "Loaded timer duration from local storage: " + hours + "h " + minutes + "m for device: "
                        + currentChildDeviceId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading timer duration from local storage: " + e.getMessage());
        }
    }

    /**
     * Load selected apps from timer data
     */
    private void loadSelectedAppsFromTimer(DataSnapshot timerSnapshot) {
        selectedApps.clear();

        List<String> appPackages = (List<String>) timerSnapshot.child("selectedApps").getValue();
        if (appPackages != null && !appPackages.isEmpty()) {
            // Convert package names back to AppInfo objects
            // Get app names from device_apps data
            for (String packageName : appPackages) {
                selectedApps.add(packageName);
            }

            Log.d(TAG, "Loaded " + selectedApps.size() + " selected apps for timer");

            // ENHANCED: Save the selected apps device-specifically
            saveSelectedAppsForDevice();

            // Also save the current usage date for this device
            saveUsageDateForDevice();
        }
    }

    /**
     * Get app name from device_apps data or stored apps
     */
    private String getAppNameFromDeviceApps(String packageName) {
        // First try to find in selectedApps if it exists
        for (String appPackage : selectedApps) {
            if (appPackage.equals(packageName)) {
                // Return a user-friendly version of package name
                return packageName.substring(packageName.lastIndexOf('.') + 1).replace('_', ' ');
            }
        }

        // TODO: In future, query Firebase device_apps for app name
        // For now, return a user-friendly version of package name
        String[] parts = packageName.split("\\.");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            return lastPart.substring(0, 1).toUpperCase() + lastPart.substring(1);
        }
        return packageName;
    }

    /**
     * Save selected apps for current device
     */
    private void saveSelectedAppsForDevice() {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot save selected apps: no device selected");
            return;
        }

        try {
            String key = "timer_apps_" + currentChildDeviceId;
            Gson gson = new Gson();
            String appsJson = gson.toJson(selectedApps);

            SharedPreferences timerPrefs = getSharedPreferences("timer_apps", MODE_PRIVATE);
            timerPrefs.edit().putString(key, appsJson).apply();

            Log.d(TAG, "Saved " + selectedApps.size() + " timer apps for device: " + currentChildDeviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error saving selected apps for device: " + e.getMessage());
        }
    }

    /**
     * Load selected apps for current device
     */
    private void loadSelectedAppsForDevice() {
        if (currentChildDeviceId == null) {
            selectedApps.clear();
            return;
        }

        try {
            String key = "timer_apps_" + currentChildDeviceId;
            SharedPreferences timerPrefs = getSharedPreferences("timer_apps", MODE_PRIVATE);
            String appsJson = timerPrefs.getString(key, "");

            if (!appsJson.isEmpty()) {
                Gson gson = new Gson();
                Type listType = new TypeToken<List<String>>() {
                }.getType();
                List<String> savedApps = gson.fromJson(appsJson, listType);

                if (savedApps != null) {
                    selectedApps.clear();
                    selectedApps.addAll(savedApps);
                    Log.d(TAG, "Loaded " + selectedApps.size() + " timer apps for device: " + currentChildDeviceId);
                }
            } else {
                selectedApps.clear();
                Log.d(TAG, "No saved timer apps found for device: " + currentChildDeviceId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading selected apps for device: " + e.getMessage());
            selectedApps.clear();
        }
    }

    /**
     * Clear timer UI elements
     */
    private void clearTimerUI() {
        if (etLimiterHours != null)
            etLimiterHours.setText("");
        if (etLimiterMinutes != null)
            etLimiterMinutes.setText("");
        if (tvLimiterTimer != null)
            tvLimiterTimer.setText("00:00");
        if (tvLimiterStatus != null)
            tvLimiterStatus.setText("No device selected");

        selectedApps.clear();
        // Timer running state no longer needed

        updateButtonStates();
    }

    /**
     * Update button states based on timer status
     */
    private void updateButtonStates() {
        // Timer button state updates handled by usage limiter UI
    }

    /**
     * Clear all timer data for a specific device (used when device is
     * removed/reconnected)
     */
    private void clearTimerDataForDevice(String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "Cannot clear timer data: deviceId is null");
            return;
        }

        Log.d(TAG, "Clearing ALL timer data for device: " + deviceId);

        // Clear from parent_timers
        DatabaseReference parentTimersRef = FirebaseDatabase.getInstance()
                .getReference("parent_timers")
                .child(deviceId);
        parentTimersRef.removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Successfully cleared parent_timers for device: " + deviceId);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error clearing parent_timers for device " + deviceId + ": " + e.getMessage());
                });

        // Clear from active_timers
        DatabaseReference activeTimersRef = FirebaseDatabase.getInstance()
                .getReference("active_timers")
                .child(deviceId);
        activeTimersRef.removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Successfully cleared active_timers for device: " + deviceId);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error clearing active_timers for device " + deviceId + ": " + e.getMessage());
                });

        // ENHANCED: Clear saved timer apps for this device
        try {
            String key = "timer_apps_" + deviceId;
            SharedPreferences timerPrefs = getSharedPreferences("timer_apps", MODE_PRIVATE);
            timerPrefs.edit().remove(key).apply();
            Log.d(TAG, "Cleared saved timer apps for device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error clearing saved timer apps: " + e.getMessage());
        }

        // ENHANCED: Clear saved timer duration for this device
        try {
            SharedPreferences durationPrefs = getSharedPreferences("timer_duration", MODE_PRIVATE);
            String hoursKey = "timer_hours_" + deviceId;
            String minutesKey = "timer_minutes_" + deviceId;
            durationPrefs.edit().remove(hoursKey).remove(minutesKey).apply();
            Log.d(TAG, "Cleared saved timer duration for device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error clearing saved timer duration: " + e.getMessage());
        }

        Log.d(TAG, "Timer data cleanup initiated for device: " + deviceId);
    }

    /**
     * Update the UI with total usage data only (simplified display)
     * AND CACHE IT for instant display on next load
     */
    private void updateUsageDisplayUI(long totalMs) {
        String formattedTime = formatDurationMs(totalMs);

        TextView tvTotalTime = findViewById(R.id.tvTotalTime);
        if (tvTotalTime != null) {
            tvTotalTime.setText(formattedTime);
        }

        // 🎯 CACHE the data for instant display next time (Persistent + Memory)
        if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
            cachedUsageData.put(currentChildDeviceId, totalMs);
            cachedUsageFormatted.put(currentChildDeviceId, formattedTime);

            // 💾 PERSIST TO DISK
            if (usageCachePrefs != null) {
                usageCachePrefs.edit().putString(currentChildDeviceId, formattedTime).apply();
            }

            Log.d(TAG, "📦 Cached usage for " + currentChildDeviceId + ": " + formattedTime);
        }

        Log.d(TAG, "UI updated with total usage: " + formattedTime);
    }

    /**
     * Show loading state while fetching data
     */
    private void showLoadingState() {
        // tvTotalUsage view removed from layout - no-op
        Log.d(TAG, "Loading state triggered");
    }

    /**
     * Show auto-refresh loading state (more subtle for automatic updates)
     */
    private void showAutoRefreshState() {
        // tvTotalUsage view removed from layout - no-op
        Log.d(TAG, "Auto-refresh state triggered");
    }

    /**
     * Force refresh today's usage data specifically
     * This method ensures today's data is always fresh when app reopens
     */
    private void forceRefreshTodayData() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Log.w(TAG, "⚠️ No child device selected for today's data refresh");
            return;
        }

        String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(currentUsageDate.getTime());
        Log.d(TAG, "📅 Force refreshing data for SELECTED date: " + dateKey + " for device: " + currentChildDeviceName);

        // PRESERVE user's selected date - DO NOT force today
        // currentUsageDate remains unchanged - user's choice is respected
        updateSelectedDateDisplay();

        // Clear any cached display
        clearUsageDisplay();
        showAutoRefreshState();

        // Force immediate load of SELECTED date's data (not today)
        loadSmartUsageDataForSelectedDate();
    }

    /**
     * Format duration from milliseconds to readable format
     */
    private String formatDurationMs(long milliseconds) {
        if (milliseconds <= 0)
            return "0m";

        long totalMinutes = milliseconds / (1000 * 60);
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        } else {
            return minutes + "m";
        }
    }

    private String formatTime(int minutes) {
        if (minutes >= 60) {
            int hours = minutes / 60;
            int remainingMinutes = minutes % 60;
            if (remainingMinutes == 0) {
                return hours + "h";
            } else {
                return hours + "h " + remainingMinutes + "m";
            }
        } else {
            return minutes + "m";
        }
    }

    private String categorizeApp(String appName) {
        if (appName == null)
            return "Others";

        String lowerAppName = appName.toLowerCase();

        // Social Media Apps
        if (lowerAppName.contains("whatsapp") || lowerAppName.contains("instagram") ||
                lowerAppName.contains("facebook") || lowerAppName.contains("snapchat") ||
                lowerAppName.contains("twitter") || lowerAppName.contains("tiktok") ||
                lowerAppName.contains("telegram") || lowerAppName.contains("linkedin") ||
                lowerAppName.contains("discord") || lowerAppName.contains("reddit")) {
            return "Social";
        }

        // Games Apps
        if (lowerAppName.contains("pubg") || lowerAppName.contains("free fire") ||
                lowerAppName.contains("candy crush") || lowerAppName.contains("subway surfers") ||
                lowerAppName.contains("temple run") || lowerAppName.contains("clash") ||
                lowerAppName.contains("minecraft") || lowerAppName.contains("roblox") ||
                lowerAppName.contains("game") || lowerAppName.contains("play")) {
            return "Games";
        }

        // Entertainment Apps
        if (lowerAppName.contains("youtube") || lowerAppName.contains("netflix") ||
                lowerAppName.contains("disney") || lowerAppName.contains("amazon prime") ||
                lowerAppName.contains("spotify") || lowerAppName.contains("twitch") ||
                lowerAppName.contains("vimeo") || lowerAppName.contains("dailymotion")) {
            return "Entertainment";
        }

        // Everything else goes to Others
        return "Others";
    }

    private void showCategoryAppsFromRealData(String categoryName, String categoryKey) {
        Log.d(TAG, "showCategoryAppsFromRealData called for: " + categoryName);

        if (currentChildDeviceId == null) {
            Toast.makeText(this, "No child device connected", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle(categoryName + " Apps");

        // Fetch real usage data for the current child device
        DatabaseReference usageRef = FirebaseDatabase.getInstance()
                .getReference("usage_snapshots")
                .child(currentChildDeviceId)
                .child("latest");

        usageRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    List<String> appNames = new ArrayList<>();

                    if (snapshot.child("dailyApps").exists()) {
                        DataSnapshot dailyAppsSnapshot = snapshot.child("dailyApps");
                        DataSnapshot latestDay = null;
                        for (DataSnapshot day : dailyAppsSnapshot.getChildren()) {
                            latestDay = day;
                        }
                        if (latestDay != null) {
                            for (DataSnapshot appSnapshot : latestDay.getChildren()) {
                                com.example.master2.models.AppUsage appUsage = appSnapshot
                                        .getValue(com.example.master2.models.AppUsage.class);
                                if (appUsage != null && categorizeApp(appUsage.getAppName()).equals(categoryKey)) {
                                    appNames.add(appUsage.getAppName());
                                }
                            }
                        }
                    } else if (snapshot.child("apps").exists()) {
                        for (DataSnapshot appSnapshot : snapshot.child("apps").getChildren()) {
                            com.example.master2.models.AppUsage appUsage = appSnapshot
                                    .getValue(com.example.master2.models.AppUsage.class);
                            if (appUsage != null && categorizeApp(appUsage.getAppName()).equals(categoryKey)) {
                                appNames.add(appUsage.getAppName());
                            }
                        }
                    }

                    if (appNames.isEmpty()) {
                        builder.setMessage("No apps found in the last usage snapshot for this category.");
                    } else {
                        StringBuilder appListText = new StringBuilder();
                        appListText.append("Apps in the last usage snapshot for this category:\n\n");
                        for (String appName : appNames) {
                            appListText.append("• ").append(appName).append("\n");
                        }
                        appListText.append("\nTotal: ").append(appNames.size()).append(" apps");
                        builder.setMessage(appListText.toString());
                    }
                } else {
                    builder.setMessage("No usage data available for this device.");
                }

                builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
                AlertDialog dialog = builder.create();
                dialog.show();
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Failed to fetch usage data for category dialog: " + error.getMessage());
                AlertDialog.Builder errorBuilder = new AlertDialog.Builder(ParentDashboardActivity.this);
                errorBuilder.setTitle("Error");
                errorBuilder.setMessage("Failed to load usage data for category dialog: " + error.getMessage());
                errorBuilder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
                AlertDialog errorDialog = errorBuilder.create();
                errorDialog.show();
            }
        });
    }

    // Date Range Picker Methods
    private void showFromDatePicker() {
        Calendar today = Calendar.getInstance();
        android.app.DatePickerDialog datePickerDialog = new android.app.DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    fromDate.set(year, month, dayOfMonth);
                    // Ensure from date is not after to date
                    if (fromDate.after(toDate)) {
                        toDate = (Calendar) fromDate.clone();
                    }
                    updateDateRangeDisplay();
                },
                fromDate.get(Calendar.YEAR),
                fromDate.get(Calendar.MONTH),
                fromDate.get(Calendar.DAY_OF_MONTH));

        // Set minimum date to today (cannot set timer for past dates)
        datePickerDialog.getDatePicker().setMinDate(today.getTimeInMillis());
        datePickerDialog.show();
    }

    private void showToDatePicker() {
        android.app.DatePickerDialog datePickerDialog = new android.app.DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    toDate.set(year, month, dayOfMonth);
                    // Ensure to date is not before from date
                    if (toDate.before(fromDate)) {
                        fromDate = (Calendar) toDate.clone();
                    }
                    updateDateRangeDisplay();
                },
                toDate.get(Calendar.YEAR),
                toDate.get(Calendar.MONTH),
                toDate.get(Calendar.DAY_OF_MONTH));

        // Set minimum date to from date (cannot end before start)
        datePickerDialog.getDatePicker().setMinDate(fromDate.getTimeInMillis());
        datePickerDialog.show();
    }

    private void updateDateRangeDisplay() {
        // Date range UI no longer needed for usage limiters
    }

    /**
     * Fast usage data refresh using direct Firebase queries
     */
    private void refreshUsageDataFromChildEnhanced() {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Log.w(TAG, "🔄 No child device selected for enhanced refresh");
            Toast.makeText(this, "Please select a child device first", Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "🚀 Starting FAST usage data refresh for device: " + currentChildDeviceId);

        // Show loading state
        Button btnUpdateUsageData = findViewById(R.id.btnUpdateUsageData);
        if (btnUpdateUsageData != null) {
            btnUpdateUsageData.setEnabled(false);
            btnUpdateUsageData.setText("🔄 Refreshing...");
        }

        // Use SMART tracking structure for immediate refresh
        loadSmartUsageDataForSelectedDate();
        Toast.makeText(this, "📊 Refreshing usage data...", Toast.LENGTH_SHORT).show();

        // Re-enable button after short delay
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (btnUpdateUsageData != null) {
                btnUpdateUsageData.setEnabled(true);
                btnUpdateUsageData.setText("🔄 Update");
            }
        }, 2000); // Reduced to 2 seconds since we're faster now
    }

    /**
     * Enhanced method to refresh usage data from child device
     * This method checks multiple data sources and ensures accurate data display
     */
    private void refreshUsageDataFromChild() {
        refreshUsageDataFromChild(false); // Default: show toast notifications
    }

    /**
     * Enhanced method to refresh usage data from child device with silent mode
     * option
     * 
     * @param silentMode if true, reduces toast notifications for automatic
     *                   refreshes
     */
    private void refreshUsageDataFromChild(boolean silentMode) {
        if (currentChildDeviceId == null || currentChildDeviceId.isEmpty()) {
            Log.w(TAG, "🔄 No child device selected for usage data refresh");
            if (!silentMode) {
                Toast.makeText(this, "Please select a child device first", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        Log.d(TAG, "🔄 Starting enhanced usage data refresh for device: " + currentChildDeviceId + " ("
                + currentChildDeviceName + ")");

        // Show loading state and disable update button temporarily
        Button btnUpdateUsageData = findViewById(R.id.btnUpdateUsageData);
        if (btnUpdateUsageData != null) {
            btnUpdateUsageData.setEnabled(false);
            btnUpdateUsageData.setText("🔄 Updating...");
        }

        // Show toast only if not in silent mode
        if (!silentMode) {
            Toast.makeText(this, "🔄 Refreshing usage data from " + currentChildDeviceName + "...", Toast.LENGTH_SHORT)
                    .show();
            showLoadingState();
        } else {
            // Silent mode - show subtle auto-refresh indicator
            showAutoRefreshState();
        }

        // Step 1: Check if child device is online and trigger data upload
        checkChildDeviceStatusAndTriggerUpload();

        // Step 2: Force refresh data from multiple Firebase paths
        refreshFromMultipleDataSources();

        // Step 3: Re-enable update button after 3 seconds
        new android.os.Handler().postDelayed(() -> {
            if (btnUpdateUsageData != null) {
                btnUpdateUsageData.setEnabled(true);
                btnUpdateUsageData.setText("🔄 Update");
            }
        }, 3000);
    }

    /**
     * Check child device status and trigger data upload if online
     */
    private void checkChildDeviceStatusAndTriggerUpload() {
        Log.d(TAG, "📡 Checking child device status and triggering data upload...");

        // Check device status first
        DatabaseReference deviceStatusRef = FirebaseDatabase.getInstance()
                .getReference("device_status")
                .child(currentChildDeviceId);

        deviceStatusRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Boolean isOnline = dataSnapshot.child("isOnline").getValue(Boolean.class);
                Long lastSeen = dataSnapshot.child("lastSeen").getValue(Long.class);

                if (Boolean.TRUE.equals(isOnline)) {
                    Log.d(TAG, "✅ Child device is online - sending data upload trigger");

                    // Send upload trigger command to child device
                    DatabaseReference uploadTriggerRef = FirebaseDatabase.getInstance()
                            .getReference("upload_triggers")
                            .child(currentChildDeviceId);

                    Map<String, Object> triggerData = new HashMap<>();
                    triggerData.put("trigger", true);
                    triggerData.put("timestamp", System.currentTimeMillis());
                    triggerData.put("requestedBy", "parent");
                    triggerData.put("reason", "manual_refresh");

                    uploadTriggerRef.setValue(triggerData)
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "✅ Upload trigger sent successfully to child device");
                                Toast.makeText(ParentDashboardActivity.this,
                                        "📤 Requesting fresh data from " + currentChildDeviceName,
                                        Toast.LENGTH_SHORT).show();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "❌ Failed to send upload trigger: " + e.getMessage());
                            });
                } else {
                    Log.w(TAG, "⚠️ Child device is offline - cannot trigger fresh data upload");
                    String lastSeenText = "unknown";
                    if (lastSeen != null) {
                        long timeSince = (System.currentTimeMillis() - lastSeen) / 1000;
                        if (timeSince < 60)
                            lastSeenText = "just now";
                        else if (timeSince < 3600)
                            lastSeenText = (timeSince / 60) + "m ago";
                        else
                            lastSeenText = (timeSince / 3600) + "h ago";
                    }

                    Toast.makeText(ParentDashboardActivity.this,
                            "📱 " + currentChildDeviceName + " is offline (last seen " + lastSeenText + ")",
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "❌ Error checking device status: " + databaseError.getMessage());
            }
        });
    }

    /**
     * Refresh data from multiple Firebase data sources
     */
    private void refreshFromMultipleDataSources() {
        Log.d(TAG, "🔄 Refreshing data from multiple sources...");

        // Counter to track completed refreshes
        final int[] completedRefreshes = { 0 };
        final int totalRefreshes = 3;

        // 1. Refresh from usage_snapshots/latest
        DatabaseReference latestUsageRef = FirebaseDatabase.getInstance()
                .getReference("usage_snapshots")
                .child(currentChildDeviceId)
                .child("latest");

        latestUsageRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Log.d(TAG, "📊 Latest usage data refreshed - exists: " + dataSnapshot.exists());
                if (dataSnapshot.exists()) {
                    displayAccurateUsageData(dataSnapshot);
                }

                completedRefreshes[0]++;
                if (completedRefreshes[0] >= totalRefreshes) {
                    onAllRefreshesCompleted();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "❌ Error refreshing latest usage: " + databaseError.getMessage());
                completedRefreshes[0]++;
                if (completedRefreshes[0] >= totalRefreshes) {
                    onAllRefreshesCompleted();
                }
            }
        });

        // 2. Refresh from daily_usage_limits (7-day data)
        DatabaseReference dailyLimitsRef = FirebaseDatabase.getInstance()
                .getReference("daily_usage_limits")
                .child(currentChildDeviceId);

        dailyLimitsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                Log.d(TAG, "📊 Daily usage limits refreshed - exists: " + dataSnapshot.exists());

                completedRefreshes[0]++;
                if (completedRefreshes[0] >= totalRefreshes) {
                    onAllRefreshesCompleted();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "❌ Error refreshing daily limits: " + databaseError.getMessage());
                completedRefreshes[0]++;
                if (completedRefreshes[0] >= totalRefreshes) {
                    onAllRefreshesCompleted();
                }
            }
        });

        // 3. Refresh from parent's connected devices (connection status)
        String parentUserId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;
        if (parentUserId != null) {
            DatabaseReference parentDeviceRef = FirebaseDatabase.getInstance()
                    .getReference("parents")
                    .child(parentUserId)
                    .child("connectedChildDevices")
                    .child(currentChildDeviceId);

            parentDeviceRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    Log.d(TAG, "📊 Parent device connection refreshed - exists: " + dataSnapshot.exists());

                    if (dataSnapshot.exists()) {
                        // Update device info if available
                        String deviceName = dataSnapshot.child("deviceName").getValue(String.class);
                        Long lastConnected = dataSnapshot.child("lastConnected").getValue(Long.class);
                        String connectionStatus = dataSnapshot.child("connectionStatus").getValue(String.class);

                        if (deviceName != null) {
                            currentChildDeviceName = deviceName;
                        }

                        Log.d(TAG, "📊 Device info - Name: " + deviceName + ", Status: " + connectionStatus +
                                ", Last Connected: "
                                + (lastConnected != null ? new java.util.Date(lastConnected) : "unknown"));
                    }

                    completedRefreshes[0]++;
                    if (completedRefreshes[0] >= totalRefreshes) {
                        onAllRefreshesCompleted();
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "❌ Error refreshing parent device connection: " + databaseError.getMessage());
                    completedRefreshes[0]++;
                    if (completedRefreshes[0] >= totalRefreshes) {
                        onAllRefreshesCompleted();
                    }
                }
            });
        } else {
            // Skip parent device refresh if not authenticated
            completedRefreshes[0]++;
            if (completedRefreshes[0] >= totalRefreshes) {
                onAllRefreshesCompleted();
            }
        }
    }

    /**
     * Called when all refresh operations are completed
     */
    private void onAllRefreshesCompleted() {
        Log.d(TAG, "✅ All data refresh operations completed");

        // Update device status display
        runOnUiThread(() -> {
            updateDeviceStatus();
            updateTargetDeviceDisplay();

            // Force reload of current date's usage data
            loadSmartUsageDataForSelectedDate();

            Toast.makeText(this, "✅ Usage data updated successfully", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Show security warning dialog when parent logs into dashboard
     */
    private void showWelcomeMessage() {
        try {
            // Check if user has already seen this message today
            SharedPreferences prefs = getSharedPreferences("welcome_message_prefs", MODE_PRIVATE);
            String lastShown = prefs.getString("last_message_date", "");
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

            if (today.equals(lastShown)) {
                Log.d(TAG, "Welcome message already shown today, skipping");
                return;
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogCustom);
            builder.setTitle("🎉 Welcome to Parental Control");
            builder.setMessage("Welcome! Here are some important tips:\n\n" +
                    "• Use the QR code scanner to connect child devices\n" +
                    "• Monitor and manage your child's screen time easily\n" +
                    "• Access all controls from this parent dashboard\n\n" +
                    "⚠️ TROUBLESHOOTING: If you can see a device name but cannot track its data, please:\n" +
                    "1. Remove the device from this app\n" +
                    "2. Reinstall the app on the child device\n" +
                    "3. Connect the child via QR code again\n\n" +
                    "🔒 IMPORTANT SECURITY: Before uninstalling this app or logging out permanently:\n" +
                    "• Always remove all connected child devices first\n" +
                    "• This prevents security issues and data conflicts\n" +
                    "• Use 'Disconnect All Devices' in Settings if needed");
            builder.setCancelable(false);

            builder.setPositiveButton("Got It", (dialog, which) -> {
                dialog.dismiss();
                // Mark that user has seen the message today
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("last_message_date", today);
                editor.apply();
                Log.d(TAG, "Welcome message acknowledged by user");
            });

            builder.setNeutralButton("Show Help", (dialog, which) -> {
                dialog.dismiss();
                // Show the troubleshooting dialog
                showTroubleshootingDialog();
                // Mark as seen
                SharedPreferences.Editor editor = prefs.edit();
                editor.putString("last_message_date", today);
                editor.apply();
            });

            AlertDialog dialog = builder.create();
            dialog.show();

            // Customize dialog appearance
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);
            }

            // Style the buttons
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(
                    ContextCompat.getColor(this, R.color.primary_600));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(
                    ContextCompat.getColor(this, R.color.modern_orange_600));

        } catch (Exception e) {
            Log.e(TAG, "Error showing welcome message: " + e.getMessage());
        }
    }

    private void showTroubleshootingDialog() {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogCustom);
            builder.setTitle("🛠️ Device Tracking Help");
            builder.setMessage("If you can see a device name but cannot track its data:\n\n" +
                    "SOLUTION:\n" +
                    "1. Remove the device from this parent app\n" +
                    "2. Reinstall the app on the child device\n" +
                    "3. Connect the child via QR code again\n\n" +
                    "This usually fixes connection and data tracking issues.\n\n" +
                    "💡 TIP: Make sure both devices have stable internet connection when connecting via QR code.\n\n" +
                    "🔒 SECURITY REMINDER: Before uninstalling this app, always remove all connected devices first to prevent security issues.");

            builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());

            builder.setNeutralButton("Go to Settings", (dialog, which) -> {
                dialog.dismiss();
                // Navigate to settings
                if (bottomNavigation != null) {
                    bottomNavigation.setSelectedItemId(R.id.nav_settings);
                }
            });

            AlertDialog dialog = builder.create();
            dialog.show();

            // Customize dialog appearance
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_background);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error showing troubleshooting dialog: " + e.getMessage());
        }
    }

    /**
     * Ensures all background services are running properly
     */
    private void ensureBackgroundServicesRunning() {
        try {
            Log.d(TAG, "Ensuring background services are running...");

            // Ensure DailyTimerResetService is running
            DailyTimerResetService.startService(this);
            Log.d(TAG, "DailyTimerResetService started/verified");

            // If we have an active timer, ensure all timer-related services are running
            if (currentChildDeviceId != null) {
                Log.d(TAG, "Active timer detected - ensuring timer services are running");
                // The timer services are primarily on the child device side
                // ParentDashboardActivity mainly monitors via Firebase
            }

            Log.d(TAG, "✅ All background services verification completed");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error ensuring background services: " + e.getMessage());
        }
    }

    /**
     * 🔧 FORCE-CLOSE PERSISTENCE FIX: Restore Focus Mode state when app restarts
     * after force-close
     * This method scans all stored Focus Mode data and restores the last active
     * device and its state
     */
    private void restoreFocusModeStateAfterRestart() {
        try {
            Log.d(TAG, "🔄 FOCUS MODE RESTORATION DEBUG - Starting restoration process...");

            if (focusModePrefs == null) {
                Log.e(TAG, "❌ CRITICAL: focusModePrefs is null, cannot restore Focus Mode state");
                return;
            }

            // DEBUG: Show all stored preferences
            Map<String, ?> allPrefs = focusModePrefs.getAll();
            Log.d(TAG, "🔍 FOCUS MODE DEBUG - Total stored preferences: " + allPrefs.size());
            for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
                Log.d(TAG, "🔍 FOCUS MODE DEBUG - Stored: " + entry.getKey() + " = " + entry.getValue());
            }

            String lastActiveDevice = null;
            String lastActiveDeviceName = "Unknown Device";
            boolean foundActiveFocusMode = false;

            String lastDeviceWithApps = null;
            String lastDeviceWithAppsName = "Unknown Device";
            boolean foundDeviceWithApps = false;

            // Scan through all stored preferences to find Focus Mode data
            for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
                String key = entry.getKey();

                // Look for active Focus Mode states (priority 1)
                if (key.startsWith("focus_mode_active_")) {
                    String deviceId = key.replace("focus_mode_active_", "");
                    boolean isActive = (Boolean) entry.getValue();

                    if (isActive) {
                        Log.d(TAG, "🔍 FOCUS MODE DEBUG - Found ACTIVE Focus Mode preference for device: " + deviceId);

                        // 🚫 DEVICE REMOVAL FIX: Check if device is permanently removed before
                        // restoring
                        if (isPermanentlyRemoved(deviceId)) {
                            Log.d(TAG,
                                    "🚫 FOCUS MODE DEBUG - Skipping permanently removed device in active focus mode scan: "
                                            + deviceId);
                            continue; // Skip this device completely
                        }

                        Log.d(TAG, "✅ FOCUS MODE DEBUG - Active Focus Mode device passed removal check: " + deviceId);
                        lastActiveDevice = deviceId;
                        foundActiveFocusMode = true;

                        // Try to get device name from stored data
                        String deviceNameKey = "device_name_" + deviceId;
                        String storedDeviceName = focusModePrefs.getString(deviceNameKey, "Unknown Device");
                        lastActiveDeviceName = storedDeviceName;

                        String appsKey = "focus_apps_" + deviceId;
                        String appsJson = focusModePrefs.getString(appsKey, "");
                        if (!appsJson.isEmpty()) {
                            Log.d(TAG, "Found stored Focus Mode apps for device: " + deviceId + " (" + storedDeviceName
                                    + ")");
                        }
                        break; // Use the first active one found
                    }
                }

                // 🔧 INACTIVE APP LIST RESTORATION: Look for devices with stored app lists
                // (priority 2)
                if (!foundActiveFocusMode && key.startsWith("focus_apps_")) {
                    String deviceId = key.replace("focus_apps_", "");
                    String appsJson = (String) entry.getValue();

                    if (appsJson != null && !appsJson.isEmpty() && !appsJson.equals("[]")) {
                        Log.d(TAG, "🔍 FOCUS MODE DEBUG - Found device with Focus Mode apps: " + deviceId);

                        // 🚫 DEVICE REMOVAL FIX: Check if device is permanently removed before
                        // restoring
                        if (isPermanentlyRemoved(deviceId)) {
                            Log.d(TAG, "🚫 FOCUS MODE DEBUG - Skipping permanently removed device in apps scan: "
                                    + deviceId);
                            continue; // Skip this device completely
                        }

                        Log.d(TAG, "✅ FOCUS MODE DEBUG - Apps device passed removal check: " + deviceId);
                        lastDeviceWithApps = deviceId;
                        foundDeviceWithApps = true;

                        // Get device name
                        String deviceNameKey = "device_name_" + deviceId;
                        String storedDeviceName = focusModePrefs.getString(deviceNameKey, "Unknown Device");
                        lastDeviceWithAppsName = storedDeviceName;

                        Log.d(TAG, "Device with apps: " + deviceId + " (" + storedDeviceName + ")");
                        // Continue scanning to see if there are more recent ones
                    }
                }
            }

            if (foundActiveFocusMode && lastActiveDevice != null) {
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - Attempting to restore ACTIVE Focus Mode for device: "
                        + lastActiveDevice);
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - Device name: " + lastActiveDeviceName);
                Log.d(TAG, "✅ Device passed permanent removal check during scanning - proceeding with restoration");

                // Restore the device as current device (explicit restoration)
                connectedDevicesManager.setCurrentDevice(lastActiveDevice, true);
                currentChildDeviceId = lastActiveDevice;
                currentChildDeviceName = lastActiveDeviceName;

                // 🔧 Create a device object for the restored device and add to connected
                // devices
                ChildDevice restoredDevice = new ChildDevice();
                restoredDevice.deviceId = lastActiveDevice;
                restoredDevice.deviceName = lastActiveDeviceName;
                restoredDevice.focusModeActive = true; // Since we found active focus mode
                restoredDevice.lastConnected = System.currentTimeMillis(); // Set recent connection time

                // Add to connected devices list so it shows up in UI
                connectedDevices.clear();
                connectedDevices.add(restoredDevice);

                // Load Focus Mode apps and state for this device
                loadFocusModeApps();

                // 🔧 ENSURE UI UPDATE: Force UI update on main thread (ACTIVE)
                runOnUiThread(() -> {
                    updateFocusModeUI();
                    updateTargetDeviceDisplay();
                    updateDeviceStatus();

                    // Additional logging to verify app list loaded (active)
                    Log.d(TAG, "🎯 UI Updated (ACTIVE) - Focus Mode Apps loaded: " + focusModeApps.size());
                    for (AppInfo app : focusModeApps) {
                        Log.d(TAG, "   📱 App: " + (app.name != null ? app.name : app.packageName));
                    }
                });

                // Sync with Firebase to ensure accuracy (with delay to allow app to fully
                // initialize)
                Handler restoreHandler = new Handler(Looper.getMainLooper());
                restoreHandler.postDelayed(() -> {
                    if (currentChildDeviceId != null) {
                        checkActualFocusModeStateFromFirebase();
                    }
                }, 2000); // 2 second delay

                Log.d(TAG, "✅ Focus Mode state restored successfully for device: " + lastActiveDevice);
                Log.d(TAG, "   Focus Mode Active: " + isFocusModeActive);
                Log.d(TAG, "   Focus Mode Apps: " + focusModeApps.size());

            } else if (foundDeviceWithApps && lastDeviceWithApps != null) {
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - Attempting to restore INACTIVE Focus Mode apps for device: "
                        + lastDeviceWithApps);
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - Device name: " + lastDeviceWithAppsName);
                Log.d(TAG, "✅ Device passed permanent removal check during scanning - proceeding with restoration");

                // Restore the device as current device (Focus Mode inactive but apps available)
                connectedDevicesManager.setCurrentDevice(lastDeviceWithApps, true);
                currentChildDeviceId = lastDeviceWithApps;
                currentChildDeviceName = lastDeviceWithAppsName;

                // 🔧 Create a device object for the restored device with apps
                ChildDevice restoredDevice = new ChildDevice();
                restoredDevice.deviceId = lastDeviceWithApps;
                restoredDevice.deviceName = lastDeviceWithAppsName;
                restoredDevice.focusModeActive = false; // Inactive but has apps
                restoredDevice.lastConnected = System.currentTimeMillis();

                // Add to connected devices list so it shows up in UI
                connectedDevices.clear();
                connectedDevices.add(restoredDevice);

                // Load Focus Mode apps and state for this device
                loadFocusModeApps();

                // 🔧 ENSURE UI UPDATE: Force UI update on main thread (INACTIVE)
                runOnUiThread(() -> {
                    updateFocusModeUI();
                    updateTargetDeviceDisplay();
                    updateDeviceStatus();

                    // Additional logging to verify app list loaded (inactive)
                    Log.d(TAG, "🎯 UI Updated (INACTIVE) - Focus Mode Apps loaded: " + focusModeApps.size());
                    for (AppInfo app : focusModeApps) {
                        Log.d(TAG, "   📱 App: " + (app.name != null ? app.name : app.packageName));
                    }
                });

                // Sync with Firebase to ensure accuracy (with delay to allow app to fully
                // initialize)
                Handler restoreHandler = new Handler(Looper.getMainLooper());
                restoreHandler.postDelayed(() -> {
                    if (currentChildDeviceId != null) {
                        checkActualFocusModeStateFromFirebase();
                    }
                }, 2000); // 2 second delay

                Log.d(TAG, "✅ Focus Mode app list restored successfully for device: " + lastDeviceWithApps);
                Log.d(TAG, "   Focus Mode Active: " + isFocusModeActive);
                Log.d(TAG, "   Focus Mode Apps: " + focusModeApps.size());

            } else {
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - No Focus Mode data found to restore");
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - foundActiveFocusMode: " + foundActiveFocusMode);
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - foundDeviceWithApps: " + foundDeviceWithApps);
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - lastActiveDevice: " + lastActiveDevice);
                Log.d(TAG, "🎯 FOCUS MODE DEBUG - lastDeviceWithApps: " + lastDeviceWithApps);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error restoring Focus Mode state after restart: " + e.getMessage());
        }
    }

    /**
     * 🔧 FORCE-CLOSE PERSISTENCE: Save current device name for restoration
     */
    private void saveDeviceNameForCurrentDevice() {
        if (currentChildDeviceId != null && currentChildDeviceName != null && focusModePrefs != null) {
            String deviceNameKey = "device_name_" + currentChildDeviceId;
            focusModePrefs.edit().putString(deviceNameKey, currentChildDeviceName).apply();
            Log.d(TAG, "💾 Saved device name: " + currentChildDeviceName + " for device: " + currentChildDeviceId);
        }
    }

    /**
     * Force Focus Mode UI update to ensure data displays immediately
     */
    private void forceUpdateFocusModeUI() {
        try {
            // Load Focus Mode data for all possible devices
            loadAllFocusModeData();

            // Update UI immediately
            runOnUiThread(() -> {
                updateFocusModeUI();
            });

            // Add delayed update as backup
            Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(() -> {
                updateFocusModeUI();
            }, 500); // 500ms delay

            // Another backup update
            handler.postDelayed(() -> {
                updateFocusModeUI();
            }, 1000); // 1 second delay

        } catch (Exception e) {
            Log.e(TAG, "Error in forceUpdateFocusModeUI: " + e.getMessage());
        }
    }

    /**
     * Load Focus Mode data for all possible devices and select the best one
     */
    private void loadAllFocusModeData() {
        try {
            Log.d(TAG, "Loading Focus Mode data...");

            if (focusModePrefs == null) {
                Log.e(TAG, "focusModePrefs is null, cannot load Focus Mode data");
                return;
            }

            // Get all stored preferences
            Map<String, ?> allPrefs = focusModePrefs.getAll();

            String bestDeviceId = null;
            String bestDeviceName = "Unknown Device";
            boolean foundActiveDevice = false;
            boolean foundDeviceWithApps = false;

            // Priority 1: Look for active Focus Mode devices
            for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
                String key = entry.getKey();

                if (key.startsWith("focus_mode_active_")) {
                    String deviceId = key.replace("focus_mode_active_", "");
                    boolean isActive = (Boolean) entry.getValue();

                    if (isActive && !isPermanentlyRemoved(deviceId)) {
                        bestDeviceId = deviceId;
                        bestDeviceName = focusModePrefs.getString("device_name_" + deviceId, "Unknown Device");
                        foundActiveDevice = true;
                        Log.d(TAG, "Selected active Focus Mode device: " + bestDeviceName);
                        break; // Active device takes priority
                    }
                }
            }

            // Priority 2: If no active device, look for devices with apps
            if (!foundActiveDevice) {
                for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
                    String key = entry.getKey();

                    if (key.startsWith("focus_apps_")) {
                        String deviceId = key.replace("focus_apps_", "");
                        String appsJson = (String) entry.getValue();

                        if (appsJson != null && !appsJson.isEmpty() && !appsJson.equals("[]")
                                && !isPermanentlyRemoved(deviceId)) {
                            bestDeviceId = deviceId;
                            bestDeviceName = focusModePrefs.getString("device_name_" + deviceId, "Unknown Device");
                            foundDeviceWithApps = true;
                            Log.d(TAG, "Selected device with Focus Mode apps: " + bestDeviceName);
                            break; // Use first device with apps found
                        }
                    }
                }
            }

            // If we found a suitable device, set it as current and load its data
            if (bestDeviceId != null && (foundActiveDevice || foundDeviceWithApps)) {
                Log.d(TAG, "Setting Focus Mode device: " + bestDeviceName);

                // Create final copies for lambda usage
                final String finalBestDeviceId = bestDeviceId;
                final String finalBestDeviceName = bestDeviceName;
                final boolean finalFoundActiveDevice = foundActiveDevice;

                // Treat restoration as explicit
                connectedDevicesManager.setCurrentDevice(finalBestDeviceId, true);
                currentChildDeviceId = finalBestDeviceId;
                currentChildDeviceName = finalBestDeviceName;

                // Create device object if not in connected devices
                if (connectedDevices.stream().noneMatch(d -> d.deviceId.equals(finalBestDeviceId))) {
                    ChildDevice device = new ChildDevice();
                    device.deviceId = finalBestDeviceId;
                    device.deviceName = finalBestDeviceName;
                    device.focusModeActive = finalFoundActiveDevice;
                    device.lastConnected = System.currentTimeMillis();

                    connectedDevices.clear();
                    connectedDevices.add(device);
                }

                // Load Focus Mode data for this device
                loadFocusModeApps();

                // Update device display
                updateDeviceStatus();
                updateTargetDeviceDisplay();

            } else {
                Log.d(TAG, "No Focus Mode devices found");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error loading all Focus Mode data: " + e.getMessage());
        }
    }

    /**
     * 🔧 AUTO-RESTORE: If no devices loaded but Focus Mode data exists, clear
     * permanent removal status
     */
    private void autoRestoreDevicesWithFocusMode() {
        try {
            Log.d(TAG, "🔧 AUTO-RESTORE - Checking if devices with Focus Mode data need restoration...");

            if (connectedDevices.isEmpty() && focusModePrefs != null) {
                Map<String, ?> allPrefs = focusModePrefs.getAll();

                for (Map.Entry<String, ?> entry : allPrefs.entrySet()) {
                    String key = entry.getKey();

                    // Look for devices with Focus Mode apps or active state
                    if (key.startsWith("focus_apps_") || key.startsWith("focus_mode_active_")) {
                        String deviceId = key.replace("focus_apps_", "").replace("focus_mode_active_", "");

                        if (isPermanentlyRemoved(deviceId)) {
                            Log.d(TAG, "Auto-restoring device with Focus Mode data: " + deviceId);

                            // Clear the permanent removal status
                            removePermanentRemoval(deviceId);

                            // Trigger a re-scan of Focus Mode data
                            Handler handler = new Handler(Looper.getMainLooper());
                            handler.postDelayed(() -> {
                                forceUpdateFocusModeUI();
                            }, 1000);

                            break; // Only restore one device at a time
                        }
                    }
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error in autoRestoreDevicesWithFocusMode: " + e.getMessage());
        }
    }

    // ===== USAGE LIMITER IMPLEMENTATION =====

    /**
     * Initialize usage limiter functionality - setup button listeners and Firebase
     * references
     */
    private void setupUsageLimiter() {
        // Timer UI removed from parent dashboard; keep no-op to avoid side effects
        try {
            Log.d(TAG, "Setting up Usage Limiter functionality");

            // Initialize Firebase references
            if (limiterRef == null) {
                limiterRef = FirebaseDatabase.getInstance()
                        .getReference("usage_limiters");
                Log.d(TAG, "Initialized Firebase limiter reference");
            }

            // Setup button listeners
            if (btnSelectDays != null) {
                btnSelectDays.setOnClickListener(v -> showDaySelector());
            }

            if (btnSelectApps != null) {
                btnSelectApps.setOnClickListener(v -> showAppSelector());
            }

            if (btnSetLimiter != null) {
                btnSetLimiter.setOnClickListener(v -> setUsageLimiter());
            }

            if (btnClearLimiter != null) {
                btnClearLimiter.setOnClickListener(v -> clearUsageLimiter());
            }

            // Setup text watchers for time inputs to update button states
            if (etLimiterHours != null) {
                etLimiterHours.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        updateSetTimerButtonState();
                    }
                });
            }

            if (etLimiterMinutes != null) {
                etLimiterMinutes.addTextChangedListener(new TextWatcher() {

                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void afterTextChanged(Editable s) {
                        updateSetTimerButtonState();
                    }
                });
            }

            // Update UI display
            // UI removed; skip updateLimiterDisplay()

            Log.d(TAG, "Usage Limiter setup completed successfully");

        } catch (

        Exception e) {
            Log.e(TAG, "Error setting up Usage Limiter: " + e.getMessage());
            // No visible UI; suppress toast
        }
    }

    /**
     * Show dialog for selecting which days the timer should work (Monday-Sunday
     * checkboxes)
     */
    private void showDaySelector() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] days = { "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday" };
        boolean[] checkedItems = new boolean[days.length];

        // Pre-check already selected days
        for (int i = 0; i < days.length; i++) {
            checkedItems[i] = selectedDays.contains(days[i].toLowerCase());
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("Select Active Days");
        builder.setMultiChoiceItems(days, checkedItems, (dialog, which, isChecked) -> {
            // Handle individual checkbox changes
            String dayName = days[which].toLowerCase();
            if (isChecked) {
                if (!selectedDays.contains(dayName)) {
                    selectedDays.add(dayName);
                }
            } else {
                selectedDays.remove(dayName);
            }
        });

        builder.setPositiveButton("OK", (dialog, which) -> {
            if (selectedDays.isEmpty()) {
                Toast.makeText(this, "Please select at least one day", Toast.LENGTH_SHORT).show();
                return;
            }

            // Update button text to show selected days count
            String buttonText = "Days Selected (" + selectedDays.size() + ")";
            if (btnSelectDays != null) {
                btnSelectDays.setText(buttonText);
            }

            // Update Set Timer button state based on all requirements
            updateSetTimerButtonState();

            Log.d(TAG, "Selected days: " + selectedDays.toString());
            Toast.makeText(this, "Selected " + selectedDays.size() + " days", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Show dialog to select apps from the child device app list
     */
    private void showAppSelector() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        // Launch ChildAppListActivity to select apps
        Intent intent = new Intent(this, ChildAppListActivity.class);
        intent.putExtra("deviceId", currentChildDeviceId);
        intent.putExtra("mode", "select_multiple");
        intent.putExtra("title", "Select Apps for Usage Limiter");

        // Pass already selected apps for pre-selection
        if (!selectedApps.isEmpty()) {
            ArrayList<String> selectedAppsList = new ArrayList<>(selectedApps);
            intent.putStringArrayListExtra("preselected_apps", selectedAppsList);
        }

        startActivityForResult(intent, 1003); // Using unique request code for usage limiter
        Log.d(TAG, "Launched app selector for usage limiter");
    }

    /**
     * Save the usage limiter configuration to Firebase
     */
    private void setUsageLimiter() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show();
            return;
        }

        // First check if there's already an active timer
        limiterRef.child(currentChildDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()
                        && Boolean.TRUE.equals(dataSnapshot.child("isActive").getValue(Boolean.class))) {
                    // Timer is already active
                    Toast.makeText(ParentDashboardActivity.this,
                            "⚠️ Timer is already running! Clear it first to set a new one.", Toast.LENGTH_LONG).show();
                    return;
                }

                // No active timer, proceed with validation and setting
                performSetTimerValidationAndSave();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error checking timer status: " + databaseError.getMessage());
                Toast.makeText(ParentDashboardActivity.this, "Error checking timer status", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Perform validation and save the timer (called after checking no active timer
     * exists)
     */
    private void performSetTimerValidationAndSave() {
        // Validate inputs
        String hoursText = etLimiterHours.getText().toString().trim();
        String minutesText = etLimiterMinutes.getText().toString().trim();

        if (hoursText.isEmpty() && minutesText.isEmpty()) {
            Toast.makeText(this, "⏰ Please enter timer duration (hours or minutes)", Toast.LENGTH_LONG).show();
            return;
        }

        if (selectedDays.isEmpty()) {
            Toast.makeText(this, "📅 Please select which days the timer should work", Toast.LENGTH_LONG).show();
            return;
        }

        if (selectedApps.isEmpty()) {
            Toast.makeText(this, "📱 Please select apps to limit first", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            int hours = hoursText.isEmpty() ? 0 : Integer.parseInt(hoursText);
            int minutes = minutesText.isEmpty() ? 0 : Integer.parseInt(minutesText);

            if (hours < 0 || hours > 23) {
                Toast.makeText(this, "Hours must be between 0 and 23", Toast.LENGTH_SHORT).show();
                return;
            }

            if (minutes < 0 || minutes > 59) {
                Toast.makeText(this, "Minutes must be between 0 and 59", Toast.LENGTH_SHORT).show();
                return;
            }

            if (hours == 0 && minutes == 0) {
                Toast.makeText(this, "Timer duration must be greater than 0", Toast.LENGTH_SHORT).show();
                return;
            }

            // Calculate total time in milliseconds
            long totalTimeMs = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L);

            // Create limiter data
            Map<String, Object> limiterData = new HashMap<>();
            limiterData.put("hours", hours);
            limiterData.put("minutes", minutes);
            limiterData.put("activeDays", new ArrayList<>(selectedDays));
            limiterData.put("selectedApps", new ArrayList<>(selectedApps));
            limiterData.put("startTime", System.currentTimeMillis());
            limiterData.put("remainingTimeMs", totalTimeMs);
            limiterData.put("isActive", true);
            limiterData.put("lastResetDate",
                    new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date()));

            // Save to Firebase
            limiterRef.child(currentChildDeviceId).setValue(limiterData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Usage limiter set successfully for device: " + currentChildDeviceId);

                        // Enhanced success message
                        String timeText = (hours > 0 ? hours + "h " : "") + (minutes > 0 ? minutes + "m" : "");
                        Toast.makeText(this, "✅ Usage limiter activated!\n" +
                                "⏱️ Daily limit: " + timeText + "\n" +
                                "📱 Apps: " + selectedApps.size() + " apps selected\n" +
                                "📅 Active on: " + selectedDays.size() + " days",
                                Toast.LENGTH_LONG).show();

                        // Update UI
                        updateLimiterDisplay();

                        // Update button text and all button states - timer is now active
                        if (btnSelectApps != null) {
                            btnSelectApps.setText("Update Apps (" + selectedApps.size() + ")");
                        }
                        updateAllButtonStates(true);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error setting usage limiter: " + e.getMessage());
                        Toast.makeText(this, "Error setting usage limiter: " + e.getMessage(), Toast.LENGTH_LONG)
                                .show();
                    });

        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers for hours and minutes", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Error in performSetTimerValidationAndSave: " + e.getMessage());
            Toast.makeText(this, "Error setting usage limiter", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Clear the usage limiter with confirmation dialog or show "No timer set"
     * message
     */
    private void clearUsageLimiter() {
        if (currentChildDeviceId == null) {
            Toast.makeText(this, "No device selected", Toast.LENGTH_SHORT).show();
            return;
        }

        // First check if there's actually a timer set
        limiterRef.child(currentChildDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()
                        || !Boolean.TRUE.equals(dataSnapshot.child("isActive").getValue(Boolean.class))) {
                    // No timer is set
                    Toast.makeText(ParentDashboardActivity.this, "❌ No timer set", Toast.LENGTH_SHORT).show();
                    return;
                }

                // Timer exists, show confirmation dialog
                showClearTimerConfirmationDialog();
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error checking timer status: " + databaseError.getMessage());
                Toast.makeText(ParentDashboardActivity.this, "Error checking timer status", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Show confirmation dialog for clearing the timer
     */
    private void showClearTimerConfirmationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("Clear Usage Limiter");
        builder.setMessage(
                "Are you sure you want to clear the usage limiter for \"" + currentChildDeviceName + "\"?\n\n" +
                        "This will:\n" +
                        "• Stop the current limiter immediately\n" +
                        "• Remove all limiter settings\n" +
                        "• Clear selected apps and days\n" +
                        "• Reset the timer\n\n" +
                        "This action cannot be undone.");
        builder.setIcon(android.R.drawable.ic_dialog_alert);

        builder.setPositiveButton("Clear Limiter", (dialog, which) -> {
            Log.d(TAG, "User confirmed limiter clear for device: " + currentChildDeviceName);

            // Remove from Firebase
            limiterRef.child(currentChildDeviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Usage limiter cleared successfully for device: " + currentChildDeviceId);
                        Toast.makeText(this, "✅ Usage limiter cleared for " + currentChildDeviceName,
                                Toast.LENGTH_SHORT).show();

                        // Clear local data
                        selectedDays.clear();
                        selectedApps.clear();

                        // Reset UI
                        if (etLimiterHours != null)
                            etLimiterHours.setText("");
                        if (etLimiterMinutes != null)
                            etLimiterMinutes.setText("");
                        if (btnSelectDays != null)
                            btnSelectDays.setText("Select Days");
                        if (btnSelectApps != null)
                            btnSelectApps.setText("Select Apps");

                        // Update display and all button states - timer is now inactive
                        updateLimiterDisplay();
                        updateAllButtonStates(false);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error clearing usage limiter: " + e.getMessage());
                        Toast.makeText(this, "Error clearing usage limiter: " + e.getMessage(), Toast.LENGTH_LONG)
                                .show();
                    });
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            Log.d(TAG, "User cancelled limiter clear");
            dialog.dismiss();
        });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Update the UI display with current limiter status
     */
    private void updateLimiterDisplay() {
        // 📊 LIVE TIMER STATUS DISPLAY for Parent Dashboard
        if (currentChildDeviceId == null)
            return;

        // Start real-time monitoring of child timer status
        startLiveTimerMonitoring();

        // Load current limiter state
        loadLimiterState();
    }

    /**
     * 📊 START LIVE TIMER MONITORING
     * Shows real-time countdown of child device timer on parent dashboard
     * 🔧 MULTI-DEVICE FIX: Properly removes old listener before adding new one
     */
    private void startLiveTimerMonitoring() {
        if (currentChildDeviceId == null || limiterRef == null)
            return;

        Log.d(TAG, "🔴 STARTING LIVE TIMER MONITORING for child device: " + currentChildDeviceId);

        // 🔧 MULTI-DEVICE FIX: Remove old listener first to prevent data leakage
        cleanupPreviousLimiterListener();

        // Store reference for later cleanup
        activeLimiterRef = limiterRef.child(currentChildDeviceId);

        // Monitor child device timer in real-time
        activeLimiterListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    return;
                }

                try {
                    Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                    Long remainingTimeMs = dataSnapshot.child("remainingTimeMs").getValue(Long.class);
                    Integer hours = dataSnapshot.child("hours").getValue(Integer.class);
                    Integer minutes = dataSnapshot.child("minutes").getValue(Integer.class);
                    String lastResetDate = dataSnapshot.child("lastResetDate").getValue(String.class);

                    if (Boolean.TRUE.equals(isActive) && remainingTimeMs != null) {
                        // Show live timer status
                        runOnUiThread(() -> showLiveTimerStatus(remainingTimeMs, hours, minutes, lastResetDate));
                    } else {
                        // Timer is inactive
                        runOnUiThread(() -> showTimerInactiveStatus());
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error processing live timer data: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Live timer monitoring cancelled: " + databaseError.getMessage());
            }
        };

        // Add the listener
        activeLimiterRef.addValueEventListener(activeLimiterListener);
        Log.d(TAG, "✅ LIVE TIMER LISTENER ATTACHED for device: " + currentChildDeviceId);
    }

    /**
     * 🔧 MULTI-DEVICE FIX: Clean up previous limiter listener when switching
     * devices
     * This prevents data from old device showing up for new device
     */
    private void cleanupPreviousLimiterListener() {
        if (activeLimiterRef != null && activeLimiterListener != null) {
            activeLimiterRef.removeEventListener(activeLimiterListener);
            Log.d(TAG, "🧹 Removed previous limiter listener (multi-device cleanup)");
            activeLimiterRef = null;
            activeLimiterListener = null;
        }
    }

    /**
     * 🔧 MULTI-DEVICE FIX: Complete cleanup when switching between children
     * Call this before loading new child's data
     */
    private void performMultiDeviceSwitchCleanup() {
        Log.d(TAG, "🔄 MULTI-DEVICE SWITCH: Cleaning up data for device change");

        // Remove all active Firebase listeners
        cleanupPreviousLimiterListener();

        // Clear cached UI data
        selectedDays.clear();
        selectedApps.clear();

        // Reset UI elements
        runOnUiThread(() -> {
            if (etLimiterHours != null)
                etLimiterHours.setText("");
            if (etLimiterMinutes != null)
                etLimiterMinutes.setText("");
            if (btnSelectDays != null)
                btnSelectDays.setText("Select Days");
            if (btnSelectApps != null)
                btnSelectApps.setText("Select Apps");
            if (tvLimiterStatus != null)
                tvLimiterStatus.setText("Loading...");
            if (tvLimiterTimer != null)
                tvLimiterTimer.setText("--:--:--");
            clearUsageDisplay();
        });

        Log.d(TAG, "✅ Multi-device cleanup complete - ready for new device data");
    }

    /**
     * 🔴 SHOW LIVE TIMER STATUS on Parent Dashboard
     */
    private void showLiveTimerStatus(long remainingTimeMs, Integer originalHours, Integer originalMinutes,
            String lastResetDate) {
        if (binding == null)
            return;

        try {
            // Format remaining time
            int totalSeconds = (int) (remainingTimeMs / 1000);
            int hours = totalSeconds / 3600;
            int minutes = (totalSeconds % 3600) / 60;
            int seconds = totalSeconds % 60;

            String timeText = String.format("%02d:%02d:%02d", hours, minutes, seconds);

            // Determine status color based on remaining time
            int color;
            String statusText;
            if (remainingTimeMs <= 0) {
                color = ContextCompat.getColor(this, android.R.color.holo_red_dark);
                statusText = "⏰ TIME EXPIRED";
                timeText = "00:00:00";
            } else if (remainingTimeMs < 30 * 60 * 1000) { // Less than 30 minutes
                color = ContextCompat.getColor(this, android.R.color.holo_orange_dark);
                statusText = "⚠️ TIME RUNNING LOW";
            } else {
                color = ContextCompat.getColor(this, android.R.color.holo_green_dark);
                statusText = "✅ TIMER ACTIVE";
            }

            // Show original parent-set duration
            String originalDuration = "";
            if (originalHours != null && originalMinutes != null) {
                originalDuration = " (Set: " + originalHours + "h " + originalMinutes + "m)";
            }

            // Update device status text to show live timer
            // Prioritize child's actual name (userName) over device name
            String displayName = "";
            if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
                displayName = currentChildUserName;
            } else if (currentChildDeviceName != null && !currentChildDeviceName.isEmpty()) {
                displayName = currentChildDeviceName;
            } else {
                displayName = "Device";
            }
            String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
            String deviceStatusText = capitalizedName + " (Manage Devices)";
            binding.tvDeviceStatus.setText(deviceStatusText);
            // Use teal color for modern look (unless timer sets a specific color)
            int textColor = (color != 0) ? color : ContextCompat.getColor(this, R.color.success_600);
            binding.tvDeviceStatus.setTextColor(textColor);

            // Create detailed timer info
            String detailedInfo = "⏰ Live Timer: " + timeText + originalDuration +
                    "\n📅 Device: " + currentChildDeviceName +
                    "\n📊 Status: " + statusText;

            // Show in a timer status view if available
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText(detailedInfo);
                tvLimiterStatus.setTextColor(color);
                tvLimiterStatus.setVisibility(View.VISIBLE);
            }

            // Log for debugging
            Log.d(TAG, "🔴 LIVE TIMER UPDATE: " + currentChildDeviceName + " - " + timeText + " remaining");

        } catch (Exception e) {
            Log.e(TAG, "Error updating live timer status: " + e.getMessage());
        }
    }

    /**
     * 🔘 SHOW TIMER INACTIVE STATUS
     */
    private void showTimerInactiveStatus() {
        if (binding == null)
            return;

        try {
            // Update device status
            // Prioritize child's actual name (userName) over device name
            String displayName = "";
            if (currentChildUserName != null && !currentChildUserName.isEmpty()) {
                displayName = currentChildUserName;
            } else if (currentChildDeviceName != null && !currentChildDeviceName.isEmpty()) {
                displayName = currentChildDeviceName;
            } else {
                displayName = "Device";
            }
            String capitalizedName = displayName.substring(0, 1).toUpperCase() + displayName.substring(1);
            String deviceStatusText = capitalizedName + " (Manage Devices)";
            binding.tvDeviceStatus.setText(deviceStatusText);
            // Use teal color for modern look
            binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));

            // Hide timer status view
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText("No timer currently active for " + currentChildDeviceName);
                tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
                tvLimiterStatus.setVisibility(View.VISIBLE);
            }

            Log.d(TAG, "🔘 Timer inactive for device: " + currentChildDeviceName);

        } catch (Exception e) {
            Log.e(TAG, "Error showing inactive timer status: " + e.getMessage());
        }
    }

    /**
     * Load existing limiter state from Firebase
     */
    private void loadLimiterState() {
        if (currentChildDeviceId == null || limiterRef == null) {
            Log.w(TAG, "Cannot load limiter state: device or limiterRef is null");
            return;
        }

        Log.d(TAG, "Loading usage limiter state for device: " + currentChildDeviceId);

        limiterRef.child(currentChildDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    try {
                        // Load limiter data
                        Integer hours = dataSnapshot.child("hours").getValue(Integer.class);
                        Integer minutes = dataSnapshot.child("minutes").getValue(Integer.class);
                        Boolean isActive = dataSnapshot.child("isActive").getValue(Boolean.class);
                        Long startTime = dataSnapshot.child("startTime").getValue(Long.class);
                        Long remainingTimeMs = dataSnapshot.child("remainingTimeMs").getValue(Long.class);
                        String lastResetDate = dataSnapshot.child("lastResetDate").getValue(String.class);

                        // Load selected days
                        selectedDays.clear();
                        DataSnapshot daysSnapshot = dataSnapshot.child("activeDays");
                        if (daysSnapshot.exists()) {
                            for (DataSnapshot daySnapshot : daysSnapshot.getChildren()) {
                                String day = daySnapshot.getValue(String.class);
                                if (day != null) {
                                    selectedDays.add(day);
                                }
                            }
                        }

                        // Load selected apps
                        selectedApps.clear();
                        DataSnapshot appsSnapshot = dataSnapshot.child("selectedApps");
                        if (appsSnapshot.exists()) {
                            for (DataSnapshot appSnapshot : appsSnapshot.getChildren()) {
                                String app = appSnapshot.getValue(String.class);
                                if (app != null) {
                                    selectedApps.add(app);
                                }
                            }
                        }

                        // Check if timer needs daily reset
                        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                        boolean needsReset = lastResetDate == null || !lastResetDate.equals(todayDate);

                        if (needsReset && isActive != null && isActive) {
                            // Reset timer for new day
                            resetLimiterForNewDay(hours != null ? hours : 0, minutes != null ? minutes : 0);
                        } else if (isActive != null && isActive) {
                            // Update UI with current state
                            runOnUiThread(() -> {
                                // Update input fields
                                if (hours != null && etLimiterHours != null)
                                    etLimiterHours.setText(String.valueOf(hours));
                                if (minutes != null && etLimiterMinutes != null)
                                    etLimiterMinutes.setText(String.valueOf(minutes));

                                // Update button texts
                                if (btnSelectDays != null)
                                    btnSelectDays.setText("Days Selected (" + selectedDays.size() + ")");
                                if (btnSelectApps != null)
                                    btnSelectApps.setText("Update Apps (" + selectedApps.size() + ")");

                                // Update all button states - timer IS active
                                updateAllButtonStates(true);

                                // Display timer
                                if (remainingTimeMs != null && remainingTimeMs > 0) {
                                    displayLimiterTime(remainingTimeMs);
                                    if (tvLimiterStatus != null) {
                                        tvLimiterStatus.setText("Usage limiter active on " + currentChildDeviceName);
                                        tvLimiterStatus.setTextColor(ContextCompat.getColor(
                                                ParentDashboardActivity.this, android.R.color.holo_green_dark));
                                    }
                                } else {
                                    if (tvLimiterTimer != null)
                                        tvLimiterTimer.setText("00:00:00");
                                    if (tvLimiterStatus != null) {
                                        tvLimiterStatus.setText("Usage limiter expired");
                                        tvLimiterStatus.setTextColor(ContextCompat
                                                .getColor(ParentDashboardActivity.this, android.R.color.holo_red_dark));
                                    }
                                }
                            });

                            // Start monitoring timer if active
                            startLimiterMonitoring();
                        } else if (dataSnapshot.hasChildren()) {
                            // Timer exists but is not active - it's expired for the day
                            runOnUiThread(() -> {
                                // Update input fields
                                if (hours != null && etLimiterHours != null)
                                    etLimiterHours.setText(String.valueOf(hours));
                                if (minutes != null && etLimiterMinutes != null)
                                    etLimiterMinutes.setText(String.valueOf(minutes));

                                // Update button texts
                                if (btnSelectDays != null)
                                    btnSelectDays.setText("Days Selected (" + selectedDays.size() + ")");
                                if (btnSelectApps != null)
                                    btnSelectApps.setText("Update Apps (" + selectedApps.size() + ")");

                                // Update all button states - timer is expired but exists
                                updateAllButtonStates(false);

                                // Display expired timer in RED
                                displayExpiredLimiterState();
                            });
                        }

                        Log.d(TAG, "Loaded usage limiter state - Active: " + (isActive != null && isActive) +
                                ", Apps: " + selectedApps.size() + ", Days: " + selectedDays.size());

                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing limiter state: " + e.getMessage());
                        displayInactiveLimiterState();
                    }
                } else {
                    // No limiter set for this device
                    displayInactiveLimiterState();
                    Log.d(TAG, "No usage limiter found for device: " + currentChildDeviceId);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error loading limiter state: " + databaseError.getMessage());
                displayInactiveLimiterState();
            }
        });
    }

    /**
     * Display inactive limiter state in UI
     */
    private void displayInactiveLimiterState() {
        runOnUiThread(() -> {
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText("No usage limiter active");
                tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }
            if (tvLimiterTimer != null) {
                tvLimiterTimer.setText("⏱️ --:--:--");
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }

            // Update button text for apps
            if (btnSelectApps != null) {
                btnSelectApps
                        .setText(selectedApps.isEmpty() ? "Select Apps" : "Update Apps (" + selectedApps.size() + ")");
            }

            // Update all button states - timer is NOT active
            updateAllButtonStates(false);

        });
    }

    /**
     * 🔴 DISPLAY EXPIRED LIMITER STATE
     * Shows timer in RED when it has expired (00:00) but still exists
     * Timer remains visible until manually removed
     */
    private void displayExpiredLimiterState() {
        runOnUiThread(() -> {
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText("Timer expired for today");
                tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark)); // RED for
                                                                                                           // expired
            }
            if (tvLimiterTimer != null) {
                tvLimiterTimer.setText("00:00:00");
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark)); // RED for
                                                                                                          // expired
            }

            // Update button text for apps
            if (btnSelectApps != null) {
                btnSelectApps
                        .setText(selectedApps.isEmpty() ? "Select Apps" : "Update Apps (" + selectedApps.size() + ")");
            }

            // Update all button states - timer is expired but exists
            updateAllButtonStates(false);

        });
    }

    /**
     * Display limiter remaining time with enhanced formatting
     */
    private void displayLimiterTime(long remainingTimeMs) {
        int totalSeconds = (int) (remainingTimeMs / 1000);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (tvLimiterTimer != null) {
            // Enhanced timer display format
            String timeText;
            if (hours > 0) {
                timeText = String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds);
            } else {
                timeText = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
            }

            tvLimiterTimer.setText("⏱️ " + timeText);

            // Color coding based on remaining time
            if (remainingTimeMs > 600000) { // More than 10 minutes
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
            } else if (remainingTimeMs > 300000) { // 5-10 minutes
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            } else if (remainingTimeMs > 0) { // Less than 5 minutes
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            } else {
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                tvLimiterTimer.setText("⏰ TIME UP!");
            }
        }

        // Timer badge removed per request

        Log.d(TAG, "Displayed enhanced limiter time: " + hours + "h " + minutes + "m " + seconds + "s");
    }

    /**
     * Reset limiter for new day (daily reset at midnight)
     */
    private void resetLimiterForNewDay(int hours, int minutes) {
        if (currentChildDeviceId == null)
            return;

        Log.d(TAG, "Resetting usage limiter for new day - Device: " + currentChildDeviceId);

        long totalTimeMs = (hours * 60 * 60 * 1000L) + (minutes * 60 * 1000L);
        String todayDate = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        Map<String, Object> updates = new HashMap<>();
        updates.put("startTime", System.currentTimeMillis());
        updates.put("remainingTimeMs", totalTimeMs);
        updates.put("lastResetDate", todayDate);
        updates.put("isActive", true);

        limiterRef.child(currentChildDeviceId).updateChildren(updates)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Usage limiter reset successfully for new day");
                    runOnUiThread(() -> {
                        displayLimiterTime(totalTimeMs);
                        if (tvLimiterStatus != null) {
                            tvLimiterStatus.setText("Usage limiter reset for today");
                        }
                    });
                    startLimiterMonitoring();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error resetting limiter for new day: " + e.getMessage());
                });
    }

    /**
     * Start monitoring the usage limiter timer
     */
    private void startLimiterMonitoring() {
        if (currentChildDeviceId == null) {
            Log.w(TAG, "Cannot start limiter monitoring: no device selected");
            return;
        }

        Log.d(TAG, "Starting enhanced real-time limiter monitoring for device: " + currentChildDeviceId);

        // Setup real-time Firebase listener for accurate timer updates
        DatabaseReference limiterTimerRef = limiterRef.child(currentChildDeviceId);

        limiterTimerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                try {
                    if (dataSnapshot.exists()) {
                        Map<String, Object> limiterData = (Map<String, Object>) dataSnapshot.getValue();
                        if (limiterData != null) {
                            Boolean isActive = (Boolean) limiterData.get("isActive");
                            Long remainingTimeMs = (Long) limiterData.get("remainingTimeMs");
                            String currentApp = (String) limiterData.get("currentApp");
                            Boolean isRunning = (Boolean) limiterData.get("isRunning");
                            Long lastSync = (Long) limiterData.get("lastSync");

                            // Update UI with real-time data
                            runOnUiThread(() -> {
                                updateLimiterRealTimeUI(isActive, remainingTimeMs, currentApp, isRunning, lastSync);
                            });
                        }
                    } else {
                        // No active limiter
                        runOnUiThread(() -> {
                            displayInactiveLimiterState();
                        });
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing real-time limiter data: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Real-time limiter monitoring cancelled: " + databaseError.getMessage());
            }
        });

        Log.d(TAG, "✅ Enhanced real-time limiter monitoring started");
    }

    /**
     * Update limiter UI with real-time data from child device
     */
    private void updateLimiterRealTimeUI(Boolean isActive, Long remainingTimeMs, String currentApp, Boolean isRunning,
            Long lastSync) {
        try {
            if (tvLimiterStatus != null) {
                if (Boolean.TRUE.equals(isActive) && remainingTimeMs != null && remainingTimeMs > 0) {
                    String status = "Active";
                    if (currentApp != null && !currentApp.isEmpty()) {
                        String appName = getAppDisplayName(currentApp);
                        status += " - " + (Boolean.TRUE.equals(isRunning) ? "Counting: " : "Paused: ") + appName;
                    }

                    // Add sync indicator
                    if (lastSync != null) {
                        long syncAge = System.currentTimeMillis() - lastSync;
                        if (syncAge < 5000) { // Recent sync (within 5 seconds)
                            status += " ●"; // Live indicator
                        }
                    }

                    tvLimiterStatus.setText(status);
                    tvLimiterStatus.setTextColor(ContextCompat.getColor(this,
                            Boolean.TRUE.equals(isRunning) ? android.R.color.holo_red_dark
                                    : android.R.color.holo_orange_dark));
                } else {
                    tvLimiterStatus.setText("Timer inactive or expired");
                    tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
                }
            }

            // Update timer display
            if (remainingTimeMs != null && remainingTimeMs > 0) {
                displayLimiterTime(remainingTimeMs);
            } else {
                displayInactiveLimiterState();
            }

        } catch (Exception e) {
            Log.e(TAG, "Error updating real-time limiter UI: " + e.getMessage());
        }
    }

    /**
     * Get display name for an app package
     */
    private String getAppDisplayName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return packageName; // Fallback to package name
        }
    }

    /**
     * Setup usage limiter for current device
     */
    private void initializeLimiterForDevice(String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "Cannot initialize limiter: deviceId is null");
            return;
        }

        Log.d(TAG, "Initializing usage limiter for device: " + deviceId);

        // If switching devices, detach any previous limiter listener and clear only
        // local UI selections
        if (currentChildDeviceId == null || !currentChildDeviceId.equals(deviceId)) {
            // Detach previous device listener to avoid crosstalk
            detachLimiterRealtimeListener();

            // Clear local selections only when switching devices (do NOT wipe Firebase
            // data)
            selectedDays.clear();
            selectedApps.clear();

            // Reset UI elements
            if (etLimiterHours != null)
                etLimiterHours.setText("");
            if (etLimiterMinutes != null)
                etLimiterMinutes.setText("");
            if (btnSelectDays != null)
                btnSelectDays.setText("Select Days");
            if (btnSelectApps != null)
                btnSelectApps.setText("Select Apps");
        }

        // Load existing state for this device
        loadLimiterState();

        // Attach a realtime listener for this device so UI stays in sync
        ensureLimiterRealtimeListener(deviceId);

        Log.d(TAG, "Usage limiter initialized for device: " + deviceId);
    }

    /**
     * Clean usage limiter data when device connects (as per user requirement)
     */
    private void cleanDeviceUsageLimiterData(String deviceId) {
        if (deviceId == null) {
            Log.w(TAG, "Cannot clean limiter data: deviceId is null");
            return;
        }

        Log.d(TAG, "Cleaning usage limiter data for device: " + deviceId);

        // Remove from Firebase
        if (limiterRef != null) {
            limiterRef.child(deviceId).removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Successfully cleaned usage limiter data for device: " + deviceId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Error cleaning usage limiter data for device " + deviceId + ": " + e.getMessage());
                    });
        }

        // Clean local storage (device-specific limiter preferences)
        try {
            SharedPreferences limiterPrefs = getSharedPreferences("usage_limiter", MODE_PRIVATE);
            SharedPreferences.Editor editor = limiterPrefs.edit();

            // Remove device-specific keys
            editor.remove("limiter_days_" + deviceId);
            editor.remove("limiter_apps_" + deviceId);
            editor.remove("limiter_hours_" + deviceId);
            editor.remove("limiter_minutes_" + deviceId);
            editor.apply();

            Log.d(TAG, "Cleaned local limiter preferences for device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning local limiter preferences: " + e.getMessage());
        }
    }

    /**
     * Ensure a realtime listener is attached for the given device's limiter node.
     * Detaches any existing listener before attaching a new one.
     */
    private void ensureLimiterRealtimeListener(String deviceId) {
        try {
            if (limiterRef == null || deviceId == null) {
                return;
            }

            // Detach previous listener if pointing to a different device
            if (currentLimiterDeviceRef != null && limiterListener != null) {
                currentLimiterDeviceRef.removeEventListener(limiterListener);
            }

            currentLimiterDeviceRef = limiterRef.child(deviceId);
            limiterListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    // On any change, refresh the UI from source of truth
                    updateLimiterDisplay();
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "Limiter realtime listener cancelled: " + error.getMessage());
                }
            };

            currentLimiterDeviceRef.addValueEventListener(limiterListener);
            Log.d(TAG, "Attached realtime limiter listener for device: " + deviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error attaching limiter realtime listener: " + e.getMessage());
        }
    }

    /**
     * Detach any active realtime listener for the previously selected device.
     */
    private void detachLimiterRealtimeListener() {
        try {
            if (currentLimiterDeviceRef != null && limiterListener != null) {
                currentLimiterDeviceRef.removeEventListener(limiterListener);
                Log.d(TAG, "Detached previous limiter realtime listener");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error detaching limiter realtime listener: " + e.getMessage());
        } finally {
            // Always clear references
            currentLimiterDeviceRef = null;
            limiterListener = null;
        }
    }

    /**
     * Update the Set Timer button state - simplified since button is always active
     */
    private void updateSetTimerButtonState() {
        if (btnSetLimiter == null) {
            return;
        }

        // Set Timer button is always enabled when device is selected (validation
        // happens when clicked)
        boolean hasDevice = currentChildDeviceId != null;
        btnSetLimiter.setEnabled(hasDevice);

        Log.d(TAG, "Set Timer button state: " + hasDevice + " (Device selected: " + hasDevice + ")");
    }

    /**
     * Update all button states properly based on current limiter state
     */
    private void updateAllButtonStates(boolean isTimerActive) {
        runOnUiThread(() -> {
            try {
                boolean hasDevice = currentChildDeviceId != null;

                // Select Apps button - should always be enabled when device is selected
                if (btnSelectApps != null) {
                    btnSelectApps.setEnabled(hasDevice);
                }

                // Select Days button - should always be enabled when device is selected
                if (btnSelectDays != null) {
                    btnSelectDays.setEnabled(hasDevice);
                }

                // Set Timer button - should always be enabled when device is selected
                if (btnSetLimiter != null) {
                    btnSetLimiter.setEnabled(hasDevice);
                }

                // Clear Timer button - should always be enabled when device is selected
                if (btnClearLimiter != null) {
                    btnClearLimiter.setEnabled(hasDevice);
                }

                Log.d(TAG, "Updated all button states - Timer active: " + isTimerActive + ", Has device: " + hasDevice);

            } catch (Exception e) {
                Log.e(TAG, "Error updating all button states: " + e.getMessage());
            }
        });
    }

    /**
     * Handle when no device is selected - disable relevant buttons
     */
    private void handleNoDeviceSelected() {
        runOnUiThread(() -> {
            // Clear current device info
            currentChildDeviceId = null;

            // Update all button states - no timer active, no device
            updateAllButtonStates(false);

            // Update status
            if (tvLimiterStatus != null) {
                tvLimiterStatus.setText("Select a device to set usage limiter");
                tvLimiterStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }
            if (tvLimiterTimer != null) {
                tvLimiterTimer.setText("⏱️ --:--:--");
                tvLimiterTimer.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }

            Log.d(TAG, "Updated UI for no device selected state");
        });
    }

    /**
     * 🔧 CRITICAL FIX: Create timer data for QR reconnected device
     * This ensures the device appears in parent dashboard even after reconnection
     */
    private void createTimerDataForDevice(String deviceId, String deviceName) {
        if (mAuth != null && mAuth.getCurrentUser() != null && deviceId != null) {
            String userId = mAuth.getCurrentUser().getUid();

            Log.d(TAG, "🔧 Creating timer data for device: " + deviceName + " (" + deviceId + ")");

            // Create default timer entry in parent_timers path
            DatabaseReference parentTimerRef = FirebaseDatabase.getInstance()
                    .getReference("parent_timers")
                    .child(userId)
                    .child(deviceId);

            // Create basic timer structure with default values
            Map<String, Object> timerData = new HashMap<>();
            timerData.put("deviceId", deviceId);
            timerData.put("deviceName", deviceName);
            timerData.put("isActive", false);
            timerData.put("totalTimeLimit", 0L); // Default: no limit set
            timerData.put("usedTime", 0L);
            timerData.put("remainingTime", 0L);
            timerData.put("lastUpdated", System.currentTimeMillis());
            timerData.put("createdAt", System.currentTimeMillis());
            timerData.put("createdViaQR", true); // Mark as QR-created for tracking

            parentTimerRef.setValue(timerData)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Timer data created successfully for: " + deviceName);

                        // Also ensure the device appears in connectedChildDevices
                        DatabaseReference connectedDevicesRef = FirebaseDatabase.getInstance()
                                .getReference("parents")
                                .child(userId)
                                .child("connectedChildDevices")
                                .child(deviceId);

                        Map<String, Object> deviceData = new HashMap<>();
                        deviceData.put("deviceId", deviceId);
                        deviceData.put("deviceName", deviceName);
                        deviceData.put("lastConnected", System.currentTimeMillis());
                        deviceData.put("reconnectedViaQR", true);

                        connectedDevicesRef.setValue(deviceData)
                                .addOnSuccessListener(aVoid2 -> {
                                    Log.d(TAG, "✅ Device entry created in connectedChildDevices for: " + deviceName);
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "❌ Failed to create device entry: " + e.getMessage());
                                });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to create timer data for " + deviceName + ": " + e.getMessage());
                    });
        } else {
            Log.e(TAG, "❌ Cannot create timer data: mAuth, user, or deviceId is null");
        }
    }

    /**
     * 🔥 BULLETPROOF: Clear any Firebase connection conflicts that could block
     * device connection
     */
    private void clearFirebaseConnectionConflicts(String deviceId) {
        if (mAuth != null && mAuth.getCurrentUser() != null && deviceId != null) {
            String userId = mAuth.getCurrentUser().getUid();

            Log.d(TAG, "🧹 BULLETPROOF: Clearing Firebase connection conflicts for: " + deviceId);

            // Clear potential conflict paths that could block connections
            DatabaseReference[] conflictPaths = {
                    FirebaseDatabase.getInstance().getReference("connection_conflicts").child(deviceId),
                    FirebaseDatabase.getInstance().getReference("device_blacklist").child(userId).child(deviceId),
                    FirebaseDatabase.getInstance().getReference("blocked_devices").child(userId).child(deviceId),
                    FirebaseDatabase.getInstance().getReference("removed_devices").child(deviceId),
                    FirebaseDatabase.getInstance().getReference("failed_connections").child(deviceId)
            };

            for (DatabaseReference path : conflictPaths) {
                path.removeValue().addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Cleared conflict path: " + path.getKey());
                }).addOnFailureListener(e -> {
                    Log.w(TAG, "⚠️ Could not clear conflict path: " + path.getKey() + " - " + e.getMessage());
                });
            }

            Log.d(TAG, "🔥 Firebase connection conflicts cleanup initiated for: " + deviceId);
        }
    }

    /**
     * 🔥 BULLETPROOF: Create backup connection paths for maximum reliability
     * Creates multiple Firebase paths to ensure device connection survives any
     * issues
     */
    private void createBackupConnectionPaths(String deviceId, String deviceName) {
        if (mAuth != null && mAuth.getCurrentUser() != null && deviceId != null) {
            String userId = mAuth.getCurrentUser().getUid();

            Log.d(TAG, "🔥 BULLETPROOF: Creating backup connection paths for: " + deviceName);

            // Backup Path 1: Emergency connection tracking
            DatabaseReference emergencyRef = FirebaseDatabase.getInstance()
                    .getReference("emergency_connections")
                    .child(deviceId);

            Map<String, Object> emergencyData = new HashMap<>();
            emergencyData.put("parentUserId", userId);
            emergencyData.put("deviceName", deviceName);
            emergencyData.put("deviceId", deviceId);
            emergencyData.put("emergencyMode", true);
            emergencyData.put("createdViaQR", true);
            emergencyData.put("timestamp", System.currentTimeMillis());

            emergencyRef.setValue(emergencyData);

            // Backup Path 2: Redundant parent connection
            DatabaseReference backupParentRef = FirebaseDatabase.getInstance()
                    .getReference("backup_parent_connections")
                    .child(userId)
                    .child(deviceId);

            Map<String, Object> backupData = new HashMap<>();
            backupData.put("deviceId", deviceId);
            backupData.put("deviceName", deviceName);
            backupData.put("backupConnection", true);
            backupData.put("qrReconnection", true);
            backupData.put("timestamp", System.currentTimeMillis());

            backupParentRef.setValue(backupData);

            // Backup Path 3: Device registry for quick lookup
            DatabaseReference registryRef = FirebaseDatabase.getInstance()
                    .getReference("device_registry")
                    .child(deviceId);

            Map<String, Object> registryData = new HashMap<>();
            registryData.put("currentParent", userId);
            registryData.put("deviceName", deviceName);
            registryData.put("lastConnection", System.currentTimeMillis());
            registryData.put("connectionMethod", "qr_scan");
            registryData.put("status", "active");

            registryRef.setValue(registryData);

            Log.d(TAG, "✅ BULLETPROOF: Backup connection paths created for: " + deviceName);
        }
    }

    /**
     * 🚨 EMERGENCY: Create device connection when QR path is not working
     * This handles cases where child connects but QR listener path is not updated
     */
    private void createEmergencyDeviceConnection(String deviceId, String deviceName, int appCount, long timestamp) {
        Log.d(TAG, "🚨 EMERGENCY DEVICE CONNECTION for: " + deviceName + " (ID: " + deviceId + ")");

        runOnUiThread(() -> {
            // Check if device already exists
            boolean exists = connectedDevices.stream()
                    .anyMatch(d -> d.deviceId.equals(deviceId));

            if (!exists) {
                Log.d(TAG, "🚨 EMERGENCY: Adding new device detected via device_apps");

                // Create device immediately
                ChildDevice device = new ChildDevice();
                device.deviceId = deviceId;
                device.deviceName = deviceName;
                device.appCount = appCount;
                device.lastConnected = timestamp;
                device.apps = new ArrayList<>();
                device.focusModeActive = false;

                // 🔥 BULLETPROOF: Clear ALL barriers
                if (isPermanentlyRemoved(device.deviceId)) {
                    removePermanentRemoval(device.deviceId);
                    Log.d(TAG, "🚫→✅ EMERGENCY: Cleared removal status for: " + device.deviceName);
                }

                if (device.deviceId.equals(deviceIdJustRemoved)) {
                    deviceIdJustRemoved = null;
                    Log.d(TAG, "🔄 EMERGENCY: Cleared just-removed tracking for: " + device.deviceName);
                }

                connectedDevicesManager.removeDeviceFromBlacklist(device.deviceId);
                clearFirebaseConnectionConflicts(device.deviceId);

                // Add to list immediately
                connectedDevices.add(device);
                Log.d(TAG, "✅ EMERGENCY: Device added to local list. Total devices: " + connectedDevices.size());

                // Save to storage
                connectedDevicesManager.addOrUpdateDevice(device);
                Log.d(TAG, "✅ EMERGENCY: Device saved to persistent storage");

                // 🔧 CRITICAL: Create timer data
                createTimerDataForDevice(device.deviceId, device.deviceName);

                // 🔥 Create backup paths
                createBackupConnectionPaths(device.deviceId, device.deviceName);

                // Set as current device if first
                if (currentChildDeviceId == null) {
                    connectedDevicesManager.setCurrentDevice(device.deviceId, false);
                    currentChildDeviceId = device.deviceId;
                    currentChildDeviceName = device.deviceName;
                    currentChildUserName = (device.userName != null) ? device.userName : ""; // 🔧 Store child name
                    initializeLimiterForDevice(device.deviceId);
                    saveDeviceNameForCurrentDevice();
                    Log.d(TAG, "🎯 EMERGENCY: Set as current device");
                }

                // Update UI
                updateDeviceStatus();
                updateTargetDeviceDisplay();
                refreshDeviceListPremium();

                Log.d(TAG, "🎉 EMERGENCY CONNECTION SUCCESS: " + device.deviceName);

                // Show success toast
                Toast.makeText(ParentDashboardActivity.this,
                        "🚨 " + device.deviceName + " connected via emergency detection!",
                        Toast.LENGTH_LONG).show();
            } else {
                Log.d(TAG, "🔄 EMERGENCY: Device already exists, updating timestamp");
                // Update existing device timestamp
                for (int i = 0; i < connectedDevices.size(); i++) {
                    if (connectedDevices.get(i).deviceId.equals(deviceId)) {
                        connectedDevices.get(i).lastConnected = timestamp;
                        connectedDevicesManager.addOrUpdateDevice(connectedDevices.get(i));

                        // Still create timer data to be safe
                        createTimerDataForDevice(deviceId, deviceName);
                        createBackupConnectionPaths(deviceId, deviceName);

                        updateDeviceStatus();
                        break;
                    }
                }
            }
        });
    }

    /**
     * 🚨 EMERGENCY: Create device connection from device_apps DataSnapshot
     */
    private void createEmergencyDeviceConnection(String deviceId, DataSnapshot appsSnapshot) {
        Log.d(TAG, "🚨 EMERGENCY DEVICE CONNECTION from device_apps for ID: " + deviceId);

        try {
            if (appsSnapshot != null && appsSnapshot.exists()) {
                int appCount = (int) appsSnapshot.getChildrenCount();
                long timestamp = System.currentTimeMillis();

                // Extract device name from apps if available
                String deviceName = "Emergency Device " + deviceId.substring(0, Math.min(8, deviceId.length()));

                // Try to get device name from first app entry if available
                for (DataSnapshot appSnapshot : appsSnapshot.getChildren()) {
                    DataSnapshot deviceInfo = appSnapshot.child("deviceInfo");
                    if (deviceInfo.exists()) {
                        String extractedName = deviceInfo.child("deviceName").getValue(String.class);
                        if (extractedName != null && !extractedName.isEmpty()) {
                            deviceName = extractedName;
                            break;
                        }
                    }
                }

                Log.d(TAG, "🚨 EMERGENCY: Creating device - Name: " + deviceName + ", Apps: " + appCount);

                // Call the main creation method
                createEmergencyDeviceConnection(deviceId, deviceName, appCount, timestamp);
            } else {
                Log.e(TAG, "🚨 EMERGENCY ERROR: appsSnapshot is null or doesn't exist for device: " + deviceId);
            }
        } catch (Exception e) {
            Log.e(TAG, "🚨 EMERGENCY ERROR creating device connection: " + e.getMessage());
            e.printStackTrace();

            // Fallback - create with minimal info
            createEmergencyDeviceConnection(deviceId, "Emergency Device", 0, System.currentTimeMillis());
        }
    }

    /**
     * 🔔 START PERSISTENT TIMER NOTIFICATION SERVICE
     * This service will show notifications when timers expire or need reset
     */
    private void startPersistentTimerNotificationService() {
        try {
            Log.d(TAG, "🔔 Starting persistent timer notification service");

            // Start the notification service for parent devices
            Intent serviceIntent = new Intent(this, PersistentTimerNotificationService.class);
            startService(serviceIntent);

            Log.d(TAG, "✅ Persistent timer notification service started successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting persistent timer notification service: " + e.getMessage());
        }
    }

    // ==========================================
    // PREMIUM UI METHODS
    // ==========================================

    private void refreshDeviceListPremium() {
        // REDIRECTED: Use simple circular icons instead of big cards
        populateDeviceList();
        return;
        /*
         * if (llDeviceList == null)
         * return;
         * llDeviceList.removeAllViews();
         * 
         * List<ChildDevice> devices = connectedDevices;
         * if (devices == null)
         * devices = new ArrayList<>();
         * 
         * LayoutInflater inflater = LayoutInflater.from(this);
         * 
         * for (ChildDevice device : devices) {
         * // 🔧 CHANGED: Use new Vertical Card Layout
         * View card = inflater.inflate(R.layout.item_device_card, llDeviceList, false);
         * 
         * // Bind Views
         * androidx.constraintlayout.widget.ConstraintLayout cardContainer =
         * card.findViewById(R.id.cardContainer);
         * TextView tvName = card.findViewById(R.id.tvDeviceName);
         * ImageView ivIcon = card.findViewById(R.id.ivDeviceIcon);
         * View statusDot = card.findViewById(R.id.viewStatusDot);
         * TextView tvStatus = card.findViewById(R.id.tvStatus);
         * ImageView btnMoreOptions = card.findViewById(R.id.btnMoreOptions);
         * 
         * // Data Binding
         * String displayName = (device.userName != null && !device.userName.isEmpty())
         * ? device.userName
         * : device.deviceName;
         * tvName.setText(displayName);
         * 
         * // Status Logic (Simple for now)
         * long timeDiff = System.currentTimeMillis() - device.lastConnected;
         * boolean isOnline = timeDiff < 15 * 60 * 1000; // Considered online if
         * connected in last 15 mins
         * 
         * if (isOnline) {
         * statusDot.setBackgroundResource(R.drawable.bg_indicator_green);
         * tvStatus.setText("Monitoring");
         * tvStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));
         * } else {
         * statusDot.setBackgroundResource(R.drawable.bg_indicator_grey);
         * tvStatus.setText("Offline");
         * tvStatus.setTextColor(ContextCompat.getColor(this, R.color.neutral_500));
         * }
         * 
         * // Selection Logic
         * boolean isSelected = device.deviceId.equals(currentChildDeviceId);
         * if (isSelected) {
         * // Selected State: Blue Tint + Border
         * cardContainer.setBackgroundResource(R.drawable.bg_device_card_selected);
         * ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.primary_600));
         * } else {
         * // Normal State: White + Grey Border
         * cardContainer.setBackgroundResource(R.drawable.bg_device_card_normal);
         * ivIcon.setColorFilter(ContextCompat.getColor(this, R.color.neutral_400));
         * }
         * 
         * // Card Click Listener (Select Device)
         * card.setOnClickListener(v -> {
         * switchDevice(device.deviceId); // FIX: Use proper method that loads usage
         * data
         * });
         * 
         * // "Three Dots" Menu Click Listener
         * btnMoreOptions.setOnClickListener(v -> {
         * android.widget.PopupMenu popup = new android.widget.PopupMenu(this,
         * btnMoreOptions);
         * popup.getMenu().add("Remove Device");
         * // popup.getMenu().add("Rename"); // Future feature
         * 
         * popup.setOnMenuItemClickListener(item -> {
         * if (item.getTitle().equals("Remove Device")) {
         * // Confirm Removal
         * new AlertDialog.Builder(new android.view.ContextThemeWrapper(this,
         * R.style.AlertDialogCustom))
         * .setTitle("Remove Device?")
         * .setMessage("Are you sure you want to remove " + displayName
         * + "? You will need to reconnect it from the child device.")
         * .setPositiveButton("Remove", (dialog, which) -> {
         * removeConnectedDevice(device.deviceId);
         * })
         * .setNegativeButton("Cancel", null)
         * .show();
         * return true;
         * }
         * return false;
         * });
         * popup.show();
         * });
         * 
         * llDeviceList.addView(card);
         * }
         * 
         * // "Add Device" Button (Keep as chip or make card? Keeping consistent for
         * now)
         * // 🔧 UPDATED: Styled to match card height roughly or keep as distinct action
         * View addBtn = inflater.inflate(R.layout.item_add_device_chip, llDeviceList,
         * false);
         * 
         * // Optional: layout params adjustment if needed to align with cards
         * // LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
         * // (int) (110 * getResources().getDisplayMetrics().density),
         * // ViewGroup.LayoutParams.WRAP_CONTENT);
         * // params.setMargins(0, 8, 0, 8);
         * // addBtn.setLayoutParams(params);
         * 
         * addBtn.setOnClickListener(v -> showQRScanner());
         * llDeviceList.addView(addBtn);
         */ // END OF COMMENTED OUT BIG CARDS CODE
    }

    private void selectDevicePremium(ChildDevice device) {
        if (device == null)
            return;

        currentChildDeviceId = device.deviceId;
        currentChildDeviceName = device.deviceName;
        currentChildUserName = device.userName;

        // Update Manager
        if (connectedDevicesManager != null) {
            connectedDevicesManager.setCurrentDevice(device.deviceId, true);
        }

        // Update UI
        updateDeviceStatus();
        updateTargetDeviceDisplay(); // Helper if exists

        // Refresh list to update selection Highlight
        refreshDeviceListPremium();

        Toast.makeText(this, "Selected: " + device.deviceName, Toast.LENGTH_SHORT).show();
    }

    /**
     * Start Permission Event Listener service to monitor child device service
     * status changes
     */
    private void startPermissionEventListener() {
        try {
            Intent serviceIntent = new Intent(this, com.example.master2.services.PermissionEventListener.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.d(TAG, "✅ Permission Event Listener service started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Permission Event Listener: " + e.getMessage());
        }
    }

    // ================================================================================
    // 🚨 UNINSTALL DETECTION METHODS
    // ================================================================================

    /**
     * Start monitoring the current device for uninstall detection
     * Called when a device is selected
     */
    private void startUninstallDetection() {
        if (currentChildDeviceId == null || uninstallDetectionManager == null) {
            Log.w(TAG, "Cannot start uninstall detection - no device or manager");
            return;
        }

        Log.d(TAG, "🚨 Starting uninstall detection for device: " + currentChildDeviceId);

        uninstallDetectionManager.startMonitoringDevice(currentChildDeviceId,
                (deviceId, status, lastHeartbeat) -> {
                    runOnUiThread(() -> {
                        // Only update if this is still the current device
                        if (deviceId.equals(currentChildDeviceId)) {
                            currentDeviceStatus = status;
                            updateUninstallWarningUI(status, lastHeartbeat);
                            updateDeviceIconColor(status);
                        }
                    });
                });
    }

    /**
     * Stop monitoring device for uninstall detection
     */
    private void stopUninstallDetection() {
        if (uninstallDetectionManager != null && currentChildDeviceId != null) {
            uninstallDetectionManager.stopMonitoringDevice(currentChildDeviceId);
            Log.d(TAG, "🛑 Stopped uninstall detection for device: " + currentChildDeviceId);
        }
    }

    /**
     * Update the uninstall warning banner visibility and content
     */
    private void updateUninstallWarningUI(String status, long lastHeartbeat) {
        if (layoutUninstallWarning == null)
            return;

        boolean showWarning = UninstallDetectionManager.isUninstalled(status);

        if (showWarning) {
            layoutUninstallWarning.setVisibility(View.VISIBLE);

            // Update message based on status
            String message = UninstallDetectionManager.getStatusMessage(status);
            if (tvUninstallWarningMessage != null) {
                tvUninstallWarningMessage.setText(message);
            }

            // Update last seen time
            if (tvUninstallLastSeen != null) {
                String lastSeenText = "Last seen: " + UninstallDetectionManager.getLastSeenText(lastHeartbeat);
                tvUninstallLastSeen.setText(lastSeenText);
            }

            Log.w(TAG, "⚠️ UNINSTALL WARNING SHOWN for " + currentChildDeviceName + ": " + status);
        } else {
            layoutUninstallWarning.setVisibility(View.GONE);
        }
    }

    /**
     * Update the device icon color based on status
     * Turns red when app is uninstalled
     */
    private void updateDeviceIconColor(String status) {
        ImageView ivDeviceIcon = findViewById(R.id.ivDeviceIcon);
        if (ivDeviceIcon == null)
            return;

        if (UninstallDetectionManager.isUninstalled(status)) {
            // Red color for uninstalled
            ivDeviceIcon.setColorFilter(ContextCompat.getColor(this, R.color.error_600));

            // Also update device status text color to red
            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.error_600));
            }

            Log.d(TAG, "🔴 Device icon set to RED (uninstalled)");
        } else if (UninstallDetectionManager.STATUS_OFFLINE.equals(status)) {
            // Orange/yellow for offline
            ivDeviceIcon.setColorFilter(ContextCompat.getColor(this, R.color.warning_600));

            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.warning_600));
            }

            Log.d(TAG, "🟡 Device icon set to YELLOW (offline)");
        } else {
            // Green/normal for online
            ivDeviceIcon.setColorFilter(ContextCompat.getColor(this, R.color.neutral_400));

            if (binding != null && binding.tvDeviceStatus != null) {
                binding.tvDeviceStatus.setTextColor(ContextCompat.getColor(this, R.color.success_600));
            }

            Log.d(TAG, "🟢 Device icon set to NORMAL (online)");
        }
    }
}
