package com.example.master2;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Parent email/password login activity.
 * Authenticates existing parents via Firebase Auth.
 */
public class ParentEmailLoginActivity extends AppCompatActivity {
    private static final String TAG = "ParentEmailLogin";

    private EditText etEmail, etPassword;
    private Button btnLogin;
    private TextView tvSignupLink;
    private TextView tvForgotPassword;

    private FirebaseAuth mAuth;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_email_login);

        mAuth = FirebaseAuth.getInstance();
        sessionManager = new SessionManager(this);

        initViews();
        setupListeners();
    }

    private void initViews() {
        etEmail = findViewById(R.id.etEmail);
        etPassword = findViewById(R.id.etPassword);
        btnLogin = findViewById(R.id.btnLogin);
        tvSignupLink = findViewById(R.id.tvSignupLink);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
    }

    private void setupListeners() {
        btnLogin.setOnClickListener(v -> attemptLogin());

        tvSignupLink.setOnClickListener(v -> {
            startActivity(new Intent(this, ParentSignupActivity.class));
            finish();
        });

        tvForgotPassword.setOnClickListener(v -> {
            startActivity(new Intent(this, ForgotPasswordActivity.class));
        });
    }

    private void attemptLogin() {
        String email = etEmail.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        // Validate
        if (TextUtils.isEmpty(email)) {
            etEmail.setError("Email is required");
            etEmail.requestFocus();
            return;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Please enter a valid email");
            etEmail.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password)) {
            etPassword.setError("Password is required");
            etPassword.requestFocus();
            return;
        }

        if (password.length() < 6) {
            etPassword.setError("Password must be at least 6 characters");
            etPassword.requestFocus();
            return;
        }

        // Login with Firebase Auth
        setLoading(true);

        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "Firebase Auth successful");
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            // Check for existing device session before proceeding
                            checkDeviceSession(user.getUid());
                        }
                    } else {
                        setLoading(false);
                        Log.e(TAG, "Login failed", task.getException());
                        String errorMsg = "Login failed";
                        if (task.getException() != null) {
                            String msg = task.getException().getMessage();
                            if (msg != null && msg.contains("password")) {
                                errorMsg = "Incorrect password";
                            } else if (msg != null && msg.contains("no user")) {
                                errorMsg = "No account found. Please sign up.";
                            } else {
                                errorMsg = msg;
                            }
                        }
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void loadParentDataAndProceed(String uid) {
        DatabaseReference parentRef = FirebaseDatabase.getInstance()
                .getReference("parent_accounts")
                .child(uid);

        // 🔧 Add timeout to prevent infinite loading
        final boolean[] dataLoaded = { false };
        Handler timeoutHandler = new Handler(Looper.getMainLooper());
        Runnable timeoutRunnable = () -> {
            if (!dataLoaded[0]) {
                setLoading(false);
                Log.e(TAG, "Database read timeout after 15 seconds");
                Toast.makeText(this, "Connection timeout. Please check your internet and try again.", Toast.LENGTH_LONG)
                        .show();
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, 15000); // 15 second timeout

        parentRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                dataLoaded[0] = true;
                timeoutHandler.removeCallbacks(timeoutRunnable);

                if (snapshot.exists()) {
                    String name = snapshot.child("name").getValue(String.class);
                    String phone = snapshot.child("phone").getValue(String.class);

                    // Save session
                    sessionManager.saveParentSession(phone, uid, ParentUtils.getParentDeviceName(),
                            name != null ? name : "Parent");

                    // Save active device to Firebase to track this login session
                    saveActiveDeviceToFirebase(uid);

                    setLoading(false);

                    Toast.makeText(ParentEmailLoginActivity.this,
                            "Welcome back, " + (name != null ? name : "Parent") + "!",
                            Toast.LENGTH_SHORT).show();

                    // Go to Parent Dashboard
                    Intent intent = new Intent(ParentEmailLoginActivity.this, ParentDashboardActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                } else {
                    // Profile was deleted but Auth still exists - recreate profile
                    Log.d(TAG, "Profile missing, recreating from Auth data...");
                    FirebaseUser user = mAuth.getCurrentUser();
                    if (user != null && user.getEmail() != null) {
                        recreateParentProfile(uid, user.getEmail());
                    } else {
                        setLoading(false);
                        Toast.makeText(ParentEmailLoginActivity.this,
                                "Unable to restore profile. Please sign up again.", Toast.LENGTH_LONG).show();
                    }
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                dataLoaded[0] = true;
                timeoutHandler.removeCallbacks(timeoutRunnable);
                setLoading(false);
                Log.e(TAG, "Database error: " + error.getMessage());
                Toast.makeText(ParentEmailLoginActivity.this,
                        "Error loading profile", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void recreateParentProfile(String uid, String email) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        java.util.Map<String, Object> parentData = new java.util.HashMap<>();
        parentData.put("name", "Parent"); // Default name
        parentData.put("email", email);
        parentData.put("phone", "");
        parentData.put("deviceId", deviceId);
        parentData.put("createdAt", System.currentTimeMillis());
        parentData.put("userType", "parent");
        parentData.put("profileRecreated", true); // Flag that profile was auto-recovered

        DatabaseReference parentRef = FirebaseDatabase.getInstance()
                .getReference("parent_accounts")
                .child(uid);

        // 🔧 CRITICAL: Also create users node for child compatibility
        DatabaseReference usersRef = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(uid);

        parentRef.setValue(parentData)
                .addOnSuccessListener(aVoid -> {
                    // Also save to users node
                    usersRef.setValue(parentData)
                            .addOnSuccessListener(v -> {
                                Log.d(TAG, "Profile recreated successfully in both nodes");

                                // Save session
                                sessionManager.saveParentSession("", uid, ParentUtils.getParentDeviceName(), "Parent");

                                // Save active device to Firebase
                                saveActiveDeviceToFirebase(uid);

                                setLoading(false);

                                Toast.makeText(this, "Profile recovered! Welcome back!", Toast.LENGTH_SHORT).show();

                                // Go to Parent Dashboard
                                Intent intent = new Intent(this, ParentDashboardActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            })
                            .addOnFailureListener(e -> {
                                // Continue even if users node fails
                                setLoading(false);
                                Log.w(TAG, "Profile recreated but users node failed: " + e.getMessage());

                                sessionManager.saveParentSession("", uid, ParentUtils.getParentDeviceName(), "Parent");
                                Toast.makeText(this, "Profile recovered!", Toast.LENGTH_SHORT).show();

                                Intent intent = new Intent(this, ParentDashboardActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            });
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Log.e(TAG, "Failed to recreate profile: " + e.getMessage());
                    Toast.makeText(this, "Failed to restore profile. Try signing up again.", Toast.LENGTH_LONG).show();
                });
    }

    /**
     * Check if another device is already logged in with this account
     */
    private void checkDeviceSession(String uid) {
        DatabaseReference activeDeviceRef = FirebaseDatabase.getInstance()
                .getReference("parent_accounts")
                .child(uid)
                .child("activeDevice");

        String currentDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String currentDeviceModel = Build.MODEL;

        activeDeviceRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    // Active device session found
                    String loggedInDeviceId = snapshot.child("deviceId").getValue(String.class);
                    String loggedInDeviceModel = snapshot.child("deviceModel").getValue(String.class);
                    Long lastHeartbeat = snapshot.child("lastHeartbeat").getValue(Long.class);

                    if (loggedInDeviceId != null && !loggedInDeviceId.equals(currentDeviceId)) {
                        // Different device - check if it's still alive via heartbeat
                        long currentTime = System.currentTimeMillis();
                        long STALE_THRESHOLD = 90 * 1000; // 90 seconds - if no heartbeat, device is dead

                        boolean isDeviceAlive = lastHeartbeat != null &&
                                (currentTime - lastHeartbeat) < STALE_THRESHOLD;

                        if (!isDeviceAlive) {
                            // Device is DEAD (app deleted/crashed) - auto-clear and allow login
                            Log.d(TAG, "🔓 Old device heartbeat STALE - app was deleted/uninstalled");
                            Log.d(TAG,
                                    "🔓 Last heartbeat: "
                                            + (lastHeartbeat != null ? ((currentTime - lastHeartbeat) / 1000) + "s ago"
                                                    : "never"));
                            Log.d(TAG, "🔓 Auto-clearing stale session and proceeding with login...");

                            // Clear the stale session
                            activeDeviceRef.removeValue();

                            // Proceed with login
                            loadParentDataAndProceed(uid);
                        } else {
                            // Device is ALIVE - block login
                            setLoading(false);
                            Log.d(TAG, "⛔ Login blocked: Device is still active (heartbeat fresh)");
                            Log.d(TAG, "⛔ Last heartbeat: " + ((currentTime - lastHeartbeat) / 1000) + "s ago");
                            Log.d(TAG, "Current device: " + currentDeviceModel + " (" + currentDeviceId + ")");
                            Log.d(TAG, "Logged in device: " + loggedInDeviceModel + " (" + loggedInDeviceId + ")");

                            // Sign out from Firebase Auth since we're blocking the login
                            mAuth.signOut();

                            // Show blocking dialog
                            showDeviceBlockedDialog(
                                    loggedInDeviceModel != null ? loggedInDeviceModel : "Unknown Device");
                        }
                    } else {
                        // Same device or no conflict - proceed with login
                        Log.d(TAG, "Device session check passed - same device or first login");
                        loadParentDataAndProceed(uid);
                    }
                } else {
                    // No active device session - proceed with login (first time or after logout)
                    Log.d(TAG, "No active device session found - proceeding with login");
                    loadParentDataAndProceed(uid);
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                setLoading(false);
                Log.e(TAG, "Device session check failed: " + error.getMessage());
                Toast.makeText(ParentEmailLoginActivity.this,
                        "Error checking device session", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Save active device information to Firebase to track this login session
     */
    private void saveActiveDeviceToFirebase(String uid) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String deviceModel = Build.MODEL;

        DatabaseReference activeDeviceRef = FirebaseDatabase.getInstance()
                .getReference("parent_accounts")
                .child(uid)
                .child("activeDevice");

        java.util.Map<String, Object> deviceData = new java.util.HashMap<>();
        deviceData.put("deviceId", deviceId);
        deviceData.put("deviceModel", deviceModel);
        deviceData.put("loginTimestamp", System.currentTimeMillis());
        deviceData.put("lastHeartbeat", System.currentTimeMillis()); // For detecting if app is deleted

        activeDeviceRef.setValue(deviceData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Active device saved to Firebase: " + deviceModel);
                    // Also save to local session
                    sessionManager.saveDeviceModel(deviceModel);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to save active device: " + e.getMessage());
                    // Don't block login on failure, just log it
                });
    }

    /**
     * Show floating dialog when login is blocked due to another device being logged
     * in
     */
    private void showDeviceBlockedDialog(String deviceModel) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_device_already_logged_in, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        AlertDialog dialog = builder.create();

        // Set device model text
        TextView tvDeviceModel = dialogView.findViewById(R.id.tvDeviceModel);
        tvDeviceModel.setText(deviceModel);

        // OK button to dismiss
        Button btnOk = dialogView.findViewById(R.id.btnOk);
        btnOk.setOnClickListener(v -> dialog.dismiss());

        // Make dialog background transparent for floating effect
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        dialog.show();
    }

    private void setLoading(boolean loading) {
        btnLogin.setEnabled(!loading);
        btnLogin.setText(loading ? "Logging in..." : "Login");
        etEmail.setEnabled(!loading);
        etPassword.setEnabled(!loading);
    }
}
