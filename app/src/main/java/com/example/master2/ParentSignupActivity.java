package com.example.master2;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.example.master2.services.OTPService;

import java.util.HashMap;
import java.util.Map;

/**
 * Parent signup activity.
 * Creates new parent accounts using Firebase Auth and stores profile in
 * Realtime Database.
 */
public class ParentSignupActivity extends AppCompatActivity {
    private static final String TAG = "ParentSignup";

    private EditText etName, etEmail, etPhone, etPassword, etConfirmPassword;
    private Button btnSignup;
    private TextView tvLoginLink;

    private FirebaseAuth mAuth;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_signup);

        mAuth = FirebaseAuth.getInstance();
        sessionManager = new SessionManager(this);

        initViews();
        setupListeners();
    }

    private void initViews() {
        etName = findViewById(R.id.etName);
        etEmail = findViewById(R.id.etEmail);
        etPhone = findViewById(R.id.etPhone);
        etPassword = findViewById(R.id.etPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnSignup = findViewById(R.id.btnSignup);
        tvLoginLink = findViewById(R.id.tvLoginLink);
    }

    private void setupListeners() {
        btnSignup.setOnClickListener(v -> attemptSignup());

        tvLoginLink.setOnClickListener(v -> {
            startActivity(new Intent(this, ParentEmailLoginActivity.class));
            finish();
        });
    }

    private void attemptSignup() {
        String name = etName.getText().toString().trim();
        String email = etEmail.getText().toString().trim();
        String phone = etPhone.getText().toString().trim();
        String password = etPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        // Validate inputs
        if (TextUtils.isEmpty(name)) {
            etName.setError("Name is required");
            etName.requestFocus();
            return;
        }

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

        if (TextUtils.isEmpty(phone)) {
            etPhone.setError("Phone number is required");
            etPhone.requestFocus();
            return;
        }

        if (phone.length() < 10) {
            etPhone.setError("Enter a valid phone number");
            etPhone.requestFocus();
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

        if (!password.equals(confirmPassword)) {
            etConfirmPassword.setError("Passwords do not match");
            etConfirmPassword.requestFocus();
            return;
        }

        // 🔧 FIX: Check if email already exists BEFORE sending OTP
        setLoading(true);
        mAuth.fetchSignInMethodsForEmail(email)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        boolean emailExists = task.getResult() != null &&
                                task.getResult().getSignInMethods() != null &&
                                !task.getResult().getSignInMethods().isEmpty();

                        if (emailExists) {
                            setLoading(false);
                            // Show dialog with option to login
                            new AlertDialog.Builder(this)
                                    .setTitle("Email Already Registered")
                                    .setMessage("This email is already registered. Would you like to login instead?")
                                    .setPositiveButton("Go to Login", (dialog, which) -> {
                                        Intent loginIntent = new Intent(this, ParentEmailLoginActivity.class);
                                        loginIntent.putExtra("email", email);
                                        startActivity(loginIntent);
                                        finish();
                                    })
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        } else {
                            setLoading(false);
                            // Email is available, proceed with OTP
                            sendSignupOTP(email, name, phone, password);
                        }
                    } else {
                        setLoading(false);
                        Log.e(TAG, "Email check failed", task.getException());
                        // Proceed anyway, error will be caught later
                        sendSignupOTP(email, name, phone, password);
                    }
                });
    }

    /**
     * 🆕 Send OTP to email for signup verification
     */
    private void sendSignupOTP(String email, String name, String phone, String password) {
        setLoading(true);

        OTPService otpService = new OTPService(this);

        otpService.sendOTP(email, "parent")
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        setLoading(false);

                        if (result.isSuccess()) {
                            Log.d(TAG, "OTP sent successfully for signup");
                            Toast.makeText(this, "OTP sent to " + email, Toast.LENGTH_SHORT).show();

                            // Navigate to OTP verification with signup data
                            Intent intent = new Intent(ParentSignupActivity.this, OtpVerificationActivity.class);
                            intent.putExtra("email", email);
                            intent.putExtra("isSignup", true);
                            intent.putExtra("signupName", name);
                            intent.putExtra("signupPhone", phone);
                            intent.putExtra("signupPassword", password);
                            startActivity(intent);
                            finish();
                        } else {
                            Log.e(TAG, "Failed to send OTP: " + result.getMessage());
                            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        setLoading(false);
                        Log.e(TAG, "Error sending OTP", throwable);
                        Toast.makeText(this, "Error: " + throwable.getMessage(), Toast.LENGTH_LONG).show();
                    });
                    return null;
                });
    }

    private void saveParentProfile(String uid, String name, String email, String phone) {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        Map<String, Object> parentData = new HashMap<>();
        parentData.put("name", name);
        parentData.put("email", email);
        parentData.put("phone", phone);
        parentData.put("deviceId", deviceId);
        parentData.put("createdAt", System.currentTimeMillis());
        parentData.put("userType", "parent");

        DatabaseReference parentRef = FirebaseDatabase.getInstance()
                .getReference("parent_accounts")
                .child(uid);

        parentRef.setValue(parentData)
                .addOnSuccessListener(aVoid -> {
                    setLoading(false);
                    Log.d(TAG, "Parent profile saved");

                    // Save session
                    sessionManager.saveParentSession(phone, uid, ParentUtils.getParentDeviceName(), name);

                    Toast.makeText(this, "Account created! Welcome, " + name + "!", Toast.LENGTH_SHORT).show();

                    // Go to Parent Dashboard
                    Intent intent = new Intent(ParentSignupActivity.this, ParentDashboardActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                    startActivity(intent);
                    finish();
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    Log.e(TAG, "Failed to save profile: " + e.getMessage());
                    Toast.makeText(this, "Failed to create profile. Please try again.", Toast.LENGTH_SHORT).show();
                });
    }

    private void setLoading(boolean loading) {
        btnSignup.setEnabled(!loading);
        btnSignup.setText(loading ? "Creating Account..." : "Create Account");
        etName.setEnabled(!loading);
        etEmail.setEnabled(!loading);
        etPhone.setEnabled(!loading);
        etPassword.setEnabled(!loading);
        etConfirmPassword.setEnabled(!loading);
    }
}
