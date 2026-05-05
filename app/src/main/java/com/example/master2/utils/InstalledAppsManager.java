package com.example.master2.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.util.Log;

import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manager for syncing installed apps list from child to Firebase.
 * Parent can view all apps installed on child device with icons.
 */
public class InstalledAppsManager {
    private static final String TAG = "InstalledAppsManager";
    private static InstalledAppsManager instance;
    private final Context context;
    private final PackageManager packageManager;

    private InstalledAppsManager(Context context) {
        this.context = context.getApplicationContext();
        this.packageManager = this.context.getPackageManager();
    }

    public static synchronized InstalledAppsManager getInstance(Context context) {
        if (instance == null) {
            instance = new InstalledAppsManager(context);
        }
        return instance;
    }

    /**
     * Collect all installed apps and upload to Firebase
     */
    public void syncInstalledApps(String deviceId, OnSyncCompleteListener listener) {
        if (deviceId == null || deviceId.isEmpty()) {
            if (listener != null)
                listener.onError("No device ID");
            return;
        }

        new Thread(() -> {
            try {
                Log.d(TAG, "📱 Collecting installed apps...");
                List<Map<String, Object>> appsList = collectInstalledApps();
                Log.d(TAG, "📱 Found " + appsList.size() + " apps");

                uploadToFirebase(deviceId, appsList, listener);
            } catch (Exception e) {
                Log.e(TAG, "❌ Error collecting apps: " + e.getMessage());
                if (listener != null)
                    listener.onError(e.getMessage());
            }
        }).start();
    }

    /**
     * Collect all installed apps with icons encoded as Base64
     */
    private List<Map<String, Object>> collectInstalledApps() {
        List<Map<String, Object>> appsList = new ArrayList<>();
        List<ApplicationInfo> installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);

        for (ApplicationInfo appInfo : installedApps) {
            // Skip our own app
            if (appInfo.packageName.contains("com.example.master2")) {
                continue;
            }

            // Only include apps that user can launch (not system services)
            if (packageManager.getLaunchIntentForPackage(appInfo.packageName) == null) {
                // Check if it's a system app without launcher - skip it
                if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }
            }

            try {
                Map<String, Object> appData = new HashMap<>();
                String appName = appInfo.loadLabel(packageManager).toString();

                appData.put("packageName", appInfo.packageName);
                appData.put("appName", appName);
                appData.put("isSystemApp", (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                appData.put("lastUpdated", System.currentTimeMillis());

                // Encode icon
                try {
                    String iconBase64 = encodeIconToBase64(appInfo.packageName);
                    appData.put("iconBase64", iconBase64);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to encode icon for " + appInfo.packageName);
                }

                appsList.add(appData);
            } catch (Exception e) {
                Log.w(TAG, "Skipping app: " + appInfo.packageName);
            }
        }

        // Sort by app name
        Collections.sort(appsList, (a, b) -> {
            String nameA = (String) a.get("appName");
            String nameB = (String) b.get("appName");
            return nameA.compareToIgnoreCase(nameB);
        });

        return appsList;
    }

    /**
     * Upload apps list to Firebase
     */
    private void uploadToFirebase(String deviceId, List<Map<String, Object>> appsList,
            OnSyncCompleteListener listener) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("installed_apps")
                .child(deviceId);

        Map<String, Object> uploadData = new HashMap<>();
        uploadData.put("lastUpdated", System.currentTimeMillis());
        uploadData.put("appCount", appsList.size());

        // Convert list to map with sanitized keys
        Map<String, Object> appsMap = new HashMap<>();
        for (Map<String, Object> app : appsList) {
            String packageName = (String) app.get("packageName");
            String safeKey = sanitizeFirebaseKey(packageName);
            appsMap.put(safeKey, app);
        }
        uploadData.put("apps", appsMap);

        Log.d(TAG, "📤 Uploading " + appsList.size() + " apps to Firebase...");

        ref.setValue(uploadData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Installed apps synced successfully!");
                    if (listener != null)
                        listener.onSuccess(appsList.size());
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to sync apps: " + e.getMessage());
                    if (listener != null)
                        listener.onError(e.getMessage());
                });
    }

    /**
     * Encode app icon to Base64 (48x48 PNG)
     */
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

    public interface OnSyncCompleteListener {
        void onSuccess(int appCount);

        void onError(String error);
    }
}
