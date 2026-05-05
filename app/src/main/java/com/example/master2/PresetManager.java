package com.example.master2;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class PresetManager {
    private static final String TAG = "PresetManager";
    private static final String PREF_NAME = "device_presets";

    private DatabaseReference presetsRef;
    private DatabaseReference blockCommandsRef;
    private SharedPreferences prefs;
    private Context context;
    private Gson gson;

    public interface OnPresetSavedListener {
        void onSuccess();
        void onError(String error);
    }

    public interface OnPresetAppliedListener {
        void onSuccess(int blockedApps, int totalApps);
        void onError(String error);
    }

    public interface OnPresetLoadedListener {
        void onSuccess(FocusModePreset preset);
        void onError(String error);
    }

    public PresetManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();

        FirebaseDatabase database = FirebaseDatabase.getInstance();
        presetsRef = database.getReference("parent_presets");
        blockCommandsRef = database.getReference("block_commands");
    }

    // Check if preset exists for specific device
    public boolean hasPreset(String deviceId) {
        String presetKey = "preset_" + deviceId;
        return prefs.contains(presetKey);
    }

    // Check if focus mode is active for specific device
    public boolean isFocusModeActive(String deviceId) {
        String activeKey = "focus_active_" + deviceId;
        return prefs.getBoolean(activeKey, false);
    }

    // Get preset for specific device
    public FocusModePreset getPreset(String deviceId) {
        String presetKey = "preset_" + deviceId;
        String presetJson = prefs.getString(presetKey, null);

        if (presetJson != null) {
            try {
                return gson.fromJson(presetJson, FocusModePreset.class);
            } catch (Exception e) {
                Log.e(TAG, "Error parsing preset for device " + deviceId + ": " + e.getMessage());
            }
        }
        return null;
    }

    // Get all saved presets
    public List<FocusModePreset> getAllPresets() {
        List<FocusModePreset> presets = new ArrayList<>();

        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("preset_") && !key.contains("_count") && !key.contains("_active")) {
                String deviceId = key.replace("preset_", "");
                FocusModePreset preset = getPreset(deviceId);
                if (preset != null) {
                    presets.add(preset);
                }
            }
        }

        return presets;
    }

    // Save preset for specific device
    public void savePreset(FocusModePreset preset, OnPresetSavedListener listener) {
        String parentId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

        // Save locally with device-specific key
        String presetKey = "preset_" + preset.deviceId;
        String countKey = "preset_count_" + preset.deviceId;

        preset.createdTimestamp = System.currentTimeMillis();

        prefs.edit()
                .putString(presetKey, gson.toJson(preset))
                .putInt(countKey, preset.blockedAppPackages.size())
                .apply();

        Log.d(TAG, "Saved preset for device: " + preset.deviceName + " (" + preset.deviceId + ") with " +
                preset.blockedAppPackages.size() + " blocked apps");

        // Save to Firebase with device-specific path
        presetsRef.child(parentId).child(preset.deviceId)
                .setValue(preset)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Preset synced to Firebase for device: " + preset.deviceId);
                    listener.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to sync preset to Firebase: " + e.getMessage());
                    listener.onError(e.getMessage());
                });
    }

    // Update existing preset
    public void updatePreset(FocusModePreset updatedPreset, OnPresetSavedListener listener) {
        Log.d(TAG, "Updating preset for device: " + updatedPreset.deviceName);

        // Keep original creation time, update modification time
        FocusModePreset existingPreset = getPreset(updatedPreset.deviceId);
        if (existingPreset != null) {
            updatedPreset.createdTimestamp = existingPreset.createdTimestamp;
        }

        // Save updated preset
        savePreset(updatedPreset, listener);
    }

    // Delete preset for specific device
    public void deletePreset(String deviceId, OnPresetSavedListener listener) {
        String presetKey = "preset_" + deviceId;
        String countKey = "preset_count_" + deviceId;
        String activeKey = "focus_active_" + deviceId;

        // Remove locally
        prefs.edit()
                .remove(presetKey)
                .remove(countKey)
                .remove(activeKey)
                .apply();

        Log.d(TAG, "Deleted preset for device: " + deviceId);

        // Remove from Firebase
        String parentId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        presetsRef.child(parentId).child(deviceId)
                .removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Preset removed from Firebase for device: " + deviceId);
                    listener.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to remove preset from Firebase: " + e.getMessage());
                    listener.onError(e.getMessage());
                });
    }

    // Activate preset for specific device
    public void activatePreset(String deviceId, OnPresetAppliedListener listener) {
        FocusModePreset preset = getPreset(deviceId);
        if (preset == null) {
            listener.onError("No preset found for this device");
            return;
        }

        // Mark as active locally
        String activeKey = "focus_active_" + deviceId;
        prefs.edit().putBoolean(activeKey, true).apply();

        // Update last activated time
        preset.lastActivatedTimestamp = System.currentTimeMillis();
        preset.isActive = true;
        savePreset(preset, new OnPresetSavedListener() {
            @Override
            public void onSuccess() {
                // Continue with activation
            }

            @Override
            public void onError(String error) {
                // Continue anyway
            }
        });

        int totalCommands = preset.blockedAppPackages.size();
        Log.d(TAG, "Activating preset for device " + preset.deviceName + " with " + totalCommands + " apps");

        // Send block commands
        sendBlockCommands(deviceId, preset.blockedAppPackages.toArray(new String[0]), true, totalCommands, listener);
    }

    // Deactivate preset for specific device
    public void deactivatePreset(String deviceId, OnPresetAppliedListener listener) {
        FocusModePreset preset = getPreset(deviceId);
        if (preset == null) {
            listener.onError("No preset found for this device");
            return;
        }

        // Mark as inactive locally
        String activeKey = "focus_active_" + deviceId;
        prefs.edit().putBoolean(activeKey, false).apply();

        // Update preset status
        preset.isActive = false;
        savePreset(preset, new OnPresetSavedListener() {
            @Override
            public void onSuccess() {
                // Continue with deactivation
            }

            @Override
            public void onError(String error) {
                // Continue anyway
            }
        });

        int totalCommands = preset.blockedAppPackages.size();
        Log.d(TAG, "Deactivating preset for device " + preset.deviceName + " with " + totalCommands + " apps");

        // Send unblock commands
        sendBlockCommands(deviceId, preset.blockedAppPackages.toArray(new String[0]), false, totalCommands, listener);
    }

    // Load preset from Firebase for specific device
    public void loadPresetFromFirebase(String deviceId, OnPresetLoadedListener listener) {
        String parentId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

        presetsRef.child(parentId).child(deviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        FocusModePreset preset = dataSnapshot.getValue(FocusModePreset.class);
                        if (preset != null) {
                            // Save locally
                            String presetKey = "preset_" + deviceId;
                            prefs.edit().putString(presetKey, gson.toJson(preset)).apply();

                            listener.onSuccess(preset);
                        } else {
                            listener.onError("No preset found in Firebase");
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        listener.onError(databaseError.getMessage());
                    }
                });
    }

    // Get preset statistics
    public PresetStats getPresetStats(String deviceId) {
        FocusModePreset preset = getPreset(deviceId);
        if (preset == null) return null;

        PresetStats stats = new PresetStats();
        stats.deviceId = deviceId;
        stats.deviceName = preset.deviceName;
        stats.totalBlockedApps = preset.blockedAppPackages.size();
        stats.isActive = isFocusModeActive(deviceId);
        stats.createdDate = preset.createdTimestamp;
        stats.lastActivated = preset.lastActivatedTimestamp;

        return stats;
    }

    private void sendBlockCommands(String targetDeviceId, String[] packageNames, boolean blockStatus,
                                   int totalCommands, OnPresetAppliedListener listener) {
        String controllerDeviceId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);

        final int[] completedCommands = {0};
        final boolean[] hasError = {false};

        for (String packageName : packageNames) {
            BlockCommand command = new BlockCommand(targetDeviceId, controllerDeviceId,
                    packageName, getAppNameFromPackage(packageName), blockStatus);

            blockCommandsRef.child(targetDeviceId).child(command.commandId)
                    .setValue(command)
                    .addOnSuccessListener(aVoid -> {
                        completedCommands[0]++;
                        Log.d(TAG, "Block command sent for " + packageName + " (" + completedCommands[0] + "/" + totalCommands + ")");

                        if (completedCommands[0] == totalCommands && !hasError[0]) {
                            listener.onSuccess(totalCommands, totalCommands);
                        }
                    })
                    .addOnFailureListener(e -> {
                        if (!hasError[0]) {
                            hasError[0] = true;
                            Log.e(TAG, "Failed to send block command for " + packageName + ": " + e.getMessage());
                            listener.onError("Failed to send commands: " + e.getMessage());
                        }
                    });
        }
    }

    private String getAppNameFromPackage(String packageName) {
        String[] parts = packageName.split("\\.");
        return parts.length > 0 ? parts[parts.length - 1] : packageName;
    }

    // Helper class for preset statistics
    public static class PresetStats {
        public String deviceId;
        public String deviceName;
        public int totalBlockedApps;
        public boolean isActive;
        public long createdDate;
        public long lastActivated;
    }
}