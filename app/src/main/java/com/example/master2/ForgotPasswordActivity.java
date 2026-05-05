package com.example.master2;

import android.content.Intent;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.master2.services.OTPService;
import com.example.master2.utils.LoadingDialogManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class ForgotPasswordActivity extends AppCompatActivity {
    private static final String TAG = "ForgotPassword";

    // Views
    private ImageView btnBack;
    private TextView tvTitle, tvSubtitle;

    // State 1: Email
    private LinearLayout layoutEmailState;
    private EditText etEmail;
    private Button btnSendOTP;

    // State 2: OTP
    private LinearLayout layoutOtpState;
    private TextView tvOtpSentTo, tvTimer, tvResendOtp;
    private EditText etOtp;
    private Button btnVerifyOtp;

    // State 3: Password
    private LinearLayout layoutPasswordState;
    private EditText etNewPassword, etConfirmPassword;
    private Button btnResetPassword;

    private FirebaseAuth mAuth;
    private OTPService otpService;
    private LoadingDialogManager loadingDialogManager;
    private CountDownTimer countDownTimer;

    private String email;
    private String userFirebaseUid;
    private static final long COUNTDOWN_TIME = 120000; // 2 minutes

    // Only EMAIL and OTP states - password reset is done via email link
    private enum State {
        EMAIL, OTP
    }

    private State currentState = State.EMAIL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_forgot_password);

        initViews();
        mAuth = FirebaseAuth.getInstance();
        otpService = new OTPService(this);
        loadingDialogManager = new LoadingDialogManager(this);

        setupClickListeners();
        showState(State.EMAIL);
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        tvTitle = findViewById(R.id.tvTitle);
        tvSubtitle = findViewById(R.id.tvSubtitle);

        // Email state
        layoutEmailState = findViewById(R.id.layoutEmailState);
        etEmail = findViewById(R.id.etEmail);
        btnSendOTP = findViewById(R.id.btnSendOTP);

        // OTP state
        layoutOtpState = findViewById(R.id.layoutOtpState);
        tvOtpSentTo = findViewById(R.id.tvOtpSentTo);
        tvTimer = findViewById(R.id.tvTimer);
        tvResendOtp = findViewById(R.id.tvResendOtp);
        etOtp = findViewById(R.id.etOtp);
        btnVerifyOtp = findViewById(R.id.btnVerifyOtp);

        // Password state
        layoutPasswordState = findViewById(R.id.layoutPasswordState);
        etNewPassword = findViewById(R.id.etNewPassword);
        etConfirmPassword = findViewById(R.id.etConfirmPassword);
        btnResetPassword = findViewById(R.id.btnResetPassword);
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> onBackPressed());
        btnSendOTP.setOnClickListener(v -> sendOTP());
        btnVerifyOtp.setOnClickListener(v -> verifyOTP());
        tvResendOtp.setOnClickListener(v -> resendOTP());
        btnResetPassword.setOnClickListener(v -> resetPassword());
    }

    private void showState(State state) {
        currentState = state;

        layoutEmailState.setVisibility(View.GONE);
        layoutOtpState.setVisibility(View.GONE);
        layoutPasswordState.setVisibility(View.GONE); // Keep hidden, not used

        switch (state) {
            case EMAIL:
                layoutEmailState.setVisibility(View.VISIBLE);
                tvTitle.setText("Forgot Password?");
                tvSubtitle.setText("Don't worry! We'll help you reset it");
                break;

            case OTP:
                layoutOtpState.setVisibility(View.VISIBLE);
                tvTitle.setText("Verify Code");
                tvSubtitle.setText("Enter the code sent to your email");
                tvOtpSentTo.setText("We've sent a 6-digit code to\n" + email);
                startCountdownTimer();
                break;
        }
    }

    private void sendOTP() {
        email = etEmail.getText().toString().trim();

        if (!validateEmail(email)) {
            return;
        }

        loadingDialogManager.show("Checking Email...", "Please wait");

        checkEmailExists(email, exists -> {
            if (exists) {
                loadingDialogManager.updateText("Sending Code...", "Sending verification code to your email");

                otpService.sendOTP(email, "parent")
                        .thenAccept(result -> {
                            runOnUiThread(() -> {
                                loadingDialogManager.hide();
                                if (result.isSuccess()) {
                                    Toast.makeText(this, "Verification code sent to your email!", Toast.LENGTH_SHORT)
                                            .show();
                                    showState(State.OTP);
                                } else {
                                    Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                                }
                            });
                        })
                        .exceptionally(throwable -> {
                            runOnUiThread(() -> {
                                loadingDialogManager.hide();
                                Toast.makeText(this, "Failed to send code. Please try again.", Toast.LENGTH_SHORT)
                                        .show();
                                Log.e(TAG, "Error sending OTP", throwable);
                            });
                            return null;
                        });
            } else {
                loadingDialogManager.hide();
                new AlertDialog.Builder(this)
                        .setTitle("Email Not Found")
                        .setMessage(
                                "No account found with this email address.\n\nPlease check your email or sign up for a new account.")
                        .setPositiveButton("Try Again", null)
                        .setNegativeButton("Sign Up", (dialog, which) -> {
                            startActivity(new Intent(this, ParentSignupActivity.class));
                            finish();
                        })
                        .show();
            }
        });
    }

    private void checkEmailExists(String email, EmailExistsCallback callback) {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("parent_accounts");

        ref.orderByChild("email").equalTo(email).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    for (DataSnapshot userSnapshot : snapshot.getChildren()) {
                        userFirebaseUid = userSnapshot.getKey();
                        Log.d(TAG, "Email found, UID: " + userFirebaseUid);
                        callback.onResult(true);
                        return;
                    }
                }
                callback.onResult(false);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                Log.e(TAG, "Error checking email: " + error.getMessage());
                callback.onResult(false);
            }
        });
    }

    private void verifyOTP() {
        String otp = etOtp.getText().toString().trim();

        if (!validateOTP(otp)) {
            return;
        }

        loadingDialogManager.show("Verifying Code...", "Please wait");

        otpService.verifyOTP(email, otp)
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        if (result.isSuccess()) {
                            if (countDownTimer != null) {
                                countDownTimer.cancel();
                            }
                            // OTP verified - now send password reset link immediately
                            loadingDialogManager.updateText("Sending Reset Link...", "Preparing secure password reset");
                            sendPasswordResetEmail();
                        } else {
                            loadingDialogManager.hide();
                            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        Toast.makeText(this, "Failed to verify code. Please try again.", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Error verifying OTP", throwable);
                    });
                    return null;
                });
    }

    private void resendOTP() {
        new AlertDialog.Builder(this)
                .setTitle("🔄 Resend Code")
                .setMessage("Are you sure you want to resend the verification code?\n\n" +
                        "✉️ A new code will be sent to your email\n" +
                        "⏰ Timer will reset to 2 minutes")
                .setPositiveButton("Yes, Resend", (dialog, which) -> performResendOTP())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void performResendOTP() {
        loadingDialogManager.show("Sending New Code...", "Please wait");
        tvResendOtp.setEnabled(false);
        tvResendOtp.setText("Sending...");

        otpService.resendOTP(email, "parent")
                .thenAccept(result -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        if (result.isSuccess()) {
                            Toast.makeText(this, "🔄 New code sent to your email!", Toast.LENGTH_SHORT).show();
                            etOtp.setText("");
                            startCountdownTimer();
                        } else {
                            Toast.makeText(this, result.getMessage(), Toast.LENGTH_LONG).show();
                            tvResendOtp.setEnabled(true);
                            tvResendOtp.setText("Resend OTP");
                        }
                    });
                })
                .exceptionally(throwable -> {
                    runOnUiThread(() -> {
                        loadingDialogManager.hide();
                        tvResendOtp.setEnabled(true);
                        tvResendOtp.setText("Resend OTP");
                        Toast.makeText(this, "Failed to resend code. Please try again.", Toast.LENGTH_SHORT).show();
                        Log.e(TAG, "Error resending OTP", throwable);
                    });
                    return null;
                });
    }

    private void resetPassword() {
        String newPassword = etNewPassword.getText().toString().trim();
        String confirmPassword = etConfirmPassword.getText().toString().trim();

        if (!validatePasswords(newPassword, confirmPassword)) {
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Reset Password")
                .setMessage(
                        "Since you've verified your identity with OTP, we'll now send you a secure password reset link.\n\n"
                                +
                                "This is the most secure way to reset your password.\n\n" +
                                "Click the link in your email to set your new password.")
                .setPositiveButton("Send Reset Link", (dialog, which) -> {
                    sendPasswordResetEmail();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void sendPasswordResetEmail() {
        mAuth.sendPasswordResetEmail(email)
                .addOnCompleteListener(task -> {
                    loadingDialogManager.hide();
                    if (task.isSuccessful()) {
                        new AlertDialog.Builder(this)
                                .setTitle("✅ Password Reset Link Sent!")
                                .setMessage("We've sent a password reset link to:\n\n📧 " + email + "\n\n" +
                                        "Please check your inbox for the password reset email.\n\n" +
                                        "⚠️ If you don't see it in your primary inbox, please check your SPAM/JUNK folder.\n\n"
                                        +
                                        "🔗 Click the link in the email to set your new password\n" +
                                        "⏰ Link expires in 1 hour")
                                .setPositiveButton("OK, Got it!", (dialog, which) -> {
                                    Intent intent = new Intent(this, ParentEmailLoginActivity.class);
                                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                    startActivity(intent);
                                    finish();
                                })
                                .setCancelable(false)
                                .show();
                    } else {
                        String errorMsg = "Failed to send reset link";
                        if (task.getException() != null) {
                            errorMsg += ": " + task.getException().getMessage();
                        }
                        Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private boolean validateEmail(String email) {
        if (TextUtils.isEmpty(email)) {
            etEmail.setError("Email is required");
            etEmail.requestFocus();
            return false;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.setError("Enter a valid email");
            etEmail.requestFocus();
            return false;
        }

        return true;
    }

    private boolean validateOTP(String otp) {
        if (TextUtils.isEmpty(otp)) {
            etOtp.setError("Enter OTP");
            etOtp.requestFocus();
            return false;
        }

        if (otp.length() != 6) {
            etOtp.setError("Enter valid 6-digit OTP");
            etOtp.requestFocus();
            return false;
        }

        return true;
    }

    private boolean validatePasswords(String newPassword, String confirmPassword) {
        if (TextUtils.isEmpty(newPassword)) {
            etNewPassword.setError("Password is required");
            etNewPassword.requestFocus();
            return false;
        }

        if (newPassword.length() < 6) {
            etNewPassword.setError("Password must be at least 6 characters");
            etNewPassword.requestFocus();
            return false;
        }

        if (TextUtils.isEmpty(confirmPassword)) {
            etConfirmPassword.setError("Confirm your password");
            etConfirmPassword.requestFocus();
            return false;
        }

        if (!newPassword.equals(confirmPassword)) {
            etConfirmPassword.setError("Passwords do not match");
            etConfirmPassword.requestFocus();
            return false;
        }

        return true;
    }

    private void startCountdownTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }

        tvResendOtp.setEnabled(false);
        tvResendOtp.setText("Wait to resend");

        countDownTimer = new CountDownTimer(COUNTDOWN_TIME, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                long minutes = millisUntilFinished / 60000;
                long seconds = (millisUntilFinished % 60000) / 1000;

                tvTimer.setText(String.format("Code expires in %d:%02d", minutes, seconds));
                tvResendOtp.setText(String.format("Resend in %d:%02d", minutes, seconds));
            }

            @Override
            public void onFinish() {
                tvTimer.setText("⏰ Code has expired");
                tvResendOtp.setEnabled(true);
                tvResendOtp.setText("Resend OTP");
            }
        }.start();
    }

    @Override
    protected void onDestroy() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
        }
        if (loadingDialogManager != null) {
            loadingDialogManager.cleanup();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (loadingDialogManager != null && loadingDialogManager.isShowing()) {
            Toast.makeText(this, "Please wait...", Toast.LENGTH_SHORT).show();
            return;
        }
        super.onBackPressed();
    }

    interface EmailExistsCallback {
        void onResult(boolean exists);
    }
}
