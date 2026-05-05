package com.example.master2.services;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.master2.AppStatusEvent;
import com.example.master2.SessionManager;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Service that automatically detects app install/uninstall and syncs only
 * changed apps
 */
public class PackageChangeService extends Service {
    private static final String TAG = "PackageChangeService";

    private BroadcastReceiver packageReceiver;
    private SessionManager sessionManager;
    private PackageManager packageManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "📦 PackageChangeService started");

        sessionManager = new SessionManager(this);
        packageManager = getPackageManager();

        setupPackageReceiver();
    }

    private void setupPackageReceiver() {
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null)
                    return;

                String packageName = intent.getData() != null ? intent.getData().getSchemeSpecificPart() : null;

                if (packageName == null || packageName.contains("com.example.master2")) {
                    return; // Skip our own app
                }

                Log.d(TAG, "📦 Package change detected: " + action + " - " + packageName);

                String deviceId = sessionManager.getChildDeviceId();
                if (deviceId == null || deviceId.isEmpty()) {
                    Log.w(TAG, "No device ID, skipping sync");
                    return;
                }

                switch (action) {
                    case Intent.ACTION_PACKAGE_ADDED:
                        if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            Log.d(TAG, "➕ New app installed: " + packageName);
                            syncSingleAppAdd(deviceId, packageName);
                            // 📦 PUSH APP STATUS EVENT
                            pushAppStatusEvent(deviceId, packageName, "INSTALLED");
                        }
                        break;

                    case Intent.ACTION_PACKAGE_REMOVED:
                        if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            Log.d(TAG, "➖ App uninstalled: " + packageName);
                            syncSingleAppRemove(deviceId, packageName);
                            // 📦 PUSH APP STATUS EVENT
                            pushAppStatusEvent(deviceId, packageName, "UNINSTALLED");
                        }
                        break;

                    case Intent.ACTION_PACKAGE_REPLACED:
                        Log.d(TAG, "🔄 App updated: " + packageName);
                        syncSingleAppAdd(deviceId, packageName); // Re-sync in case icon/name changed
                        break;
                }
            }
        };

        // Register for all package events
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);

        Log.d(TAG, "✅ Package change listener registered");
    }

    /**
     * Sync ONLY the newly installed app to Firebase
     */
    private void syncSingleAppAdd(String deviceId, String packageName) {
        new Thread(() -> {
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);

                // Skip if no launcher intent and is system app
                if (packageManager.getLaunchIntentForPackage(packageName) == null &&
                        (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    Log.d(TAG, "Skipping system service: " + packageName);
                    return;
                }

                Map<String, Object> appData = new HashMap<>();
                String appName = appInfo.loadLabel(packageManager).toString();

                appData.put("packageName", packageName);
                appData.put("appName", appName);
                appData.put("isSystemApp", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                appData.put("lastUpdated", System.currentTimeMillis());

                // Encode icon
                try {
                    String iconBase64 = encodeIconToBase64(packageName);
                    appData.put("iconBase64", iconBase64);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to encode icon: " + e.getMessage());
                }

                // Upload to Firebase - only this app
                String safeKey = sanitizeFirebaseKey(packageName);
                DatabaseReference ref = FirebaseDatabase.getInstance()
                        .getReference("installed_apps")
                        .child(deviceId)
                        .child("apps")
                        .child(safeKey);

                ref.setValue(appData)
                        .addOnSuccessListener(aVoid -> {
                            Log.d(TAG, "✅ Added app to Firebase: " + appName);
                            updateMetadata(deviceId, 1); // Increment app count
                        })
                        .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to add app: " + e.getMessage()));

            } catch (Exception e) {
                Log.e(TAG, "Error syncing app: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Remove ONLY the uninstalled app from Firebase
     */
    private void syncSingleAppRemove(String deviceId, String packageName) {
        String safeKey = sanitizeFirebaseKey(packageName);
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("installed_apps")
                .child(deviceId)
                .child("apps")
                .child(safeKey);

        ref.removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Removed app from Firebase: " + packageName);
                    updateMetadata(deviceId, -1); // Decrement app count
                })
                .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to remove app: " + e.getMessage()));
    }

    /**
     * Update lastUpdated and appCount metadata
     */
    private void updateMetadata(String deviceId, int countDelta) {
        DatabaseReference metaRef = FirebaseDatabase.getInstance()
                .getReference("installed_apps")
                .child(deviceId);

        metaRef.child("lastUpdated").setValue(System.currentTimeMillis());

        if (countDelta != 0) {
            metaRef.child("appCount").get().addOnSuccessListener(snapshot -> {
                Long currentCount = snapshot.getValue(Long.class);
                long newCount = (currentCount != null ? currentCount : 0) + countDelta;
                metaRef.child("appCount").setValue(Math.max(0, newCount));
            });
        }
    }

    private String encodeIconToBase64(String packageName) throws Exception {
        Drawable drawable = packageManager.getApplicationIcon(packageName);
        Bitmap bitmap = drawableToBitmap(drawable);
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 48, 48, true);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.PNG, 100, baos);
        byte[] byteArray = baos.toByteArray();

        return Base64.encodeToString(byteArray, Base64.NO_WRAP);
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable) {
            return ((BitmapDrawable) drawable).getBitmap();
        }

        Bitmap bitmap = Bitmap.createBitmap(
                drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(),
                Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);

        return bitmap;
    }

    private String sanitizeFirebaseKey(String key) {
        if (key == null || key.isEmpty())
            return "unknown";
        return key.replaceAll("[.#$\\[\\]/]", "_");
    }

    /**
     * 📦 Push app status event to Firebase for parent notifications
     */
    private void pushAppStatusEvent(String deviceId, String packageName, String action) {
        try {
            String appName;
            if (action.equals("INSTALLED")) {
                // Get app name from package manager
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                appName = packageManager.getApplicationLabel(appInfo).toString();
            } else {
                // For uninstalled apps, use package name
                appName = packageName.substring(packageName.lastIndexOf('.') + 1);
            }

            AppStatusEvent event = new AppStatusEvent(
                    appName,
                    packageName,
                    action,
                    System.currentTimeMillis());

            String firebasePath = "child_devices/" + deviceId + "/app_events";
            Log.d(TAG, "📦 Pushing app status event to: " + firebasePath);

            DatabaseReference eventsRef = FirebaseDatabase.getInstance()
                    .getReference("child_devices")
                    .child(deviceId)
                    .child("app_events");

            eventsRef.push().setValue(event)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅✅✅ App status event pushed: " + action + " - " + appName);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to push app status event: " + e.getMessage());
                    });
        } catch (Exception e) {
            Log.e(TAG, "Error pushing app status event: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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
        if (packageReceiver != null) {
            unregisterReceiver(packageReceiver);
        }
        Log.d(TAG, "📦 PackageChangeService stopped");
    }
}
