package com.example.master2;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * Manager class to handle battery optimization permissions and settings
 * to ensure timer reset functionality works reliably
 */
public class BatteryOptimizationManager {
    private static final String TAG = "BatteryOptimizationManager";
    private static final int REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 1001;
    private static final int REQUEST_EXACT_ALARM_PERMISSION = 1002;

    private Context context;

    public BatteryOptimizationManager(Context context) {
        this.context = context;
    }

    /**
     * Check if the app is exempted from battery optimization
     */
    public boolean isBatteryOptimizationDisabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                boolean isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
                Log.d(TAG, "Battery optimization ignored: " + isIgnoring);
                return isIgnoring;
            }
        }
        return true; // For older Android versions, assume no optimization
    }

    /**
     * Check if exact alarm permission is granted (Android 12+)
     */
    public boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                boolean canSchedule = alarmManager.canScheduleExactAlarms();
                Log.d(TAG, "Can schedule exact alarms: " + canSchedule);
                return canSchedule;
            }
        }
        return true; // For older Android versions, assume permission granted
    }

    /**
     * Request battery optimization exemption
     */
    public void requestBatteryOptimizationExemption(AppCompatActivity activity) {
        if (!isBatteryOptimizationDisabled()) {
            // Directly open settings without explanation dialog (User Request)
            openBatteryOptimizationSettings(activity);
        }
    }

    /**
     * Request exact alarm permission (Android 12+)
     */
    public void requestExactAlarmPermission(AppCompatActivity activity) {
        if (!canScheduleExactAlarms()) {
            showExactAlarmPermissionDialog(activity);
        }
    }

    /**
     * Check all required permissions and request if needed
     */
    public void checkAndRequestAllPermissions(AppCompatActivity activity) {
        boolean needsBatteryExemption = !isBatteryOptimizationDisabled();
        boolean needsAlarmPermission = !canScheduleExactAlarms();

        if (needsBatteryExemption && needsAlarmPermission) {
            showCombinedPermissionDialog(activity);
        } else if (needsBatteryExemption) {
            requestBatteryOptimizationExemption(activity);
        } else if (needsAlarmPermission) {
            requestExactAlarmPermission(activity);
        } else {
            Log.d(TAG, "✅ All required permissions granted for timer functionality");
        }
    }

    private void showBatteryOptimizationDialog(AppCompatActivity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("⚡ Battery Optimization Required")
                .setMessage("To ensure automatic timer resets work properly even when the app is closed, " +
                        "please disable battery optimization for this app.\n\n" +
                        "This will:\n" +
                        "✅ Allow timers to reset at midnight automatically\n" +
                        "✅ Prevent the system from killing timer services\n" +
                        "✅ Ensure reliable daily usage limits\n\n" +
                        "Your battery life will not be significantly affected.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    openBatteryOptimizationSettings(activity);
                })
                .setNegativeButton("Later", (dialog, which) -> {
                    Log.w(TAG, "User declined battery optimization exemption");
                })
                .setCancelable(false)
                .show();
    }

    private void showExactAlarmPermissionDialog(AppCompatActivity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("⏰ Exact Alarm Permission Required")
                .setMessage("Android 12+ requires special permission for exact timing.\n\n" +
                        "This is needed for:\n" +
                        "✅ Precise midnight timer resets\n" +
                        "✅ Accurate daily usage limit resets\n" +
                        "✅ Reliable scheduled tasks\n\n" +
                        "Please grant the 'Alarms & reminders' permission.")
                .setIcon(android.R.drawable.ic_dialog_info)
                .setPositiveButton("Grant Permission", (dialog, which) -> {
                    openExactAlarmSettings(activity);
                })
                .setNegativeButton("Later", (dialog, which) -> {
                    Log.w(TAG, "User declined exact alarm permission");
                })
                .setCancelable(false)
                .show();
    }

    private void showCombinedPermissionDialog(AppCompatActivity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("🔧 Timer Setup Required")
                .setMessage("For automatic timer resets to work properly, two permissions are needed:\n\n" +
                        "1. ⚡ Battery Optimization Exemption\n" +
                        "   • Prevents system from killing timer services\n" +
                        "   • Ensures midnight resets work when app is closed\n\n" +
                        "2. ⏰ Exact Alarm Permission (Android 12+)\n" +
                        "   • Required for precise midnight timing\n" +
                        "   • Ensures accurate daily resets\n\n" +
                        "Both are essential for reliable timer functionality.")
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("Setup Now", (dialog, which) -> {
                    // Start with battery optimization first
                    openBatteryOptimizationSettings(activity);
                })
                .setNegativeButton("Later", (dialog, which) -> {
                    Log.w(TAG, "User declined permission setup");
                })
                .setCancelable(false)
                .show();
    }

    private void openBatteryOptimizationSettings(AppCompatActivity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                activity.startActivityForResult(intent, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                Log.d(TAG, "Opened battery optimization settings");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open battery optimization settings: " + e.getMessage());
            // Fallback to general battery settings
            openGeneralBatterySettings(activity);
        }
    }

    private void openExactAlarmSettings(AppCompatActivity activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                activity.startActivityForResult(intent, REQUEST_EXACT_ALARM_PERMISSION);
                Log.d(TAG, "Opened exact alarm settings");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to open exact alarm settings: " + e.getMessage());
            // Fallback to general app settings
            openAppSettings(activity);
        }
    }

    private void openGeneralBatterySettings(AppCompatActivity activity) {
        try {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            activity.startActivity(intent);
            Log.d(TAG, "Opened general battery optimization settings");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open general battery settings: " + e.getMessage());
            openAppSettings(activity);
        }
    }

    private void openAppSettings(AppCompatActivity activity) {
        try {
            Intent intent = new Intent();
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + context.getPackageName()));
            activity.startActivity(intent);
            Log.d(TAG, "Opened app settings as fallback");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open app settings: " + e.getMessage());
        }
    }

    /**
     * Get status summary for logging/debugging
     */
    public String getPermissionStatus() {
        StringBuilder status = new StringBuilder();
        status.append("=== Timer Permissions Status ===\n");
        status.append("Battery Optimization Disabled: ").append(isBatteryOptimizationDisabled()).append("\n");
        status.append("Can Schedule Exact Alarms: ").append(canScheduleExactAlarms()).append("\n");
        status.append("Android Version: ").append(Build.VERSION.SDK_INT).append("\n");
        status.append("Device Manufacturer: ").append(Build.MANUFACTURER).append("\n");
        status.append("Device Model: ").append(Build.MODEL).append("\n");
        return status.toString();
    }

    /**
     * Log comprehensive status for debugging
     */
    public void logPermissionStatus() {
        Log.d(TAG, getPermissionStatus());
    }
}
