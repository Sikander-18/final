package com.example.master2;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.recyclerview.widget.LinearLayoutManager;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import android.content.Context;
import androidx.recyclerview.widget.RecyclerView;
import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Date;
import java.util.Set;
import java.util.Arrays;
import android.content.SharedPreferences;
import com.example.master2.PermanentExpiredNotificationService;
import android.widget.ImageView;
import android.app.DatePickerDialog;
import java.util.Calendar;
import androidx.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.app.usage.UsageEvents;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import androidx.core.app.NotificationCompat;
import android.os.Build;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.text.SimpleDateFormat;
import java.util.concurrent.TimeUnit;
import android.graphics.Color;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import android.provider.Settings;
import android.text.TextUtils;
import android.content.ComponentName;
import android.app.AlertDialog;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.example.master2.SessionManager;
import com.google.firebase.auth.FirebaseAuth;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import java.util.Collections;
import java.util.Comparator;
import android.content.DialogInterface;
import androidx.annotation.NonNull;
import com.example.master2.ChildLoginActivity;
import com.example.master2.ChildDevice;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Build;

public class ChildDashboardActivity extends AppCompatActivity {
    private static final String TAG = "ChildDashboard";
    private TextView tvParentName, tvConnectionStatus;
    private BottomNavigationView bottomNavigation;
    private View homeContent;
    private View settingsContent;
    private View todoContent;
    private String parentName, shareKey;
    private SessionManager sessionManager;
    private DeviceStatusManager deviceStatusManager;
    private BatteryOptimizationManager batteryOptimizationManager;

    // Timer components
    private androidx.cardview.widget.CardView timerCard;
    private TextView tvTimerDisplay, tvTimerStatus;
    private String childDeviceId;

    // Usage Limiter components
    private androidx.cardview.widget.CardView usageLimiterCard;
    private TextView tvTimerDisplayLimiter, tvTimerStatusLimiter, tvSelectedApps;
    private LinearLayout selectedAppsInfo;
    private DatabaseReference usageLimiterRef;
    private ValueEventListener usageLimiterListener;
    private Handler limiterUpdateHandler;
    private Runnable limiterUpdateRunnable;
    private String currentForegroundApp = "";
    private boolean isLimiterActive = false;

    // Active Timers components
    private androidx.cardview.widget.CardView activeTimersCard;
    private RecyclerView rvActiveTimers;
    private ActiveTimerAdapter activeTimerAdapter;
    private List<ActiveTimerItem> activeTimerList = new ArrayList<>();
    private DatabaseReference activeTimersRef;
    private ValueEventListener activeTimersListener;
    private long remainingTimeMs = 0;
    private List<String> limitedApps = new ArrayList<>();
    private BroadcastReceiver foregroundAppReceiver;
    private boolean isTimerCountingDown = false;

    // Direct foreground app monitoring
    private Handler foregroundAppHandler;
    private Runnable foregroundAppRunnable;

    // Flag to prevent recursive Firebase listener processing
    private boolean isUpdatingFirebase = false;

    // High-precision timer tracking
    private long lastTimerUpdateMs = 0;
    private long lastFirebaseUpdateMs = 0;

    /**
     * Format time with higher precision (shows seconds more accurately)
     */
    private String formatTimePrecise(long timeMs) {
        if (timeMs <= 0)
            return "0:00";

        long totalSeconds = (timeMs + 500) / 1000; // Round to nearest second
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        if (minutes > 0) {
            return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "0:%02d", seconds);
        }
    }

    // Todo List variables
    private ArrayList<Task> taskList = new ArrayList<>();
    private LinearLayout taskContainer;
    private Button submitButton;
    private FloatingActionButton addButton;
    private Button btnPrev, btnNext;
    private TextView txtSelectedDate;
    private Calendar currentDate = Calendar.getInstance();
    private SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy", Locale.getDefault());

    private DatabaseReference snapshotRef;

    private String lastDisplayedTime = "";
    private String lastDisplayedStatus = "";

    // NEW: Broadcast receiver for logout
    private BroadcastReceiver logoutReceiver;

    // 🎯 SINGLE USAGE TRACKER - BulletproofUsageTracker only
    // OLD TRACKERS REMOVED: AccurateUsageTracker, DateAwareUsageDataManager,
    // RollingUsageDataManager
    // These were causing conflicts and duplicate Firebase writes
    private FreshConnectionManager freshConnectionManager;
    private BulletproofUsageTracker bulletproofUsageTracker; // ONLY usage tracker now

    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 🛡️ BULLETPROOF: Wrap EVERYTHING in try-catch to prevent crashes
        try {
            setContentView(R.layout.activity_child_dashboard);
        } catch (Exception e) {
            Log.e(TAG, "❌ CRITICAL: Failed to set content view: " + e.getMessage());
            finish();
            return;
        }

        // Initialize core components first (these should never fail)
        try {
            mAuth = FirebaseAuth.getInstance();
            sessionManager = new SessionManager(this);
        } catch (Exception e) {
            Log.e(TAG, "❌ CRITICAL: Failed to initialize core components: " + e.getMessage());
            finish();
            return;
        }

        // 🛡️ BULLETPROOF SESSION RECOVERY: Never logout, always try to recover
        Log.d(TAG, "🛡️ BULLETPROOF MODE: Child app will NEVER logout automatically");

        // Get the current child's device ID from session manager
        childDeviceId = sessionManager.getChildDeviceId();

        // Initialize Fresh Connection Manager (non-critical)
        try {
            freshConnectionManager = new FreshConnectionManager(this, childDeviceId);

            // Handle fresh connection if this is a new QR pairing
            boolean isFreshConnection = getIntent().getBooleanExtra("fresh_connection", false);
            if (isFreshConnection) {
                Log.d(TAG, "🧹 Fresh connection detected - clearing all previous data");
                freshConnectionManager.handleFreshConnection();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing FreshConnectionManager: " + e.getMessage());
        }

        // 🎯 BULLETPROOF USAGE TRACKING - Uses reliable UsageStatsManager
        if (childDeviceId != null && !childDeviceId.isEmpty()) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    Log.d(TAG, "🎯 Starting BULLETPROOF usage tracking...");

                    // Create the new bulletproof tracker
                    bulletproofUsageTracker = new BulletproofUsageTracker(ChildDashboardActivity.this);

                    // Check permission first
                    if (bulletproofUsageTracker.hasUsagePermission()) {
                        bulletproofUsageTracker.start();
                        Log.d(TAG, "✅ Bulletproof usage tracking started!");
                        Log.d(TAG, bulletproofUsageTracker.getStatus());
                    } else {
                        Log.w(TAG, "⚠️ No usage stats permission - requesting...");
                        bulletproofUsageTracker.requestUsagePermission();
                    }

                    // 🆕 IMMEDIATE SUSAGE UPLOAD - Force upload on app start
                    Log.d(TAG, "🚀 Triggering immediate SUSAGE data upload...");
                    final String deviceIdForUpload = childDeviceId;
                    new Thread(() -> {
                        try {
                            com.example.master2.utils.SUsageDataManager susageManager = com.example.master2.utils.SUsageDataManager
                                    .getInstance(ChildDashboardActivity.this);

                            susageManager.uploadToFirebase(deviceIdForUpload,
                                    new com.example.master2.utils.SUsageDataManager.OnUploadCompleteListener() {
                                        @Override
                                        public void onSuccess() {
                                            Log.d(TAG, "✅✅✅ IMMEDIATE SUSAGE UPLOAD SUCCESSFUL!");
                                        }

                                        @Override
                                        public void onError(String error) {
                                            Log.e(TAG, "❌ Immediate SUSAGE upload failed: " + error);
                                        }
                                    });
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Exception in immediate SUSAGE upload: " + e.getMessage());
                        }
                    }).start();

                } catch (Exception e) {
                    Log.e(TAG, "❌ Error starting usage tracking: " + e.getMessage());
                }
            }, 5000); // 5 second delay
        } else {
            Log.w(TAG, "⚠️ Cannot initialize usage tracking - no device ID");
        }

        // 🔧 LOGOUT FIX: Check if logout was intentional before attempting recovery
        SharedPreferences logoutPrefs = getSharedPreferences("logout_state", MODE_PRIVATE);
        boolean intentionalLogout = logoutPrefs.getBoolean("intentional_logout", false);
        long logoutTimestamp = logoutPrefs.getLong("logout_timestamp", 0);
        String logoutReason = logoutPrefs.getString("logout_reason", "");

        // Check if logout was recent (within last 10 seconds)
        boolean recentLogout = (System.currentTimeMillis() - logoutTimestamp) < 10000;

        // 🔧 LOGOUT FIX: Clear old logout flags (older than 30 seconds) to allow normal
        // operation
        if (intentionalLogout && (System.currentTimeMillis() - logoutTimestamp) > 30000) {
            Log.d(TAG, "🧹 Clearing old intentional logout flag (older than 30s)");
            logoutPrefs.edit().clear().apply();
            intentionalLogout = false;
            recentLogout = false;
        }

        if (intentionalLogout && recentLogout) {
            Log.d(TAG, "🚪 INTENTIONAL LOGOUT DETECTED - Preventing emergency recovery");
            Log.d(TAG, "   Logout reason: " + logoutReason);
            Log.d(TAG, "   Logout was " + (System.currentTimeMillis() - logoutTimestamp) + "ms ago");

            // Navigate back to MainActivity/login screen
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            intent.putExtra("logout_message", "Device was removed by parent");
            intent.putExtra("force_login_screen", true);
            startActivity(intent);
            finish();
            return;
        }

        // If no session data, try to recover from intent or create emergency session
        if (!sessionManager.isLoggedIn() || !"child".equals(sessionManager.getUserType()) || childDeviceId == null) {
            Log.w(TAG, "⚠️ Session data missing - attempting EMERGENCY RECOVERY");
            Log.w(TAG, "   Session isLoggedIn: " + sessionManager.isLoggedIn());
            Log.w(TAG, "   Session userType: " + sessionManager.getUserType());
            Log.w(TAG, "   Child Device ID: " + childDeviceId);

            // Try to recover from intent data first
            if (tryEmergencySessionRecovery()) {
                Log.d(TAG, "✅ Emergency session recovery successful!");
                childDeviceId = sessionManager.getChildDeviceId();
            } else {
                // Create a minimal working session to prevent logout
                createEmergencySession();
                childDeviceId = sessionManager.getChildDeviceId();
                Log.d(TAG, "🚨 Created emergency session to prevent logout");
            }
        }

        Log.d(TAG, "onCreate: ChildDashboardActivity started with session protection");
        Log.d(TAG, "Child Device ID: " + childDeviceId);

        // 🛡️ BULLETPROOF: Setup listeners with error handling
        try {
            // NEW: Setup broadcast receiver for logout
            setupLogoutBroadcastReceiver();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to setup logout broadcast receiver: " + e.getMessage());
        }

        // Delay Firebase listeners to prevent initialization race conditions
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                // Add ENHANCED logout monitoring (monitors multiple Firebase paths)
                listenForRemoteLogoutCommand();
                Log.d(TAG, "✅ Logout monitoring started");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to setup logout monitoring: " + e.getMessage());
            }

            try {
                // Add upload trigger listener for parent data refresh requests
                listenForUploadTriggers();
                Log.d(TAG, "✅ Upload trigger listener started");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to setup upload trigger listener: " + e.getMessage());
            }

            try {
                // Setup Active Timers Listener
                setupActiveTimersListener();
                Log.d(TAG, "✅ Active timers listener started");
            } catch (Exception e) {
                Log.e(TAG, "❌ Failed to setup active timers listener: " + e.getMessage());
            }
        }, 1500); // 1.5 second delay for Firebase listeners

        // Update session activity
        try {
            sessionManager.updateLastActivity();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to update session activity: " + e.getMessage());
        }

        // Get data from intent or session
        parentName = getIntent().getStringExtra("parentName");
        shareKey = getIntent().getStringExtra("shareKey");
        String intentChildDeviceId = getIntent().getStringExtra("deviceId");

        // If not from intent, try to get from session
        if (parentName == null && sessionManager.isLoggedIn()) {
            Log.d(TAG, "🔄 No intent data - loading from session");
            parentName = sessionManager.getParentName();
            shareKey = sessionManager.getQRShareKey();
        }

        // Debug session information
        Log.d(TAG, "📊 Session Debug Info:");
        Log.d(TAG, "   Intent parentName: " + getIntent().getStringExtra("parentName"));
        Log.d(TAG, "   Session parentName: " + sessionManager.getParentName());
        Log.d(TAG, "   Session userType: " + sessionManager.getUserType());
        Log.d(TAG, "   Session isLoggedIn: " + sessionManager.isLoggedIn());
        Log.d(TAG, "   Final parentName: " + parentName);

        // CRITICAL: If we have intent data, re-save session to ensure persistence
        if (getIntent().hasExtra("parentName") && getIntent().hasExtra("deviceId")) {
            String intentParentName = getIntent().getStringExtra("parentName");
            String intentDeviceId = getIntent().getStringExtra("deviceId");
            String intentShareKey = getIntent().getStringExtra("shareKey");

            if (intentParentName != null && intentDeviceId != null) {
                Log.d(TAG, "💾 Re-saving session data from intent to ensure persistence");
                sessionManager.saveChildSession(intentDeviceId, intentParentName, intentShareKey);

                // Update our local variables
                parentName = intentParentName;
                shareKey = intentShareKey;
                childDeviceId = intentDeviceId;
            }
        }

        // Use intent device ID if available, otherwise use session device ID
        if (intentChildDeviceId != null) {
            childDeviceId = intentChildDeviceId;
        }

        // 🛡️ BULLETPROOF: Always ensure we have working connection data
        if (parentName == null || childDeviceId == null) {
            Log.w(TAG, "⚠️ Missing connection data - attempting auto-recovery");
            Log.w(TAG, "   Parent Name: " + parentName);
            Log.w(TAG, "   Child Device ID: " + childDeviceId);

            // Try to recover missing data
            recoverMissingConnectionData();
        }

        Log.d(TAG, "✅ Connection data secured - proceeding with dashboard initialization");

        // 🛡️ BULLETPROOF: Initialize managers with error handling
        try {
            // Initialize device status manager
            deviceStatusManager = new DeviceStatusManager(this);

            // Initialize battery optimization manager for timer service reliability
            batteryOptimizationManager = new BatteryOptimizationManager(this);
            batteryOptimizationManager.checkAndRequestAllPermissions(this);

            // Start as child device with proper device info
            if (childDeviceId != null && parentName != null) {
                String deviceName = ChildAppUtils.getChildDeviceName();
                // Use the stored parent device ID from session, not this device's ID
                String parentDeviceId = sessionManager.getParentUserId();
                if (parentDeviceId == null) {
                    // Fallback: extract from share key or use default
                    parentDeviceId = "unknown_parent";
                }
                deviceStatusManager.startAsChildDevice(parentDeviceId, deviceName);
                Log.d(TAG, "Started device status tracking for child device: " + deviceName + " with parent: "
                        + parentDeviceId);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize managers: " + e.getMessage());
            // Continue anyway - these are not critical for basic functionality
        }

        // Permissions are now handled in ChildPermissionsActivity

        // 🛡️ BULLETPROOF: Start CRITICAL services only, with error handling
        try {
            // CRITICAL: Start RemoteBlockService to listen for focus mode commands
            startRemoteBlockService();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start RemoteBlockService: " + e.getMessage());
        }

        // Initialize the views and setup navigation (CRITICAL - must succeed)
        try {
            initViews();
            setupBottomNavigation();
            setupSettingsClickListeners();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to initialize views: " + e.getMessage());
        }

        // CRITICAL: Restore parent connection if app was restarted
        try {
            restoreParentConnection();
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to restore parent connection: " + e.getMessage());
        }

        // SAFETY: Ensure session is always saved with current connection data
        if (parentName != null && childDeviceId != null) {
            Log.d(TAG, "💾 Safety session save - ensuring data persistence");
            sessionManager.saveChildSession(childDeviceId, parentName, shareKey);

            // 🛡️ BULLETPROOF: Create emergency backup of session data
            createEmergencySessionBackup(parentName, childDeviceId, shareKey);

            // Also save parent user ID if available
            if (sessionManager.getParentUserId() == null && getIntent().hasExtra("parentUserId")) {
                String parentUserId = getIntent().getStringExtra("parentUserId");
                if (parentUserId != null) {
                    sessionManager.saveParentUserId(parentUserId);
                }
            }
        }

        // Add logout button to settings
        addLogoutButton();

        // Show home content by default
        showMainContent();

        // Initialize todo functionality
        // initializeTodoFunctionality(); // Disabled due to R.id generation issues

        // Update UI with session data
        updateUI();

        // Start periodic UI refresh to keep connection status accurate
        startPeriodicUIRefresh();

        // Start timer monitoring
        setupTimerMonitoring();

        // 🎯 USAGE TRACKING: Using BulletproofUsageTracker for reliable 7-day data
        // This tracker uses UsageStatsManager.queryUsageStats() for accurate data
        // Updates every 2 minutes and stores in Firebase under usage_7day/{deviceId}
        Log.d(TAG, "🎯 Using BulletproofUsageTracker for reliable usage tracking");

        // 🛡️ BULLETPROOF: Start NON-CRITICAL services with DELAY to prevent crash
        // This staggers service initialization to reduce memory pressure
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Log.d(TAG, "🚀 Starting delayed services (Phase 1 - 2s delay)...");

                // Start live timer service
                startLiveTimerService();

                // Initialize usage limiter
                initializeUsageLimiter();

                Log.d(TAG, "✅ Phase 1 services started successfully");
            } catch (Exception e) {
                Log.e(TAG, "❌ Error starting Phase 1 services: " + e.getMessage());
            }
        }, 2000); // 2 second delay

        // Phase 2: Start remaining services after 5 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Log.d(TAG, "🚀 Starting delayed services (Phase 2 - 5s delay)...");

                // Start Enhanced Usage Limiter Service
                EnhancedUsageLimiterService.startService(ChildDashboardActivity.this);

                // 🔔 Start Permission Monitor Service for real-time permission change detection
                Intent permissionMonitorIntent = new Intent(ChildDashboardActivity.this,
                        com.example.master2.services.PermissionMonitorService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(permissionMonitorIntent);
                } else {
                    startService(permissionMonitorIntent);
                }
                Log.d(TAG, "🔔 PermissionMonitorService started");

                // 📦 Start Package Change Service for automatic app list updates
                Intent packageChangeIntent = new Intent(ChildDashboardActivity.this,
                        com.example.master2.services.PackageChangeService.class);
                startService(packageChangeIntent);
                Log.d(TAG, "📦 PackageChangeService started - auto app list sync enabled");

                // Start DailyTimerResetService
                startDailyTimerResetService();

                // Start permanent notification service (only once here)
                startPermanentNotificationService();

                // Start Per-App Timer Service
                if (childDeviceId != null) {
                    com.example.master2.services.AppTimerService.start(ChildDashboardActivity.this, childDeviceId);
                    Log.d(TAG, "⏱️ AppTimerService started");
                }

                // 🆕 Schedule WorkManager for reliable background usage uploads
                // This survives app kills and device reboots
                com.example.master2.workers.UsageUploadScheduler.schedulePeriodicUpload(ChildDashboardActivity.this);
                Log.d(TAG, "📅 WorkManager usage upload scheduled");

                // 🆕 Sync installed apps list to Firebase for parent viewing
                if (childDeviceId != null) {
                    com.example.master2.utils.InstalledAppsManager.getInstance(ChildDashboardActivity.this)
                            .syncInstalledApps(childDeviceId,
                                    new com.example.master2.utils.InstalledAppsManager.OnSyncCompleteListener() {
                                        @Override
                                        public void onSuccess(int appCount) {
                                            Log.d(TAG, "📱 Synced " + appCount + " installed apps to Firebase");
                                        }

                                        @Override
                                        public void onError(String error) {
                                            Log.e(TAG, "❌ Failed to sync installed apps: " + error);
                                        }
                                    });
                }

                Log.d(TAG, "✅ Phase 2 services started successfully");
            } catch (Exception e) {
                Log.e(TAG, "❌ Error starting Phase 2 services: " + e.getMessage());
            }
        }, 5000); // 5 second delay

        // Phase 3: Start OEM checks and remaining items after 8 seconds
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Log.d(TAG, "🚀 Starting delayed services (Phase 3 - 8s delay)...");

                // Check timer permissions
                checkTimerPermissions();

                // OEM compatibility check (shows dialog if needed)
                checkOEMCompatibility();

                Log.d(TAG, "✅ Phase 3 completed - all services initialized");
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in Phase 3: " + e.getMessage());
            }
        }, 8000); // 8 second delay

        // Add some sample tasks for testing (non-critical)
        try {
            if (taskList.isEmpty()) {
                addSampleTasks();
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error adding sample tasks: " + e.getMessage());
        }

        // 🆕 Fetch latest parent profile name to ensure device name display IS NOT used
        fetchRealParentProfile();

        Log.d(TAG, "✅ ChildDashboardActivity onCreate completed - services will start in phases");
    }

    /**
     * 🛡️ Initialize automatic service protection system
     */
    private void checkOEMCompatibility() {
        try {
            Log.d(TAG, "🛡️ Initializing service watchdog...");

            // Initialize the ServiceWatchdog
            ServiceWatchdog.initialize(this);
            Log.d(TAG, "✅ Service protection initialized");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing protection: " + e.getMessage());
        }
    }

    private void listenForRemoteLogoutCommand() {
        String childDeviceId = sessionManager.getChildDeviceId();
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.e(TAG, "❌ Cannot listen for logout command: childDeviceId is null/empty");
            return;
        }

        Log.d(TAG, "🔄 Starting logout monitoring for device: " + childDeviceId);

        // ⚠️ STABILITY FIX: Removed duplicate logout listeners to prevent race
        // conditions
        // The PersistentConnectionService now handles logout commands exclusively
        // Having multiple listeners causes memory leaks, duplicate callbacks, and
        // freezing
        // See: PersistentConnectionService.startLogoutCommandMonitoring()
        Log.d(TAG, "✅ Logout monitoring delegated to PersistentConnectionService");
    }

    /**
     * 🚨 ENHANCED: Listen for logout commands from logout_commands path
     */
    private void setupLogoutCommandListener(String childDeviceId) {
        DatabaseReference logoutCommandRef = FirebaseDatabase.getInstance()
                .getReference("logout_commands")
                .child(childDeviceId);

        logoutCommandRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(TAG, "📱 [logout_commands] Logout command data changed: " + snapshot.toString());

                Boolean trigger = snapshot.child("trigger").getValue(Boolean.class);
                String reason = snapshot.child("reason").getValue(String.class);
                String parentName = snapshot.child("parentName").getValue(String.class);

                Log.d(TAG, "🔍 [logout_commands] Logout command details - trigger: " + trigger + ", reason: " + reason
                        + ", parent: " + parentName);

                if (trigger != null && trigger) {
                    Log.d(TAG, "🚨 [logout_commands] LOGOUT TRIGGERED! Reason: " + reason + ", Parent: " + parentName);

                    // 🚨 IMMEDIATE AUTOMATIC LOGOUT - No user confirmation needed
                    Log.d(TAG, "� Performing immediate automatic logout...");
                    performLogout();

                    // Remove the logout command after processing it
                    logoutCommandRef.removeValue();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "❌ [logout_commands] Logout command listener cancelled: " + error.getMessage());
            }
        });
    }

    /**
     * 🚨 ENHANCED: Listen for automatic logout signals from parent device path
     */
    private void setupParentDeviceLogoutListener(String childDeviceId) {
        String parentUserId = sessionManager.getParentUserId();
        if (parentUserId == null || parentUserId.isEmpty()) {
            Log.w(TAG, "⚠️ No parent user ID - cannot setup parent device logout listener");
            return;
        }

        DatabaseReference parentDeviceRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(parentUserId)
                .child("connectedChildDevices")
                .child(childDeviceId);

        Log.d(TAG, "🔄 Setting up parent device logout listener: parents/" + parentUserId + "/connectedChildDevices/"
                + childDeviceId);

        parentDeviceRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    // � DEVICE REMOVED: Parent deleted this device - immediate logout
                    Log.w(TAG, "🚨 [parent_device] Child device data NO LONGER EXISTS - Device was removed by parent!");
                    Log.w(TAG, "🔥 Performing immediate automatic logout due to device removal...");
                    performLogout();
                    return;
                }

                // Check for explicit logout flags
                Boolean automaticLogout = snapshot.child("automaticLogout").getValue(Boolean.class);
                Boolean forceLogout = snapshot.child("forceLogout").getValue(Boolean.class);
                Boolean logout = snapshot.child("logout").getValue(Boolean.class);
                String logoutReason = snapshot.child("logoutReason").getValue(String.class);

                Log.d(TAG, "🔍 [parent_device] Logout flags - automaticLogout: " + automaticLogout + ", forceLogout: "
                        + forceLogout + ", logout: " + logout + ", reason: " + logoutReason);

                // 🔧 LOGOUT FIX: Enhanced logout detection with better logging
                if ((logout != null && logout) || (automaticLogout != null && automaticLogout)
                        || (forceLogout != null && forceLogout)) {
                    Log.d(TAG, "🚨 [parent_device] LOGOUT FLAGS DETECTED! Reason: " + logoutReason);
                    Log.d(TAG, "🚨 logout=" + logout + ", automaticLogout=" + automaticLogout + ", forceLogout="
                            + forceLogout);
                    Log.d(TAG, "🚪 Performing immediate automatic logout...");

                    // 🔧 LOGOUT FIX: Force logout on UI thread to ensure proper navigation
                    runOnUiThread(() -> {
                        try {
                            performLogout();
                        } catch (Exception e) {
                            Log.e(TAG, "❌ Error in runOnUiThread logout: " + e.getMessage());
                            // Fallback: try direct logout
                            performLogout();
                        }
                    });
                    return;
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "❌ [parent_device] Parent device logout listener cancelled: " + error.getMessage());
            }
        });
    }

    /**
     * 🚨 ENHANCED: Listen for logout signals from device_status path (backup path)
     */
    private void setupDeviceStatusLogoutListener(String childDeviceId) {
        DatabaseReference deviceStatusRef = FirebaseDatabase.getInstance()
                .getReference("device_status")
                .child(childDeviceId);

        Log.d(TAG, "🔄 Setting up device status logout listener: device_status/" + childDeviceId);

        deviceStatusRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    return; // No data, nothing to check
                }

                // 🛡️ BULLETPROOF: Only logout on EXPLICIT removal with confirmation
                Boolean forceLogout = snapshot.child("forceLogout").getValue(Boolean.class);
                Boolean explicitRemoval = snapshot.child("explicitRemoval").getValue(Boolean.class); // New explicit
                                                                                                     // flag
                String logoutReason = snapshot.child("logoutReason").getValue(String.class);

                Log.d(TAG, "🔍 [device_status] Logout flags - forceLogout: " + forceLogout + ", explicitRemoval: "
                        + explicitRemoval + ", reason: " + logoutReason);

                // Only logout if BOTH forceLogout AND explicitRemoval are true
                if ((forceLogout != null && forceLogout) && (explicitRemoval != null && explicitRemoval)) {
                    Log.d(TAG, "🚨 [device_status] EXPLICIT DEVICE REMOVAL DETECTED! Reason: " + logoutReason);

                    // Clear the logout signal to prevent repeated triggers
                    deviceStatusRef.child("forceLogout").removeValue();
                    deviceStatusRef.child("explicitRemoval").removeValue();

                    // Always show confirmation for explicit removal
                    runOnUiThread(() -> {
                        new AlertDialog.Builder(ChildDashboardActivity.this)
                                .setTitle("🚨 Device Removed")
                                .setMessage("Your device was explicitly removed by the parent.\n\nReason: "
                                        + (logoutReason != null ? logoutReason : "Device removed"))
                                .setPositiveButton("OK", (dialog, which) -> {
                                    performLogout();
                                })
                                .setCancelable(false)
                                .show();
                    });
                } else {
                    // 🛡️ Log but don't logout for other flags
                    Log.d(TAG, "🛡️ [device_status] Non-explicit logout signals ignored in bulletproof mode");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "❌ [device_status] Device status logout listener cancelled: " + error.getMessage());
            }
        });
    }

    /**
     * Listen for upload triggers from parent device to refresh usage data
     */
    private void listenForUploadTriggers() {
        String childDeviceId = sessionManager.getChildDeviceId();
        if (childDeviceId == null || childDeviceId.isEmpty()) {
            Log.w(TAG, "No child device ID available for upload trigger listener");
            return;
        }

        DatabaseReference uploadTriggerRef = FirebaseDatabase.getInstance()
                .getReference("upload_triggers")
                .child(childDeviceId);

        uploadTriggerRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean trigger = snapshot.child("trigger").getValue(Boolean.class);
                String requestedBy = snapshot.child("requestedBy").getValue(String.class);
                String reason = snapshot.child("reason").getValue(String.class);
                Long timestamp = snapshot.child("timestamp").getValue(Long.class);

                if (trigger != null && trigger && "parent".equals(requestedBy)) {
                    Log.d(TAG, "🔄 Received upload trigger from parent - reason: " + reason);

                    // Trigger immediate data upload via RemoteBlockService
                    triggerImmediateDataUpload();

                    // Remove the trigger so it doesn't repeat
                    uploadTriggerRef.removeValue()
                            .addOnSuccessListener(aVoid -> {
                                Log.d(TAG, "✅ Upload trigger command processed and removed");
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "❌ Failed to remove upload trigger: " + e.getMessage());
                            });
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Upload trigger listener cancelled: " + error.getMessage());
            }
        });

        Log.d(TAG, "📡 Upload trigger listener initialized for device: " + childDeviceId);
    }

    /**
     * Trigger immediate data upload by sending intent to RemoteBlockService
     */
    private void triggerImmediateDataUpload() {
        Log.d(TAG, "🚀 Triggering immediate data upload...");

        try {
            // Send broadcast to RemoteBlockService to trigger immediate upload
            Intent uploadIntent = new Intent("com.example.master2.TRIGGER_UPLOAD");
            uploadIntent.putExtra("reason", "parent_request");
            uploadIntent.putExtra("timestamp", System.currentTimeMillis());
            sendBroadcast(uploadIntent);

            Log.d(TAG, "✅ Upload trigger broadcast sent to RemoteBlockService");

            // Also try direct service call as backup
            Intent serviceIntent = new Intent(this, RemoteBlockService.class);
            serviceIntent.setAction("UPLOAD_USAGE_DATA");
            startService(serviceIntent);

            Log.d(TAG, "✅ Direct service upload request sent to RemoteBlockService");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error triggering data upload: " + e.getMessage());
        }
    }

    // NEW: Setup broadcast receiver for logout from RemoteBlockService
    private void setupLogoutBroadcastReceiver() {
        logoutReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if ("com.example.master2.CHILD_LOGOUT".equals(intent.getAction())) {
                    Log.d(TAG, "Received logout broadcast from RemoteBlockService");

                    // Show final dialog and redirect to login
                    showLogoutDialog();
                }
            }
        };

        IntentFilter filter = new IntentFilter("com.example.master2.CHILD_LOGOUT");

        // Fix for Android 8.0+ (API 26+) - specify receiver export flag for security
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(logoutReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            Log.d(TAG, "Logout receiver registered with RECEIVER_NOT_EXPORTED flag");
        } else {
            registerReceiver(logoutReceiver, filter);
            Log.d(TAG, "Logout receiver registered (legacy mode)");
        }
        Log.d(TAG, "Logout broadcast receiver registered");
    }

    private void showLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Device Removed")
                .setMessage(
                        "Your device has been removed from parental monitoring by the parent.\n\nYou will be redirected to the login screen.")
                .setPositiveButton("OK", (dialog, which) -> {
                    // Redirect to login screen
                    Intent intent = new Intent(this, ChildLoginActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    private void initViews() {
        tvParentName = findViewById(R.id.tvParentName);
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus);
        bottomNavigation = findViewById(R.id.bottomNavigation);
        homeContent = findViewById(R.id.homeContent);
        settingsContent = findViewById(R.id.settingsContent);

        // Disable TODO functionality due to R.id generation issues
        // TODO: Fix R.java generation to restore full TODO functionality
        todoContent = null;
        Log.w(TAG, "TODO functionality disabled due to R.id.todoContent generation issues");

        // Initialize timer views
        usageLimiterCard = findViewById(R.id.usageLimiterCard);
        tvTimerDisplay = findViewById(R.id.tvTimerDisplay);
        tvTimerStatus = findViewById(R.id.tvTimerStatus);

        // Initialize usage limiter views
        usageLimiterCard = findViewById(R.id.usageLimiterCard);
        tvTimerDisplayLimiter = findViewById(R.id.tvTimerDisplay);
        tvTimerStatusLimiter = findViewById(R.id.tvTimerStatus);
        tvSelectedApps = findViewById(R.id.tvSelectedApps);
        selectedAppsInfo = findViewById(R.id.selectedAppsInfo);

        // Initialize debug buttons
        Button btnDebugSession = findViewById(R.id.btnDebugSession);
        Button btnRefreshLogout = findViewById(R.id.btnRefreshLogout);
        Button btnTestLogout = findViewById(R.id.btnTestLogout);

        // Set up debug button listeners
        if (btnDebugSession != null) {
            btnDebugSession.setOnClickListener(v -> showDebugSessionInfo());
        }

        if (btnRefreshLogout != null) {
            btnRefreshLogout.setOnClickListener(v -> refreshLogoutListener());
        }

        if (btnTestLogout != null) {
            btnTestLogout.setOnClickListener(v -> testLogoutFunction());
        }

        // Setup View Usage Data card click handler
        View cardViewUsageData = findViewById(R.id.cardViewUsageData);
        if (cardViewUsageData != null) {
            cardViewUsageData.setOnClickListener(v -> {
                Log.d(TAG, "📊 View Usage Data card clicked");
                Intent intent = new Intent(ChildDashboardActivity.this, ChildDeviceUsageActivity.class);
                startActivity(intent);
            });
            Log.d(TAG, "✅ View Usage Data card click listener set");
        } else {
            Log.w(TAG, "⚠️ cardViewUsageData not found in layout");
        }

        // Re-enable TODO debugging with null check
        if (todoContent == null) {
            Log.e(TAG, "todoContent view is null! Check layout_todo_list.xml is properly included");
        } else {
            Log.d(TAG, "todoContent view found successfully");
        }

        if (homeContent == null) {
            Log.e(TAG, "homeContent view is null!");
        } else {
            Log.d(TAG, "homeContent view found successfully");
        }

        if (timerCard == null || tvTimerDisplay == null || tvTimerStatus == null) {
            Log.e(TAG, "Timer views not found! Check if they exist in layout");
        } else {
            Log.d(TAG, "Timer views initialized successfully");
        }

        if (usageLimiterCard == null) {
            Log.e(TAG, "Usage limiter card not found! Check if usageLimiterCard exists in layout");
        } else {
            Log.d(TAG, "Usage limiter views initialized successfully");
        }
    }

    // Debug methods
    private void showDebugSessionInfo() {
        if (sessionManager != null) {
            String sessionInfo = sessionManager.getDetailedSessionInfo();
            Log.d(TAG, sessionInfo);

            // Show in a dialog
            new AlertDialog.Builder(this)
                    .setTitle("🔍 Session Debug Info")
                    .setMessage(sessionInfo)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copy to Clipboard", (dialog, which) -> {
                        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(
                                Context.CLIPBOARD_SERVICE);
                        android.content.ClipData clip = android.content.ClipData.newPlainText("Session Info",
                                sessionInfo);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, "Session info copied to clipboard", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } else {
            Toast.makeText(this, "SessionManager is null!", Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshLogoutListener() {
        Toast.makeText(this, "🔄 Refreshing logout listener...", Toast.LENGTH_SHORT).show();

        // Send intent to RemoteBlockService to refresh logout listener
        Intent refreshIntent = new Intent(this, RemoteBlockService.class);
        refreshIntent.putExtra("action", "refresh_logout_listener");
        startService(refreshIntent);

        Toast.makeText(this, "✅ Logout listener refresh requested", Toast.LENGTH_SHORT).show();
    }

    // CRITICAL FIX: Validate connection with parent to prevent auto-reconnect after
    // removal
    private void validateConnectionWithParent() {
        String parentUserId = sessionManager.getParentUserId();
        String childDeviceId = sessionManager.getChildDeviceId();

        if (parentUserId == null || childDeviceId == null) {
            Log.w(TAG, "Missing parent user ID or child device ID - connection invalid");
            return;
        }

        Log.d(TAG, "Validating connection with parent: " + parentUserId);

        // Check if this child device still exists in the parent's connected devices
        FirebaseDatabase.getInstance().getReference("parents")
                .child(parentUserId)
                .child("connectedChildDevices")
                .child(childDeviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                        if (!dataSnapshot.exists()) {
                            // Connection no longer exists - child device was removed
                            Log.w(TAG, "⚠️ Child device no longer exists in parent's connected devices");
                            runOnUiThread(() -> {
                                new AlertDialog.Builder(ChildDashboardActivity.this)
                                        .setTitle("🚫 Device Disconnected")
                                        .setMessage(
                                                "Your device was removed by the parent.\n\nPlease scan the QR code again to reconnect.")
                                        .setPositiveButton("OK", (dialog, which) -> {
                                            performLogout();
                                        })
                                        .setCancelable(false)
                                        .show();
                            });
                        } else {
                            Log.d(TAG, "✅ Connection with parent is valid");
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError databaseError) {
                        Log.e(TAG, "Failed to validate connection: " + databaseError.getMessage());
                        // On error, continue normally but log the issue
                    }
                });
    }

    private void testLogoutFunction() {
        new AlertDialog.Builder(this)
                .setTitle("🚨 Test Logout")
                .setMessage("This will test the logout functionality directly. Are you sure?")
                .setPositiveButton("Yes, Test Logout", (dialog, which) -> {
                    Log.d(TAG, "Testing logout function directly");

                    // Send broadcast to test logout
                    Intent testLogoutIntent = new Intent("com.example.master2.CHILD_LOGOUT");
                    sendBroadcast(testLogoutIntent);

                    Toast.makeText(this, "🚨 Test logout broadcast sent", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 🔧 TIMER FIX: Handle Usage Access permission request result
        if (requestCode == 1001) {
            Log.d(TAG, "🔧 User returned from Usage Access settings");
            // Check if permission was granted
            new android.os.Handler().postDelayed(() -> {
                checkUsageAccessPermission();
            }, 1000); // Delay to ensure settings have been applied
            return;
        }

        // Handle todo tasks
        if (resultCode == RESULT_OK && data != null) {
            Task t = (Task) data.getSerializableExtra("task");
            if (t == null) {
                Log.e(TAG, "Task received from intent is null");
                return;
            }

            String now = sdf.format(currentDate.getTime());
            Log.d(TAG, "Task received: " + t.title);

            if (requestCode == 1) {
                // New task
                t.createdDate = now;
                taskList.add(t);
                Log.d(TAG, "Added new task: " + t.title);
            } else if (requestCode == 2) {
                // Edited task
                int pos = data.getIntExtra("position", -1);
                if (pos >= 0 && pos < taskList.size()) {
                    t.createdDate = taskList.get(pos).createdDate;
                    t.dailyCompletionMap = taskList.get(pos).dailyCompletionMap;
                    taskList.set(pos, t);
                    Log.d(TAG, "Updated task at position " + pos + ": " + t.title);
                } else {
                    Log.e(TAG, "Invalid position for task edit: " + pos);
                }
            }

            showTasks();
        }
    }

    private void startRemoteBlockService() {
        try {
            Intent serviceIntent = new Intent(this, RemoteBlockService.class);
            startForegroundService(serviceIntent);
            Log.d(TAG, "Started RemoteBlockService for focus mode commands");
        } catch (Exception e) {
            Log.e(TAG, "Error starting RemoteBlockService: " + e.getMessage());
            Toast.makeText(this, "Error starting blocking service", Toast.LENGTH_SHORT).show();
        }
    }

    private void startDailyUsageLimitService() {
        try {
            Intent serviceIntent = new Intent(this, DailyUsageLimitService.class);
            startForegroundService(serviceIntent);
            Log.d(TAG, "Started DailyUsageLimitService for daily limits");
        } catch (Exception e) {
            Log.e(TAG, "Error starting DailyUsageLimitService: " + e.getMessage());
            Toast.makeText(this, "Error starting daily limits service", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Start the DailyTimerResetService to ensure automatic midnight resets
     * for both timers and daily usage limits work even when app is closed
     */
    private void startDailyTimerResetService() {
        try {
            Log.d(TAG, "🕛 Starting DailyTimerResetService for automatic midnight resets");
            Intent serviceIntent = new Intent(this, DailyTimerResetService.class);
            startService(serviceIntent);
            Log.d(TAG, "✅ DailyTimerResetService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start DailyTimerResetService: " + e.getMessage());
        }
    }

    /**
     * Check and request permissions needed for reliable timer functionality on
     * CHILD device
     */
    private void checkTimerPermissions() {
        try {
            Log.d(TAG, "🔋 Checking battery optimization permissions for timer service");

            // Log current permission status
            batteryOptimizationManager.logPermissionStatus();

            // Check if we need to request permissions
            batteryOptimizationManager.checkAndRequestAllPermissions(this);

            // 🔧 TIMER FIX: Check Usage Access permission - CRITICAL for timer
            // functionality
            checkUsageAccessPermission();

        } catch (Exception e) {
            Log.e(TAG, "Error checking timer permissions: " + e.getMessage());
        }
    }

    /**
     * 🔧 TIMER FIX: Check and request Usage Access permission
     * This is CRITICAL for the timer to detect which app is running
     */
    private void checkUsageAccessPermission() {
        Log.d(TAG, "🔍 Checking Usage Access permission...");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                android.app.usage.UsageStatsManager usm = (android.app.usage.UsageStatsManager) getSystemService(
                        Context.USAGE_STATS_SERVICE);

                boolean hasPermission = false;
                if (usm != null) {
                    long now = System.currentTimeMillis();
                    long oneMinuteAgo = now - java.util.concurrent.TimeUnit.MINUTES.toMillis(1);

                    try {
                        android.app.usage.UsageEvents events = usm.queryEvents(oneMinuteAgo, now);
                        hasPermission = (events != null && events.hasNextEvent());
                    } catch (SecurityException se) {
                        hasPermission = false;
                        Log.w(TAG, "⚠️ Usage Access permission is denied");
                    }
                }

                if (hasPermission) {
                    Log.d(TAG, "✅ Usage Access permission is GRANTED - Timer will work properly");
                } else {
                    Log.w(TAG, "❌ Usage Access permission is DENIED - Timer cannot detect running apps!");
                    showUsageAccessPermissionDialog();
                }

            } catch (Exception e) {
                Log.e(TAG, "Error checking Usage Access permission: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "📱 Android version < 5.1 - Usage Access not required");
        }
    }

    /**
     * 🔧 TIMER FIX: Show dialog to request Usage Access permission
     */
    private void showUsageAccessPermissionDialog() {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ Timer Setup Required")
                .setMessage("For the timer to work properly, this app needs access to see which apps are running.\n\n" +
                        "Please follow these steps:\n" +
                        "1. Tap 'Open Settings'\n" +
                        "2. Find and select this app (Master2)\n" +
                        "3. Toggle the switch to ON\n" +
                        "4. Come back to this app\n\n" +
                        "Without this permission, the timer cannot start when monitored apps are opened.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        startActivityForResult(intent, 1001);
                        Log.d(TAG, "🔧 Opened Usage Access settings");
                    } catch (Exception e) {
                        Log.e(TAG, "Error opening Usage Access settings: " + e.getMessage());
                        Toast.makeText(this, "Please go to Settings > Apps > Special access > Usage access",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Skip", (dialog, which) -> {
                    Log.w(TAG, "⚠️ User skipped Usage Access permission - Timer may not work");
                    Toast.makeText(this, "Timer may not work properly without this permission",
                            Toast.LENGTH_LONG).show();
                })
                .setCancelable(false)
                .show();
    }

    private void addLogoutButton() {
        // Logout button removed from settings layout - now shows educational content
        // for children
        // The settings page now displays helpful information about the app instead of
        // controls
        Log.d(TAG, "Settings page now shows educational content for children");
    }

    private void showManualLogoutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect")
                .setMessage("Are you sure you want to disconnect from " + parentName
                        + "?\n\nYou will need to scan the QR code again to reconnect.")
                .setPositiveButton("Disconnect", (dialog, which) -> {
                    performLogout();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogout() {
        Log.d(TAG, "🚪 PERFORMING BULLETPROOF LOGOUT - Starting comprehensive logout process");

        try {
            // 🛡️ BULLETPROOF CLEANUP: Use nuclear cleanup system
            String childDeviceId = sessionManager.getChildDeviceId();
            if (childDeviceId != null) {
                BulletproofConnectionCleanup cleanup = new BulletproofConnectionCleanup(this);
                cleanup.performNuclearChildDeviceCleanup(childDeviceId);

                // Verify cleanup was successful
                boolean cleanupSuccess = cleanup.verifyCleanupSuccess();
                Log.d(TAG, "🔍 Bulletproof cleanup success: " + cleanupSuccess);
            }

            // Stop persistent connection service
            com.example.master2.services.PersistentConnectionService.stopService(this);
            Log.d(TAG, "✅ Persistent connection service stopped");

            // Clear Firebase device status (backup cleanup)
            clearFirebaseDeviceStatus();

            // Clear all local connection state (backup cleanup)
            clearAllConnectionState();

            // Clear session (backup cleanup)
            sessionManager.logoutUser();
            Log.d(TAG, "✅ Session cleared");

            // Clear local preferences (backup cleanup)
            SharedPreferences prefs = getSharedPreferences("blocked_apps", MODE_PRIVATE);
            prefs.edit().clear().apply();
            Log.d(TAG, "✅ Blocked apps preferences cleared");

            // Clear any other app-specific preferences (backup cleanup)
            try {
                String[] additionalPrefs = { "todo_prefs", "device_info", "device_connection",
                        "logout_state", "auth_cache", "app_state" };

                for (String prefName : additionalPrefs) {
                    SharedPreferences prefTolear = getSharedPreferences(prefName, MODE_PRIVATE);
                    prefTolear.edit().clear().apply();
                    Log.d(TAG, "✅ Cleared preference: " + prefName);
                }

                // 🔧 BULLETPROOF: Set nuclear disconnection flags
                SharedPreferences disconnectionPrefs = getSharedPreferences("disconnection_state", MODE_PRIVATE);
                disconnectionPrefs.edit()
                        .putBoolean("device_was_removed", true)
                        .putBoolean("bulletproof_logout_completed", true)
                        .putBoolean("require_qr_reconnection", true)
                        .putLong("logout_timestamp", System.currentTimeMillis())
                        .putString("logout_reason", "parent_device_removal")
                        .apply();
                Log.d(TAG, "✅ Nuclear disconnection flags set");

            } catch (Exception e) {
                Log.e(TAG, "Error clearing additional preferences: " + e.getMessage());
            }

            Toast.makeText(this, "🚪 Device removed by parent - Scan QR code to reconnect", Toast.LENGTH_LONG).show();
            Log.d(TAG, "✅ Toast shown to user");

            // 🔧 BULLETPROOF NAVIGATION: Enhanced navigation to ensure proper logout
            try {
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK |
                        Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
                intent.putExtra("logout_message", "Device was removed by parent");
                intent.putExtra("require_qr_reconnection", true);
                intent.putExtra("force_login_screen", true);
                intent.putExtra("auto_logout_completed", true);
                intent.putExtra("bulletproof_cleanup_completed", true); // �️ Bulletproof flag
                intent.putExtra("device_was_removed", true); // 🛡️ Explicit removal flag

                Log.d(TAG, "🚪 Starting MainActivity with bulletproof logout flags...");
                startActivity(intent);
                Log.d(TAG, "✅ MainActivity started successfully");

                // 🔧 BULLETPROOF: Force finish with delay to ensure navigation completes
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    finish();
                    Log.d(TAG, "✅ ChildDashboardActivity finished with bulletproof cleanup");
                }, 500); // 500ms delay

            } catch (Exception e) {
                Log.e(TAG, "❌ Error navigating to MainActivity: " + e.getMessage());
                // Fallback: direct finish
                finish();
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error during bulletproof logout: " + e.getMessage());
            e.printStackTrace();
            Toast.makeText(this, "Error during logout: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void clearFirebaseDeviceStatus() {
        try {
            String deviceId = sessionManager.getChildDeviceId();
            if (deviceId != null) {
                DatabaseReference deviceRef = FirebaseDatabase.getInstance()
                        .getReference("children")
                        .child(deviceId);

                Map<String, Object> disconnectionData = new HashMap<>();
                disconnectionData.put("status", "disconnected");
                disconnectionData.put("isMonitored", false);
                disconnectionData.put("disconnectedAt", System.currentTimeMillis());
                disconnectionData.put("requiresQRReconnection", true);

                deviceRef.updateChildren(disconnectionData)
                        .addOnSuccessListener(aVoid -> Log.d(TAG, "✅ Firebase device status cleared"))
                        .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to clear Firebase status: " + e.getMessage()));
            }
        } catch (Exception e) {
            Log.e(TAG, "Error clearing Firebase device status: " + e.getMessage());
        }
    }

    private void clearAllConnectionState() {
        // Clear all connection-related shared preferences
        String[] prefNames = { "device_connection", "child_prefs", "focus_mode_prefs" };

        for (String prefName : prefNames) {
            try {
                getSharedPreferences(prefName, MODE_PRIVATE).edit().clear().apply();
                Log.d(TAG, "✅ Cleared preferences: " + prefName);
            } catch (Exception e) {
                Log.e(TAG, "❌ Error clearing preferences " + prefName + ": " + e.getMessage());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "ChildDashboardActivity resumed");

        // CRITICAL: Restore connection if it was lost during app pause/background
        restoreParentConnection();

        // Notify device status manager that app is back in foreground
        if (deviceStatusManager != null) {
            deviceStatusManager.setAppActive(true);
            Log.d(TAG, "Device marked as active");
        } else {
            Log.w(TAG, "Device status manager is null after connection restore");
        }

        // 🔧 TIMER PERSISTENCE FIX: Restore timer monitoring when app resumes
        Log.d(TAG, "🔄 App resumed - restoring timer monitoring");
        ensureBackgroundServicesRunning(); // Ensure all background services are running
        setupTimerMonitoring(); // Re-establish timer monitoring and display

        // Permissions are handled in ChildPermissionsActivity - no need to ask again
        // But we should ensure RemoteBlockService is collecting usage data
        ensureUsageDataCollection();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "ChildDashboardActivity paused");

        // CRITICAL: Save session data on pause to ensure persistence
        if (parentName != null && childDeviceId != null) {
            Log.d(TAG, "💾 Saving session data on pause");
            sessionManager.saveChildSession(childDeviceId, parentName, shareKey);
            sessionManager.updateLastActivity();
        }

        // Notify device status manager that app is going to background
        if (deviceStatusManager != null) {
            deviceStatusManager.setAppActive(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "ChildDashboardActivity destroyed - preserving connection data");

        try {
            // Stop bulletproof usage tracking
            if (bulletproofUsageTracker != null) {
                bulletproofUsageTracker.stop();
                Log.d(TAG, "🛑 Stopped bulletproof usage tracking");
            }
            // IMPORTANT: Only stop status tracking, but DON'T clear connection data
            // This preserves the parent-child connection across app restarts
            if (deviceStatusManager != null) {
                deviceStatusManager.setAppActive(false); // Mark as inactive, but keep connection
                // DO NOT call stopStatusTracking() - this would clear the connection
                Log.d(TAG, "Device marked as inactive but connection preserved");
            }

            // OLD TRACKERS REMOVED - only BulletproofUsageTracker is used now

            // NEW: Unregister logout broadcast receiver
            if (logoutReceiver != null) {
                try {
                    unregisterReceiver(logoutReceiver);
                    Log.d(TAG, "Logout broadcast receiver unregistered");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to unregister logout receiver: " + e.getMessage());
                }
                logoutReceiver = null;
            }

            // Clear Firebase listeners but keep connection data intact
            if (snapshotRef != null) {
                snapshotRef.removeEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                    }

                    @Override
                    public void onCancelled(DatabaseError error) {
                    }
                });
            }

            // Clean up usage limiter resources
            stopLimiterCountdown();

            if (usageLimiterListener != null && usageLimiterRef != null) {
                usageLimiterRef.removeEventListener(usageLimiterListener);
            }

            if (foregroundAppReceiver != null) {
                try {
                    unregisterReceiver(foregroundAppReceiver);
                    Log.d(TAG, "Foreground app receiver unregistered");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to unregister foreground app receiver: " + e.getMessage());
                }
            }

            Log.d(TAG, "🧹 Usage limiter resources cleaned up");
            Log.d(TAG, "✅ App destroyed but parent connection preserved for next launch");

        } catch (Exception e) {
            Log.e(TAG, "Error in onDestroy: " + e.getMessage());
        }
    }

    private void initializeTodoFunctionality() {
        if (todoContent == null) {
            Log.e(TAG, "Cannot initialize Todo functionality: todoContent view is null");
            return;
        }

        // Initialize Todo List views within the included layout
        taskContainer = todoContent.findViewById(R.id.taskContainer);
        submitButton = todoContent.findViewById(R.id.btnSubmit);
        addButton = todoContent.findViewById(R.id.fabAddTask);
        btnPrev = todoContent.findViewById(R.id.btnPrev);
        btnNext = todoContent.findViewById(R.id.btnNext);
        txtSelectedDate = todoContent.findViewById(R.id.txtSelectedDate);

        if (taskContainer == null || submitButton == null || addButton == null ||
                btnPrev == null || btnNext == null || txtSelectedDate == null) {
            Log.e(TAG, "One or more Todo views were not found in the layout");
            return;
        }

        Log.d(TAG, "Todo views initialized successfully");
        updateDateDisplay();

        btnPrev.setOnClickListener(v -> {
            currentDate.add(Calendar.DATE, -1);
            updateDateDisplay();
        });

        btnNext.setOnClickListener(v -> {
            currentDate.add(Calendar.DATE, 1);
            updateDateDisplay();
        });

        addButton.setOnClickListener(v -> {
            Intent intent = new Intent(ChildDashboardActivity.this, AddTaskActivity.class);
            startActivityForResult(intent, 1);
        });

        submitButton.setOnClickListener(v -> {
            int total = 0, done = 0;
            String today = sdf.format(currentDate.getTime());

            for (Task t : taskList) {
                if (shouldShowTask(t, today)) {
                    total++;
                    if (t.isCompleted(today))
                        done++;
                }
            }

            Toast.makeText(this, "Completed " + done + " of " + total + " tasks.", Toast.LENGTH_SHORT).show();
        });
    }

    void updateDateDisplay() {
        if (txtSelectedDate != null) {
            txtSelectedDate.setText(sdf.format(currentDate.getTime()));
            showTasks();
        }
    }

    boolean shouldShowTask(Task t, String selectedDate) {
        if (t.isToday) {
            return selectedDate.equals(t.createdDate);
        } else {
            try {
                Date start = sdf.parse(t.createdDate);
                Date selected = sdf.parse(selectedDate);
                Calendar temp = Calendar.getInstance();
                temp.setTime(start);
                temp.add(Calendar.DATE, 7);
                return selected.compareTo(start) >= 0 && selected.before(temp.getTime());
            } catch (Exception e) {
                return false;
            }
        }
    }

    void showTasks() {
        if (taskContainer == null) {
            Log.e(TAG, "Cannot show tasks: taskContainer is null");
            return;
        }

        taskContainer.removeAllViews();
        String current = sdf.format(currentDate.getTime());

        for (int i = 0; i < taskList.size(); i++) {
            Task t = taskList.get(i);
            if (!shouldShowTask(t, current))
                continue;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(8, 8, 8, 8);

            CheckBox cb = new CheckBox(this);
            cb.setChecked(t.isCompleted(current));
            cb.setOnCheckedChangeListener((v, isChecked) -> t.setCompleted(current, isChecked));

            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView txtTitle = new TextView(this);
            txtTitle.setText(t.title);
            txtTitle.setTextSize(18);
            txtTitle.setTextColor(getResources().getColor(android.R.color.black));

            TextView txtTime = new TextView(this);
            txtTime.setText(t.time);
            txtTime.setTextSize(16);
            txtTime.setTextColor(getResources().getColor(android.R.color.black));

            texts.addView(txtTitle);
            texts.addView(txtTime);

            View.OnClickListener dialogListener = v -> showTaskDialog(t);
            txtTitle.setOnClickListener(dialogListener);
            txtTime.setOnClickListener(dialogListener);

            Button btnEdit = new Button(this);
            btnEdit.setText("✏️");
            btnEdit.setMinimumWidth(0);
            btnEdit.setPadding(8, 8, 8, 8);
            int finalI = i;
            btnEdit.setOnClickListener(v -> {
                Intent editIntent = new Intent(ChildDashboardActivity.this, AddTaskActivity.class);
                editIntent.putExtra("task", t);
                editIntent.putExtra("position", finalI);
                startActivityForResult(editIntent, 2);
            });

            row.addView(cb);
            row.addView(texts);
            row.addView(btnEdit);
            taskContainer.addView(row);
        }
    }

    void showTaskDialog(Task t) {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_task_detail);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        TextView titleView = dialog.findViewById(R.id.dialogTitle);
        TextView timeView = dialog.findViewById(R.id.dialogTime);
        TextView descView = dialog.findViewById(R.id.dialogDescription);
        Button closeButton = dialog.findViewById(R.id.dialogCloseBtn);

        titleView.setText(t.title);
        timeView.setText(t.time);
        descView.setText(t.description);

        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener(item -> {
            String title = item.getTitle() != null ? item.getTitle().toString() : "";

            // Use title-based navigation to avoid R.id issues
            if ("Home".equals(title)) {
                showMainContent();
                return true;
            } else if ("Settings".equals(title)) {
                showSettingsContent();
                return true;
            }
            return false;
        });
    }

    private void setupSettingsClickListeners() {
        // Settings buttons removed - settings page now shows educational content for
        // children
        // No interactive elements needed as it's now informational only
        Log.d(TAG, "Settings page is now informational - no button handlers needed");
    }

    private void showMainContent() {
        // Show simple home screen
        if (homeContent != null)
            homeContent.setVisibility(View.VISIBLE);
        if (todoContent != null)
            todoContent.setVisibility(View.GONE);
        if (settingsContent != null)
            settingsContent.setVisibility(View.GONE);
    }

    private void showTodoContent() {
        if (homeContent != null)
            homeContent.setVisibility(View.GONE);
        if (todoContent != null)
            todoContent.setVisibility(View.VISIBLE);
        if (settingsContent != null)
            settingsContent.setVisibility(View.GONE);
    }

    private void showSettingsContent() {
        if (homeContent != null)
            homeContent.setVisibility(View.GONE);
        if (todoContent != null)
            todoContent.setVisibility(View.GONE);
        if (settingsContent != null)
            settingsContent.setVisibility(View.VISIBLE);
    }

    private void updateUI() {
        // Enhanced connection status check - verify actual connection state
        if (parentName != null) {
            // UPDATED: Show ONLY the parent name as requested by user
            // "Connected to: " prefix removed
            tvParentName.setText(parentName);

            // Status text is now hidden in layout, so we don't need to set it
            // The green indicator is also hidden
        } else {
            // No connection data at all
            tvParentName.setText("Not Connected");
        }
    }

    /**
     * Check if the child device is actually connected to parent
     * This prevents false disconnection display when connection is still active
     */
    private boolean isActuallyConnected() {
        try {
            // Check multiple indicators of active connection

            // 1. Check if session data is valid and not emergency
            String deviceId = sessionManager.getChildDeviceId();
            String parentUserId = sessionManager.getParentUserId();

            if (deviceId != null && deviceId.startsWith("emergency_")) {
                Log.d(TAG, "🚨 Emergency session detected - not actually connected");
                return false;
            }

            // 2. Check if we have valid parent information
            if (parentUserId == null || parentName == null) {
                Log.d(TAG, "⚠️ Missing parent info - connection may be stale");
                return false;
            }

            // 3. Check connection state in shared preferences
            SharedPreferences connectionPrefs = getSharedPreferences("device_connection", MODE_PRIVATE);
            boolean connectionActive = connectionPrefs.getBoolean("is_connected", false);
            boolean isRealConnection = connectionPrefs.getBoolean("is_real_connection", false);
            long lastConnectionTime = connectionPrefs.getLong("connection_time", 0);
            String connectionStatus = connectionPrefs.getString("connection_status", "");

            // Consider connection stale if older than 24 hours
            long staleThreshold = 24 * 60 * 60 * 1000L; // 24 hours
            boolean isRecentConnection = (System.currentTimeMillis() - lastConnectionTime) < staleThreshold;

            if (!connectionActive || !isRealConnection || !isRecentConnection || !"active".equals(connectionStatus)) {
                Log.d(TAG, "⚠️ Connection state indicates stale or invalid connection");
                Log.d(TAG, "   Active: " + connectionActive + ", Real: " + isRealConnection +
                        ", Recent: " + isRecentConnection + ", Status: " + connectionStatus);
                return false;
            }

            // 4. Additional check: verify we're not showing emergency parent name
            if (parentName.contains("Emergency") || parentName.contains("Reconnection Needed") ||
                    parentName.contains("Data Recovery")) {
                Log.d(TAG, "⚠️ Emergency/recovery parent name detected");
                return false;
            }

            Log.d(TAG, "✅ Connection appears to be active and valid");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error checking connection status: " + e.getMessage());
            return false;
        }
    }

    /**
     * Start periodic UI refresh to keep connection status accurate
     * This prevents the interface from showing stale disconnection status
     */
    private void startPeriodicUIRefresh() {
        Handler uiRefreshHandler = new Handler(Looper.getMainLooper());

        Runnable uiRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                // Refresh UI every 30 seconds to keep status accurate
                updateUI();

                // Schedule next refresh
                uiRefreshHandler.postDelayed(this, 30000); // 30 seconds
            }
        };

        // Start the periodic refresh
        uiRefreshHandler.postDelayed(uiRefreshRunnable, 30000);

        Log.d(TAG, "🔄 Started periodic UI refresh (every 30 seconds) to maintain accurate connection status");
    }

    /**
     * Verify and restore connection when status appears stale
     */
    private void verifyAndRestoreConnection() {
        try {
            Log.d(TAG, "🔄 Verifying and restoring connection...");

            // Update UI to show verification in progress
            runOnUiThread(() -> {
                tvConnectionStatus.setText("Status: 🔄 Verifying connection...\n📡 Please wait");
            });

            // Get connection details
            String deviceId = sessionManager.getChildDeviceId();
            String parentUserId = sessionManager.getParentUserId();

            if (deviceId != null && parentUserId != null) {
                // Check if device still exists in parent's connected devices
                DatabaseReference parentDeviceRef = FirebaseDatabase.getInstance()
                        .getReference("parents")
                        .child(parentUserId)
                        .child("connectedChildDevices")
                        .child(deviceId);

                parentDeviceRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            // Connection exists in Firebase - update local state
                            Log.d(TAG, "✅ Connection verified in Firebase - restoring active status");

                            // Update local connection state with full validation data
                            SharedPreferences connectionPrefs = getSharedPreferences("device_connection", MODE_PRIVATE);
                            connectionPrefs.edit()
                                    .putBoolean("is_connected", true)
                                    .putBoolean("is_real_connection", true)
                                    .putString("connection_status", "active")
                                    .putLong("connection_time", System.currentTimeMillis())
                                    .putLong("last_verified_time", System.currentTimeMillis())
                                    .apply();

                            // Update UI to show active connection
                            runOnUiThread(() -> {
                                tvConnectionStatus.setText(
                                        "Status: ✅ Active - Remote blocking enabled\n🔍 Apps are being monitored");
                            });

                            // Ensure services are running
                            startRemoteBlockService();

                        } else {
                            // Device not found in parent's connected devices
                            Log.w(TAG, "⚠️ Device not found in parent's connected devices - connection may be lost");

                            runOnUiThread(() -> {
                                tvConnectionStatus.setText(
                                        "Status: ⚠️ Connection Lost\n🔄 Please scan QR code again to reconnect");
                            });
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "❌ Failed to verify connection: " + error.getMessage());
                        runOnUiThread(() -> {
                            tvConnectionStatus
                                    .setText("Status: ⚠️ Connection Check Failed\n🔄 Tap to retry or scan QR again");
                        });
                    }
                });
            } else {
                Log.w(TAG, "⚠️ Missing device or parent ID - cannot verify connection");
                runOnUiThread(() -> {
                    tvConnectionStatus
                            .setText("Status: ❌ Connection Data Missing\n📱 Please scan QR code to reconnect");
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error in connection verification: " + e.getMessage());
            runOnUiThread(() -> {
                tvConnectionStatus.setText("Status: ❌ Verification Error\n📱 Please scan QR code to reconnect");
            });
        }
    }

    private void addSampleTasks() {
        String today = sdf.format(new Date());

        // Create a few sample tasks
        Task task1 = new Task("Complete homework", "15:00", "Math homework from page 45-50", false, today);
        task1.createdDate = today;
        task1.setCompleted(today, false);
        taskList.add(task1);

        Task task2 = new Task("Clean room", "17:30", "Clean bedroom and organize desk", false, today);
        task2.createdDate = today;
        task2.setCompleted(today, true);
        taskList.add(task2);

        Calendar tomorrow = Calendar.getInstance();
        tomorrow.add(Calendar.DATE, 1);
        String tomorrowStr = sdf.format(tomorrow.getTime());

        Task task3 = new Task("Study for test", "14:00", "Science test preparation", false, today);
        task3.createdDate = today;
        task3.setCompleted(today, false);
        taskList.add(task3);

        Log.d(TAG, "Added " + taskList.size() + " sample tasks");
    }

    private void setupTimerMonitoring() {
        Log.d(TAG, "Setting up timer monitoring for device: " + childDeviceId);

        if (childDeviceId == null) {
            Log.e(TAG, "Child device ID is null, cannot setup timer monitoring");
            return;
        }

        // Listen for timer updates from Firebase
        DatabaseReference timerRef = FirebaseDatabase.getInstance()
                .getReference("active_timers")
                .child(childDeviceId);

        timerRef.addValueEventListener(new com.google.firebase.database.ValueEventListener() {
            @Override
            public void onDataChange(com.google.firebase.database.DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    try {
                        java.util.Map<String, Object> timerData = (java.util.Map<String, Object>) dataSnapshot
                                .getValue();
                        if (timerData != null) {
                            // Check if parent has cleared this timer before displaying
                            DatabaseReference flagsRef = FirebaseDatabase.getInstance().getReference()
                                    .child("parent_cleared_flags").child(childDeviceId);

                            flagsRef.addListenerForSingleValueEvent(
                                    new com.google.firebase.database.ValueEventListener() {
                                        @Override
                                        public void onDataChange(
                                                @NonNull com.google.firebase.database.DataSnapshot flagSnapshot) {
                                            if (flagSnapshot.exists()) {
                                                try {
                                                    java.util.Map<String, Object> flagData = (java.util.Map<String, Object>) flagSnapshot
                                                            .getValue();
                                                    if (flagData != null && flagData.containsKey("clearedAt")) {
                                                        Object clearedAtObj = flagData.get("clearedAt");
                                                        if (clearedAtObj instanceof Long) {
                                                            long clearedAt = (Long) clearedAtObj;
                                                            long currentTime = System.currentTimeMillis();
                                                            long fiveMinutesMs = 5 * 60 * 1000L; // 5 minutes

                                                            if (currentTime - clearedAt < fiveMinutesMs) {
                                                                Log.d(TAG,
                                                                        "🛑 Parent recently cleared timer, hiding display");
                                                                hideTimerDisplay();
                                                                return; // Don't show timer
                                                            }
                                                        }
                                                    }
                                                } catch (Exception e) {
                                                    Log.e(TAG, "Error checking cleared flag: " + e.getMessage());
                                                }
                                            }

                                            // No recent clear flag - proceed with normal display
                                            updateTimerDisplay(timerData);
                                        }

                                        @Override
                                        public void onCancelled(
                                                @NonNull com.google.firebase.database.DatabaseError databaseError) {
                                            Log.e(TAG, "Error reading cleared flags: " + databaseError.getMessage());
                                            // Proceed with display on error to be safe
                                            updateTimerDisplay(timerData);
                                        }
                                    });
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing timer data: " + e.getMessage());
                    }
                } else {
                    // No active timer
                    hideTimerDisplay();
                }
            }

            @Override
            public void onCancelled(com.google.firebase.database.DatabaseError databaseError) {
                Log.e(TAG, "Error listening for timer updates: " + databaseError.getMessage());
            }
        });
    }

    private void updateTimerDisplay(java.util.Map<String, Object> timerData) {
        try {
            Long remainingTime = (Long) timerData.get("remainingTime");
            Boolean isTimerRunning = (Boolean) timerData.get("isTimerRunning");
            Boolean isTimerActive = (Boolean) timerData.get("isTimerActive");
            String currentMonitoredApp = (String) timerData.get("currentMonitoredApp");

            if (remainingTime == null || isTimerRunning == null || isTimerActive == null) {
                Log.e(TAG, "Invalid timer data received from Firebase. Hiding timer.");
                hideTimerDisplay();
                return;
            }

            boolean shouldShowTimerCard = Boolean.TRUE.equals(isTimerActive) && remainingTime > 0;

            // Update timer card visibility only if it changes
            if (timerCard != null) {
                int newVisibility = shouldShowTimerCard ? android.view.View.VISIBLE : android.view.View.GONE;
                if (timerCard.getVisibility() != newVisibility) {
                    timerCard.setVisibility(newVisibility);
                    Log.d(TAG, "Timer card visibility changed to: " + (shouldShowTimerCard ? "VISIBLE" : "GONE"));
                }
            }

            if (!shouldShowTimerCard) {
                // Timer is not active or has expired
                String expiredTimeText = "00:00:00";
                if (!lastDisplayedTime.equals(expiredTimeText)) {
                    tvTimerDisplay.setText(expiredTimeText);
                    tvTimerDisplay.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
                    lastDisplayedTime = expiredTimeText;
                }
                String noTimerStatus = "No timer active";
                if (!lastDisplayedStatus.equals(noTimerStatus)) {
                    tvTimerStatus.setText(noTimerStatus);
                    tvTimerStatus.setTextColor(android.graphics.Color.parseColor("#F44336")); // Red
                    lastDisplayedStatus = noTimerStatus;
                }
                return;
            }

            // Update timer display
            String timeText = formatTime(remainingTime);
            if (!lastDisplayedTime.equals(timeText)) {
                tvTimerDisplay.setText(timeText);
                lastDisplayedTime = timeText;

                // Change color based on time left
                if (remainingTime <= 300000) { // 5 minutes
                    tvTimerDisplay.setTextColor(android.graphics.Color.parseColor("#FF9800")); // Orange
                } else {
                    tvTimerDisplay.setTextColor(android.graphics.Color.parseColor("#4CAF50")); // Green
                }
            }

            // Update status
            String newStatusText;
            int newStatusColor;

            if (Boolean.TRUE.equals(isTimerRunning)) {
                newStatusText = "Monitoring " + (currentMonitoredApp != null ? currentMonitoredApp : "...");
                newStatusColor = android.graphics.Color.parseColor("#4CAF50");
            } else { // Timer active but paused
                newStatusText = "Paused: " + (currentMonitoredApp != null ? currentMonitoredApp : "...")
                        + " not in foreground";
                newStatusColor = android.graphics.Color.parseColor("#FF9800");
            }

            if (!lastDisplayedStatus.equals(newStatusText)) {
                tvTimerStatus.setText(newStatusText);
                tvTimerStatus.setTextColor(newStatusColor);
                lastDisplayedStatus = newStatusText;
            }

            Log.d(TAG, "Timer updated: " + timeText + " - Active: " + isTimerActive + " - Running: " + isTimerRunning);

        } catch (Exception e) {
            Log.e(TAG, "Error updating timer display: " + e.getMessage());
            hideTimerDisplay(); // Hide on error
        }
    }

    private void hideTimerDisplay() {
        if (timerCard != null) {
            if (timerCard.getVisibility() != android.view.View.GONE) {
                timerCard.setVisibility(android.view.View.GONE);
                Log.d(TAG, "Timer display hidden - no active timer");
            }
        }
        // Reset last displayed values when hidden
        lastDisplayedTime = "";
        lastDisplayedStatus = "";
    }

    private String formatTime(long milliseconds) {
        if (milliseconds <= 0)
            return "00:00:00";

        long seconds = milliseconds / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        return String.format(java.util.Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, secs);
    }

    private void ensureUsageDataCollection() {
        // Check if usage access permission is granted
        android.app.AppOpsManager appOps = (android.app.AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), getPackageName());
        boolean hasUsageAccess = (mode == android.app.AppOpsManager.MODE_ALLOWED);

        if (hasUsageAccess) {
            Log.d(TAG, "✅ Usage access permission granted - RemoteBlockService should be collecting data");
            // Send a refresh signal to RemoteBlockService to ensure it's collecting data
            Intent refreshIntent = new Intent(this, RemoteBlockService.class);
            refreshIntent.putExtra("action", "refresh_usage_collection");
            startService(refreshIntent);
        } else {
            Log.w(TAG, "❌ Usage access permission not granted - no usage data will be collected");
            Log.w(TAG, "Please grant Usage Access permission in Settings to see usage data on parent dashboard");
        }
    }

    private void startSmartTimerService() {
        try {
            Intent serviceIntent = new Intent(this, SmartTimerService.class);
            serviceIntent.putExtra("deviceId", childDeviceId);
            serviceIntent.putExtra("shareKey", shareKey);
            startService(serviceIntent);
            Log.d(TAG, "SmartTimerService started for device: " + childDeviceId);
        } catch (Exception e) {
            Log.e(TAG, "Error starting SmartTimerService: " + e.getMessage());
        }
    }

    private void startLiveTimerService() {
        try {
            Intent serviceIntent = new Intent(this, LiveTimerService.class);
            // 🔧 FIX: Use regular startService instead of startForegroundService to prevent
            // crash
            startService(serviceIntent);
            Log.d(TAG, "LiveTimerService started for timer functionality (fixed crash)");
        } catch (Exception e) {
            Log.e(TAG, "Error starting LiveTimerService: " + e.getMessage());
            Toast.makeText(this, "Error starting live timer service", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Start Real-Time Data Sync Service for QR device pairing system
     */
    private void startRealTimeDataSyncService() {
        try {
            Log.d(TAG, "🚀 Starting RealTimeDataSyncService for QR pairing system");
            Intent syncIntent = new Intent(this, com.example.master2.services.RealTimeDataSyncService.class);
            startService(syncIntent);
            Log.d(TAG, "✅ RealTimeDataSyncService started successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start RealTimeDataSyncService: " + e.getMessage());
        }
    }

    /**
     * Restore persistent connection to parent device
     * This method ensures connection survives app restarts
     */
    private void restoreParentConnection() {
        try {
            // 🛡️ BULLETPROOF: Always attempt connection restore, never give up
            if (!sessionManager.isLoggedIn() || !"child".equals(sessionManager.getUserType())) {
                Log.w(TAG, "⚠️ Invalid session detected - attempting recovery before connection restore");

                // Try to recover session instead of giving up
                if (tryEmergencySessionRecovery()) {
                    Log.d(TAG, "✅ Session recovered successfully - proceeding with connection restore");
                } else {
                    Log.w(TAG, "⚠️ Session recovery failed - creating emergency session");
                    createEmergencySession();
                }
            }

            String storedParentName = sessionManager.getParentName();
            String storedChildDeviceId = sessionManager.getChildDeviceId();
            String storedParentUserId = sessionManager.getParentUserId();
            String storedShareKey = sessionManager.getQRShareKey();

            if (storedParentName != null && storedChildDeviceId != null) {
                Log.d(TAG, "🔄 Restoring persistent connection...");

                // Restore all connection variables
                parentName = storedParentName;
                childDeviceId = storedChildDeviceId;
                shareKey = storedShareKey;

                // Re-establish device status manager if needed
                if (deviceStatusManager == null && storedParentUserId != null) {
                    deviceStatusManager = new DeviceStatusManager(this);
                    String deviceName = ChildAppUtils.getChildDeviceName();
                    deviceStatusManager.startAsChildDevice(storedParentUserId, deviceName);
                    Log.d(TAG, "✅ Device status tracking restored");
                }

                // Update UI to reflect restored connection
                updateUI();

                // Ensure remote blocking service is running
                startRemoteBlockService();

                // Start persistent connection service to maintain connection across app
                // restarts
                com.example.master2.services.PersistentConnectionService.startService(this);

                Log.d(TAG, "🎉 Parent connection successfully restored!");
                Log.d(TAG, "   📱 Parent: " + storedParentName);
                Log.d(TAG, "   🆔 Child Device: " + storedChildDeviceId);
                Log.d(TAG,
                        "   👤 Parent User ID: " + (storedParentUserId != null
                                ? storedParentUserId.substring(0, Math.min(8, storedParentUserId.length())) + "..."
                                : "null"));

            } else {
                Log.w(TAG, "❌ Incomplete connection data - cannot restore");
                Log.w(TAG, "   Parent Name: " + storedParentName);
                Log.w(TAG, "   Child Device ID: " + storedChildDeviceId);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to restore parent connection: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 🛡️ BULLETPROOF RECOVERY METHODS
     * These methods ensure the child app NEVER logs out automatically
     */

    /**
     * Create emergency session backup to prevent data loss
     */
    private void createEmergencySessionBackup(String parentName, String childDeviceId, String shareKey) {
        try {
            SharedPreferences backupPrefs = getSharedPreferences("emergency_session_backup", MODE_PRIVATE);
            backupPrefs.edit()
                    .putString("parentName", parentName)
                    .putString("childDeviceId", childDeviceId)
                    .putString("shareKey", shareKey)
                    .putLong("lastBackupTime", System.currentTimeMillis())
                    .putBoolean("backupValid", true)
                    .apply();

            Log.d(TAG, "🛡️ Emergency session backup created successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to create emergency session backup: " + e.getMessage());
        }
    }

    /**
     * Try to recover session from intent data or shared preferences
     */
    private boolean tryEmergencySessionRecovery() {
        try {
            // First try intent data
            String intentParentName = getIntent().getStringExtra("parentName");
            String intentDeviceId = getIntent().getStringExtra("deviceId");
            String intentShareKey = getIntent().getStringExtra("shareKey");
            String intentParentUserId = getIntent().getStringExtra("parentUserId");

            if (intentParentName != null && intentDeviceId != null) {
                Log.d(TAG, "🔄 Recovering session from intent data");
                sessionManager.saveChildSession(intentDeviceId, intentParentName, intentShareKey);
                if (intentParentUserId != null) {
                    sessionManager.saveParentUserId(intentParentUserId);
                }
                return true;
            }

            // Try to recover from shared preferences backup
            SharedPreferences backupPrefs = getSharedPreferences("emergency_session_backup", MODE_PRIVATE);
            String backupParentName = backupPrefs.getString("parentName", null);
            String backupDeviceId = backupPrefs.getString("childDeviceId", null);
            String backupShareKey = backupPrefs.getString("shareKey", null);

            if (backupParentName != null && backupDeviceId != null) {
                Log.d(TAG, "🔄 Recovering session from emergency backup");
                sessionManager.saveChildSession(backupDeviceId, backupParentName, backupShareKey);
                return true;
            }

        } catch (Exception e) {
            Log.e(TAG, "Emergency recovery failed: " + e.getMessage());
        }

        return false;
    }

    /**
     * Create an emergency session to prevent logout - IMPROVED VERSION
     * This creates a minimal session that won't interfere with real connections
     */
    private void createEmergencySession() {
        try {
            // Check if there's a recent real connection first - don't overwrite it
            if (hasRecentRealConnection()) {
                Log.d(TAG, "🛡️ Recent real connection detected - skipping emergency session creation");
                return;
            }

            // Generate emergency connection data with clear indicators
            String emergencyDeviceId = "emergency_" + System.currentTimeMillis();
            String emergencyParentName = "📱 Please Scan QR Code";

            Log.d(TAG, "🚨 Creating emergency session to prevent logout (won't interfere with real connections)");
            sessionManager.saveChildSession(emergencyDeviceId, emergencyParentName, null);

            // Save emergency backup with clear markers
            SharedPreferences backupPrefs = getSharedPreferences("emergency_session_backup", MODE_PRIVATE);
            backupPrefs.edit()
                    .putString("parentName", emergencyParentName)
                    .putString("childDeviceId", emergencyDeviceId)
                    .putLong("emergencyCreatedAt", System.currentTimeMillis())
                    .putBoolean("isEmergencySession", true) // Clear marker
                    .apply();

            Log.d(TAG,
                    "✅ Emergency session created successfully (temporary - will not interfere with real connection)");

        } catch (Exception e) {
            Log.e(TAG, "Failed to create emergency session: " + e.getMessage());
        }
    }

    /**
     * Check if there's a recent real (non-emergency) connection
     */
    private boolean hasRecentRealConnection() {
        try {
            SharedPreferences connectionPrefs = getSharedPreferences("device_connection", MODE_PRIVATE);
            String connectionMethod = connectionPrefs.getString("connection_method", "");
            long connectionTime = connectionPrefs.getLong("connection_time", 0);
            boolean isRealConnection = connectionPrefs.getBoolean("is_real_connection", false);
            String connectionStatus = connectionPrefs.getString("connection_status", "");

            // Consider connection recent if within last hour and was made via QR scan
            long oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000L);
            boolean isRecentQRConnection = "qr_scan".equals(connectionMethod) &&
                    connectionTime > oneHourAgo &&
                    isRealConnection &&
                    "active".equals(connectionStatus);

            if (isRecentQRConnection) {
                Log.d(TAG, "✅ Recent QR connection found - preserving it");
                return true;
            }

            // Also check session manager for valid non-emergency data
            String currentDeviceId = sessionManager.getChildDeviceId();
            String currentParentName = sessionManager.getParentName();

            if (currentDeviceId != null && !currentDeviceId.startsWith("emergency_") &&
                    currentParentName != null && !currentParentName.contains("Scan QR") &&
                    !currentParentName.contains("Reconnection Needed")) {
                Log.d(TAG, "✅ Valid existing connection found - preserving it");
                return true;
            }

            return false;

        } catch (Exception e) {
            Log.e(TAG, "Error checking for recent real connection: " + e.getMessage());
            return false;
        }
    }

    /**
     * Recover missing connection data without logging out
     */
    private void recoverMissingConnectionData() {
        try {
            // Try to get from session first
            if (parentName == null) {
                parentName = sessionManager.getParentName();
                if (parentName == null) {
                    parentName = "Parent Device (Data Recovery)";
                }
            }

            if (childDeviceId == null) {
                childDeviceId = sessionManager.getChildDeviceId();
                if (childDeviceId == null) {
                    childDeviceId = "recovery_" + System.currentTimeMillis();
                    // Re-save session with recovered data
                    sessionManager.saveChildSession(childDeviceId, parentName, shareKey);
                }
            }

            Log.d(TAG, "✅ Connection data recovery completed");
            Log.d(TAG, "   Parent Name: " + parentName);
            Log.d(TAG, "   Child Device ID: " + childDeviceId);

        } catch (Exception e) {
            Log.e(TAG, "Connection data recovery failed: " + e.getMessage());
        }
    }

    /**
     * 🔧 PRODUCTION STABILITY: Ensures all background services are running properly
     * This method is called when the app resumes to guarantee persistence
     */
    private void ensureBackgroundServicesRunning() {
        try {
            Log.d(TAG, "🔧 Ensuring all background services are running...");

            // Ensure LiveTimerService is running
            startLiveTimerService();
            Log.d(TAG, "✅ LiveTimerService started/verified");

            // DISABLED: Don't automatically start SmartTimerService - let parent control
            // timers
            // try {
            // Intent smartTimerIntent = new Intent(this, SmartTimerService.class);
            // startService(smartTimerIntent);
            // Log.d(TAG, "✅ SmartTimerService started/verified");
            // } catch (Exception e) {
            // Log.d(TAG, "SmartTimerService start attempt: " + e.getMessage());
            // }

            // Ensure RemoteBlockService is running
            try {
                Intent blockServiceIntent = new Intent(this, RemoteBlockService.class);
                startService(blockServiceIntent);
                Log.d(TAG, "✅ RemoteBlockService started/verified");
            } catch (Exception e) {
                Log.d(TAG, "RemoteBlockService start attempt: " + e.getMessage());
            }

            Log.d(TAG, "✅ All background services verification completed");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error ensuring background services: " + e.getMessage());
        }
    }

    // ================================
    // USAGE LIMITER FUNCTIONALITY
    // ================================

    /**
     * Initialize usage limiter functionality with UI setup and Firebase listeners
     */
    private void initializeUsageLimiter() {
        try {
            Log.d(TAG, "🚀 Initializing enhanced usage limiter functionality...");
            Log.d(TAG, "🚀 Child Device ID: " + childDeviceId);

            if (childDeviceId == null || childDeviceId.isEmpty()) {
                Log.w(TAG, "⚠️ Cannot initialize usage limiter: childDeviceId is null/empty");
                return;
            }

            // Initialize handler for UI updates only
            limiterUpdateHandler = new Handler(Looper.getMainLooper());

            // Setup Firebase reference
            usageLimiterRef = FirebaseDatabase.getInstance()
                    .getReference("usage_limiters")
                    .child(childDeviceId);

            Log.d(TAG, "🚀 Firebase reference path: usage_limiters/" + childDeviceId);

            // Start monitoring usage limiter data from Firebase (UI updates only)
            startLimiterUIMonitoring();

            // Hide usage limiter card initially
            if (usageLimiterCard != null) {
                usageLimiterCard.setVisibility(View.GONE);
                Log.d(TAG, "🚀 Usage limiter card found and initially hidden");
            } else {
                Log.w(TAG, "⚠️ Usage limiter card is null!");
            }

            Log.d(TAG, "✅ Enhanced usage limiter initialization completed - service handles timer logic");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error initializing usage limiter: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Start monitoring usage limiter data from Firebase for UI updates only
     * (Timer logic is handled by EnhancedUsageLimiterService)
     */
    private void startLimiterUIMonitoring() {
        try {
            Log.d(TAG, "📡 Starting usage limiter Firebase monitoring...");

            if (usageLimiterRef == null) {
                Log.e(TAG, "❌ Cannot start monitoring: usageLimiterRef is null");
                return;
            }

            // Remove any existing listener
            if (usageLimiterListener != null) {
                usageLimiterRef.removeEventListener(usageLimiterListener);
            }

            usageLimiterListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                    try {
                        Log.d(TAG, "📊 Usage limiter data received from Firebase for device: " + childDeviceId);
                        Log.d(TAG, "📊 DataSnapshot exists: " + dataSnapshot.exists());

                        if (dataSnapshot.exists()) {
                            // Parse limiter data
                            Map<String, Object> limiterData = (Map<String, Object>) dataSnapshot.getValue();
                            if (limiterData != null) {
                                Log.d(TAG, "📊 Limiter data keys: " + limiterData.keySet());
                                Log.d(TAG, "📊 isActive: " + limiterData.get("isActive"));
                                Log.d(TAG, "📊 remainingTimeMs: " + limiterData.get("remainingTimeMs"));
                                Log.d(TAG, "📊 selectedApps: " + limiterData.get("selectedApps"));
                                processLimiterData(limiterData);
                            } else {
                                Log.w(TAG, "📭 Limiter data is null even though snapshot exists");
                                hideUsageLimiterCard();
                            }
                        } else {
                            Log.d(TAG, "📭 No usage limiter data found for device " + childDeviceId
                                    + " - hiding limiter card");
                            hideUsageLimiterCard();
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error processing usage limiter data: " + e.getMessage());
                        e.printStackTrace();
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError databaseError) {
                    Log.e(TAG, "❌ Usage limiter monitoring cancelled: " + databaseError.getMessage());
                }
            };

            // Attach listener
            usageLimiterRef.addValueEventListener(usageLimiterListener);
            Log.d(TAG, "✅ Usage limiter Firebase monitoring started successfully");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting limiter monitoring: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Process usage limiter data received from Firebase
     */
    private void processLimiterData(Map<String, Object> limiterData) {
        try {
            // CRITICAL: Prevent recursive processing during our own Firebase updates
            if (isUpdatingFirebase) {
                Log.d(TAG, "🔄 Skipping Firebase data processing - we're updating Firebase ourselves");
                return;
            }

            Log.d(TAG, "🔄 Processing usage limiter data...");

            // Extract data fields
            Boolean isActive = (Boolean) limiterData.get("isActive");
            Long remainingTimeMsData = (Long) limiterData.get("remainingTimeMs");
            List<String> selectedApps = (List<String>) limiterData.get("selectedApps");

            Log.d(TAG, "🔄 Timer data - isActive: " + isActive + ", remainingTimeMs: " + remainingTimeMsData);
            Log.d(TAG, "🔄 Selected apps: " + selectedApps);

            // Validate required fields
            if (isActive == null || remainingTimeMsData == null) {
                Log.w(TAG, "⚠️ Invalid limiter data - missing required fields (isActive: " + isActive
                        + ", remainingTimeMs: " + remainingTimeMsData + ")");
                hideUsageLimiterCard();
                return;
            }

            // For testing: ALWAYS show timer if data exists, ignore date/day restrictions
            Log.d(TAG, "� Timer data is valid - showing timer regardless of date restrictions for testing");

            // Update local state
            isLimiterActive = Boolean.TRUE.equals(isActive);
            remainingTimeMs = remainingTimeMsData;
            limitedApps = selectedApps != null ? selectedApps : new ArrayList<>();

            Log.d(TAG, "📋 Limiter state - Active: " + isLimiterActive +
                    ", Remaining: " + formatTime(remainingTimeMs) +
                    ", Limited apps count: " + limitedApps.size());

            if (limitedApps != null && !limitedApps.isEmpty()) {
                Log.d(TAG, "📱 Limited apps: " + limitedApps.toString());
            } else {
                Log.w(TAG, "⚠️ No limited apps found! selectedApps from Firebase was: " + selectedApps);
            }

            // Update UI - timer logic is handled by EnhancedUsageLimiterService
            updateLimiterUI();

            Log.d(TAG, "✅ UI updated - timer logic handled by EnhancedUsageLimiterService");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error processing limiter data: " + e.getMessage());
            e.printStackTrace();
            hideUsageLimiterCard();
        }
    }

    /**
     * Check if usage limiter is active for today (old day-based system)
     */
    private boolean isLimiterActiveForToday(List<String> activeDays) {
        if (activeDays == null || activeDays.isEmpty()) {
            return false;
        }

        // Get current day of week
        Calendar calendar = Calendar.getInstance();
        String[] daysOfWeek = { "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
        String today = daysOfWeek[calendar.get(Calendar.DAY_OF_WEEK) - 1];

        boolean isActiveToday = activeDays.contains(today);
        Log.d(TAG, "📅 Today: " + today + ", Active days: " + activeDays + ", Active today: " + isActiveToday);

        return isActiveToday;
    }

    /**
     * Check if usage limiter is active for today (new date-based system)
     */
    private boolean isLimiterActiveForDateRange(String startDateStr, String endDateStr) {
        try {
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Date startDate = dateFormat.parse(startDateStr);
            Date endDate = dateFormat.parse(endDateStr);
            Date today = new Date();

            // Normalize today's date to compare only date part (not time)
            Calendar todayCalendar = Calendar.getInstance();
            todayCalendar.setTime(today);
            todayCalendar.set(Calendar.HOUR_OF_DAY, 0);
            todayCalendar.set(Calendar.MINUTE, 0);
            todayCalendar.set(Calendar.SECOND, 0);
            todayCalendar.set(Calendar.MILLISECOND, 0);
            Date todayNormalized = todayCalendar.getTime();

            // Check if today is within the date range (inclusive)
            boolean isInRange = (todayNormalized.equals(startDate) || todayNormalized.after(startDate)) &&
                    (todayNormalized.equals(endDate) || todayNormalized.before(endDate));

            Log.d(TAG, "📅 Date range check - Start: " + startDateStr + ", End: " + endDateStr +
                    ", Today: " + dateFormat.format(todayNormalized) + ", In range: " + isInRange);

            return isInRange;

        } catch (Exception e) {
            Log.e(TAG, "❌ Error parsing date range: " + e.getMessage());
            return false;
        }
    }

    /**
     * Update the usage limiter UI with current status and time
     */
    private void updateLimiterUI() {
        try {
            runOnUiThread(() -> {
                if (usageLimiterCard == null)
                    return;

                // Show timer card when timer is active (even if expired)
                // Only hide when timer is completely removed (isLimiterActive = false)
                if (isLimiterActive) {
                    // Show limiter card for BOTH active and expired timers
                    usageLimiterCard.setVisibility(View.VISIBLE);

                    // Update timer display
                    if (tvTimerDisplayLimiter != null) {
                        String timeText;
                        int textColor;

                        if (remainingTimeMs <= 0) {
                            // Timer expired - show 00:00:00 in red
                            timeText = "00:00:00";
                            textColor = Color.parseColor("#F44336"); // Red - expired

                            // � PERMANENT NOTIFICATION IS NOW HANDLED BY
                            // PermanentExpiredNotificationService
                            // No need to trigger notification here - service monitors Firebase
                            // automatically
                        } else {
                            // Timer active - show remaining time with color coding
                            timeText = formatTime(remainingTimeMs);

                            if (remainingTimeMs <= 300000) { // 5 minutes
                                textColor = Color.parseColor("#FF9800"); // Orange - low time
                            } else {
                                textColor = Color.parseColor("#4CAF50"); // Green - time remaining
                            }

                            // Service automatically hides notification when timer is active
                        }

                        tvTimerDisplayLimiter.setText(timeText);
                        tvTimerDisplayLimiter.setTextColor(textColor);
                    }

                    // Update status
                    if (tvTimerStatusLimiter != null) {
                        String statusText;
                        int statusColor;

                        if (remainingTimeMs <= 0) {
                            statusText = "Timer expired - Tap parent to remove";
                            statusColor = Color.parseColor("#F44336"); // Red for expired
                        } else if (isCurrentAppLimited()) {
                            statusText = "Monitoring: " + getCurrentAppName(currentForegroundApp);
                            statusColor = Color.parseColor("#FF9800");
                        } else {
                            statusText = "Timer active - " + limitedApps.size() + " apps limited";
                            statusColor = Color.parseColor("#4CAF50");
                        }

                        tvTimerStatusLimiter.setText(statusText);
                        tvTimerStatusLimiter.setTextColor(statusColor);
                    }

                    // Update selected apps display
                    updateSelectedAppsDisplay();

                    Log.d(TAG, "🔄 Limiter UI updated - Time: "
                            + (remainingTimeMs <= 0 ? "EXPIRED" : formatTime(remainingTimeMs)) +
                            ", Status: "
                            + (remainingTimeMs <= 0 ? "Expired" : (isCurrentAppLimited() ? "Monitoring" : "Active")));

                } else {
                    // Hide limiter card only when timer is removed (isLimiterActive = false)
                    hideUsageLimiterCard();

                    // � PERMANENT NOTIFICATION SERVICE AUTOMATICALLY HANDLES HIDING
                    // No need to manually hide - service monitors Firebase and handles it
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating limiter UI: " + e.getMessage());
        }
    }

    /**
     * Update the selected apps display section
     */
    private void updateSelectedAppsDisplay() {
        try {
            if (selectedAppsInfo == null || tvSelectedApps == null)
                return;

            if (limitedApps != null && !limitedApps.isEmpty()) {
                selectedAppsInfo.setVisibility(View.VISIBLE);

                StringBuilder appsText = new StringBuilder();
                for (int i = 0; i < limitedApps.size() && i < 5; i++) { // Show max 5 apps
                    String appName = getCurrentAppName(limitedApps.get(i));
                    if (i > 0)
                        appsText.append(", ");
                    appsText.append(appName);
                }

                if (limitedApps.size() > 5) {
                    appsText.append(" and ").append(limitedApps.size() - 5).append(" more");
                }

                tvSelectedApps.setText(appsText.toString());

            } else {
                selectedAppsInfo.setVisibility(View.GONE);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating selected apps display: " + e.getMessage());
        }
    }

    /**
     * Hide the usage limiter card
     */
    private void hideUsageLimiterCard() {
        runOnUiThread(() -> {
            if (usageLimiterCard != null) {
                usageLimiterCard.setVisibility(View.GONE);
                Log.d(TAG, "📱 Usage limiter card hidden");
            }

            // Stop countdown if running
            stopLimiterCountdown();

            // Hide timer notification
            hideTimerNotification();

            // Reset state
            isLimiterActive = false;
            remainingTimeMs = 0;
            isTimerCountingDown = false;
        });
    }

    /**
     * Start the limiter countdown timer - Only counts down when selected apps are
     * in foreground
     */
    private void startLimiterCountdown() {
        try {
            Log.d(TAG, "🔄 startLimiterCountdown() called - checking if timer already running...");

            // CRITICAL: Always stop existing countdown first to prevent multiple timers
            stopLimiterCountdown();

            if (!isLimiterActive || remainingTimeMs <= 0) {
                Log.d(TAG, "⏹️ Not starting countdown - limiter inactive or no time remaining");
                return;
            }

            // Double-check no timer is already running
            if (isTimerCountingDown) {
                Log.w(TAG, "⚠️ Timer already counting down! Stopping existing timer first...");
                stopLimiterCountdown();
            }

            Log.d(TAG, "⏱️ Starting HIGH-PRECISION usage limiter countdown (real-time accuracy)...");
            isTimerCountingDown = true;
            lastTimerUpdateMs = System.currentTimeMillis(); // Initialize precise timing
            lastFirebaseUpdateMs = System.currentTimeMillis();

            // Initialize handler if needed
            if (limiterUpdateHandler == null) {
                limiterUpdateHandler = new Handler(Looper.getMainLooper());
            }

            limiterUpdateRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!isTimerCountingDown || !isLimiterActive) {
                        Log.d(TAG, "⏹️ Timer stopped - isTimerCountingDown: " + isTimerCountingDown
                                + ", isLimiterActive: " + isLimiterActive);
                        return;
                    }

                    try {
                        long currentTimeMs = System.currentTimeMillis();
                        long elapsedMs = currentTimeMs - lastTimerUpdateMs;

                        // Get current foreground app directly (integrated monitoring)
                        String detectedApp = getCurrentForegroundApp();
                        if (detectedApp != null && !detectedApp.equals(currentForegroundApp)) {
                            String previousApp = currentForegroundApp;
                            currentForegroundApp = detectedApp;
                            Log.d(TAG, "📱 App change detected: " + previousApp + " → " + currentForegroundApp);
                        }

                        // Check if current app is in the limited apps list
                        boolean isLimited = isCurrentAppLimited();

                        // Only count down when a limited app is active
                        if (isLimited) {
                            long beforeTime = remainingTimeMs;

                            // HIGH-PRECISION countdown: subtract the EXACT elapsed time
                            remainingTimeMs -= elapsedMs;

                            long afterTime = remainingTimeMs;

                            Log.d(TAG,
                                    "⏳ PRECISION TIMING: " + formatTimePrecise(beforeTime) + " → "
                                            + formatTimePrecise(afterTime) +
                                            " (elapsed: " + elapsedMs + "ms, app: " + currentForegroundApp + ")");

                            // Update Firebase periodically (not every 100ms to avoid spam)
                            if (currentTimeMs - lastFirebaseUpdateMs >= 1000) { // Update Firebase every 1 second
                                updateFirebaseRemainingTime();
                                lastFirebaseUpdateMs = currentTimeMs;
                            }

                            // Check if time is up
                            if (remainingTimeMs <= 0) {
                                Log.d(TAG, "⏰ PRECISION TIMER COMPLETED!");
                                remainingTimeMs = 0; // Don't go negative
                                handleLimiterTimeUp();
                                return; // Stop the timer
                            }
                        } else {
                            Log.v(TAG, "⏸️ TIMER PAUSED - App not limited: " + currentForegroundApp + " (elapsed: "
                                    + elapsedMs + "ms)");
                        }

                        // Update timestamp for next calculation
                        lastTimerUpdateMs = currentTimeMs;

                        // Update UI and notification (less frequently to save battery)
                        updateLimiterUI();
                        updateTimerNotification();

                    } catch (Exception e) {
                        Log.e(TAG, "❌ Error in precision timer: " + e.getMessage());
                    }

                    // Schedule next check in 100ms for high precision (10x more accurate)
                    if (isTimerCountingDown && isLimiterActive) {
                        limiterUpdateHandler.postDelayed(this, 100);
                    } else {
                        Log.d(TAG, "⏹️ Precision timer loop ended");
                    }
                }
            };

            // Start the single integrated timer
            limiterUpdateHandler.post(limiterUpdateRunnable);
            Log.d(TAG, "✅ HIGH-PRECISION timer started (100ms intervals for real-time accuracy)");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error starting precision limiter countdown: " + e.getMessage());
        }
    }

    /**
     * Stop ALL timer-related activities to prevent multiple countdown issues
     */
    private void stopLimiterCountdown() {
        try {
            Log.d(TAG, "🛑 STOPPING ALL TIMERS to prevent double-counting...");

            // Stop main countdown timer
            if (limiterUpdateRunnable != null && limiterUpdateHandler != null) {
                limiterUpdateHandler.removeCallbacks(limiterUpdateRunnable);
                Log.d(TAG, "⏹️ Main countdown timer stopped");
            }

            // Stop ANY remaining foreground monitoring timer
            if (foregroundAppRunnable != null && foregroundAppHandler != null) {
                foregroundAppHandler.removeCallbacks(foregroundAppRunnable);
                Log.d(TAG, "⏹️ Foreground monitoring timer stopped");
            }

            // Clear all handlers to prevent memory leaks
            limiterUpdateRunnable = null;
            foregroundAppRunnable = null;

            // Mark timer as not counting
            isTimerCountingDown = false;

            Log.d(TAG, "✅ ALL timer components completely stopped and cleared");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error stopping timers: " + e.getMessage());
            // Force stop anyway
            isTimerCountingDown = false;
        }
    }

    /**
     * Handle when limiter time is up - UPDATED: Show permanent notification instead
     * of blocking
     */
    private void handleLimiterTimeUp() {
        try {
            Log.d(TAG, "⏰ Usage limiter time is up!");

            // Stop countdown
            stopLimiterCountdown();

            // Update UI to show expired state
            remainingTimeMs = 0;
            updateLimiterUI();

            // 🔔 SHOW PERMANENT NOTIFICATION instead of blocking apps
            showPermanentLimiterExpiredNotification();

            // Update Firebase to mark timer as expired
            if (usageLimiterRef != null) {
                usageLimiterRef.child("remainingTimeMs").setValue(0);
                usageLimiterRef.child("isActive").setValue(false);
                usageLimiterRef.child("showPermanentNotification").setValue(true);
            }

            Log.d(TAG, "✅ Timer expired - Apps remain accessible with permanent notification");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error handling limiter time up: " + e.getMessage());
        }
    }

    /**
     * Get current foreground app using UsageStatsManager
     */
    private String getCurrentForegroundApp() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
                if (usageStatsManager == null)
                    return null;

                long currentTime = System.currentTimeMillis();
                long startTime = currentTime - 3000; // Last 3 seconds

                // Use simpler approach with UsageStats instead of UsageEvents
                List<UsageStats> usageStatsList = usageStatsManager.queryUsageStats(
                        UsageStatsManager.INTERVAL_DAILY,
                        startTime,
                        currentTime);

                if (usageStatsList != null && !usageStatsList.isEmpty()) {
                    // Find the most recently used app
                    UsageStats recentApp = null;
                    for (UsageStats usageStats : usageStatsList) {
                        if (recentApp == null || usageStats.getLastTimeUsed() > recentApp.getLastTimeUsed()) {
                            recentApp = usageStats;
                        }
                    }

                    if (recentApp != null) {
                        return recentApp.getPackageName();
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error getting current foreground app: " + e.getMessage());
        }
        return null;
    }

    /**
     * Check if the current foreground app is in the limited apps list
     */
    private boolean isCurrentAppLimited() {
        boolean isLimited = isAppLimited(currentForegroundApp);
        if (isLimiterActive) {
            Log.d(TAG,
                    "🔍 Checking if current app is limited - App: " + currentForegroundApp + ", Limited: " + isLimited);
        }
        return isLimited;
    }

    /**
     * Check if a specific app is in the limited apps list
     */
    private boolean isAppLimited(String packageName) {
        if (packageName == null || packageName.isEmpty() || limitedApps == null) {
            return false;
        }

        // Don't limit system apps and our own app
        if (packageName.equals(getPackageName()) ||
                packageName.startsWith("com.android") ||
                packageName.contains("launcher") ||
                packageName.contains("systemui") ||
                packageName.equals("android")) {
            return false;
        }

        boolean isLimited = limitedApps.contains(packageName);
        if (isLimited) {
            Log.v(TAG, "✅ App is limited: " + packageName);
        }

        return isLimited;
    }

    /**
     * 🔔 Show permanent notification when usage limiter expires
     * This notification cannot be dismissed and persists until timer resets
     */
    private void showPermanentLimiterExpiredNotification() {
        try {
            Log.d(TAG, "🔔 Creating permanent usage limiter expired notification...");

            Intent intent = new Intent(this, ChildDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Create notification channel for permanent notifications
            createPermanentLimiterNotificationChannel();

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "permanent_limiter_channel")
                    .setContentTitle("⏰ Usage Limit Reached")
                    .setContentText("Daily usage limit reached. Apps remain accessible.")
                    .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(false) // CRITICAL: Cannot be dismissed
                    .setOngoing(true) // CRITICAL: Persistent notification
                    .setColor(Color.parseColor("#FF5722")) // Deep orange for attention
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setStyle(new NotificationCompat.BigTextStyle()
                            .bigText("Your daily usage limit has been reached.\n\n" +
                                    "Apps remain accessible, but please be mindful of your screen time.\n\n" +
                                    "This reminder will remain until your parent clears the timer."))
                    .addAction(android.R.drawable.ic_menu_view, "View Dashboard", pendingIntent);

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                // Use unique notification ID for limiter notifications
                int limiterNotificationId = 8888;
                manager.notify(limiterNotificationId, builder.build());

                // Save notification state for persistence
                SharedPreferences notificationPrefs = getSharedPreferences("permanent_limiter_notifications",
                        MODE_PRIVATE);
                notificationPrefs.edit()
                        .putBoolean("limiter_expired_notification_active", true)
                        .putLong("notification_created_time", System.currentTimeMillis())
                        .putString("device_id", childDeviceId)
                        .apply();

                Log.d(TAG, "✅ Permanent usage limiter notification created");

                // Show a brief toast to inform user
                Toast.makeText(this, "⏰ Daily usage limit reached - Apps remain accessible", Toast.LENGTH_LONG).show();
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error creating permanent limiter notification: " + e.getMessage());
        }
    }

    /**
     * Create notification channel for permanent usage limiter notifications
     */
    private void createPermanentLimiterNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "permanent_limiter_channel",
                    "Usage Limit Notifications",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(
                    "Permanent notifications when daily usage limits are reached - Only removable by parent");
            channel.enableLights(true);
            channel.setLightColor(Color.parseColor("#FF5722"));
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(true);
            channel.setBypassDnd(false); // Respect Do Not Disturb

            // Make the notification channel non-blockable by user (requires system
            // permission)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                channel.setBlockable(false);
            }

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "✅ Permanent limiter notification channel created (non-removable)");
            }
        }
    }

    // ================================
    // � PERMANENT NOTIFICATION SERVICE INTEGRATION
    // ================================

    /**
     * Start permanent notification service for expired timer notifications
     */
    private void startPermanentNotificationService() {
        try {
            Intent serviceIntent = new Intent(this, PermanentExpiredNotificationService.class);

            // Start as foreground service for persistence
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            Log.d(TAG, "🚀 Permanent notification service started successfully");
        } catch (Exception e) {
            Log.e(TAG, "❌ Failed to start permanent notification service: " + e.getMessage());
        }
    }

    /**
     * NOTE: Bulletproof notification methods removed - now handled by
     * PermanentExpiredNotificationService
     * The dedicated service provides better persistence and notification management
     */

    /**
     * Handle app blocking when time is up or app usage exceeds limit
     * 🔔 UPDATED: No longer blocks apps - shows notification instead
     */
    private void handleAppBlocking(String packageName) {
        // 🔔 APP BLOCKING DISABLED - Using permanent notification instead
        Log.d(TAG, "🔔 App blocking DISABLED for: " + packageName + " - Using notification reminder instead");

        // Just show a brief reminder toast
        String appName = getCurrentAppName(packageName);
        Toast.makeText(this, "📱 Reminder: Daily limit reached for " + appName, Toast.LENGTH_SHORT).show();

        /*
         * ORIGINAL BLOCKING CODE - DISABLED
         * try {
         * Log.d(TAG, "🚫 Blocking limited app: " + packageName);
         * 
         * // Go to home screen
         * Intent homeIntent = new Intent(Intent.ACTION_MAIN);
         * homeIntent.addCategory(Intent.CATEGORY_HOME);
         * homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
         * startActivity(homeIntent);
         * 
         * // Show blocking notification
         * String appName = getCurrentAppName(packageName);
         * Toast.makeText(this, "🚫 Time limit reached for " + appName,
         * Toast.LENGTH_LONG).show();
         * 
         * Log.d(TAG, "✅ App blocked successfully: " + appName);
         * 
         * } catch (Exception e) {
         * Log.e(TAG, "❌ Error blocking app: " + e.getMessage());
         * }
         */
    }

    /**
     * Show dialog when timer expires - UPDATED: Apps remain accessible
     */
    private void showTimeUpDialog() {
        try {
            runOnUiThread(() -> {
                new AlertDialog.Builder(this)
                        .setTitle("⏰ Daily Limit Reached")
                        .setMessage("Your daily usage limit has been reached.\n\n" +
                                "Apps remain accessible, but please be mindful of your screen time.\n\n" +
                                "A reminder notification will stay visible until the timer resets at midnight.")
                        .setPositiveButton("OK", (dialog, which) -> {
                            dialog.dismiss();
                        })
                        .setCancelable(false)
                        .show();
            });

        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing time up dialog: " + e.getMessage());
        }
    }

    /**
     * Start BlockService for foreground app monitoring (now integrated into main
     * timer)
     */
    private void startBlockService() {
        try {
            // Note: Foreground app monitoring is now integrated into the main countdown
            // timer
            // to prevent double-counting and ensure precise 1-second intervals
            Log.d(TAG, "✅ BlockService integration noted - using integrated monitoring");
        } catch (Exception e) {
            Log.e(TAG, "❌ Error in BlockService setup: " + e.getMessage());
        }
    }

    /**
     * Start direct foreground app monitoring using UsageStats
     * NOTE: This is now DISABLED - monitoring is integrated into main timer to
     * prevent double-counting
     */
    private void startForegroundAppMonitoring() {
        Log.d(TAG, "⚠️ Separate foreground monitoring is DISABLED - using integrated timer monitoring only");
        // This method is intentionally empty to prevent duplicate timers
        // All monitoring is now handled in startLimiterCountdown()
    }

    /**
     * Setup foreground app monitoring using broadcast receiver
     */
    private void setupForegroundAppMonitoring() {
        try {
            Log.d(TAG, "📡 Setting up foreground app monitoring...");

            if (foregroundAppReceiver != null) {
                try {
                    unregisterReceiver(foregroundAppReceiver);
                } catch (Exception e) {
                    // Receiver might not have been registered
                }
            }

            foregroundAppReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if ("com.example.master2.APP_FOREGROUND".equals(intent.getAction())) {
                        String packageName = intent.getStringExtra("package_name");
                        Log.d(TAG, "📱 Foreground app broadcast received: " + packageName);

                        if (packageName != null && !packageName.equals(currentForegroundApp)) {
                            String previousApp = currentForegroundApp;
                            currentForegroundApp = packageName;
                            Log.d(TAG, "📱 Foreground app changed: " + previousApp + " → " + packageName);

                            // If limiter is active, check if we need to handle this app
                            if (isLimiterActive) {
                                boolean isLimited = isAppLimited(packageName);
                                Log.d(TAG, "⏱️ Timer active - App limited: " + isLimited + ", Time remaining: "
                                        + formatTime(remainingTimeMs));

                                // If time is up and user opened a limited app, block it
                                if (remainingTimeMs <= 0 && isLimited) {
                                    Log.d(TAG, "🚫 Time is up and limited app opened - blocking: " + packageName);
                                    handleAppBlocking(packageName);
                                }
                            }
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter("com.example.master2.APP_FOREGROUND");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                registerReceiver(foregroundAppReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(foregroundAppReceiver, filter);
            }

            Log.d(TAG, "✅ Foreground app monitoring setup completed");

        } catch (Exception e) {
            Log.e(TAG, "❌ Error setting up foreground app monitoring: " + e.getMessage());
        }
    }

    /**
     * Update remaining time in Firebase (prevents recursive listener calls)
     */
    private void updateFirebaseRemainingTime() {
        try {
            if (usageLimiterRef != null && remainingTimeMs >= 0) {
                isUpdatingFirebase = true; // Prevent recursive processing

                usageLimiterRef.child("remainingTimeMs").setValue(remainingTimeMs)
                        .addOnCompleteListener(task -> {
                            isUpdatingFirebase = false; // Reset flag when complete
                            if (task.isSuccessful()) {
                                Log.v(TAG, "✅ Firebase time updated: " + formatTime(remainingTimeMs));
                            } else {
                                Log.e(TAG, "❌ Failed to update Firebase time: " + task.getException());
                            }
                        });
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error updating Firebase remaining time: " + e.getMessage());
            isUpdatingFirebase = false; // Reset flag on error
        }
    }

    /**
     * Get app name from package name
     */
    private String getCurrentAppName(String packageName) {
        try {
            if (packageName == null || packageName.isEmpty()) {
                return "Unknown App";
            }

            PackageManager packageManager = getPackageManager();
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            String appName = (String) packageManager.getApplicationLabel(appInfo);
            return appName != null ? appName : packageName;

        } catch (Exception e) {
            // Return package name if we can't get the app name
            return packageName;
        }
    }

    // Timer Notification Management
    private static final String TIMER_NOTIFICATION_CHANNEL_ID = "TimerCountdownChannel";
    private static final int TIMER_NOTIFICATION_ID = 1001;
    private NotificationManager notificationManager;

    /**
     * Create notification channel for timer notifications
     */
    private void createTimerNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    TIMER_NOTIFICATION_CHANNEL_ID,
                    "App Timer Countdown",
                    NotificationManager.IMPORTANCE_DEFAULT // Changed from LOW to DEFAULT for better visibility
            );
            channel.setDescription("Shows remaining time for app usage limit and current status");
            channel.setShowBadge(true); // Show badge
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); // Show on lockscreen

            if (notificationManager == null) {
                notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            }

            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d(TAG, "✅ Timer notification channel created with DEFAULT importance");
            }
        }
    }

    /**
     * Show persistent timer notification
     */
    private void showTimerNotification() {
        try {
            if (notificationManager == null) {
                notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            }

            createTimerNotificationChannel();

            Intent notificationIntent = new Intent(this, ChildDashboardActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            String timeText = formatTime(remainingTimeMs);
            String title = "⏱️ App Timer: " + timeText;

            // Create detailed content text
            StringBuilder contentBuilder = new StringBuilder();

            // Show current status
            boolean isLimited = isCurrentAppLimited();
            if (isLimited) {
                contentBuilder.append("🔴 ACTIVE - Timer counting down");
            } else {
                contentBuilder.append("⏸️ PAUSED - Switch to limited app to continue");
            }

            // Show limited apps count
            if (limitedApps != null && !limitedApps.isEmpty()) {
                contentBuilder.append(" • ").append(limitedApps.size()).append(" apps limited");
            }

            // Show current app if it's limited
            if (isLimited && currentForegroundApp != null) {
                String appName = getCurrentAppName(currentForegroundApp);
                contentBuilder.append(" • Current: ").append(appName);
            }

            String contentText = contentBuilder.toString();

            Notification notification = new NotificationCompat.Builder(this, TIMER_NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(contentText)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(contentText))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true) // Makes it non-removable
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Changed from LOW to DEFAULT for better
                                                                      // visibility
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(false)
                    .setShowWhen(false) // Don't show timestamp
                    .build();

            if (notificationManager != null) {
                notificationManager.notify(TIMER_NOTIFICATION_ID, notification);
                Log.d(TAG, "✅ Timer notification updated: " + title + " | " + contentText);
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error showing timer notification: " + e.getMessage());
        }
    }

    /**
     * Update timer notification with current time
     */
    private void updateTimerNotification() {
        if (isLimiterActive && remainingTimeMs > 0) {
            showTimerNotification(); // This will update the existing notification
        }
    }

    /**
     * Hide timer notification
     */
    private void hideTimerNotification() {
        try {
            if (notificationManager == null) {
                notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            }

            if (notificationManager != null) {
                notificationManager.cancel(TIMER_NOTIFICATION_ID);
                Log.d(TAG, "✅ Timer notification hidden");
            }

        } catch (Exception e) {
            Log.e(TAG, "❌ Error hiding timer notification: " + e.getMessage());
        }
    }

    /**
     * Clear permanent limiter notifications when timer resets at midnight
     */
    public static void clearPermanentLimiterNotifications(Context context) {
        try {
            Log.d("ChildDashboardActivity", "🧹 Clearing permanent limiter notifications...");

            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                // Clear the permanent limiter notification
                manager.cancel(8888); // Same ID used in showPermanentLimiterExpiredNotification

                // Clear notification state from SharedPreferences
                SharedPreferences notificationPrefs = context.getSharedPreferences("permanent_limiter_notifications",
                        Context.MODE_PRIVATE);
                notificationPrefs.edit()
                        .putBoolean("limiter_expired_notification_active", false)
                        .putLong("notification_cleared_time", System.currentTimeMillis())
                        .apply();

                Log.d("ChildDashboardActivity", "✅ Permanent limiter notifications cleared for daily reset");
            }
        } catch (Exception e) {
            Log.e("ChildDashboardActivity", "❌ Error clearing permanent limiter notifications: " + e.getMessage());
        }
    }

    // ==========================================
    // Active App Timers Logic
    // ==========================================

    private void setupActiveTimersListener() {
        try {
            activeTimersCard = findViewById(R.id.activeTimersCard);
            rvActiveTimers = findViewById(R.id.rvActiveTimers);

            if (activeTimersCard == null || rvActiveTimers == null)
                return;

            activeTimerAdapter = new ActiveTimerAdapter(activeTimerList);
            rvActiveTimers.setLayoutManager(new LinearLayoutManager(this));
            rvActiveTimers.setAdapter(activeTimerAdapter);

            if (childDeviceId == null)
                return;

            activeTimersRef = FirebaseDatabase.getInstance()
                    .getReference("app_timers")
                    .child(childDeviceId);

            activeTimersListener = new ValueEventListener() {
                @Override
                public void onDataChange(@NonNull DataSnapshot snapshot) {
                    activeTimerList.clear();

                    for (DataSnapshot timerSnap : snapshot.getChildren()) {
                        try {
                            String packageName = timerSnap.child("packageName").getValue(String.class);
                            Long remainingMs = timerSnap.child("remainingTimeMillis").getValue(Long.class);
                            Boolean active = timerSnap.child("active").getValue(Boolean.class);

                            if (packageName != null && remainingMs != null
                                    && Boolean.TRUE.equals(active)) {
                                ActiveTimerItem item = new ActiveTimerItem();
                                item.packageName = packageName;
                                item.remainingTimeMillis = remainingMs;

                                // Get App Name and Icon
                                PackageManager pm = getPackageManager();
                                try {
                                    ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                                    item.appName = pm.getApplicationLabel(appInfo).toString();
                                    item.icon = pm.getApplicationIcon(appInfo);
                                } catch (PackageManager.NameNotFoundException e) {
                                    item.appName = packageName; // Fallback
                                    item.icon = null;
                                }

                                activeTimerList.add(item);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing active timer: " + e.getMessage());
                        }
                    }

                    // Sort by remaining time (ascending)
                    Collections.sort(activeTimerList, new Comparator<ActiveTimerItem>() {
                        @Override
                        public int compare(ActiveTimerItem o1, ActiveTimerItem o2) {
                            return Long.compare(o1.remainingTimeMillis, o2.remainingTimeMillis);
                        }
                    });

                    activeTimerAdapter.notifyDataSetChanged();

                    if (activeTimerList.isEmpty()) {
                        activeTimersCard.setVisibility(View.GONE);
                    } else {
                        activeTimersCard.setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "Active timers listener cancelled: " + error.getMessage());
                }
            };

            activeTimersRef.addValueEventListener(activeTimersListener);

        } catch (Exception e) {
            Log.e(TAG, "Error setting up active timers: " + e.getMessage());
        }
    }

    private String formatTimeLeft(long millis) {
        if (millis <= 0)
            return "0m left";
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %dm left", hours, minutes);
        } else {
            return String.format(Locale.getDefault(), "%dm left", minutes);
        }
    }

    // Inner Classes for Active Timers
    class ActiveTimerItem {
        String packageName;
        String appName;
        long remainingTimeMillis;
        Drawable icon;
    }

    class ActiveTimerAdapter extends RecyclerView.Adapter<ActiveTimerAdapter.ViewHolder> {
        private final List<ActiveTimerItem> timers;

        ActiveTimerAdapter(List<ActiveTimerItem> timers) {
            this.timers = timers;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_child_active_timer, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ActiveTimerItem item = timers.get(position);

            holder.tvAppName.setText(item.appName);

            if (item.remainingTimeMillis <= 0) {
                holder.tvTimeLeft.setText("Time's Up");
                holder.tvTimeLeft.setTextColor(Color.RED);
                // Make progress bar red if possible, or just max out
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    holder.pbTimer.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.RED));
                }
            } else {
                holder.tvTimeLeft.setText(formatTimeLeft(item.remainingTimeMillis));
                // Reset color to default (e.g., specific purple/primary if known, or
                // black/grey)
                // Assuming standard text color or checking layout. defaulting to generic text
                // color
                holder.tvTimeLeft.setTextColor(getResources().getColor(android.R.color.tab_indicator_text)); // Using a
                                                                                                             // safe
                                                                                                             // default
                                                                                                             // or
                                                                                                             // explicitly
                                                                                                             // Parsing
                                                                                                             // color
                // Actually, let's look at the screenshot, it was purple-ish text.
                // Using a safe dark grey/black for normal text or just parse the original
                // color.
                // Assuming original was a theme color. I'll use Color.parseColor("#4A4A4A") or
                // similar to be safe, or just Color.DKGRAY.
                holder.tvTimeLeft.setTextColor(Color.parseColor("#6200EE")); // Using the purple from screenshot
                                                                             // approximation

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    holder.pbTimer.setProgressTintList(
                            android.content.res.ColorStateList.valueOf(Color.parseColor("#6200EE")));
                }
            }

            if (item.icon != null) {
                holder.imgIcon.setImageDrawable(item.icon);
            } else {
                holder.imgIcon.setImageResource(R.mipmap.ic_launcher);
            }

            // Simple progress bar logic
            holder.pbTimer.setIndeterminate(false);
            holder.pbTimer.setMax(100);
            holder.pbTimer.setProgress(100);
        }

        @Override
        public int getItemCount() {
            return timers.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imgIcon;
            TextView tvAppName;
            android.widget.ProgressBar pbTimer;
            TextView tvTimeLeft;

            ViewHolder(View itemView) {
                super(itemView);
                imgIcon = itemView.findViewById(R.id.imgAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                pbTimer = itemView.findViewById(R.id.pbTimer);
                tvTimeLeft = itemView.findViewById(R.id.tvTimeLeft);
            }
        }
    }

    /**
     * 🆕 Fetch the real parent profile name from Firebase using the parentUserId.
     * This fixes the issue where "Google sdk_gphone..." is displayed instead of
     * "Dad".
     */
    private void fetchRealParentProfile() {
        String parentUserId = sessionManager.getParentUserId();
        if (parentUserId == null || parentUserId.isEmpty()) {
            return;
        }

        DatabaseReference parentRef = FirebaseDatabase.getInstance()
                .getReference("parent_accounts")
                .child(parentUserId)
                .child("name"); // We only need the name

        parentRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String realName = snapshot.getValue(String.class);
                    if (realName != null && !realName.isEmpty()) {
                        Log.d(TAG, "🆕 Fetched real parent name from Firebase: " + realName);

                        // Update UI immediately
                        if (tvParentName != null) {
                            tvParentName.setText(realName);
                        }

                        // Update session for future
                        String currentDeviceId = sessionManager.getChildDeviceId();
                        String currentShareKey = sessionManager.getQRShareKey();

                        if (currentDeviceId != null) {
                            sessionManager.saveChildSession(currentDeviceId, realName,
                                    currentShareKey != null ? currentShareKey : "");
                            // Also update local variable
                            parentName = realName;
                        }
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "❌ Failed to fetch real parent name: " + error.getMessage());
            }
        });
    }
}