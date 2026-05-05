package com.example.master2;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Reader;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QRScannerActivity extends AppCompatActivity {
    private static final String TAG = "QRScannerActivity";
    private static final int CAMERA_PERMISSION_CODE = 100;

    // UI Components
    private PreviewView previewView;
    private TextView tvInstructions;
    private TextView tvStatus;
    private Button btnBack;
    private Button btnManualConnect;

    // Camera components
    private ProcessCameraProvider cameraProvider;
    private ExecutorService cameraExecutor;
    private boolean isScanning = true;

    // Connection data
    private String childUserName = "";
    private String childDeviceId;
    private String childDeviceName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scanner);

        initViews();
        setupClickListeners();
        initializeDeviceInfo();

        if (checkCameraPermission()) {
            startCamera();
        } else {
            requestCameraPermission();
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    private void initViews() {
        previewView = findViewById(R.id.previewView);
        tvInstructions = findViewById(R.id.tvInstructions);
        tvStatus = findViewById(R.id.tvStatus);
        btnBack = findViewById(R.id.btnBack);
        btnManualConnect = findViewById(R.id.btnManualConnect);

        tvInstructions.setText("Point your camera at the QR code displayed on the parent device");
        tvStatus.setText("📷 Ready to scan");
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> finish());
        btnManualConnect.setOnClickListener(v -> showManualConnectionDialog());
    }

    private void initializeDeviceInfo() {
        childDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        childDeviceName = ChildAppUtils.getChildDeviceName();

        // Get child's name from SessionManager (set in ChildNameActivity)
        SessionManager sessionManager = new SessionManager(this);
        String savedName = sessionManager.getChildName();
        if (!savedName.isEmpty()) {
            childUserName = savedName;
            Log.d(TAG, "Child name loaded from SessionManager: " + childUserName);
        }
    }

    private boolean checkCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                bindCameraUseCases();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera", e);
                tvStatus.setText("❌ Camera error");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCameraUseCases() {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        imageAnalysis.setAnalyzer(cameraExecutor, new QRCodeAnalyzer());

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            runOnUiThread(() -> tvStatus.setText("📷 Scanning for QR code..."));

        } catch (Exception e) {
            Log.e(TAG, "Error binding camera use cases", e);
            runOnUiThread(() -> tvStatus.setText("❌ Camera binding error"));
        }
    }

    private class QRCodeAnalyzer implements ImageAnalysis.Analyzer {
        @Override
        public void analyze(@NonNull androidx.camera.core.ImageProxy image) {
            if (!isScanning) {
                image.close();
                return;
            }

            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);

                int width = image.getWidth();
                int height = image.getHeight();

                // Convert to RGB
                int[] pixels = new int[width * height];
                for (int i = 0; i < bytes.length; i++) {
                    int gray = bytes[i] & 0xff;
                    pixels[i] = 0xff000000 | (gray << 16) | (gray << 8) | gray;
                }

                LuminanceSource source = new RGBLuminanceSource(width, height, pixels);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                Reader reader = new MultiFormatReader();
                Result result = reader.decode(bitmap);

                String qrText = result.getText();
                if (qrText != null && !qrText.isEmpty()) {
                    isScanning = false;
                    runOnUiThread(() -> handleQRCodeResult(qrText));
                }

            } catch (Exception e) {
                // QR code not found or not readable, continue scanning
            } finally {
                image.close();
            }
        }
    }

    private void handleQRCodeResult(String qrData) {
        Log.d(TAG, "🔍 QR Code detected: " + qrData);
        tvStatus.setText("✅ QR Code detected! Processing...");

        try {
            // Parse QR data: baseQRKey|deviceId|deviceName|sessionId|timestamp
            String[] parts = qrData.split("\\|");
            if (parts.length < 4) {
                showError("Invalid QR code format");
                return;
            }

            String baseQRKey = parts[0];
            String parentDeviceId = parts[1];
            String parentDeviceName = parts[2];
            String sessionId = parts[3];
            long qrTimestamp = parts.length > 4 ? Long.parseLong(parts[4]) : 0;

            Log.d(TAG, "📱 Connecting to parent: " + parentDeviceName + " (Session: " + sessionId + ")");

            // Check if QR is still valid (not older than 3 minutes)
            long currentTime = System.currentTimeMillis();
            if (qrTimestamp > 0 && (currentTime - qrTimestamp) > 180000) {
                showError("QR code has expired. Please scan a fresh QR code from the parent device.");
                return;
            }

            // Prompt for child's name if not set
            if (childUserName.isEmpty()) {
                promptForChildName(baseQRKey, parentDeviceId, parentDeviceName, sessionId);
            } else {
                connectToParent(baseQRKey, parentDeviceId, parentDeviceName, sessionId);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing QR code: " + e.getMessage());
            showError("Error processing QR code: " + e.getMessage());
        }
    }

    private void promptForChildName(String baseQRKey, String parentDeviceId, String parentDeviceName,
            String sessionId) {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("👤 Enter Your Name");
        builder.setMessage("Please enter your name so the parent can identify this device:");

        EditText input = new EditText(this);
        input.setHint("Enter your name");
        builder.setView(input);

        builder.setPositiveButton("Connect", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show();
                return;
            }

            childUserName = name;

            // Save child's name to SessionManager for future use
            SessionManager sessionManager = new SessionManager(this);
            sessionManager.saveChildName(name);

            connectToParent(baseQRKey, parentDeviceId, parentDeviceName, sessionId);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            isScanning = true;
            tvStatus.setText("📷 Scanning for QR code...");
        });

        builder.show();
    }

    private void connectToParent(String baseQRKey, String parentDeviceId, String parentDeviceName, String sessionId) {
        tvStatus.setText("🔄 Connecting to " + parentDeviceName + "...");

        Log.d(TAG, "🔗 Initiating connection to parent device");

        // First, verify the QR session is still active
        DatabaseReference sessionRef = FirebaseDatabase.getInstance()
                .getReference("qr_sessions")
                .child(sessionId);

        sessionRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (!dataSnapshot.exists()) {
                    showError("QR session not found. Please scan a fresh QR code.");
                    return;
                }

                Map<String, Object> sessionData = (Map<String, Object>) dataSnapshot.getValue();
                Boolean isActive = (Boolean) sessionData.get("isActive");
                Long expiresAt = (Long) sessionData.get("expiresAt");

                if (!Boolean.TRUE.equals(isActive)) {
                    showError("QR code has expired. Please scan a fresh QR code.");
                    return;
                }

                if (expiresAt != null && System.currentTimeMillis() > expiresAt) {
                    showError("QR code has expired. Please scan a fresh QR code.");
                    return;
                }

                // Session is valid, proceed with connection
                performConnection(baseQRKey, parentDeviceId, parentDeviceName, sessionId);
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                showError("Failed to verify QR session: " + databaseError.getMessage());
            }
        });
    }

    private void performConnection(String baseQRKey, String parentDeviceId, String parentDeviceName, String sessionId) {
        // Clear any previous connection state first
        clearPreviousConnectionState();

        // Add connection to the QR session
        DatabaseReference connectionRef = FirebaseDatabase.getInstance()
                .getReference("qr_sessions")
                .child(sessionId)
                .child("connections")
                .child(childDeviceId);

        Map<String, Object> connectionData = new HashMap<>();
        connectionData.put("childDeviceId", childDeviceId);
        connectionData.put("childDeviceName", childDeviceName);
        connectionData.put("childUserName", childUserName);
        connectionData.put("connectedAt", System.currentTimeMillis());
        connectionData.put("status", "connected");
        connectionData.put("requiresQRToReconnect", true); // Flag to ensure only QR reconnection

        connectionRef.setValue(connectionData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Connection data added to QR session");

                    // CRITICAL: Add to parent's connected devices IMMEDIATELY
                    addToParentConnectedDevices(parentDeviceId, parentDeviceName);

                    // Also add to the legacy qr_shares structure for backward compatibility
                    addToLegacyQRShares(baseQRKey, parentDeviceId, parentDeviceName);

                    // Add to child device's parent reference
                    addParentReference(parentDeviceId, parentDeviceName);

                    // Set connection flag in local storage
                    setDeviceConnectionState(true, parentDeviceId, parentDeviceName);

                    // Save child session data
                    saveChildSessionData(parentDeviceId, parentDeviceName);

                    showConnectionSuccess(parentDeviceName);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to add connection: " + e.getMessage());
                    showError("Failed to establish connection: " + e.getMessage());
                });
    }

    private void addToLegacyQRShares(String baseQRKey, String parentDeviceId, String parentDeviceName) {
        // Add to legacy qr_shares structure for backward compatibility
        DatabaseReference legacyRef = FirebaseDatabase.getInstance()
                .getReference("qr_shares")
                .child(baseQRKey)
                .child("connected_devices")
                .child(childDeviceId);

        Map<String, Object> deviceInfo = new HashMap<>();
        deviceInfo.put("deviceName", childDeviceName);
        deviceInfo.put("userName", childUserName);
        deviceInfo.put("connectedAt", System.currentTimeMillis());
        deviceInfo.put("status", "online");

        legacyRef.setValue(deviceInfo);
    }

    private void addParentReference(String parentDeviceId, String parentDeviceName) {
        // Add parent reference to child device
        DatabaseReference childRef = FirebaseDatabase.getInstance()
                .getReference("children")
                .child(childDeviceId);

        Map<String, Object> childData = new HashMap<>();
        childData.put("deviceName", childDeviceName);
        childData.put("userName", childUserName);
        childData.put("parentDeviceId", parentDeviceId);
        childData.put("parentDeviceName", parentDeviceName);
        childData.put("connectedAt", System.currentTimeMillis());
        childData.put("status", "online");
        childData.put("isMonitored", true);

        childRef.setValue(childData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Child device data saved");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Failed to save child data: " + e.getMessage());
                });
    }

    private void clearPreviousConnectionState() {
        Log.d(TAG, "🧽 Clearing any previous connection state");

        // Clear local session data to ensure fresh connection
        SessionManager sessionManager = new SessionManager(this);
        if (sessionManager.getUserType().equals("child")) {
            sessionManager.logoutUser();
            Log.d(TAG, "✅ Previous child session cleared");
        }
    }

    private void setDeviceConnectionState(boolean connected, String parentDeviceId, String parentDeviceName) {
        // Save connection state with enhanced data to prevent false disconnections
        getSharedPreferences("device_connection", MODE_PRIVATE)
                .edit()
                .putBoolean("is_connected", connected)
                .putString("parent_device_id", parentDeviceId)
                .putString("parent_device_name", parentDeviceName)
                .putString("connection_method", "qr_scan")
                .putLong("connection_time", System.currentTimeMillis())
                .putBoolean("is_real_connection", true) // Mark as real connection, not emergency
                .putString("connection_status", connected ? "active" : "disconnected")
                .apply();

        Log.d(TAG, "💾 Enhanced device connection state saved: " + connected);
        Log.d(TAG, "   Parent: " + parentDeviceName + " (" + parentDeviceId + ")");
        Log.d(TAG, "   Method: qr_scan, Time: " + System.currentTimeMillis());
    }

    private void showConnectionSuccess(String parentDeviceName) {
        runOnUiThread(() -> {
            tvStatus.setText("🎉 Connected to " + parentDeviceName + "!");
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));

            // Show success dialog
            AlertDialog.Builder builder = new AlertDialog.Builder(
                    new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
            builder.setTitle("🎉 Connection Successful!");
            builder.setMessage("Your device has been successfully connected to " + parentDeviceName +
                    ".\n\nThe parent device can now monitor this device's activities." +
                    "\n\nChild Name: " + childUserName +
                    "\nDevice: " + childDeviceName +
                    "\n\nNote: You will need to scan the QR code again if disconnected.");
            builder.setCancelable(false);

            builder.setPositiveButton("Continue to Dashboard", (dialog, which) -> {
                // Mark connection as established for fresh start handling
                String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                FreshConnectionManager freshManager = new FreshConnectionManager(this, deviceId);
                freshManager.markConnectionEstablished();

                // Navigate to child dashboard or main activity
                Intent intent = new Intent(QRScannerActivity.this, ChildDashboardActivity.class);
                intent.putExtra("parent_device_name", parentDeviceName);
                intent.putExtra("child_name", childUserName);
                intent.putExtra("fresh_connection", true); // Mark as fresh connection
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });

            builder.show();
        });
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            tvStatus.setText("❌ " + message);
            tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));

            Toast.makeText(this, message, Toast.LENGTH_LONG).show();

            // Resume scanning after 3 seconds
            new Handler().postDelayed(() -> {
                isScanning = true;
                tvStatus.setText("📷 Scanning for QR code...");
                tvStatus.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
            }, 3000);
        });
    }

    private void showManualConnectionDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom));
        builder.setTitle("🔗 Manual Connection");
        builder.setMessage("Enter the connection code provided by the parent device:");

        EditText input = new EditText(this);
        input.setHint("Enter connection code");
        builder.setView(input);

        builder.setPositiveButton("Connect", (dialog, which) -> {
            String code = input.getText().toString().trim();
            if (!code.isEmpty()) {
                handleQRCodeResult(code);
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
    }

    private void addToParentConnectedDevices(String parentDeviceId, String parentDeviceName) {
        Log.d(TAG, "🔗 CRITICAL: Adding device to parent's connected devices list");

        DatabaseReference parentDeviceRef = FirebaseDatabase.getInstance()
                .getReference("parents")
                .child(parentDeviceId)
                .child("connectedChildDevices")
                .child(childDeviceId);

        Map<String, Object> childDeviceData = new HashMap<>();
        childDeviceData.put("childDeviceId", childDeviceId);
        childDeviceData.put("childDeviceName", childDeviceName);
        childDeviceData.put("childUserName", childUserName); // Legacy field name
        childDeviceData.put("userName", childUserName); // Matches ChildDevice field
        childDeviceData.put("connectedAt", System.currentTimeMillis());
        childDeviceData.put("status", "online");
        childDeviceData.put("connectionMethod", "qr_scan");
        childDeviceData.put("isMonitored", true);
        childDeviceData.put("parentDeviceId", parentDeviceId);
        childDeviceData.put("parentDeviceName", parentDeviceName);

        parentDeviceRef.setValue(childDeviceData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ CRITICAL SUCCESS: Device added to parent's connected list!");
                    Log.d(TAG, "🎉 Parent will now see this device in their app!");
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ CRITICAL FAILURE: Failed to add to parent's connected devices: " + e.getMessage());
                });
    }

    private void saveChildSessionData(String parentDeviceId, String parentDeviceName) {
        // Save child session using SessionManager
        SessionManager sessionManager = new SessionManager(this);
        sessionManager.saveChildSession(childDeviceId, parentDeviceName, null);

        Log.d(TAG, "💾 Child session data saved via SessionManager");
    }
}
