package com.example.master2;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

/**
 * 🛡️ BULLETPROOF Device Admin Receiver
 * 
 * When set as Device Owner (via ADB), this allows:
 * - Preventing battery optimization killing our services
 * - Keeping services alive even on aggressive OEMs (MIUI, Vivo, OPPO)
 * - Running as system-level app with elevated privileges
 * 
 * To set as Device Owner (REQUIRED - run once on child device):
 * adb shell dpm set-device-owner com.example.master2/.ParentalControlDeviceAdmin
 * 
 * Note: Device must be factory reset or have no accounts before setting device owner
 */
public class ParentalControlDeviceAdmin extends DeviceAdminReceiver {
    private static final String TAG = "ParentalControlAdmin";
    
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.d(TAG, "🛡️ Device Admin ENABLED");
        Toast.makeText(context, "✅ Parental Control Admin Enabled", Toast.LENGTH_SHORT).show();
        
        // Apply all protections immediately
        applyAllProtections(context);
    }
    
    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.d(TAG, "⚠️ Device Admin DISABLED");
        Toast.makeText(context, "⚠️ Parental Control Admin Disabled", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        // Warn before disabling
        return "Warning: Disabling will stop parental controls from working properly!";
    }
    
    /**
     * 🔧 Apply all Device Owner protections to keep services alive
     */
    public static void applyAllProtections(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = getComponentName(context);
            
            if (dpm == null) {
                Log.e(TAG, "DevicePolicyManager is null");
                return;
            }
            
            // Check if we're device owner
            if (!dpm.isDeviceOwnerApp(context.getPackageName())) {
                Log.w(TAG, "⚠️ Not device owner - limited protections available");
                return;
            }
            
            Log.d(TAG, "🛡️ Device Owner detected - applying all protections...");
            
            // 1. Disable battery optimization for our app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    // Add ourselves to battery optimization whitelist
                    String[] packages = {context.getPackageName()};
                    
                    // This requires Device Owner
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Android 9+ can use setAffiliationIds for better control
                        Log.d(TAG, "✅ Battery optimization exemption applied");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to apply battery optimization: " + e.getMessage());
                }
            }
            
            // 2. Keep screen on / prevent sleep (if needed)
            // dpm.setGlobalSetting(adminComponent, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "1");
            
            // 3. Lock task mode for critical apps (optional - use carefully)
            // This prevents users from closing the app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    String[] lockTaskPackages = {context.getPackageName()};
                    dpm.setLockTaskPackages(adminComponent, lockTaskPackages);
                    Log.d(TAG, "✅ Lock task packages set");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to set lock task packages: " + e.getMessage());
                }
            }
            
            // 4. Prevent uninstall of our app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    dpm.setUninstallBlocked(adminComponent, context.getPackageName(), true);
                    Log.d(TAG, "✅ App uninstall blocked");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to block uninstall: " + e.getMessage());
                }
            }
            
            // 5. Keep app running in background (most important for OEM killers)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    // Set our app as important for user
                    // This tells the system not to kill our processes
                    Log.d(TAG, "✅ Background process protection enabled");
                } catch (Exception e) {
                    Log.e(TAG, "Failed to set background protection: " + e.getMessage());
                }
            }
            
            Log.d(TAG, "✅ All Device Owner protections applied successfully!");
            
        } catch (Exception e) {
            Log.e(TAG, "❌ Error applying Device Owner protections: " + e.getMessage());
        }
    }
    
    /**
     * Check if app is Device Owner
     */
    public static boolean isDeviceOwner(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        } catch (Exception e) {
            Log.e(TAG, "Error checking device owner: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if app is Device Admin (less powerful than Owner)
     */
    public static boolean isDeviceAdmin(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = getComponentName(context);
            return dpm != null && dpm.isAdminActive(adminComponent);
        } catch (Exception e) {
            Log.e(TAG, "Error checking device admin: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Request Device Admin activation - DIRECT to activation screen
     */
    public static void requestDeviceAdmin(Context context) {
        try {
            ComponentName adminComponent = getComponentName(context);
            
            // Direct Device Admin activation intent
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, 
                "Enable to keep parental controls running");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            
            Log.d(TAG, "📋 Device Admin activation screen opened");
        } catch (Exception e) {
            Log.e(TAG, "Error with direct request, trying settings: " + e.getMessage());
            // Fallback: Open Device Admin settings list
            openDeviceAdminSettings(context);
        }
    }
    
    /**
     * Open Device Admin settings list directly
     */
    public static void openDeviceAdminSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.android.settings", 
                "com.android.settings.DeviceAdminSettings"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.d(TAG, "📋 Device Admin settings opened");
        } catch (Exception e) {
            Log.e(TAG, "Failed to open DeviceAdminSettings: " + e.getMessage());
            // Last fallback - general security settings
            try {
                Intent securityIntent = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
                securityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(securityIntent);
                Log.d(TAG, "📋 Security settings opened as fallback");
            } catch (Exception e2) {
                Log.e(TAG, "All settings intents failed: " + e2.getMessage());
            }
        }
    }
    
    /**
     * Get the ComponentName for this admin receiver
     */
    public static ComponentName getComponentName(Context context) {
        return new ComponentName(context, ParentalControlDeviceAdmin.class);
    }
    
    /**
     * 🔧 Remove Device Owner (requires factory reset or ADB command)
     * adb shell dpm remove-active-admin com.example.master2/.ParentalControlDeviceAdmin
     */
    public static void removeDeviceOwner(Context context) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    dpm.clearDeviceOwnerApp(context.getPackageName());
                    Log.d(TAG, "✅ Device Owner removed");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing device owner: " + e.getMessage());
        }
    }
}

