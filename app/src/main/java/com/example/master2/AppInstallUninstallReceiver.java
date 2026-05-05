package com.example.master2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

/**
 * Broadcast receiver that detects app installations and uninstallations
 * Pushes events to Firebase for parent tracking
 */
public class AppInstallUninstallReceiver extends BroadcastReceiver {

    private static final String TAG = "AppInstallReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String packageName = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;

        if (packageName == null || packageName.isEmpty()) {
            Log.w(TAG, "Package name is null or empty");
            return;
        }

        Log.d(TAG, "📦 Package event: " + action + " for " + packageName);

        // Ignore our own app and system packages
        if (packageName.equals(context.getPackageName()) || isSystemPackage(context, packageName)) {
            Log.d(TAG, "Ignoring system app or own app: " + packageName);
            return;
        }

        // Determine action type
        String eventAction = null;
        if (Intent.ACTION_PACKAGE_ADDED.equals(action) && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            eventAction = "INSTALLED";
        } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)
                && !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
            eventAction = "UNINSTALLED";
        }

        if (eventAction != null) {
            String appName = getAppName(context, packageName, eventAction.equals("INSTALLED"));
            pushEventToFirebase(context, packageName, appName, eventAction);
        }
    }

    /**
     * Get app name from package manager
     */
    private String getAppName(Context context, String packageName, boolean isInstalled) {
        try {
            if (isInstalled) {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                return pm.getApplicationLabel(appInfo).toString();
            } else {
                // For uninstalled apps, just use package name
                return packageName.substring(packageName.lastIndexOf('.') + 1);
            }
        } catch (PackageManager.NameNotFoundException e) {
            return packageName.substring(packageName.lastIndexOf('.') + 1);
        }
    }

    /**
     * Check if package is a system app
     */
    private boolean isSystemPackage(Context context, String packageName) {
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Push event to Firebase
     */
    private void pushEventToFirebase(Context context, String packageName, String appName, String action) {
        SessionManager sessionManager = new SessionManager(context);
        String deviceId = sessionManager.getChildDeviceId();

        Log.d(TAG, "========================================");
        Log.d(TAG, "📦 PUSHING APP EVENT TO FIREBASE");
        Log.d(TAG, "Device ID: " + deviceId);
        Log.d(TAG, "App Name: " + appName);
        Log.d(TAG, "Package: " + packageName);
        Log.d(TAG, "Action: " + action);
        Log.d(TAG, "========================================");

        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "⚠️⚠️⚠️ NO CHILD DEVICE ID - CANNOT PUSH APP EVENT!");
            return;
        }

        AppStatusEvent event = new AppStatusEvent(
                appName,
                packageName,
                action,
                System.currentTimeMillis());

        String firebasePath = "child_devices/" + deviceId + "/app_events";
        Log.d(TAG, "Firebase Path: " + firebasePath);

        DatabaseReference eventsRef = FirebaseDatabase.getInstance()
                .getReference("child_devices")
                .child(deviceId)
                .child("app_events");

        eventsRef.push().setValue(event)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅✅✅ SUCCESS! App event pushed to Firebase!");
                    Log.d(TAG, "Event: " + action + " - " + appName + " (" + packageName + ")");
                    Log.d(TAG, "Path: " + firebasePath);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌❌❌ FAILED to push app event!");
                    Log.e(TAG, "Error: " + e.getMessage());
                    Log.e(TAG, "Path: " + firebasePath);
                    e.printStackTrace();
                });
    }
}
