package com.example.master2;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Activity to collect child's name before proceeding to permissions and QR
 * scanning.
 * The child name will be stored and displayed on parent dashboard instead of
 * device name.
 */
public class ChildNameActivity extends AppCompatActivity {
    private static final String TAG = "ChildNameActivity";

    private EditText etChildName;
    private Button btnContinue;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_child_name);

        sessionManager = new SessionManager(this);

        initViews();
        setupListeners();
    }

    private void initViews() {
        etChildName = findViewById(R.id.etChildName);
        btnContinue = findViewById(R.id.btnContinue);
    }

    private void setupListeners() {
        btnContinue.setOnClickListener(v -> {
            String childName = etChildName.getText().toString().trim();

            if (TextUtils.isEmpty(childName)) {
                etChildName.setError("Please enter your name");
                etChildName.requestFocus();
                return;
            }

            if (childName.length() < 2) {
                etChildName.setError("Name must be at least 2 characters");
                etChildName.requestFocus();
                return;
            }

            // Save child name to session
            sessionManager.saveChildName(childName);

            Toast.makeText(this, "Hello, " + childName + "!", Toast.LENGTH_SHORT).show();

            // Continue to permissions activity (existing flow)
            Intent intent = new Intent(this, ChildPermissionsActivity.class);
            startActivity(intent);
            finish();
        });
    }

    @Override
    public void onBackPressed() {
        // Go back to login type selection
        super.onBackPressed();
    }
}
