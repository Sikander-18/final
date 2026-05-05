package com.example.master2.models;

import android.content.Context;
import android.util.Log;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.List;

// Import the missing classes from the main package
import com.example.master2.AppInfo;
import com.example.master2.ChildDevice;
import com.example.master2.DeviceAppList;
import com.example.master2.MultiDeviceQRShare;

public class ChildDeviceManager {
    private static final String TAG = "ChildDeviceManager";
    private DatabaseReference databaseRef;
    private DatabaseReference qrShareRef;

    public interface OnDeviceConnectionListener {
        void onDeviceConnected(ChildDevice device);

        void onDeviceDisconnected(String deviceId);

        void onError(String error);
    }

    public interface OnDevicesLoadedListener {
        void onDevicesLoaded(List<ChildDevice> devices);

        void onError(String error);
    }

    public interface OnAppsLoadedListener {
        void onAppsLoaded(List<AppInfo> apps);

        void onError(String error);
    }

    public ChildDeviceManager(Context context) {
        FirebaseDatabase database = FirebaseDatabase.getInstance();
        qrShareRef = database.getReference("qr_shares");
        databaseRef = database.getReference();
    }

    public void listenForConnections(String shareKey, OnDeviceConnectionListener listener) {
        Log.d(TAG, "Listening for connections with key: " + shareKey);
        qrShareRef.child(shareKey).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                try {
                    if (!dataSnapshot.exists()) {
                        Log.d(TAG, "No share data found for key: " + shareKey);
                        listener.onError("No share data found");
                        return;
                    }

                    // Check for allDeviceAppLists first (new format)
                    if (dataSnapshot.child("allDeviceAppLists").exists()) {
                        for (DataSnapshot listSnapshot : dataSnapshot.child("allDeviceAppLists").getChildren()) {
                            if (listSnapshot.child("deviceId").exists()) {
                                String deviceId = listSnapshot.child("deviceId").getValue(String.class);
                                String deviceName = listSnapshot.child("deviceName").getValue(String.class);

                                List<AppInfo> appsList = new ArrayList<>();
                                if (listSnapshot.child("apps").exists()) {
                                    for (DataSnapshot appSnapshot : listSnapshot.child("apps").getChildren()) {
                                        AppInfo appInfo = appSnapshot.getValue(AppInfo.class);
                                        if (appInfo != null) {
                                            appsList.add(appInfo);
                                        }
                                    }
                                }

                                ChildDevice device = new ChildDevice();
                                device.deviceId = deviceId;
                                device.deviceName = deviceName != null ? deviceName : "Unknown Device";
                                device.appCount = appsList.size();
                                device.lastConnected = listSnapshot.child("timestamp").exists()
                                        ? listSnapshot.child("timestamp").getValue(Long.class)
                                        : System.currentTimeMillis();
                                device.apps = appsList;
                                device.focusModeActive = false;

                                Log.d(TAG, "Device connected (new format): " + device.deviceName + " with "
                                        + appsList.size() + " apps");
                                listener.onDeviceConnected(device);
                            }
                        }
                    }
                    // Check for deviceAppLists (alternate format)
                    else if (dataSnapshot.child("deviceAppLists").exists()) {
                        for (DataSnapshot deviceSnapshot : dataSnapshot.child("deviceAppLists").getChildren()) {
                            String deviceId = deviceSnapshot.getKey();

                            String deviceName = deviceSnapshot.child("deviceName").getValue(String.class);
                            Long timestamp = deviceSnapshot.child("timestamp").getValue(Long.class);

                            List<AppInfo> appsList = new ArrayList<>();
                            if (deviceSnapshot.child("apps").exists()) {
                                for (DataSnapshot appSnapshot : deviceSnapshot.child("apps").getChildren()) {
                                    AppInfo appInfo = appSnapshot.getValue(AppInfo.class);
                                    if (appInfo != null) {
                                        appsList.add(appInfo);
                                    }
                                }
                            }

                            ChildDevice device = new ChildDevice();
                            device.deviceId = deviceId;
                            device.deviceName = deviceName != null ? deviceName : "Unknown Device";
                            device.appCount = appsList.size();
                            device.lastConnected = timestamp != null ? timestamp : System.currentTimeMillis();
                            device.apps = appsList;
                            device.focusModeActive = false;

                            Log.d(TAG, "Device connected: " + device.deviceName + " with " + appsList.size() + " apps");
                            listener.onDeviceConnected(device);
                        }
                    }
                    // Handle legacy format as fallback
                    else {
                        MultiDeviceQRShare multiShare = dataSnapshot.getValue(MultiDeviceQRShare.class);
                        if (multiShare != null && multiShare.deviceAppLists != null) {
                            for (DeviceAppList deviceAppList : multiShare.deviceAppLists.values()) {
                                ChildDevice device = new ChildDevice();
                                device.deviceId = deviceAppList.deviceId;
                                device.deviceName = deviceAppList.deviceName != null ? deviceAppList.deviceName
                                        : "Unknown Device";
                                device.appCount = deviceAppList.apps != null ? deviceAppList.apps.size() : 0;
                                device.lastConnected = deviceAppList.timestamp;
                                device.apps = deviceAppList.apps;
                                device.focusModeActive = false;

                                Log.d(TAG, "Device connected (legacy): " + device.deviceName);
                                listener.onDeviceConnected(device);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing connection data: " + e.getMessage());
                    listener.onError("Error parsing connection data: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Connection listening cancelled: " + databaseError.getMessage());
                listener.onError(databaseError.getMessage());
            }
        });
    }

    public void loadExistingConnections(String shareKey, OnDevicesLoadedListener listener) {
        Log.d(TAG, "Loading existing connections for key: " + shareKey);
        qrShareRef.child(shareKey).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                List<ChildDevice> devices = new ArrayList<>();
                try {
                    if (!dataSnapshot.exists()) {
                        Log.d(TAG, "No share data found for key: " + shareKey);
                        listener.onDevicesLoaded(devices);
                        return;
                    }

                    // Check for allDeviceAppLists first (new format)
                    if (dataSnapshot.child("allDeviceAppLists").exists()) {
                        for (DataSnapshot listSnapshot : dataSnapshot.child("allDeviceAppLists").getChildren()) {
                            if (listSnapshot.child("deviceId").exists()) {
                                String deviceId = listSnapshot.child("deviceId").getValue(String.class);
                                String deviceName = listSnapshot.child("deviceName").getValue(String.class);

                                List<AppInfo> appsList = new ArrayList<>();
                                if (listSnapshot.child("apps").exists()) {
                                    for (DataSnapshot appSnapshot : listSnapshot.child("apps").getChildren()) {
                                        AppInfo appInfo = appSnapshot.getValue(AppInfo.class);
                                        if (appInfo != null) {
                                            appsList.add(appInfo);
                                        }
                                    }
                                }

                                ChildDevice device = new ChildDevice();
                                device.deviceId = deviceId;
                                device.deviceName = deviceName != null ? deviceName : "Unknown Device";
                                device.appCount = appsList.size();
                                device.lastConnected = listSnapshot.child("timestamp").exists()
                                        ? listSnapshot.child("timestamp").getValue(Long.class)
                                        : System.currentTimeMillis();
                                device.apps = appsList;
                                device.focusModeActive = false;

                                Log.d(TAG, "Loaded device (new format): " + device.deviceName + " with "
                                        + appsList.size() + " apps");
                                devices.add(device);
                            }
                        }
                    }
                    // Check for deviceAppLists (alternate format)
                    else if (dataSnapshot.child("deviceAppLists").exists()) {
                        for (DataSnapshot deviceSnapshot : dataSnapshot.child("deviceAppLists").getChildren()) {
                            String deviceId = deviceSnapshot.getKey();

                            String deviceName = deviceSnapshot.child("deviceName").getValue(String.class);
                            Long timestamp = deviceSnapshot.child("timestamp").getValue(Long.class);

                            List<AppInfo> appsList = new ArrayList<>();
                            if (deviceSnapshot.child("apps").exists()) {
                                for (DataSnapshot appSnapshot : deviceSnapshot.child("apps").getChildren()) {
                                    AppInfo appInfo = appSnapshot.getValue(AppInfo.class);
                                    if (appInfo != null) {
                                        appsList.add(appInfo);
                                    }
                                }
                            }

                            ChildDevice device = new ChildDevice();
                            device.deviceId = deviceId;
                            device.deviceName = deviceName != null ? deviceName : "Unknown Device";
                            device.appCount = appsList.size();
                            device.lastConnected = timestamp != null ? timestamp : System.currentTimeMillis();
                            device.apps = appsList;
                            device.focusModeActive = false;

                            Log.d(TAG, "Loaded device: " + device.deviceName + " with " + appsList.size() + " apps");
                            devices.add(device);
                        }
                    }
                    // Handle legacy format as fallback
                    else {
                        MultiDeviceQRShare multiShare = dataSnapshot.getValue(MultiDeviceQRShare.class);
                        if (multiShare != null && multiShare.deviceAppLists != null) {
                            for (DeviceAppList deviceAppList : multiShare.deviceAppLists.values()) {
                                ChildDevice device = new ChildDevice();
                                device.deviceId = deviceAppList.deviceId;
                                device.deviceName = deviceAppList.deviceName != null ? deviceAppList.deviceName
                                        : "Unknown Device";
                                device.appCount = deviceAppList.apps != null ? deviceAppList.apps.size() : 0;
                                device.lastConnected = deviceAppList.timestamp;
                                device.apps = deviceAppList.apps;
                                device.focusModeActive = false;

                                Log.d(TAG, "Loaded device (legacy): " + device.deviceName);
                                devices.add(device);
                            }
                        }
                    }

                    Log.d(TAG, "Total devices loaded: " + devices.size());
                    listener.onDevicesLoaded(devices);
                } catch (Exception e) {
                    Log.e(TAG, "Error loading existing connections: " + e.getMessage());
                    listener.onError("Error loading existing connections: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Loading existing connections cancelled: " + databaseError.getMessage());
                listener.onError(databaseError.getMessage());
            }
        });
    }

    public void getDeviceApps(String deviceId, OnAppsLoadedListener listener) {
        Log.d(TAG, "Getting apps for device: " + deviceId);
        getDeviceAppsFromShare(deviceId, listener);
    }

    private void getDeviceAppsFromShare(String deviceId, OnAppsLoadedListener listener) {
        qrShareRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                List<AppInfo> apps = new ArrayList<>();
                try {
                    boolean found = false;

                    for (DataSnapshot shareSnapshot : dataSnapshot.getChildren()) {
                        if (shareSnapshot.child("allDeviceAppLists").exists()) {
                            for (DataSnapshot listSnapshot : shareSnapshot.child("allDeviceAppLists").getChildren()) {
                                String currentDeviceId = listSnapshot.child("deviceId").getValue(String.class);
                                if (deviceId.equals(currentDeviceId)) {
                                    if (listSnapshot.child("apps").exists()) {
                                        for (DataSnapshot appSnapshot : listSnapshot.child("apps").getChildren()) {
                                            AppInfo appInfo = appSnapshot.getValue(AppInfo.class);
                                            if (appInfo != null) {
                                                apps.add(appInfo);
                                            }
                                        }
                                    }
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (found)
                            break;
                    }

                    Log.d(TAG, "Found " + apps.size() + " apps for device " + deviceId);
                    listener.onAppsLoaded(apps);
                } catch (Exception e) {
                    Log.e(TAG, "Error getting device apps: " + e.getMessage());
                    listener.onError("Error getting device apps: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Getting device apps cancelled: " + databaseError.getMessage());
                listener.onError(databaseError.getMessage());
            }
        });
    }

    public void getDeviceAppsFromShareKey(String shareKey, String deviceId, OnAppsLoadedListener listener) {
        Log.d(TAG, "Getting apps for device: " + deviceId + " from share key: " + shareKey);
        qrShareRef.child(shareKey).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                List<AppInfo> apps = new ArrayList<>();
                try {
                    if (!dataSnapshot.exists()) {
                        Log.d(TAG, "No share data found for key: " + shareKey);
                        listener.onAppsLoaded(apps);
                        return;
                    }

                    boolean found = false;

                    // Check allDeviceAppLists first
                    if (dataSnapshot.child("allDeviceAppLists").exists()) {
                        for (DataSnapshot listSnapshot : dataSnapshot.child("allDeviceAppLists").getChildren()) {
                            String currentDeviceId = listSnapshot.child("deviceId").getValue(String.class);
                            if (deviceId.equals(currentDeviceId)) {
                                if (listSnapshot.child("apps").exists()) {
                                    for (DataSnapshot appSnapshot : listSnapshot.child("apps").getChildren()) {
                                        AppInfo appInfo = appSnapshot.getValue(AppInfo.class);
                                        if (appInfo != null) {
                                            apps.add(appInfo);
                                        }
                                    }
                                }
                                found = true;
                                break;
                            }
                        }
                    }

                    // If not found, check deviceAppLists
                    if (!found && dataSnapshot.child("deviceAppLists").exists()) {
                        DataSnapshot deviceSnapshot = dataSnapshot.child("deviceAppLists").child(deviceId);
                        if (deviceSnapshot.exists() && deviceSnapshot.child("apps").exists()) {
                            for (DataSnapshot appSnapshot : deviceSnapshot.child("apps").getChildren()) {
                                AppInfo appInfo = appSnapshot.getValue(AppInfo.class);
                                if (appInfo != null) {
                                    apps.add(appInfo);
                                }
                            }
                            found = true;
                        }
                    }

                    // If still not found, check legacy format
                    if (!found) {
                        MultiDeviceQRShare multiShare = dataSnapshot.getValue(MultiDeviceQRShare.class);
                        if (multiShare != null && multiShare.deviceAppLists != null) {
                            DeviceAppList deviceAppList = multiShare.deviceAppLists.get(deviceId);
                            if (deviceAppList != null && deviceAppList.apps != null) {
                                apps.addAll(deviceAppList.apps);
                            }
                        }
                    }

                    Log.d(TAG, "Found " + apps.size() + " apps for device " + deviceId);
                    listener.onAppsLoaded(apps);
                } catch (Exception e) {
                    Log.e(TAG, "Error getting device apps from share key: " + e.getMessage());
                    listener.onError("Error getting device apps from share key: " + e.getMessage());
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Getting device apps from share key cancelled: " + databaseError.getMessage());
                listener.onError(databaseError.getMessage());
            }
        });
    }
}