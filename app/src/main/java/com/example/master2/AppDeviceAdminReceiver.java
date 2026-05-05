package com.example.master2;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/**
 * Device Admin Receiver for uninstall protection
 * When enabled, the app cannot be uninstalled without first deactivating Device
 * Admin
 */
public class AppDeviceAdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "DeviceAdmin";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.d(TAG, "🛡️ Device Admin ENABLED - Uninstall protection active");
        Toast.makeText(context, "✅ Uninstall protection enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.d(TAG, "⚠️ Device Admin DISABLED - Uninstall protection removed");
        Toast.makeText(context, "⚠️ Uninstall protection disabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        Log.w(TAG, "🚨 Device Admin disable REQUESTED");
        // This message shows when someone tries to deactivate
        return "Warning: Disabling this will allow the app to be uninstalled. Are you sure?";
    }

    @Override
    public void onPasswordChanged(Context context, Intent intent) {
        super.onPasswordChanged(context, intent);
        Log.d(TAG, "Password changed");
    }

    @Override
    public void onPasswordFailed(Context context, Intent intent) {
        super.onPasswordFailed(context, intent);
        Log.d(TAG, "Password failed");
    }

    @Override
    public void onPasswordSucceeded(Context context, Intent intent) {
        super.onPasswordSucceeded(context, intent);
        Log.d(TAG, "Password succeeded");
    }
}
