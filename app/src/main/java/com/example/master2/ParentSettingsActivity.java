package com.example.master2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;

import com.example.master2.utils.LoadingDialogManager;
import com.example.master2.utils.InfoContentRepository;
import com.google.android.material.button.MaterialButton;
import com.google.firebase.auth.FirebaseAuth;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class ParentSettingsActivity extends AppCompatActivity {

    private SessionManager sessionManager;
    private ConnectedDevicesManager connectedDevicesManager;
    private LoadingDialogManager loadingDialogManager;
    private FirebaseAuth mAuth;

    private TextView tvParentName;
    private TextView tvParentEmail;
    private TextView tvParentPhone;
    private TextView tvAccountDate;

    // 🛡️ Uninstall Protection Toggle
    private SwitchCompat switchUninstallProtection;
    private TextView tvUninstallProtectionStatus;
    private static final String PREF_UNINSTALL_PROTECTION = "uninstall_protection_prefs";
    private static final String KEY_PROTECTION_ENABLED = "protection_enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_settings);

        // Initialize managers
        sessionManager = new SessionManager(this);
        connectedDevicesManager = new ConnectedDevicesManager(this);
        loadingDialogManager = new LoadingDialogManager(this);
        mAuth = FirebaseAuth.getInstance();

        initializeViews();
        setupToolbar();
        loadProfileData();
        setupClickListeners();
        setupBottomNavigation();
    }

    private void initializeViews() {
        tvParentName = findViewById(R.id.tvParentName);
        tvParentEmail = findViewById(R.id.tvParentEmail);
        tvParentPhone = findViewById(R.id.tvParentPhone);
        tvAccountDate = findViewById(R.id.tvAccountDate);

        // 🛡️ Uninstall Protection Toggle
        switchUninstallProtection = findViewById(R.id.switchUninstallProtection);
        tvUninstallProtectionStatus = findViewById(R.id.tvUninstallProtectionStatus);
        setupUninstallProtectionToggle();
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowTitleEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void loadProfileData() {
        // 1. Get User from Firebase (Source of Truth)
        if (mAuth.getCurrentUser() != null) {
            String email = mAuth.getCurrentUser().getEmail();
            String displayName = mAuth.getCurrentUser().getDisplayName();
            String phoneNumber = mAuth.getCurrentUser().getPhoneNumber(); // Might be null if email login

            // Fallback to SessionManager if Firebase phone is null (common in email auth)
            if (phoneNumber == null || phoneNumber.isEmpty()) {
                phoneNumber = sessionManager.getPhoneNumber();
            }

            // Set Name - Load from Firebase Database
            String uid = mAuth.getCurrentUser().getUid();
            final String fallbackEmail = email;

            // Set temporary while loading
            tvParentName.setText("Loading...");

            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("parent_accounts")
                    .child(uid)
                    .child("name")
                    .get()
                    .addOnSuccessListener(snapshot -> {
                        String name = snapshot.getValue(String.class);
                        if (name != null && !name.trim().isEmpty()) {
                            tvParentName.setText(name);
                        } else if (displayName != null && !displayName.isEmpty()) {
                            tvParentName.setText(displayName);
                        } else {
                            // Extract from email as fallback
                            String extractedName = null;
                            if (fallbackEmail != null) {
                                try {
                                    String namePart = fallbackEmail.split("@")[0].replaceAll("\\d+", "")
                                            .replaceAll("[^a-zA-Z]", "");
                                    if (!namePart.isEmpty()) {
                                        extractedName = namePart.substring(0, 1).toUpperCase() + namePart.substring(1);
                                    }
                                } catch (Exception e) {
                                    // Ignore
                                }
                            }
                            tvParentName.setText(extractedName != null ? extractedName : "Parent Account");
                        }
                    })
                    .addOnFailureListener(e -> {
                        tvParentName.setText("Parent Account");
                    });

            // Set Email
            if (email != null && !email.isEmpty()) {
                tvParentEmail.setText(email);
            } else {
                tvParentEmail.setText("No Email Linked");
            }

            // Set Phone (Formatted)
            if (phoneNumber != null && !phoneNumber.isEmpty()) {
                tvParentPhone.setText(formatPhoneNumber(phoneNumber));
            } else {
                tvParentPhone.setText("No Phone Linked");
            }

            // Load account creation date
            long creationTimestamp = mAuth.getCurrentUser().getMetadata().getCreationTimestamp();
            if (creationTimestamp > 0) {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.getDefault());
                String dateStr = sdf.format(new Date(creationTimestamp));
                if (tvAccountDate != null) {
                    tvAccountDate.setText("Joined: " + dateStr);
                    tvAccountDate.setVisibility(View.VISIBLE);
                }
            }
        } else {
            // Fallback if auth is weirdly null but session exists
            tvParentName.setText("Parent");
            tvParentEmail.setText(sessionManager.getUserId()); // Shows UID unfortunately if we are here
            tvParentPhone.setText(sessionManager.getPhoneNumber());
        }
    }

    private String formatPhoneNumber(String phone) {
        if (phone == null)
            return "";
        // Simple formatting: +919876543210 -> +91 98765 43210
        // Assumes 10 digit + country code usually
        if (phone.length() > 10) {
            // Try to add spaces? keeping it simple for now to avoid breaking irregular
            // numbers
            return phone.replaceAll("(\\d{2})(\\d{5})(\\d{5})", "$1 $2 $3"); // Example for +91...
        }
        return phone;
    }

    private void setupClickListeners() {
        // Disconnect All Devices
        findViewById(R.id.btnDisconnectAll).setOnClickListener(v -> showDisconnectAllConfirmation());

        // Terms of Service
        findViewById(R.id.btnTerms).setOnClickListener(v -> openInfoPage(InfoContentRepository.KEY_TERMS));

        // Privacy Policy
        findViewById(R.id.btnPrivacy).setOnClickListener(v -> openInfoPage(InfoContentRepository.KEY_PRIVACY));

        // Help & Support
        findViewById(R.id.btnHelpSupport).setOnClickListener(v -> openInfoPage(InfoContentRepository.KEY_HELP));

        // Logout
        MaterialButton btnLogout = findViewById(R.id.btnLogout);
        if (btnLogout != null) {
            btnLogout.setOnClickListener(v -> showLogoutConfirmation());
        }
    }

    private void openInfoPage(String contentKey) {
        Intent intent = new Intent(this, InfoDetailActivity.class);
        intent.putExtra(InfoDetailActivity.EXTRA_CONTENT_KEY, contentKey);
        startActivity(intent);
    }

    private void showDisconnectAllConfirmation() {
        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                .setTitle("Disconnect All Devices?")
                .setMessage(
                        "Are you sure you want to disconnect ALL child devices?\n\nThis action cannot be undone. You will need to reconnect them via QR code.")
                .setPositiveButton("Disconnect All", (dialog, which) -> {
                    performDisconnectAll();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performDisconnectAll() {
        loadingDialogManager.show("Disconnecting...", "Removing all devices");

        // Clear devices
        connectedDevicesManager.clearAllDevices();

        // Simulate a short delay for UX
        new android.os.Handler().postDelayed(() -> {
            loadingDialogManager.hide();

            // Update protection toggle state since devices are gone
            updateProtectionAvailability();

            Toast.makeText(this, "All devices disconnected", Toast.LENGTH_SHORT).show();
            // Optionally finish or refresh UI?
            // Since this is just settings, we stay here.
            // The dashboard will refresh when we return because onResume will check
            // devices.
        }, 1500);
    }

    private void showLogoutConfirmation() {
        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                .setTitle("Confirm Logout")
                .setMessage(
                        "Are you sure you want to logout?\n\nThis will sign you out and you will need to login again.")
                .setPositiveButton("Logout", (dialog, which) -> {
                    performLogout();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performLogout() {
        loadingDialogManager.show("Logging Out", "Please wait...");

        // Clear active device from Firebase before logging out
        if (mAuth != null && mAuth.getCurrentUser() != null) {
            String uid = mAuth.getCurrentUser().getUid();
            com.google.firebase.database.FirebaseDatabase.getInstance()
                    .getReference("parent_accounts")
                    .child(uid)
                    .child("activeDevice")
                    .removeValue()
                    .addOnSuccessListener(aVoid -> {
                        android.util.Log.d("ParentSettings", "Active device cleared from Firebase");
                    })
                    .addOnFailureListener(e -> {
                        android.util.Log.e("ParentSettings", "Failed to clear active device: " + e.getMessage());
                    });
        }

        // Logout logic
        sessionManager.logoutUser();
        if (mAuth != null) {
            mAuth.signOut();
        }
        connectedDevicesManager.clearAllDevices();

        new android.os.Handler().postDelayed(() -> {
            loadingDialogManager.hide();

            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }, 1000);
    }

    private void setupBottomNavigation() {
        com.google.android.material.bottomnavigation.BottomNavigationView bottomNav = findViewById(
                R.id.bottomNavigation);
        if (bottomNav != null) {
            // Set Settings as selected
            bottomNav.setSelectedItemId(R.id.nav_settings);

            bottomNav.setOnItemSelectedListener(item -> {
                int itemId = item.getItemId();
                if (itemId == R.id.nav_home) {
                    // Go back to dashboard
                    finish();
                    return true;
                } else if (itemId == R.id.nav_settings) {
                    // Already on settings
                    return true;
                }
                return false;
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (loadingDialogManager != null) {
            loadingDialogManager.cleanup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 🛡️ Refresh protection toggle state when returning to settings
        // This ensures UI reflects any changes made elsewhere
        setupUninstallProtectionToggle();
    }

    /**
     * 🛡️ Setup Uninstall Protection Toggle with confirmation dialog
     */
    private void setupUninstallProtectionToggle() {
        if (switchUninstallProtection == null)
            return;

        // Disable switch until we load the actual state from Firebase
        switchUninstallProtection.setEnabled(false);

        // Check for connected devices first
        java.util.List<ChildDevice> devices = connectedDevicesManager.getConnectedDevices();

        if (devices == null || devices.isEmpty()) {
            // No devices connected - show disabled state
            updateProtectionAvailability();
            return;
        }

        // 🔥 Load ACTUAL state from Firebase (source of truth for child devices)
        String firstDeviceId = devices.get(0).deviceId;
        loadProtectionStateFromFirebase(firstDeviceId);

        // Setup listener with confirmation dialog
        switchUninstallProtection.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) {
                // User is trying to DISABLE - show confirmation
                showDisableProtectionConfirmation(buttonView);
            } else {
                // User is ENABLING - allow immediately
                saveProtectionState(true);
                updateProtectionStatus(true);
                Toast.makeText(this, "✅ Uninstall protection enabled", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 🔥 Load protection state from Firebase for accurate UI display
     * Firebase is the source of truth since that's what child devices read
     */
    private void loadProtectionStateFromFirebase(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            android.util.Log.w("ParentSettings", "Cannot load protection state - no device ID");
            updateProtectionAvailability();
            return;
        }

        android.util.Log.d("ParentSettings", "🔍 Loading protection state from Firebase for device: " + deviceId);

        com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("child_devices")
                .child(deviceId)
                .child("uninstall_protection")
                .addListenerForSingleValueEvent(new com.google.firebase.database.ValueEventListener() {
                    @Override
                    public void onDataChange(com.google.firebase.database.DataSnapshot snapshot) {
                        boolean isEnabled = true; // Default to enabled

                        if (snapshot.exists()) {
                            Boolean value = snapshot.getValue(Boolean.class);
                            if (value != null) {
                                isEnabled = value;
                            }
                        }

                        android.util.Log.d("ParentSettings", "🛡️ Firebase protection state: " + isEnabled);

                        // Update local SharedPreferences to match Firebase
                        SharedPreferences prefs = getSharedPreferences(PREF_UNINSTALL_PROTECTION, MODE_PRIVATE);
                        prefs.edit().putBoolean(KEY_PROTECTION_ENABLED, isEnabled).apply();

                        // Update UI on main thread
                        final boolean finalIsEnabled = isEnabled;
                        runOnUiThread(() -> {
                            // Set toggle state without triggering listener
                            switchUninstallProtection.setOnCheckedChangeListener(null);
                            switchUninstallProtection.setChecked(finalIsEnabled);
                            switchUninstallProtection.setEnabled(true);

                            // Update status text
                            updateProtectionStatus(finalIsEnabled);

                            // Re-attach listener
                            switchUninstallProtection.setOnCheckedChangeListener((buttonView, isChecked) -> {
                                if (!isChecked) {
                                    showDisableProtectionConfirmation(buttonView);
                                } else {
                                    saveProtectionState(true);
                                    updateProtectionStatus(true);
                                    Toast.makeText(ParentSettingsActivity.this, "✅ Uninstall protection enabled",
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    }

                    @Override
                    public void onCancelled(com.google.firebase.database.DatabaseError error) {
                        android.util.Log.e("ParentSettings",
                                "❌ Failed to load protection state: " + error.getMessage());
                        // Fall back to local state
                        runOnUiThread(() -> {
                            SharedPreferences prefs = getSharedPreferences(PREF_UNINSTALL_PROTECTION, MODE_PRIVATE);
                            boolean isEnabled = prefs.getBoolean(KEY_PROTECTION_ENABLED, true);
                            switchUninstallProtection.setOnCheckedChangeListener(null);
                            switchUninstallProtection.setChecked(isEnabled);
                            switchUninstallProtection.setEnabled(true);
                            updateProtectionStatus(isEnabled);

                            // Re-attach listener
                            switchUninstallProtection.setOnCheckedChangeListener((buttonView, isChecked) -> {
                                if (!isChecked) {
                                    showDisableProtectionConfirmation(buttonView);
                                } else {
                                    saveProtectionState(true);
                                    updateProtectionStatus(true);
                                    Toast.makeText(ParentSettingsActivity.this, "✅ Uninstall protection enabled",
                                            Toast.LENGTH_SHORT).show();
                                }
                            });
                        });
                    }
                });
    }

    /**
     * Show confirmation dialog before disabling protection
     */
    private void showDisableProtectionConfirmation(CompoundButton buttonView) {
        new AlertDialog.Builder(new android.view.ContextThemeWrapper(this, R.style.AlertDialogCustom))
                .setTitle("⚠️ Disable Protection?")
                .setMessage("Are you sure you want to disable uninstall protection?\n\n" +
                        "This will allow the child to uninstall the app from Device Admin settings.")
                .setPositiveButton("Yes, Disable", (dialog, which) -> {
                    // User confirmed - disable protection
                    saveProtectionState(false);
                    updateProtectionStatus(false);
                    Toast.makeText(this, "⚠️ Uninstall protection disabled", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    // User cancelled - revert switch to ON
                    switchUninstallProtection.setOnCheckedChangeListener(null);
                    switchUninstallProtection.setChecked(true);
                    setupUninstallProtectionToggle(); // Re-setup listener
                })
                .setOnCancelListener(dialog -> {
                    // User pressed back - revert switch to ON
                    switchUninstallProtection.setOnCheckedChangeListener(null);
                    switchUninstallProtection.setChecked(true);
                    setupUninstallProtectionToggle(); // Re-setup listener
                })
                .setCancelable(true)
                .show();
    }

    /**
     * Save protection state to SharedPreferences AND sync to Firebase for all
     * connected children
     */
    private void saveProtectionState(boolean enabled) {
        // Save locally
        SharedPreferences prefs = getSharedPreferences(PREF_UNINSTALL_PROTECTION, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_PROTECTION_ENABLED, enabled).apply();

        // 🔄 Sync to Firebase for ALL connected child devices
        syncProtectionStateToFirebase(enabled);
    }

    /**
     * 🔄 Sync protection state to Firebase for all connected child devices
     */
    private void syncProtectionStateToFirebase(boolean enabled) {
        if (mAuth.getCurrentUser() == null)
            return;

        // Get all connected child devices
        java.util.List<ChildDevice> devices = connectedDevicesManager.getConnectedDevices();

        if (devices == null || devices.isEmpty()) {
            android.util.Log.d("ParentSettings", "No connected devices to sync protection state");
            return;
        }

        for (ChildDevice device : devices) {
            try {
                final String deviceId = device.deviceId;

                if (deviceId != null && !deviceId.isEmpty()) {
                    // Push protection state to child device in Firebase
                    com.google.firebase.database.FirebaseDatabase.getInstance()
                            .getReference("child_devices")
                            .child(deviceId)
                            .child("uninstall_protection")
                            .setValue(enabled)
                            .addOnSuccessListener(aVoid -> {
                                android.util.Log.d("ParentSettings",
                                        "✅ Protection state synced to device " + deviceId + ": " + enabled);
                            })
                            .addOnFailureListener(e -> {
                                android.util.Log.e("ParentSettings",
                                        "❌ Failed to sync protection state: " + e.getMessage());
                            });
                }
            } catch (Exception e) {
                android.util.Log.e("ParentSettings", "Error syncing protection state: " + e.getMessage());
            }
        }
    }

    /**
     * Update status text based on protection state
     */
    private void updateProtectionStatus(boolean enabled) {
        if (tvUninstallProtectionStatus != null) {
            // Check if we even have devices first
            if (connectedDevicesManager.getDeviceCount() == 0) {
                tvUninstallProtectionStatus.setText("No child devices connected");
                tvUninstallProtectionStatus.setTextColor(getResources().getColor(android.R.color.darker_gray));
                return;
            }

            if (enabled) {
                tvUninstallProtectionStatus.setText("Enabled - Child cannot uninstall");
                tvUninstallProtectionStatus.setTextColor(getResources().getColor(R.color.primary_600));
            } else {
                tvUninstallProtectionStatus.setText("Disabled - Child can uninstall app");
                tvUninstallProtectionStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            }
        }
    }

    /**
     * Check device count and update UI availability
     */
    private void updateProtectionAvailability() {
        if (switchUninstallProtection == null || connectedDevicesManager == null)
            return;

        int deviceCount = connectedDevicesManager.getDeviceCount();
        boolean hasDevices = deviceCount > 0;

        switchUninstallProtection.setEnabled(hasDevices);

        if (!hasDevices) {
            // Force visual update if disabled
            if (tvUninstallProtectionStatus != null) {
                tvUninstallProtectionStatus.setText("No child devices connected");
                tvUninstallProtectionStatus.setTextColor(getResources().getColor(android.R.color.darker_gray));
            }
        } else {
            // Restore status text based on current toggle state
            updateProtectionStatus(switchUninstallProtection.isChecked());
        }
    }

    /**
     * 🛡️ Static method to check if protection is enabled (for BlockService)
     */
    public static boolean isUninstallProtectionEnabled(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_UNINSTALL_PROTECTION, MODE_PRIVATE);
        return prefs.getBoolean(KEY_PROTECTION_ENABLED, true); // Default: enabled
    }

    /**
     * 🛡️ Static method to enable protection (called when child connects)
     */
    public static void enableProtection(android.content.Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_UNINSTALL_PROTECTION, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_PROTECTION_ENABLED, true).apply();
    }
}
