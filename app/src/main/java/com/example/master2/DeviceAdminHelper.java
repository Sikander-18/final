package com.example.master2;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Helper class for Device Admin operations
 * Handles activation, deactivation, and status checks
 */
public class DeviceAdminHelper {
    private static final String TAG = "DeviceAdminHelper";

    private Context context;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    public static final int REQUEST_CODE_ENABLE_ADMIN = 2001;

    public DeviceAdminHelper(Context context) {
        this.context = context;
        this.devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = new ComponentName(context, AppDeviceAdminReceiver.class);
    }

    /**
     * Check if Device Admin is currently active
     */
    public boolean isAdminActive() {
        if (devicePolicyManager == null)
            return false;
        return devicePolicyManager.isAdminActive(adminComponent);
    }

    /**
     * Get intent to activate Device Admin
     * Launch this intent with startActivityForResult
     */
    public Intent getActivationIntent() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable uninstall protection to prevent the app from being removed without parent permission.");
        return intent;
    }

    /**
     * Launch Device Admin activation screen
     * Returns the intent that should be started
     */
    public void requestAdminActivation(android.app.Activity activity) {
        if (isAdminActive()) {
            Log.d(TAG, "Device Admin already active");
            android.widget.Toast.makeText(context, "✅ Uninstall protection already enabled",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        Log.d(TAG, "Launching Device Admin activation screen");
        Intent activateIntent = getActivationIntent();
        activity.startActivityForResult(activateIntent, REQUEST_CODE_ENABLE_ADMIN);
    }

    /**
     * Deactivate Device Admin (only for testing/debug)
     */
    public void removeAdminIfActive() {
        if (isAdminActive() && devicePolicyManager != null) {
            devicePolicyManager.removeActiveAdmin(adminComponent);
            Log.d(TAG, "Device Admin removed");
        }
    }

    /**
     * Get the component name for Device Admin
     */
    public ComponentName getAdminComponent() {
        return adminComponent;
    }

    /**
     * Get status text for UI
     */
    public String getStatusText() {
        if (isAdminActive()) {
            return "🛡️ Uninstall Protection: ENABLED";
        } else {
            return "⚠️ Uninstall Protection: DISABLED";
        }
    }
}
