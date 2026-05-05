package com.example.master2;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class AppBlockActivity extends AppCompatActivity {
    private static final String TAG = "AppBlockActivity";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        try {
            // Get blocked app info
            String blockedApp = getIntent().getStringExtra("blocked_app");
            Log.d(TAG, "🚫 Blocking app: " + blockedApp);
            
            // Create simple blocking UI
            setTitle("Time's Up!");
            
            TextView textView = new TextView(this);
            textView.setText("⏰ Time's up!\n\nYou've reached your time limit for this app.\n\nPlease close the app and take a break.");
            textView.setTextSize(18);
            textView.setPadding(40, 40, 40, 40);
            textView.setTextAlignment(TextView.TEXT_ALIGNMENT_CENTER);
            
            Button closeButton = new Button(this);
            closeButton.setText("OK");
            closeButton.setOnClickListener(v -> {
                finish();
                // Try to go back to home screen
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(homeIntent);
            });
            
            // Simple linear layout
            android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
            layout.setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.addView(textView);
            layout.addView(closeButton);
            
            setContentView(layout);
            
            // Auto-close after 5 seconds
            new Handler().postDelayed(() -> {
                finish();
            }, 5000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error in AppBlockActivity: " + e.getMessage());
            finish();
        }
    }
    
    @Override
    public void onBackPressed() {
        // Prevent back button from closing the block screen immediately
        super.onBackPressed();
        finish();
    }
}
