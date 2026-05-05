package com.example.master2;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

/**
 * Parent authentication choice screen.
 * Allows parents to choose between Login and Sign Up.
 */
public class ParentAuthActivity extends AppCompatActivity {
    private static final String TAG = "ParentAuthActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_parent_auth);

        CardView cardLogin = findViewById(R.id.cardLogin);
        CardView cardSignup = findViewById(R.id.cardSignup);

        cardLogin.setOnClickListener(v -> {
            startActivity(new Intent(this, ParentEmailLoginActivity.class));
        });

        cardSignup.setOnClickListener(v -> {
            startActivity(new Intent(this, ParentSignupActivity.class));
        });
    }

    @Override
    public void onBackPressed() {
        // Go back to login type selection
        super.onBackPressed();
    }
}
