package com.example.master2;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.widget.Toast;
import android.util.Log;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.example.master2.databinding.ActivityOtpVerificationBinding;
import com.example.master2.services.OTPService;
import com.example.master2.SessionManager;
import com.example.master2.utils.LoadingDialogManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.Map;

public class OtpVerificationActivity extends AppCompatActivity {
    private static final String TAG = "OtpVerification";
    private ActivityOtpVerificationBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;
    private SessionManager sessionManager;
    private OTPService otpService;
    private CountDownTimer countDownTimer;
    private LoadingDialogManager loadingDialogManager;

    private String username, email, phone, userType;
    private static final long COUNTDOWN_TIME = 120000; // 2 minutes in milliseconds

    // 🆕 Signup flow fields
    private boolean isSignupFlow = false;
    private String signupName;
    private String signupPhone;
    private String signupPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityOtpVerificationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Initialize services
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();
        sessionManager = new SessionManager(this);
        otpService = new OTPService(this);
        loadingDialogManager = new LoadingDialogManager(this);

        // Get data from intent
        email = getIntent().getStringExtra("email");
        username = getIntent().getStringExtra("username");
        phone = getIntent().getStringExtra("phone");
        userType = getIntent().getStringExtra("userType");

        // 🆕 Check if this is signup flow
        isSignupFlow = getIntent().getBooleanExtra("isSignup", false);
        if (isSignupFlow) {
            signupName = getIntent().getStringExtra("signupName");
            signupPhone = getIntent().getStringExtra("signupPhone");
            signupPassword = getIntent().getStringExtra("signupPassword");
            userType = "parent"; // Signup is always for parent
            username = signupName; // Use signup name as username
            phone = signupPhone; // Use signup phone
            Log.d(TAG, "🆕 Signup flow detected for email: " + email);
        }

        // Set up click listeners
        binding.btnVerifyOtp.setOnClickListener(v -> verifyOTP());
        binding.tvResendOtp.setOnClickListener(v -> resendOTP());
        binding.btnBack.setOnClickListener(v -> onBackPressed());

        // Start countdown timer
        startCountdownTimer();
    }

    private void verifyOTP() {
        String otp = binding.etOtp.getText().toString().trim();

        if (!validateOTP(otp)) {
            return;
        }

        // Show loading dialog - this prevents user from going back or interacting with
        // UI
        loadingDialogManager.show("Verifying OTP...", "Please wait while we validate your code");
        setLoadingState(true);

        // Verify OTP using Appwrite
        otpService.verifyOTP(email, otp)
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        if (result.isSuccess()) {
                            Log.d(TAG, "✅ OTP verified successfully");

                            // Update loading dialog to show authentication progress
                            loadingDialogManager.updateText("OTP Verified!", "Signing you in to your account...");

                            // Stop the timer
                            if (countDownTimer != null) {
                                countDownTimer.cancel();
                            }

                            // 🆕 Check if signup or login flow
                            if (isSignupFlow) {
                                // Signup flow: Create Firebase account with email/password
                                completeSignup();
                            } else {
                                // Login flow: Sign in anonymously and save user data
                                signInAndSaveUserData();
                            }
                        } else {
                            // Hide loading dialog and show error
                            loadingDialogManager.hide();
                            setLoadingState(false);
                            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        setLoadingState(false);
                        Toast.makeText(this, "Failed to verify OTP. Please try again.", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Error verifying OTP", throwable);
                    });
                    return null;
                });
    }

    private boolean validateOTP(String otp) {
        if (TextUtils.isEmpty(otp)) {
            binding.etOtp.setError("Enter OTP");
            binding.etOtp.requestFocus();
            return false;
        }

        if (otp.length() != 6) {
            binding.etOtp.setError("Enter valid 6-digit OTP");
            binding.etOtp.requestFocus();
            return false;
        }

        return true;
    }

    private void resendOTP() {
        // Show confirmation dialog before resending OTP
        new AlertDialog.Builder(this)
                .setTitle("🔄 Resend OTP")
                .setMessage("Are you sure you want to resend the OTP?\n\n" +
                        "✉️ A new verification code will be sent to your email\n" +
                        "⏰ Timer will reset to 2 minutes\n" +
                        "📝 Current input will be cleared")
                .setPositiveButton("Yes, Resend", (dialog, which) -> {
                    // User confirmed - proceed with resending OTP
                    performResendOTP();
                })
                .setNegativeButton("Cancel", (dialog, which) -> {
                    // User cancelled - do nothing
                    dialog.dismiss();
                })
                .setCancelable(true)
                .show();
    }

    private void performResendOTP() {
        // Show loading dialog for resend
        loadingDialogManager.show("Sending New OTP...", "Please wait while we send a new code to your email");
        setLoadingState(true);

        // Disable resend button temporarily
        binding.tvResendOtp.setEnabled(false);
        binding.tvResendOtp.setText("Sending...");

        otpService.resendOTP(email, userType)
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        setLoadingState(false);

                        if (result.isSuccess()) {
                            Toast.makeText(this, "🔄 New OTP sent to your email!\n⏰ Timer reset to 2 minutes.",
                                    Toast.LENGTH_LONG).show();

                            // Add a brief visual feedback before restarting timer
                            binding.tvTimer.setText("🔄 Timer resetting...");

                            // Restart countdown timer after a brief delay - this will reset it to 2 minutes
                            new android.os.Handler().postDelayed(() -> {
                                startCountdownTimer();
                            }, 800); // Brief delay to show reset message

                            // Clear any existing OTP input
                            binding.etOtp.setText("");
                            binding.etOtp.requestFocus();
                        } else {
                            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                            binding.tvResendOtp.setEnabled(true);
                            binding.tvResendOtp.setText("Resend OTP");
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        setLoadingState(false);
                        binding.tvResendOtp.setEnabled(true);
                        binding.tvResendOtp.setText("Resend OTP");
                        Toast.makeText(this, "Failed to resend OTP. Please try again.", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Error resending OTP", throwable);
                    });
                    return null;
                });
    }

    private void signInAndSaveUserData() {
        // Update loading dialog for authentication step
        loadingDialogManager.updateText("Authenticating...", "Creating your secure session...");

        // Sign in anonymously to get Firebase UID for compatibility
        mAuth.signInAnonymously()
                .addOnSuccessListener(authResult -> {
                    Log.d(TAG, "✅ Anonymous sign-in successful");
                    // Update loading dialog for data saving step
                    loadingDialogManager.updateText("Almost Done!", "Saving your account information...");
                    saveUserData();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Anonymous sign-in failed: " + e.getMessage());
                    // Still try to save user data without Firebase auth
                    loadingDialogManager.updateText("Finalizing Setup...", "Completing your account setup...");
                    saveUserDataWithoutAuth();
                });
    }

    /**
     * 🆕 Complete signup by creating Firebase account with email/password
     */
    private void completeSignup() {
        loadingDialogManager.updateText("Creating Account...", "Setting up your secure account...");

        mAuth.createUserWithEmailAndPassword(email, signupPassword)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "✅ Firebase account created successfully");
                        com.google.firebase.auth.FirebaseUser firebaseUser = mAuth.getCurrentUser();
                        if (firebaseUser != null) {
                            saveParentProfile(firebaseUser.getUid(), signupName, email, signupPhone);
                        }
                    } else {
                        loadingDialogManager.hide();
                        setLoadingState(false);
                        Log.e(TAG, "❌ Signup failed", task.getException());

                        if (task.getException() != null) {
                            String msg = task.getException().getMessage();
                            if (msg != null && msg.contains("email address is already in use")) {
                                // Show dialog with option to login
                                new AlertDialog.Builder(this)
                                        .setTitle("Email Already Registered")
                                        .setMessage(
                                                "This email is already registered. Would you like to login instead?")
                                        .setPositiveButton("Go to Login", (dialog, which) -> {
                                            startActivity(new Intent(this, ParentEmailLoginActivity.class));
                                            finish();
                                        })
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            } else {
                                Toast.makeText(this, msg != null ? msg : "Signup failed", Toast.LENGTH_LONG).show();
                            }
                        } else {
                            Toast.makeText(this, "Signup failed", Toast.LENGTH_LONG).show();
                        }
                    }
                });
    }

    /**
     * 🆕 Save parent profile after signup
     */
    private void saveParentProfile(String uid, String name, String email, String phone) {
        String deviceId = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);

        Map<String, Object> parentData = new HashMap<>();
        parentData.put("name", name);
        parentData.put("email", email);
        parentData.put("phone", phone);
        parentData.put("deviceId", deviceId);
        parentData.put("createdAt", System.currentTimeMillis());
        parentData.put("userType", "parent");

        // Save to parent_accounts node
        DatabaseReference parentRef = FirebaseDatabase.getInstance()
                .getReference("parent_accounts")
                .child(uid);

        // 🔧 CRITICAL FIX: Also save to users node for child device compatibility
        DatabaseReference usersRef = FirebaseDatabase.getInstance()
                .getReference("users")
                .child(uid);

        parentRef.setValue(parentData)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "✅ Parent profile saved to parent_accounts");

                    // 🔧 ALSO save to users node so child devices can find parent
                    usersRef.setValue(parentData)
                            .addOnSuccessListener(v -> {
                                Log.d(TAG, "✅ Parent data saved to users node for child compatibility");

                                // Save session
                                String deviceName = ParentUtils.getParentDeviceName();
                                sessionManager.saveParentSession(phone, uid, deviceName, name);

                                // 🔧 REMOVED 1.5s DELAY - Navigate immediately
                                loadingDialogManager.hide();
                                Toast.makeText(this, "Welcome, " + name + "!", Toast.LENGTH_SHORT).show();

                                Intent intent = new Intent(OtpVerificationActivity.this, ParentDashboardActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "⚠️ Failed to save to users node: " + e.getMessage());
                                // Continue anyway since parent_accounts worked
                                Toast.makeText(this, "Account created! Some features may be limited.",
                                        Toast.LENGTH_SHORT).show();

                                Intent intent = new Intent(OtpVerificationActivity.this, ParentDashboardActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                startActivity(intent);
                                finish();
                            });
                })
                .addOnFailureListener(e -> {
                    loadingDialogManager.hide();
                    setLoadingState(false);
                    Log.e(TAG, "❌ Failed to save profile: " + e.getMessage());
                    Toast.makeText(this, "Failed to create profile. Please try again.", Toast.LENGTH_SHORT).show();
                });
    }

    private void saveUserData() {
        try {
            String userId = mAuth.getCurrentUser().getUid();
            String deviceId = android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ANDROID_ID);

            Log.d(TAG, "💾 Saving user data - Firebase UID: " + userId + ", Device ID: " + deviceId);

            // Save to Firestore (for compatibility)
            Map<String, Object> firestoreUser = new HashMap<>();
            firestoreUser.put("username", username);
            firestoreUser.put("email", email);
            firestoreUser.put("phone", phone);
            firestoreUser.put("userType", "parent");
            firestoreUser.put("deviceId", deviceId);
            firestoreUser.put("createdAt", System.currentTimeMillis());

            // CRITICAL: Also save to Realtime Database for child connections
            Map<String, Object> realtimeUser = new HashMap<>();
            realtimeUser.put("username", username);
            realtimeUser.put("email", email);
            realtimeUser.put("phone", phone);
            realtimeUser.put("userType", "parent");
            realtimeUser.put("deviceId", deviceId);
            realtimeUser.put("firebaseUid", userId);
            realtimeUser.put("createdAt", System.currentTimeMillis());

            // Save to both databases simultaneously
            db.collection("users").document(userId).set(firestoreUser)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "✅ Firestore user data saved");

                        // Also save to Realtime Database
                        com.google.firebase.database.FirebaseDatabase.getInstance()
                                .getReference("users")
                                .child(userId)
                                .setValue(realtimeUser)
                                .addOnSuccessListener(aVoid2 -> {
                                    Log.d(TAG, "✅ Realtime Database user data saved");

                                    // Save session for persistent login
                                    String deviceName = ParentUtils.getParentDeviceName();
                                    sessionManager.saveParentSession(phone, userId, deviceName, username);
                                    Log.d(TAG, "✅ Parent session saved successfully after OTP verification");

                                    // Update loading dialog for final step
                                    loadingDialogManager.updateText("Welcome!", "Taking you to your dashboard...");

                                    // Add a brief delay to show success message before redirecting
                                    new android.os.Handler().postDelayed(() -> {
                                        // Hide loading dialog
                                        loadingDialogManager.hide();

                                        // Show welcome toast
                                        Toast.makeText(OtpVerificationActivity.this,
                                                "✅ Login successful! Welcome " + username, Toast.LENGTH_SHORT).show();

                                        // Navigate to parent dashboard
                                        Intent intent = new Intent(OtpVerificationActivity.this,
                                                ParentDashboardActivity.class);
                                        intent.addFlags(
                                                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        startActivity(intent);
                                        finish();
                                    }, 1500); // 1.5 second delay to show success message
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "❌ Failed to save to Realtime Database: " + e.getMessage());
                                    // Still proceed since Firestore worked
                                    proceedWithLogin();
                                });
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "❌ Failed to save to Firestore: " + e.getMessage());
                        loadingDialogManager.hide();
                        Toast.makeText(OtpVerificationActivity.this,
                                "Failed to save user data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });

        } catch (Exception e) {
            Log.e(TAG, "Critical error in saveUserData: " + e.getMessage());
            loadingDialogManager.hide();
            Toast.makeText(this, "Error saving user data: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void saveUserDataWithoutAuth() {
        // Generate a unique user ID
        String userId = "user_" + System.currentTimeMillis();
        String deviceId = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);

        // Save user data directly without Firebase auth
        Map<String, Object> userData = new HashMap<>();
        userData.put("username", username);
        userData.put("email", email);
        userData.put("phone", phone);
        userData.put("userType", userType);
        userData.put("deviceId", deviceId);
        userData.put("createdAt", System.currentTimeMillis());

        // Save session based on user type
        if ("parent".equals(userType)) {
            String deviceName = ParentUtils.getParentDeviceName();
            sessionManager.saveParentSession(phone, userId, deviceName, username);
        } else if ("child".equals(userType)) {
            // For child type - this shouldn't normally happen in OTP verification
            // But we'll handle it gracefully
            sessionManager.saveChildSession(userId, username, "");
        }

        proceedToMainActivity();
    }

    private void setLoadingState(boolean isLoading) {
        binding.btnVerifyOtp.setEnabled(!isLoading);
        binding.btnVerifyOtp.setText(isLoading ? "Verifying..." : "Verify OTP");
        binding.etOtp.setEnabled(!isLoading);
    }

    private void startCountdownTimer() {
        // Cancel existing timer
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        // Disable resend button and update text
        binding.tvResendOtp.setEnabled(false);
        binding.tvResendOtp.setText("Wait to resend");

        countDownTimer = new CountDownTimer(COUNTDOWN_TIME, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long minutes = millisUntilFinished / 60000;
                long seconds = (millisUntilFinished % 60000) / 1000;

                String timeLeft = String.format("Code expires in %d:%02d", minutes, seconds);
                binding.tvTimer.setText(timeLeft);

                // Update resend button text with countdown
                binding.tvResendOtp.setText(String.format("Resend in %d:%02d", minutes, seconds));
            }

            @Override
            public void onFinish() {
                binding.tvTimer.setText("⏰ Code has expired");
                binding.tvResendOtp.setEnabled(true);
                binding.tvResendOtp.setText("Resend OTP");
            }
        }.start();
    }

    private void proceedWithLogin() {
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid()
                : "user_" + System.currentTimeMillis();

        // Save session based on user type
        if ("parent".equals(userType)) {
            String deviceName = ParentUtils.getParentDeviceName();
            sessionManager.saveParentSession(phone, userId, deviceName, username != null ? username : "Parent");
        } else if ("child".equals(userType)) {
            // For child type - this shouldn't normally happen in OTP verification
            // But we'll handle it gracefully
            sessionManager.saveChildSession(userId, username, "");
        }
        proceedToMainActivity();
    }

    private void proceedToMainActivity() {
        // Update loading dialog for final step
        loadingDialogManager.updateText("Welcome!", "Taking you to your dashboard...");

        // Add a brief delay to show success message before redirecting
        new android.os.Handler().postDelayed(() -> {
            // Hide loading dialog
            loadingDialogManager.hide();

            // Show welcome toast
            Toast.makeText(this, "✅ Login successful! Welcome " + username, Toast.LENGTH_SHORT).show();

            Intent intent;
            if ("parent".equals(userType)) {
                intent = new Intent(this, ParentDashboardActivity.class);
            } else {
                intent = new Intent(this, ChildDashboardActivity.class);
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }, 1500); // 1.5 second delay to show success message
    }

    @Override
    public void onBackPressed() {
        // Prevent going back when loading dialog is showing
        if (loadingDialogManager != null && loadingDialogManager.isShowing()) {
            Toast.makeText(this, "Please wait while we complete the authentication process", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        // Clean up the loading dialog manager to prevent memory leaks
        if (loadingDialogManager != null) {
            loadingDialogManager.cleanup();
        }

        super.onDestroy();
        binding = null;
    }
}