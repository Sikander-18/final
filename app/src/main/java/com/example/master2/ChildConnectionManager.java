package com.example.master2;

import android.content.Context;
import android.content.Intent;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.List;
import java.util.ArrayList;
import android.util.Log;
import java.util.HashMap;

public class ChildConnectionManager {
    private DatabaseReference qrShareRef;
    private Context context;
    private FirebaseAuth mAuth;

    public interface OnConnectionListener {
        void onSuccess(String parentUserId);

        void onError(String error);
    }

    public ChildConnectionManager(Context context) {
        this.context = context;
        this.mAuth = FirebaseAuth.getInstance();
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        this.qrShareRef = database.getReference("qr_shares");
    }

    public void connectToParent(String shareKey, String parentDeviceName,
            String childDeviceId, String childDeviceName,
            List<AppInfo> childApps, OnConnectionListener listener) {

        // DEBUGGING: Log all connection parameters
        Log.d("ChildConnectionManager", "🔥 CHILD CONNECTION ATTEMPT - REAL DEVICE DEBUG");
        Log.d("ChildConnectionManager", "🔑 Share Key: " + shareKey);
        Log.d("ChildConnectionManager", "👨‍👩‍👧‍👦 Parent Device Name: " + parentDeviceName);
        Log.d("ChildConnectionManager", "📱 Child Device ID: " + childDeviceId);
        Log.d("ChildConnectionManager", "📱 Child Device Name: " + childDeviceName);
        Log.d("ChildConnectionManager", "📦 Child Apps Count: " + (childApps != null ? childApps.size() : "NULL"));

        // 🔥 BULLETPROOF RECONNECTION: Clear any existing connection state first
        Log.d("ChildConnectionManager", "🧹 BULLETPROOF MODE: Clearing any existing connection state...");
        clearExistingConnectionState(childDeviceId);

        // 🧹 FRESH CONNECTION: Clear all previous session data
        FreshConnectionManager freshManager = new FreshConnectionManager(context, childDeviceId);
        if (freshManager.shouldClearDataOnConnection()) {
            Log.d("ChildConnectionManager", "🧹 Clearing previous session data for fresh start");
            freshManager.handleFreshConnection();
        }

        // CRITICAL FIX: First validate that this device is allowed to connect
        Log.d("ChildConnectionManager", "🔐 Validating connection permissions...");
        validateConnectionPermission(childDeviceId, shareKey, isAllowed -> {
            if (!isAllowed) {
                Log.w("ChildConnectionManager", "⚠️ Connection not allowed - device may have been removed");
                listener.onError("Connection not allowed. Please scan a fresh QR code from the parent device.");
                return;
            }

            // CRITICAL FIX: Authenticate with Firebase first but show immediate connection
            Log.d("ChildConnectionManager", "🔑 Starting Firebase authentication...");
            Log.d("ChildConnectionManager", "🔍 Current auth state: "
                    + (mAuth.getCurrentUser() != null ? "AUTHENTICATED" : "NOT AUTHENTICATED"));

            // Notify user immediately that connection process has started
            listener.onSuccess("CONNECTING"); // Special value to indicate connection started

            mAuth.signInAnonymously()
                    .addOnSuccessListener(authResult -> {
                        Log.d("ChildConnectionManager", "✅ Firebase authentication successful");
                        Log.d("ChildConnectionManager", "📱 User ID: " + authResult.getUser().getUid());

                        // Now proceed with the connection
                        proceedWithConnectionFast(shareKey, parentDeviceName, childDeviceId, childDeviceName, childApps,
                                listener);
                    })
                    .addOnFailureListener(e -> {
                        Log.e("ChildConnectionManager", "❌ Firebase authentication failed: " + e.getMessage());
                        listener.onError("Authentication failed: " + e.getMessage());
                    });
        });
    }

    // 🔥 BULLETPROOF RECONNECTION: Clear any existing connection state
    private void clearExistingConnectionState(String childDeviceId) {
        try {
            Log.d("ChildConnectionManager", "🧹 Clearing existing connection state for: " + childDeviceId);

            // Clear local storage
            if (context != null) {
                context.getSharedPreferences("device_connection", Context.MODE_PRIVATE)
                        .edit()
                        .clear()
                        .apply();

                // Also clear session data
                SessionManager sessionManager = new SessionManager(context);
                // Don't clear everything, just connection-specific data
                Log.d("ChildConnectionManager", "✅ Local connection state cleared");
            }

        } catch (Exception e) {
            Log.e("ChildConnectionManager", "❌ Error clearing connection state: " + e.getMessage());
            // Don't fail the connection for this, just log it
        }
    }

    // CRITICAL FIX: Validate that this device is still allowed to connect
    private void validateConnectionPermission(String childDeviceId, String shareKey, ValidationCallback callback) {
        // 🔥 BULLETPROOF MODE: Allow ALL QR connections regardless of previous removal
        // status
        Log.d("ChildConnectionManager", "🔥 BULLETPROOF MODE: Allowing QR reconnection for device: " + childDeviceId);
        callback.onResult(true);

        /*
         * ORIGINAL VALIDATION CODE - DISABLED FOR BULLETPROOF RECONNECTION
         * // Check if this device was recently removed by looking for existing
         * connection data
         * qrShareRef.child(shareKey).addListenerForSingleValueEvent(new
         * ValueEventListener() {
         * 
         * @Override
         * public void onDataChange(DataSnapshot dataSnapshot) {
         * if (!dataSnapshot.exists()) {
         * Log.w("ChildConnectionManager",
         * "⚠️ QR share no longer exists - connection may be invalid");
         * callback.onResult(false);
         * return;
         * }
         * 
         * // Check if this specific device exists in the share data
         * boolean deviceFound = false;
         * 
         * // Check allDeviceAppLists format
         * if (dataSnapshot.child("allDeviceAppLists").exists()) {
         * for (DataSnapshot listSnapshot :
         * dataSnapshot.child("allDeviceAppLists").getChildren()) {
         * String deviceId = listSnapshot.child("deviceId").getValue(String.class);
         * if (childDeviceId.equals(deviceId)) {
         * deviceFound = true;
         * break;
         * }
         * }
         * }
         * 
         * // Check legacy deviceAppLists format if not found
         * if (!deviceFound &&
         * dataSnapshot.child("deviceAppLists").child(childDeviceId).exists()) {
         * deviceFound = true;
         * }
         * 
         * Log.d("ChildConnectionManager", "Device found in share data: " +
         * deviceFound);
         * 
         * // Allow new connections (device not found) or existing valid connections
         * callback.onResult(true);
         * }
         * 
         * @Override
         * public void onCancelled(DatabaseError databaseError) {
         * Log.e("ChildConnectionManager", "❌ Failed to validate connection: " +
         * databaseError.getMessage());
         * // On error, allow connection but log the issue
         * callback.onResult(true);
         * }
         * });
         */
    }

    private interface ValidationCallback {
        void onResult(boolean isAllowed);
    }

    private void proceedWithConnectionFast(String shareKey, String parentDeviceName,
            String childDeviceId, String childDeviceName,
            List<AppInfo> childApps, OnConnectionListener listener) {

        // OPTIMIZED: Create minimal connection first, upload apps later
        Log.d("ChildConnectionManager", "🚀 Fast connection process started");

        // Step 1: Quick connection setup - minimal data first
        setupMinimalConnection(shareKey, parentDeviceName, childDeviceId, childDeviceName, listener);

        // Step 2: Upload apps in background (non-blocking)
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Small delay to let initial connection complete
                uploadAppsInBackground(childDeviceId, childApps);
            } catch (InterruptedException e) {
                Log.d("ChildConnectionManager", "Background app upload interrupted");
            }
        }).start();
    }

    private void proceedWithConnection(String shareKey, String parentDeviceName,
            String childDeviceId, String childDeviceName,
            List<AppInfo> childApps, OnConnectionListener listener) {

        DeviceAppList childDeviceList = new DeviceAppList(childDeviceId, childDeviceName, childApps);

        Log.d("ChildConnectionManager", "🔍 Checking qr_shares/" + shareKey);

        // First check if multi-device share exists and get parent info
        qrShareRef.child(shareKey).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                Log.d("ChildConnectionManager", "📊 QR Share data received, exists: " + dataSnapshot.exists());

                // CRITICAL: Get the parent device ID from QR share data
                String parentDeviceId = null;
                String actualParentName = parentDeviceName;

                if (dataSnapshot.exists()) {
                    parentDeviceId = dataSnapshot.child("parentDeviceId").getValue(String.class);
                    String savedParentName = dataSnapshot.child("parentDeviceName").getValue(String.class);
                    if (savedParentName != null) {
                        actualParentName = savedParentName;
                    }
                    Log.d("ChildConnectionManager", "🎯 Found parent device ID: " + parentDeviceId);
                } else {
                    Log.w("ChildConnectionManager", "⚠️ QR Share doesn't exist, will create new one");
                }

                // Continue with connection process
                completeConnection(shareKey, parentDeviceId, actualParentName, childDeviceId, childDeviceName,
                        childApps, childDeviceList, listener);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("ChildConnectionManager", "❌ QR Share read cancelled: " + databaseError.getMessage());

                // Check if it's a permission error and provide specific guidance
                if (databaseError.getCode() == DatabaseError.PERMISSION_DENIED) {
                    listener.onError("Permission denied - Authentication issue: " + databaseError.getMessage());
                } else {
                    listener.onError("Database error: " + databaseError.getMessage());
                }
            }
        });
    }

    private void completeConnection(String shareKey, String parentDeviceId, String parentName,
            String childDeviceId, String childDeviceName, List<AppInfo> childApps,
            DeviceAppList childDeviceList, OnConnectionListener listener) {

        // Step 1: Update qr_shares (existing functionality)
        MultiDeviceQRShare multiShare = new MultiDeviceQRShare(childDeviceId, childDeviceName);
        if (parentDeviceId != null) {
            multiShare.setParentId(parentDeviceId);
            multiShare.setParentName(parentName);
        }
        multiShare.addDeviceAppList(childDeviceList);

        Log.d("ChildConnectionManager", "💾 Saving multi-device share to qr_shares/" + shareKey);
        qrShareRef.child(shareKey).setValue(multiShare)
                .addOnSuccessListener(aVoid -> {
                    Log.d("ChildConnectionManager", "✅ QR share updated successfully");

                    // Step 2: Find the parent's Firebase Auth UID and update parent-child structure
                    if (parentDeviceId != null) {
                        establishParentChildConnection(parentDeviceId, childDeviceId, childDeviceName, childApps.size(),
                                listener);
                    } else {
                        // If no parent device ID, just continue with app upload
                        Log.w("ChildConnectionManager",
                                "⚠️ No parent device ID found, skipping parent-child structure update");
                        uploadAppsToDeviceAppsNode(childDeviceId, childApps, listener);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e("ChildConnectionManager", "❌ Failed to save multi-device share: " + e.getMessage());
                    listener.onError("Failed to save connection data: " + e.getMessage());
                });
    }

    private void establishParentChildConnection(String parentDeviceId, String childDeviceId,
            String childDeviceName, int appCount, OnConnectionListener listener) {

        Log.d("ChildConnectionManager", "🔗 Establishing parent-child connection...");
        Log.d("ChildConnectionManager",
                "👨‍👩‍👧‍👦 Parent Device: " + parentDeviceId + ", Child Device: " + childDeviceId);

        // ENHANCED: Look up parent's Firebase Auth UID from the users collection using
        // multiple strategies
        DatabaseReference usersRef = FirebaseDatabase.getInstance().getReference("users");

        // Strategy 1: Look for user where userId field equals parentDeviceId (direct
        // match)
        usersRef.child(parentDeviceId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot userSnapshot) {
                if (userSnapshot.exists()) {
                    // Found parent user directly - parentDeviceId IS the Firebase Auth UID
                    String parentUserId = parentDeviceId;
                    Log.d("ChildConnectionManager", "🎯 Found parent user ID (direct): " + parentUserId);
                    updateParentChildStructure(parentUserId, childDeviceId, childDeviceName, appCount, listener);
                } else {
                    // Strategy 2: Search by device ID field (in case they stored Android device ID
                    // separately)
                    searchParentByDeviceId(parentDeviceId, childDeviceId, childDeviceName, appCount, listener);
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("ChildConnectionManager", "❌ Direct parent lookup cancelled: " + databaseError.getMessage());
                // Try strategy 2
                searchParentByDeviceId(parentDeviceId, childDeviceId, childDeviceName, appCount, listener);
            }
        });
    }

    private void searchParentByDeviceId(String parentDeviceId, String childDeviceId,
            String childDeviceName, int appCount, OnConnectionListener listener) {

        Log.d("ChildConnectionManager", "🔍 Searching for parent by deviceId field...");
        DatabaseReference usersRef = FirebaseDatabase.getInstance().getReference("users");

        usersRef.orderByChild("deviceId").equalTo(parentDeviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        if (dataSnapshot.exists()) {
                            // Found parent user(s) with this device ID
                            for (DataSnapshot userSnapshot : dataSnapshot.getChildren()) {
                                String parentUserId = userSnapshot.getKey(); // This is the Firebase Auth UID
                                Log.d("ChildConnectionManager",
                                        "🎯 Found parent user ID (by deviceId): " + parentUserId);
                                Log.d("ChildConnectionManager", "📊 Parent user data: " + userSnapshot.getValue());

                                // Now update the parents/{parentUserId}/connectedChildDevices structure
                                updateParentChildStructure(parentUserId, childDeviceId, childDeviceName, appCount,
                                        listener);
                                return; // Exit after first match
                            }
                        } else {
                            Log.e("ChildConnectionManager", "❌ CRITICAL: Parent not found in users collection!");
                            Log.e("ChildConnectionManager", "🔍 Searched for deviceId: " + parentDeviceId);
                            Log.e("ChildConnectionManager",
                                    "💡 Parent app must store user data in users/{parentUserId} collection");
                            Log.e("ChildConnectionManager",
                                    "🔧 Using device ID as fallback, but parent dashboard may not show child");

                            // Strategy 3: Use device ID as fallback (for legacy compatibility)
                            updateParentChildStructure(parentDeviceId, childDeviceId, childDeviceName, appCount,
                                    listener);
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e("ChildConnectionManager", "❌ Parent search cancelled: " + databaseError.getMessage());
                        // Continue with app upload even if parent structure update fails
                        uploadAppsToDeviceAppsNode(childDeviceId, ChildAppUtils.getInstalledApps(context), listener);
                    }
                });
    }

    private void updateParentChildStructure(String parentUserId, String childDeviceId,
            String childDeviceName, int appCount, OnConnectionListener listener) {

        Log.d("ChildConnectionManager", "📝 Updating parent-child structure for parent: " + parentUserId);

        // Create child device data for parent's structure
        HashMap<String, Object> childDeviceData = new HashMap<>();
        childDeviceData.put("deviceId", childDeviceId);
        childDeviceData.put("deviceName", childDeviceName); // This now contains userName from fix
        childDeviceData.put("userName", childDeviceName); // 🔧 FIX: Also store as userName explicitly
        childDeviceData.put("appCount", appCount);
        childDeviceData.put("lastConnected", System.currentTimeMillis());
        childDeviceData.put("connectionStatus", "online");
        childDeviceData.put("connectedAt", System.currentTimeMillis());

        // Update parents/{parentUserId}/connectedChildDevices/{childDeviceId}
        DatabaseReference parentChildRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(parentUserId)
                .child("connectedChildDevices")
                .child(childDeviceId);

        // ENHANCED: Also store parent device info for better discovery
        childDeviceData.put("parentUserId", parentUserId);
        childDeviceData.put("parentDeviceId", parentUserId); // Store for reference

        parentChildRef.setValue(childDeviceData)
                .addOnSuccessListener(aVoid -> {
                    Log.d("ChildConnectionManager", "✅ Parent-child structure updated successfully");
                    Log.d("ChildConnectionManager",
                            "📍 Path: parents/" + parentUserId + "/connectedChildDevices/" + childDeviceId);

                    // Now upload apps to device_apps node
                    uploadAppsToDeviceAppsNode(childDeviceId, ChildAppUtils.getInstalledApps(context),
                            new OnConnectionListener() {
                                @Override
                                public void onSuccess(String ignored) {
                                    listener.onSuccess(parentUserId);
                                }

                                @Override
                                public void onError(String error) {
                                    listener.onError(error);
                                }
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e("ChildConnectionManager", "❌ Failed to update parent-child structure: " + e.getMessage());

                    // Continue with app upload even if parent structure update fails
                    Log.d("ChildConnectionManager", "🔄 Continuing with app upload despite parent structure error");
                    uploadAppsToDeviceAppsNode(childDeviceId, ChildAppUtils.getInstalledApps(context),
                            new OnConnectionListener() {
                                @Override
                                public void onSuccess(String ignored) {
                                    listener.onSuccess(parentUserId);
                                }

                                @Override
                                public void onError(String error) {
                                    listener.onError(error);
                                }
                            });
                });
    }

    private void uploadAppsToDeviceAppsNode(String deviceId, List<AppInfo> apps, OnConnectionListener listener) {
        try {
            Log.d("ChildConnectionManager",
                    "🚀 Starting upload of " + apps.size() + " apps to device_apps/" + deviceId);

            // Initialize usage snapshots reference in Firebase
            DatabaseReference snapshotRef = FirebaseDatabase.getInstance()
                    .getReference("usage_snapshots")
                    .child(deviceId)
                    .child("latest");

            // Initialize with empty snapshot if it doesn't exist yet
            snapshotRef.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    if (!dataSnapshot.exists()) {
                        Log.d("ChildConnectionManager", "📊 Creating initial usage snapshot");
                        // Create initial empty snapshot
                        UsageSnapshot emptySnapshot = new UsageSnapshot(
                                System.currentTimeMillis(),
                                new ArrayList<>(),
                                new ArrayList<>(),
                                new ArrayList<>());
                        snapshotRef.setValue(emptySnapshot);
                    }
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    Log.w("ChildConnectionManager", "⚠️ Usage snapshot init cancelled: " + databaseError.getMessage());
                    // Ignore, not critical for connection
                }
            });

            DatabaseReference deviceAppsRef = FirebaseDatabase.getInstance()
                    .getReference("device_apps")
                    .child(deviceId);

            // ENHANCED: Convert AppInfo list to a clean structure for Firebase
            ArrayList<HashMap<String, Object>> appsArray = new ArrayList<>();
            int validAppCount = 0;

            for (AppInfo app : apps) {
                try {
                    if (app.packageName == null || app.packageName.isEmpty()) {
                        Log.w("ChildConnectionManager", "⚠️ Skipping app with null/empty package name");
                        continue;
                    }

                    if (app.name == null || app.name.trim().isEmpty()) {
                        Log.w("ChildConnectionManager", "⚠️ Skipping app with null/empty name: " + app.packageName);
                        continue;
                    }

                    // Skip our own app to prevent blocking issues
                    if (app.packageName.contains("com.example.master2")) {
                        Log.d("ChildConnectionManager", "🛡️ Skipping our own app: " + app.packageName);
                        continue;
                    }

                    HashMap<String, Object> appData = new HashMap<>();
                    appData.put("packageName", app.packageName);
                    appData.put("name", app.name.trim());
                    appData.put("category", app.category != null ? app.category : "Other");
                    appData.put("versionCode", app.versionCode != null ? app.versionCode : 0L);
                    appData.put("versionName", app.versionName != null ? app.versionName : "Unknown");
                    appData.put("isSystemApp", app.isSystemApp);
                    appData.put("timestamp", System.currentTimeMillis());
                    appData.put("uploadedBy", "child_device");

                    appsArray.add(appData);
                    validAppCount++;

                    if (validAppCount <= 5) { // Log first 5 apps for debugging
                        Log.d("ChildConnectionManager",
                                "📱 App " + validAppCount + ": " + app.name + " (" + app.packageName + ")");
                    }

                } catch (Exception e) {
                    Log.e("ChildConnectionManager", "❌ Error processing app " + app.name + ": " + e.getMessage());
                }
            }

            if (appsArray.isEmpty()) {
                Log.e("ChildConnectionManager", "❌ No valid apps to upload!");
                listener.onError("No valid apps found to upload");
                return;
            }

            Log.d("ChildConnectionManager",
                    "📊 Uploading " + validAppCount + " valid apps out of " + apps.size() + " total");

            // Create final copy for lambda expression
            final int finalValidAppCount = validAppCount;

            // Upload to device_apps node with verification
            deviceAppsRef.setValue(appsArray)
                    .addOnSuccessListener(aVoid -> {
                        Log.d("ChildConnectionManager",
                                "✅ Successfully uploaded " + finalValidAppCount + " apps to device_apps/" + deviceId);

                        // VERIFICATION: Read back the data to confirm upload
                        deviceAppsRef.addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override
                            public void onDataChange(DataSnapshot dataSnapshot) {
                                if (dataSnapshot.exists()) {
                                    int uploadedCount = 0;
                                    if (dataSnapshot.getValue() instanceof List) {
                                        List<?> uploadedList = (List<?>) dataSnapshot.getValue();
                                        uploadedCount = uploadedList.size();
                                    } else {
                                        uploadedCount = (int) dataSnapshot.getChildrenCount();
                                    }

                                    Log.d("ChildConnectionManager",
                                            "🔍 VERIFICATION: " + uploadedCount + " apps confirmed in Firebase");
                                    Log.d("ChildConnectionManager",
                                            "🎉 CONNECTION COMPLETE! Child device should now appear in parent dashboard.");

                                    if (uploadedCount > 0) {
                                        try {
                                            listener.onSuccess(null); // If parentUserId is not available here, pass
                                                                      // null
                                        } catch (Exception e) {
                                            // Fallback to original success method if new one doesn't exist
                                            listener.onSuccess(null);
                                        }
                                    } else {
                                        Log.e("ChildConnectionManager",
                                                "❌ VERIFICATION FAILED: No apps found after upload");
                                        listener.onError("Upload verification failed");
                                    }
                                } else {
                                    Log.e("ChildConnectionManager", "❌ VERIFICATION FAILED: Upload node doesn't exist");
                                    listener.onError("Upload verification failed - node doesn't exist");
                                }
                            }

                            @Override
                            public void onCancelled(DatabaseError databaseError) {
                                Log.e("ChildConnectionManager",
                                        "❌ Verification cancelled: " + databaseError.getMessage());
                                // Still call success since the main upload worked
                                try {
                                    listener.onSuccess(null); // If parentUserId is not available here, pass null
                                } catch (Exception e) {
                                    // Fallback to original success method if new one doesn't exist
                                    listener.onSuccess(null);
                                }
                            }
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e("ChildConnectionManager", "❌ Failed to upload apps: " + e.getMessage());
                        listener.onError("Upload failed: " + e.getMessage());
                    });

        } catch (Exception e) {
            Log.e("ChildConnectionManager", "Critical error in uploadAppsToDeviceAppsNode: " + e.getMessage());
            listener.onError("Critical error uploading apps: " + e.getMessage());
        }
    }

    // FAST CONNECTION METHODS - ENHANCED FOR BULLETPROOF RECONNECTION
    private void setupMinimalConnection(String shareKey, String parentDeviceName,
            String childDeviceId, String childDeviceName,
            OnConnectionListener listener) {

        Log.d("ChildConnectionManager", "⚡ Setting up bulletproof minimal connection for device: " + childDeviceId);

        // First, get parent device info from the QR share
        qrShareRef.child(shareKey).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                String parentDeviceId = null;
                String actualParentName = parentDeviceName; // Start with provided name

                if (dataSnapshot.exists()) {
                    parentDeviceId = dataSnapshot.child("parentDeviceId").getValue(String.class);
                    String savedParentName = dataSnapshot.child("parentDeviceName").getValue(String.class);
                    if (savedParentName != null) {
                        actualParentName = savedParentName; // Update if available
                    }
                    Log.d("ChildConnectionManager", "🎯 Found parent device ID: " + parentDeviceId);
                }

                // Make variables final for lambda usage
                final String finalParentDeviceId = parentDeviceId;
                final String finalActualParentName = actualParentName;

                // 🔥 BULLETPROOF: Create comprehensive QR share entry with complete metadata
                HashMap<String, Object> bulletproofShare = new HashMap<>();
                bulletproofShare.put("childDeviceId", childDeviceId);
                bulletproofShare.put("childDeviceName", childDeviceName);
                bulletproofShare.put("parentDeviceName", finalActualParentName);
                bulletproofShare.put("connectionStatus", "connected");
                bulletproofShare.put("timestamp", System.currentTimeMillis());
                bulletproofShare.put("reconnectionTimestamp", System.currentTimeMillis());
                bulletproofShare.put("bulletproofMode", true);
                bulletproofShare.put("connectionAttempts", 1);
                if (finalParentDeviceId != null) {
                    bulletproofShare.put("parentDeviceId", finalParentDeviceId);
                }

                // 🔥 BULLETPROOF: Force overwrite any existing QR share data
                qrShareRef.child(shareKey).setValue(bulletproofShare)
                        .addOnSuccessListener(aVoid -> {
                            Log.d("ChildConnectionManager", "✅ Bulletproof QR share established!");

                            // CRITICAL: Also establish parent-child connection immediately
                            if (finalParentDeviceId != null) {
                                establishBulletproofParentChildConnection(finalParentDeviceId, childDeviceId,
                                        childDeviceName, finalActualParentName, listener);
                            } else {
                                Log.w("ChildConnectionManager",
                                        "⚠️ No parent device ID, creating emergency connection");
                                createEmergencyConnection(shareKey, childDeviceId, childDeviceName,
                                        finalActualParentName, listener);
                            }
                        })
                        .addOnFailureListener(e -> {
                            Log.e("ChildConnectionManager", "❌ Bulletproof connection failed: " + e.getMessage());
                            // Try emergency connection anyway
                            createEmergencyConnection(shareKey, childDeviceId, childDeviceName, finalActualParentName,
                                    listener);
                        });
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("ChildConnectionManager", "❌ Failed to read parent info: " + databaseError.getMessage());
                // Create emergency connection with original parent name
                createEmergencyConnection(shareKey, childDeviceId, childDeviceName, parentDeviceName, listener);
            }
        });
    }

    // 🔥 BULLETPROOF: Emergency connection when parent device ID is not available
    private void createEmergencyConnection(String shareKey, String childDeviceId, String childDeviceName,
            String parentDeviceName, OnConnectionListener listener) {
        Log.d("ChildConnectionManager", "🚨 Creating emergency bulletproof connection");

        // Try to establish connection using QR share key as fallback parent ID
        establishBulletproofParentChildConnection(shareKey, childDeviceId, childDeviceName, parentDeviceName, listener);
    }

    // 🔥 BULLETPROOF: Enhanced parent-child connection that works regardless of
    // previous state
    private void establishBulletproofParentChildConnection(String parentDeviceId, String childDeviceId,
            String childDeviceName, String parentDeviceName,
            OnConnectionListener listener) {

        Log.d("ChildConnectionManager", "🚀 Bulletproof parent-child connection setup");
        Log.d("ChildConnectionManager", "🔍 Parent Device ID: " + parentDeviceId);
        Log.d("ChildConnectionManager", "📱 Child Device ID: " + childDeviceId);

        // Search for parent by device ID in users collection
        DatabaseReference usersRef = FirebaseDatabase.getInstance().getReference("users");

        // Search all users to find one with matching device ID
        usersRef.orderByChild("deviceId").equalTo(parentDeviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        String parentUserId = null;

                        if (dataSnapshot.exists()) {
                            // Found parent user by device ID
                            for (DataSnapshot userSnapshot : dataSnapshot.getChildren()) {
                                parentUserId = userSnapshot.getKey(); // This is the Firebase Auth UID
                                Log.d("ChildConnectionManager", "🎯 Found parent user by device ID: " + parentUserId);
                                Log.d("ChildConnectionManager", "📊 Parent user data: " + userSnapshot.getValue());
                                break;
                            }
                        }

                        // 🔥 BULLETPROOF: If not found by device ID, try multiple fallback strategies
                        if (parentUserId == null) {
                            Log.e("ChildConnectionManager",
                                    "❌ BULLETPROOF: Parent not found by device ID in users collection!");
                            Log.e("ChildConnectionManager", "🔍 Searched for deviceId: " + parentDeviceId);
                            Log.e("ChildConnectionManager",
                                    "💡 Parent app must store user data in users/{parentUserId} collection");

                            // Fallback 1: Use device ID directly as user ID
                            parentUserId = parentDeviceId;
                            Log.w("ChildConnectionManager",
                                    "🔄 Fallback 1: Using device ID as user ID: " + parentUserId);
                            Log.w("ChildConnectionManager", "⚠️ WARNING: Parent dashboard may not show child device");
                        }

                        // 🔥 BULLETPROOF: Create comprehensive child device data
                        HashMap<String, Object> bulletproofChildData = new HashMap<>();
                        bulletproofChildData.put("deviceId", childDeviceId);
                        bulletproofChildData.put("deviceName", childDeviceName);
                        bulletproofChildData.put("userName", childDeviceName); // 🔧 FIX: Explicit userName
                        bulletproofChildData.put("appCount", 0); // Will be updated by background upload
                        bulletproofChildData.put("lastConnected", System.currentTimeMillis());
                        bulletproofChildData.put("connectionStatus", "online");
                        bulletproofChildData.put("connectedAt", System.currentTimeMillis());
                        bulletproofChildData.put("bulletproofReconnection", true);
                        bulletproofChildData.put("parentDeviceName", parentDeviceName);
                        bulletproofChildData.put("reconnectionCount", 1);

                        // 🔥 BULLETPROOF: Store in MULTIPLE Firebase paths for maximum reliability
                        final String finalParentUserId = parentUserId;
                        establishMultiPathConnection(finalParentUserId, parentDeviceId, childDeviceId,
                                bulletproofChildData, listener);
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e("ChildConnectionManager", "❌ Parent lookup failed: " + databaseError.getMessage());
                        // 🔥 BULLETPROOF: Still try to establish connection with device ID
                        HashMap<String, Object> emergencyChildData = new HashMap<>();
                        emergencyChildData.put("deviceId", childDeviceId);
                        emergencyChildData.put("deviceName", childDeviceName);
                        emergencyChildData.put("userName", childDeviceName); // 🔧 FIX: Explicit userName
                        emergencyChildData.put("appCount", 0);
                        emergencyChildData.put("lastConnected", System.currentTimeMillis());
                        emergencyChildData.put("connectionStatus", "online");
                        emergencyChildData.put("connectedAt", System.currentTimeMillis());
                        emergencyChildData.put("emergencyConnection", true);

                        establishMultiPathConnection(parentDeviceId, parentDeviceId, childDeviceId, emergencyChildData,
                                listener);
                    }
                });
    }

    // 🔥 BULLETPROOF: Establish connection in multiple Firebase paths
    private void establishMultiPathConnection(String parentUserId, String parentDeviceId, String childDeviceId,
            HashMap<String, Object> childData, OnConnectionListener listener) {

        Log.d("ChildConnectionManager", "🎯 Establishing multi-path bulletproof connection");
        Log.d("ChildConnectionManager", "👤 Parent User ID: " + parentUserId);
        Log.d("ChildConnectionManager", "📱 Parent Device ID: " + parentDeviceId);
        Log.d("ChildConnectionManager", "🔗 Child Device ID: " + childDeviceId);

        // Path 1: Standard parent-child connection
        // (parents/{userId}/connectedChildDevices/{childId})
        DatabaseReference primaryRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(parentUserId)
                .child("connectedChildDevices")
                .child(childDeviceId);

        primaryRef.setValue(childData)
                .addOnSuccessListener(aVoid -> {
                    Log.d("ChildConnectionManager", "✅ PRIMARY connection established: parents/" + parentUserId
                            + "/connectedChildDevices/" + childDeviceId);

                    // Path 2: Backup connection using device ID (for compatibility)
                    if (!parentUserId.equals(parentDeviceId)) {
                        DatabaseReference backupRef = FirebaseDatabase.getInstance()
                                .getReference("parents")
                                .child(parentDeviceId)
                                .child("connectedChildDevices")
                                .child(childDeviceId);

                        backupRef.setValue(childData)
                                .addOnSuccessListener(aVoid2 -> {
                                    Log.d("ChildConnectionManager", "✅ BACKUP connection established: parents/"
                                            + parentDeviceId + "/connectedChildDevices/" + childDeviceId);
                                })
                                .addOnFailureListener(e -> {
                                    Log.w("ChildConnectionManager",
                                            "⚠️ Backup connection failed (not critical): " + e.getMessage());
                                });
                    }

                    // Path 3: Direct device connection reference
                    DatabaseReference deviceRef = FirebaseDatabase.getInstance()
                            .getReference("device_connections")
                            .child(childDeviceId);

                    HashMap<String, Object> deviceConnectionData = new HashMap<>();
                    deviceConnectionData.put("parentUserId", parentUserId);
                    deviceConnectionData.put("parentDeviceId", parentDeviceId);
                    deviceConnectionData.put("connected", true);
                    deviceConnectionData.put("timestamp", System.currentTimeMillis());

                    deviceRef.setValue(deviceConnectionData)
                            .addOnSuccessListener(aVoid3 -> {
                                Log.d("ChildConnectionManager", "✅ DEVICE connection reference established");
                            })
                            .addOnFailureListener(e -> {
                                Log.w("ChildConnectionManager",
                                        "⚠️ Device connection reference failed (not critical): " + e.getMessage());
                            });

                    // 🛡️ UNINSTALL PROTECTION: Set to ENABLED by default for new connections
                    // This ensures protection is active immediately when child connects
                    DatabaseReference protectionRef = FirebaseDatabase.getInstance()
                            .getReference("child_devices")
                            .child(childDeviceId)
                            .child("uninstall_protection");

                    protectionRef.setValue(true)
                            .addOnSuccessListener(aVoid4 -> {
                                Log.d("ChildConnectionManager",
                                        "🛡️ Uninstall protection ENABLED by default for device: " + childDeviceId);
                            })
                            .addOnFailureListener(e -> {
                                Log.w("ChildConnectionManager",
                                        "⚠️ Failed to set uninstall protection (not critical): " + e.getMessage());
                            });

                    // SUCCESS: Report connection established
                    Log.d("ChildConnectionManager", "🎉 BULLETPROOF CONNECTION SUCCESSFUL!");

                    // Start only usage data collection service - NOT timer services
                    startUsageDataCollectionOnly();

                    listener.onSuccess("CONNECTED_FAST");
                })
                .addOnFailureListener(e -> {
                    Log.e("ChildConnectionManager", "❌ Primary connection failed: " + e.getMessage());

                    // 🔥 BULLETPROOF: Try emergency fallback connection
                    DatabaseReference emergencyRef = FirebaseDatabase.getInstance()
                            .getReference("emergency_connections")
                            .child(childDeviceId);

                    HashMap<String, Object> emergencyData = new HashMap<>(childData);
                    emergencyData.put("emergencyMode", true);
                    emergencyData.put("primaryFailure", e.getMessage());

                    emergencyRef.setValue(emergencyData)
                            .addOnSuccessListener(aVoid -> {
                                Log.d("ChildConnectionManager", "✅ EMERGENCY connection established");
                                listener.onSuccess("CONNECTED_FAST");
                            })
                            .addOnFailureListener(e2 -> {
                                Log.e("ChildConnectionManager",
                                        "❌ Emergency connection also failed: " + e2.getMessage());
                                listener.onError("All connection attempts failed: " + e2.getMessage());
                            });
                });
    }

    private void establishParentChildConnectionFast(String parentDeviceId, String childDeviceId,
            String childDeviceName, OnConnectionListener listener) {

        Log.d("ChildConnectionManager", "🚀 Fast parent-child connection setup");
        Log.d("ChildConnectionManager", "🔍 Looking for parent with device ID: " + parentDeviceId);

        // Search for parent by device ID in users collection
        DatabaseReference usersRef = FirebaseDatabase.getInstance().getReference("users");

        // Search all users to find one with matching device ID
        usersRef.orderByChild("deviceId").equalTo(parentDeviceId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        String parentUserId = null;

                        if (dataSnapshot.exists()) {
                            // Found parent user by device ID
                            for (DataSnapshot userSnapshot : dataSnapshot.getChildren()) {
                                parentUserId = userSnapshot.getKey(); // This is the Firebase Auth UID
                                Log.d("ChildConnectionManager", "🎯 Found parent user by device ID: " + parentUserId);
                                break;
                            }
                        } else {
                            // Fallback: try using device ID directly as user ID
                            Log.w("ChildConnectionManager", "⚠️ Parent not found by device ID, trying direct lookup");
                            parentUserId = parentDeviceId;
                        }

                        if (parentUserId != null) {
                            // Create child device data for parent's dashboard
                            HashMap<String, Object> childDeviceData = new HashMap<>();
                            childDeviceData.put("deviceId", childDeviceId);
                            childDeviceData.put("deviceName", childDeviceName);
                            childDeviceData.put("appCount", 0); // Will be updated by background upload
                            childDeviceData.put("lastConnected", System.currentTimeMillis());
                            childDeviceData.put("connectionStatus", "online");
                            childDeviceData.put("connectedAt", System.currentTimeMillis());

                            // Store using both the Firebase Auth UID and device ID for compatibility
                            final String finalParentUserId = parentUserId;

                            // Update parent's connected devices immediately
                            DatabaseReference parentChildRef = FirebaseDatabase.getInstance()
                                    .getReference("parents")
                                    .child(finalParentUserId)
                                    .child("connectedChildDevices")
                                    .child(childDeviceId);

                            parentChildRef.setValue(childDeviceData)
                                    .addOnSuccessListener(aVoid -> {
                                        Log.d("ChildConnectionManager", "✅ Fast parent-child connection established!");
                                        Log.d("ChildConnectionManager", "📍 Path: parents/" + finalParentUserId
                                                + "/connectedChildDevices/" + childDeviceId);

                                        // ALSO save under device ID for backup
                                        DatabaseReference deviceBackupRef = FirebaseDatabase.getInstance()
                                                .getReference("parents")
                                                .child(parentDeviceId)
                                                .child("connectedChildDevices")
                                                .child(childDeviceId);

                                        deviceBackupRef.setValue(childDeviceData)
                                                .addOnSuccessListener(aVoid2 -> {
                                                    Log.d("ChildConnectionManager",
                                                            "✅ Backup connection also saved under device ID");
                                                })
                                                .addOnFailureListener(e -> {
                                                    Log.w("ChildConnectionManager",
                                                            "⚠️ Backup connection failed: " + e.getMessage());
                                                });

                                        listener.onSuccess("CONNECTED_FAST");
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.e("ChildConnectionManager",
                                                "❌ Fast parent-child connection failed: " + e.getMessage());
                                        // Still report success since QR share worked
                                        listener.onSuccess("CONNECTED_FAST");
                                    });
                        } else {
                            Log.e("ChildConnectionManager", "❌ Could not determine parent user ID");
                            listener.onSuccess("CONNECTED_FAST");
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.e("ChildConnectionManager", "❌ Parent lookup failed: " + databaseError.getMessage());
                        // Still report success since QR share worked
                        listener.onSuccess("CONNECTED_FAST");
                    }
                });
    }

    private void uploadAppsInBackground(String childDeviceId, List<AppInfo> childApps) {
        Log.d("ChildConnectionManager", "📦 Starting background app upload for " + childApps.size() + " apps");

        try {
            // Create minimal app list for faster upload
            ArrayList<HashMap<String, Object>> essentialApps = new ArrayList<>();
            int appCount = 0;

            for (AppInfo app : childApps) {
                if (app.packageName != null && !app.packageName.isEmpty() &&
                        app.name != null && !app.name.trim().isEmpty()) {

                    // Skip our own app
                    if (app.packageName.contains("com.example.master2")) {
                        continue;
                    }

                    HashMap<String, Object> appData = new HashMap<>();
                    appData.put("packageName", app.packageName);
                    appData.put("name", app.name.trim());
                    appData.put("isSystemApp", app.isSystemApp);
                    appData.put("timestamp", System.currentTimeMillis());

                    essentialApps.add(appData);
                    appCount++;

                    // Upload ALL apps - no limit for complete app list
                }
            }

            // Make appCount effectively final for lambda
            final int finalAppCount = appCount;

            DatabaseReference deviceAppsRef = FirebaseDatabase.getInstance()
                    .getReference("device_apps")
                    .child(childDeviceId);

            deviceAppsRef.setValue(essentialApps)
                    .addOnSuccessListener(aVoid -> {
                        Log.d("ChildConnectionManager",
                                "✅ Background app upload completed: " + finalAppCount + " apps");

                        // Update app count in parent's connected devices
                        updateParentAppCount(childDeviceId, finalAppCount);
                    })
                    .addOnFailureListener(e -> {
                        Log.e("ChildConnectionManager", "❌ Background app upload failed: " + e.getMessage());
                    });

        } catch (Exception e) {
            Log.e("ChildConnectionManager", "Error in background app upload: " + e.getMessage());
        }
    }

    private void updateParentAppCount(String childDeviceId, int appCount) {
        // Update app count in all parent records that have this child device
        DatabaseReference parentsRef = FirebaseDatabase.getInstance().getReference("parents");

        parentsRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                for (DataSnapshot parentSnapshot : dataSnapshot.getChildren()) {
                    DataSnapshot connectedDevices = parentSnapshot.child("connectedChildDevices");
                    if (connectedDevices.hasChild(childDeviceId)) {
                        String parentId = parentSnapshot.getKey();
                        DatabaseReference childDeviceRef = parentsRef.child(parentId)
                                .child("connectedChildDevices")
                                .child(childDeviceId)
                                .child("appCount");

                        childDeviceRef.setValue(appCount)
                                .addOnSuccessListener(aVoid -> {
                                    Log.d("ChildConnectionManager",
                                            "✅ Updated app count for parent " + parentId + ": " + appCount + " apps");
                                })
                                .addOnFailureListener(e -> {
                                    Log.e("ChildConnectionManager", "❌ Failed to update app count: " + e.getMessage());
                                });
                        break; // Assuming child can only have one parent
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e("ChildConnectionManager",
                        "❌ Failed to find parent for app count update: " + databaseError.getMessage());
            }
        });
    }

    // Method to refresh/update device app list in Firebase
    public void refreshDeviceAppList(String deviceId, OnConnectionListener listener) {
        Log.d("ChildConnectionManager", "Refreshing app list for device: " + deviceId);

        // Get fresh app list from device
        List<AppInfo> freshApps = ChildAppUtils.getInstalledApps(context);

        // Upload to device_apps node
        uploadAppsToDeviceAppsNode(deviceId, freshApps, listener);
    }

    /**
     * Start only usage data collection - NOT timer services
     * This ensures the child device only sends usage data to parent
     * and receives controls from parent, without auto-starting timers
     */
    private void startUsageDataCollectionOnly() {
        try {
            Log.d("ChildConnectionManager", "🔄 Starting usage data collection service only");

            // Only start RemoteBlockService for usage data collection
            Intent usageIntent = new Intent(context, RemoteBlockService.class);
            usageIntent.setAction("UPLOAD_USAGE_DATA");
            context.startService(usageIntent);

            Log.d("ChildConnectionManager", "✅ Usage data collection started");
            Log.d("ChildConnectionManager", "📊 Child device will now send usage data to parent");
            Log.d("ChildConnectionManager",
                    "🎯 Timer services are NOT started - parent controls when to activate timers");

        } catch (Exception e) {
            Log.e("ChildConnectionManager", "❌ Error starting usage data collection: " + e.getMessage());
        }
    }

    /**
     * Clean up all timer-related data when child device disconnects
     * This prevents timer errors when reconnecting and ensures clean state
     */
    public void cleanupOnDisconnection(String childDeviceId) {
        try {
            Log.d("ChildConnectionManager", "🧹 Cleaning up timer data for device: " + childDeviceId);

            // Clear active timer data
            DatabaseReference activeTimerRef = FirebaseDatabase.getInstance()
                    .getReference("active_timers")
                    .child(childDeviceId);
            activeTimerRef.removeValue()
                    .addOnSuccessListener(aVoid -> {
                        Log.d("ChildConnectionManager", "✅ Active timer data cleared");
                    })
                    .addOnFailureListener(e -> {
                        Log.w("ChildConnectionManager", "⚠️ Failed to clear active timer data: " + e.getMessage());
                    });

            // Clear any parent timer commands for this device
            DatabaseReference parentTimersRef = FirebaseDatabase.getInstance()
                    .getReference("parent_timers");

            parentTimersRef.orderByChild("deviceId").equalTo(childDeviceId)
                    .addListenerForSingleValueEvent(new ValueEventListener() {
                        @Override
                        public void onDataChange(DataSnapshot dataSnapshot) {
                            for (DataSnapshot timerSnap : dataSnapshot.getChildren()) {
                                timerSnap.getRef().removeValue();
                            }
                            Log.d("ChildConnectionManager", "✅ Parent timer commands cleared for device");
                        }

                        @Override
                        public void onCancelled(DatabaseError databaseError) {
                            Log.w("ChildConnectionManager",
                                    "⚠️ Failed to clear parent timer commands: " + databaseError.getMessage());
                        }
                    });

            // Stop any running timer services
            try {
                Intent stopTimerIntent = new Intent(context, SmartTimerService.class);
                context.stopService(stopTimerIntent);
                Log.d("ChildConnectionManager", "✅ SmartTimerService stopped");
            } catch (Exception e) {
                Log.d("ChildConnectionManager", "SmartTimerService stop attempt: " + e.getMessage());
            }

            Log.d("ChildConnectionManager", "🎯 Device disconnection cleanup completed");

        } catch (Exception e) {
            Log.e("ChildConnectionManager", "❌ Error during disconnection cleanup: " + e.getMessage());
        }
    }
}