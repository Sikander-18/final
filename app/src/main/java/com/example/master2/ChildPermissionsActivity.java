package com.example.master2;

import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import android.content.res.ColorStateList;

public class ChildPermissionsActivity extends AppCompatActivity {
    private static final String TAG = "ChildPermissions";

    // UI Components
    // UI Components
    private LinearLayout accessibilitySection, usageAccessSection, notificationSection, batteryOptimizationSection,
            deviceAdminSection;
    private ImageView accessibilityIcon, usageAccessIcon, notificationIcon, batteryOptimizationIcon, deviceAdminIcon;
    private TextView accessibilityStatus, usageAccessStatus, notificationStatus, batteryOptimizationStatus,
            deviceAdminStatus;
    private Button btnAccessibility, btnUsageAccess, btnNotification, btnBatteryOptimization, btnDeviceAdmin;
    private Button btnProceed;

    // Battery Optimization Manager
    private BatteryOptimizationManager batteryOptimizationManager;

    // 🛡️ Device Admin Helper for uninstall protection
    private DeviceAdminHelper deviceAdminHelper;

    // Permission status
    private boolean hasAccessibilityPermission = false;
    private boolean hasUsageAccessPermission = false;
    private boolean hasNotificationPermission = false;
    private boolean hasBatteryOptimizationPermission = false;
    private boolean hasDeviceAdminPermission = false;

    // Request code for Device Admin
    private static final int REQUEST_DEVICE_ADMIN = 2001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_permissions);

        Log.d(TAG, "ChildPermissionsActivity created");

        // Initialize Battery Optimization Manager
        batteryOptimizationManager = new BatteryOptimizationManager(this);

        // 🛡️ Initialize Device Admin Helper
        deviceAdminHelper = new DeviceAdminHelper(this);

        initializeViews();
        setupClickListeners();
        checkAllPermissions();
    }

    private void initializeViews() {
        // Permission sections
        accessibilitySection = findViewById(R.id.accessibilitySection);
        usageAccessSection = findViewById(R.id.usageAccessSection);
        notificationSection = findViewById(R.id.notificationSection);
        batteryOptimizationSection = findViewById(R.id.batteryOptimizationSection);

        // Status icons
        accessibilityIcon = findViewById(R.id.accessibilityIcon);
        usageAccessIcon = findViewById(R.id.usageAccessIcon);
        notificationIcon = findViewById(R.id.notificationIcon);
        batteryOptimizationIcon = findViewById(R.id.batteryOptimizationIcon);

        // Status texts
        accessibilityStatus = findViewById(R.id.accessibilityStatus);
        usageAccessStatus = findViewById(R.id.usageAccessStatus);
        notificationStatus = findViewById(R.id.notificationStatus);
        batteryOptimizationStatus = findViewById(R.id.batteryOptimizationStatus);

        // Permission buttons
        btnAccessibility = findViewById(R.id.btnAccessibility);
        btnUsageAccess = findViewById(R.id.btnUsageAccess);
        btnNotification = findViewById(R.id.btnNotification);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);

        // 🛡️ Device Admin section (uninstall protection)
        deviceAdminSection = findViewById(R.id.deviceAdminSection);
        deviceAdminIcon = findViewById(R.id.deviceAdminIcon);
        deviceAdminStatus = findViewById(R.id.deviceAdminStatus);
        btnDeviceAdmin = findViewById(R.id.btnDeviceAdmin);

        // Proceed button
        btnProceed = findViewById(R.id.btnProceed);

        Log.d(TAG, "Views initialized successfully");
    }

    private void setupClickListeners() {
        btnAccessibility.setOnClickListener(v -> requestAccessibilityPermission());
        btnUsageAccess.setOnClickListener(v -> requestUsageAccessPermission());
        btnNotification.setOnClickListener(v -> requestNotificationPermission());
        btnBatteryOptimization.setOnClickListener(v -> requestBatteryOptimizationPermission());

        // 🛡️ Device Admin button
        if (btnDeviceAdmin != null) {
            btnDeviceAdmin.setOnClickListener(v -> requestDeviceAdminPermission());
        }

        btnProceed.setOnClickListener(v -> proceedToQRScanner());

        Log.d(TAG, "Click listeners setup complete");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Check permissions every time we return to this activity
        checkAllPermissions();
    }

    private void checkAllPermissions() {
        Log.d(TAG, "Checking all permissions...");

        hasAccessibilityPermission = isAccessibilityServiceEnabled();
        hasUsageAccessPermission = hasUsageStatsPermission();
        hasNotificationPermission = hasNotificationPermission();
        hasBatteryOptimizationPermission = batteryOptimizationManager.isBatteryOptimizationDisabled();
        hasDeviceAdminPermission = deviceAdminHelper != null && deviceAdminHelper.isAdminActive();

        updateUI();
    }

    private void updateUI() {
        // Update Accessibility Permission with detailed explanation
        String accessibilityDescription = "• Blocks restricted apps during focus mode\n" +
                "• Prevents unauthorized app usage\n" +
                "• Controls screen time effectively";
        updatePermissionUI(
                accessibilityIcon, accessibilityStatus, btnAccessibility,
                hasAccessibilityPermission, "Accessibility Service", accessibilityDescription);

        // Update Usage Access Permission with detailed explanation
        String usageDescription = "• Monitors daily app usage statistics\n" +
                "• Tracks screen time for each app\n" +
                "• Provides detailed usage reports to parents";
        updatePermissionUI(
                usageAccessIcon, usageAccessStatus, btnUsageAccess,
                hasUsageAccessPermission, "Usage Access", usageDescription);

        // Update Notification Permission with detailed explanation
        String notificationDescription = "• Shows timer status notifications\n" +
                "• Alerts when focus mode starts/ends\n" +
                "• Provides system status updates";
        updatePermissionUI(
                notificationIcon, notificationStatus, btnNotification,
                hasNotificationPermission, "Notifications", notificationDescription);

        // Update Battery Optimization Permission with detailed explanation
        String batteryDescription = "• Ensures timer resets work at midnight\n" +
                "• Prevents system from killing timer services\n" +
                "• Guarantees reliable automatic resets";
        updatePermissionUI(
                batteryOptimizationIcon, batteryOptimizationStatus, btnBatteryOptimization,
                hasBatteryOptimizationPermission, "Battery Optimization", batteryDescription);

        // 🛡️ Update Device Admin Permission (Optional but recommended)
        String deviceAdminDescription = "• Prevents app from being uninstalled\n" +
                "• Protects against unauthorized removal\n" +
                "• Ask parent to enable this";
        if (deviceAdminIcon != null && deviceAdminStatus != null && btnDeviceAdmin != null) {
            updatePermissionUI(
                    deviceAdminIcon, deviceAdminStatus, btnDeviceAdmin,
                    hasDeviceAdminPermission, "Uninstall Protection", deviceAdminDescription);
        }

        // Update Proceed button - NOW INCLUDES BATTERY OPTIMIZATION AS MANDATORY
        // Device Admin is optional
        boolean allMandatoryGranted = hasAccessibilityPermission &&
                hasUsageAccessPermission &&
                hasNotificationPermission &&
                hasBatteryOptimizationPermission;

        btnProceed.setEnabled(allMandatoryGranted);
        btnProceed.setAlpha(allMandatoryGranted ? 1.0f : 0.5f);

        if (allMandatoryGranted) {
            btnProceed.setText("Continue");
            // Remove tint to show gradient
            btnProceed.setBackgroundTintList(null);
        } else {
            btnProceed.setText("Grant Permissions First");
            // Optional: keep it grayed out via alpha, or tint it gray if alpha isn't
            // enough.
            // Since we set alpha 0.5f, the gradient will just look dim, which is good.
            // If we really want it gray, we can use a gray tint, but null is usually safer
            // for gradients.
            // Let's rely on Alpha for disabled state.
            btnProceed.setBackgroundTintList(null);
        }

        Log.d(TAG, "UI updated - Mandatory permissions granted: " + allMandatoryGranted);
    }

    private void updatePermissionUI(ImageView icon, TextView status, Button button,
            boolean granted, String permissionName, String description) {
        if (granted) {
            // Keep original icon, just tint it Green
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_dark));

            status.setText("✅ " + permissionName + " - Granted\n\n" + description);
            status.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));

            button.setText("Granted");
            button.setEnabled(false);
            button.setAlpha(0.6f);

            // Revert to outline/secondary style if possible, or just dim it
            button.setBackgroundResource(R.drawable.bg_surface_soft);
            // Assuming bg_surface_soft exists or similar for a "disabled/done" look
            // Actually, keeping the primary button dimmed is fine, or simple gray.
        } else {
            // Keep original icon, tint it Red/Blue
            // If not granted, maybe keep it Red to indicate attention needed?
            // Or the default Blue from XML? The XML sets tint to modern_blue_600.
            // The logic here previously set it to RED for "required". Let's stick to RED
            // for urgency.
            icon.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_dark));

            status.setText("❌ " + permissionName + " - Required\n\n" + description);
            status.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));

            button.setText("Grant Permission");
            button.setEnabled(true);
            button.setAlpha(1.0f);
            button.setBackgroundResource(R.drawable.bg_primary_pill);
        }

        // Improve text formatting for readability
        status.setTextSize(13f);
        status.setLineSpacing(4f, 1.2f);
    }

    // Permission checking methods
    private boolean isAccessibilityServiceEnabled() {
        String settingValue = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        if (settingValue != null) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                String service = splitter.next();
                if (service.equalsIgnoreCase(getPackageName() + "/" + BlockService.class.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow("android:get_usage_stats",
                android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean hasNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }
        return true; // Pre-Android 13 doesn't need this permission
    }

    // Permission request methods
    private void requestAccessibilityPermission() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🛡️ Enable Accessibility Service")
                .setMessage("Accessibility Service is needed for:\n\n" +
                        "• Blocking restricted apps during focus mode\n" +
                        "• Preventing unauthorized app usage\n" +
                        "• Controlling screen time effectively\n\n" +
                        "📋 Steps to enable:\n" +
                        "1. Tap 'Open Settings'\n" +
                        "2. Find 'Master2' or 'BlockService'\n" +
                        "3. Turn ON the service\n" +
                        "4. Tap 'Allow' when prompted\n" +
                        "5. Return to this app\n\n" +
                        "⚠️ This permission is REQUIRED for the app to function properly.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error opening accessibility settings: " + e.getMessage());
                        Toast.makeText(this, "Please enable accessibility service in Settings",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestUsageAccessPermission() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("📊 Grant Usage Access")
                .setMessage("Usage Access permission is needed for:\n\n" +
                        "• Monitoring daily app usage statistics\n" +
                        "• Tracking screen time for each app\n" +
                        "• Providing detailed usage reports to parents\n\n" +
                        "📋 Steps to enable:\n" +
                        "1. Tap 'Open Settings'\n" +
                        "2. Find 'Master2' in the list\n" +
                        "3. Turn ON 'Permit usage access'\n" +
                        "4. Return to this app\n\n" +
                        "⚠️ This permission is REQUIRED for monitoring functionality.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            intent.setData(Uri.parse("package:" + getPackageName()));
                        }
                        startActivity(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Error opening usage access settings: " + e.getMessage());
                        Toast.makeText(this, "Please grant usage access in Settings",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("🔔 Enable Notifications")
                        .setMessage("Notification permission is needed for:\n\n" +
                                "• Showing timer status notifications\n" +
                                "• Alerting when focus mode starts/ends\n" +
                                "• Providing system status updates\n" +
                                "• Displaying important parental control alerts\n\n" +
                                "⚠️ This permission is REQUIRED for the app to function properly.")
                        .setPositiveButton("Grant Permission", (dialog, which) -> {
                            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1001);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 1001);
            }
        }
    }

    private void requestBatteryOptimizationPermission() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        android.view.LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_battery_optimization, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        // Make dialog background transparent to show rounded corners
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        // Setup Buttons
        TextView btnCancel = dialogView.findViewById(R.id.btnCancel);
        TextView btnSettings = dialogView.findViewById(R.id.btnSettings);

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnSettings.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                batteryOptimizationManager.requestBatteryOptimizationExemption(this);
                Log.d(TAG, "Opened battery optimization settings");
            } catch (Exception e) {
                Log.e(TAG, "Error opening battery optimization settings: " + e.getMessage());
                Toast.makeText(this, "Please disable battery optimization in Settings",
                        Toast.LENGTH_LONG).show();
            }
        });

        dialog.show();
    }

    /**
     * 🛡️ Request Device Admin permission for uninstall protection
     */
    private void requestDeviceAdminPermission() {
        if (deviceAdminHelper == null) {
            deviceAdminHelper = new DeviceAdminHelper(this);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("🛡️ Enable Uninstall Protection")
                .setMessage("This permission prevents the app from being uninstalled:\n\n" +
                        "• The app cannot be removed without parent permission\n" +
                        "• Protects against unauthorized removal\n" +
                        "• Provides maximum security\n\n" +
                        "📋 Steps to enable:\n" +
                        "1. Tap 'Enable Protection'\n" +
                        "2. On the next screen, tap 'Activate'\n" +
                        "3. The app is now protected!\n\n" +
                        "⚠️ This permission is OPTIONAL but highly recommended.")
                .setPositiveButton("Enable Protection", (dialog, which) -> {
                    try {
                        deviceAdminHelper.requestAdminActivation(this);
                        Log.d(TAG, "Launched Device Admin activation screen");
                    } catch (Exception e) {
                        Log.e(TAG, "Error launching Device Admin: " + e.getMessage());
                        Toast.makeText(this, "Failed to open Device Admin settings", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Skip", null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_DEVICE_ADMIN) {
            if (resultCode == RESULT_OK) {
                Log.d(TAG, "Device Admin enabled successfully");
                Toast.makeText(this, "✅ Uninstall protection enabled!", Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "Device Admin NOT enabled");
                Toast.makeText(this, "⚠️ Uninstall protection not enabled", Toast.LENGTH_SHORT).show();
            }
            checkAllPermissions(); // Refresh UI
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == 1001) { // Notification permission
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Notification permission granted");
                Toast.makeText(this, "✅ Notification permission granted!", Toast.LENGTH_SHORT).show();
            } else {
                Log.d(TAG, "Notification permission denied");
                Toast.makeText(this, "❌ Notification permission is required", Toast.LENGTH_SHORT).show();
            }
            checkAllPermissions(); // Refresh UI
        }
    }

    private void proceedToQRScanner() {
        Log.d(TAG, "All mandatory permissions granted, proceeding to QR scanner");

        // Show success message
        Toast.makeText(this, "✅ All permissions granted! Opening QR scanner...",
                Toast.LENGTH_SHORT).show();

        // Navigate to QR scanner (ChildLoginActivity)
        Intent intent = new Intent(this, ChildLoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Show confirmation dialog before going back
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Go Back?")
                .setMessage("You need to grant all mandatory permissions to use the app. Go back to login screen?")
                .setPositiveButton("Yes, Go Back", (dialog, which) -> {
                    super.onBackPressed();
                })
                .setNegativeButton("Stay Here", null)
                .show();
    }
}