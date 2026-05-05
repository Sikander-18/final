package com.example.master2;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Activity that displays all apps with active timers.
 * Shows green for time remaining, red for expired.
 * Works for both parent and child devices.
 */
public class TimerStatusActivity extends AppCompatActivity {
    private static final String TAG = "TimerStatusActivity";

    public static final String EXTRA_DEVICE_ID = "device_id";
    public static final String EXTRA_IS_PARENT = "is_parent";

    private RecyclerView rvTimerStatus;
    private LinearLayout emptyState;
    private TimerStatusAdapter adapter;
    private List<TimerAppInfo> timerApps = new ArrayList<>();

    private String deviceId;
    private boolean isParent;
    private DatabaseReference timersRef;
    private ValueEventListener timersListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_timer_status);

        // Get intent extras
        deviceId = getIntent().getStringExtra(EXTRA_DEVICE_ID);
        isParent = getIntent().getBooleanExtra(EXTRA_IS_PARENT, false);

        if (deviceId == null || deviceId.isEmpty()) {
            // Try to get from session
            SessionManager session = new SessionManager(this);
            deviceId = session.getChildDeviceId();
        }

        if (deviceId == null || deviceId.isEmpty()) {
            Log.e(TAG, "No device ID provided!");
            finish();
            return;
        }

        Log.d(TAG, "📱 Timer Status for device: " + deviceId);

        setupToolbar();
        setupRecyclerView();
        setupFirebaseListener();
    }

    private void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Timer Status");
        }
        toolbar.setNavigationOnClickListener(v -> onBackPressed());
    }

    private void setupRecyclerView() {
        rvTimerStatus = findViewById(R.id.rvTimerStatus);
        emptyState = findViewById(R.id.emptyState);

        adapter = new TimerStatusAdapter(this, timerApps);
        rvTimerStatus.setLayoutManager(new LinearLayoutManager(this));
        rvTimerStatus.setAdapter(adapter);
    }

    private void setupFirebaseListener() {
        timersRef = FirebaseDatabase.getInstance()
                .getReference("app_timers")
                .child(deviceId);

        timersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                timerApps.clear();

                for (DataSnapshot appSnapshot : snapshot.getChildren()) {
                    try {
                        String packageName = appSnapshot.getKey();
                        Long remainingMs = appSnapshot.child("remainingTimeMillis").getValue(Long.class);
                        Long dailyLimitMs = appSnapshot.child("dailyLimitMillis").getValue(Long.class);
                        String appName = appSnapshot.child("appName").getValue(String.class);
                        Boolean expired = appSnapshot.child("expired").getValue(Boolean.class);

                        if (packageName == null)
                            continue;
                        if (remainingMs == null)
                            remainingMs = 0L;
                        if (dailyLimitMs == null)
                            dailyLimitMs = remainingMs;
                        if (expired == null)
                            expired = (remainingMs <= 0);

                        // Get app name and icon
                        if (appName == null || appName.isEmpty()) {
                            appName = getAppName(packageName);
                        }
                        Drawable icon = getAppIcon(packageName);

                        TimerAppInfo appInfo = new TimerAppInfo(
                                packageName, appName, icon,
                                remainingMs, dailyLimitMs, expired);
                        timerApps.add(appInfo);

                        Log.d(TAG, "🔔 Timer: " + appName + " - " +
                                (expired ? "EXPIRED" : formatTime(remainingMs) + " left"));

                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing timer: " + e.getMessage());
                    }
                }

                updateUI();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase error: " + error.getMessage());
            }
        };

        timersRef.addValueEventListener(timersListener);
    }

    private void updateUI() {
        if (timerApps.isEmpty()) {
            rvTimerStatus.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            rvTimerStatus.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
        }
        adapter.notifyDataSetChanged();
    }

    private String getAppName(String packageName) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private Drawable getAppIcon(String packageName) {
        try {
            return getPackageManager().getApplicationIcon(packageName);
        } catch (Exception e) {
            return getDrawable(R.drawable.ic_app);
        }
    }

    private String formatTime(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
        } else if (minutes > 0) {
            return String.format(Locale.getDefault(), "%dm", minutes);
        } else {
            return "<1m";
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (timersRef != null && timersListener != null) {
            timersRef.removeEventListener(timersListener);
        }
    }

    // ========== DATA CLASS ==========

    public static class TimerAppInfo {
        public String packageName;
        public String appName;
        public Drawable icon;
        public long remainingMs;
        public long dailyLimitMs;
        public boolean expired;

        public TimerAppInfo(String packageName, String appName, Drawable icon,
                long remainingMs, long dailyLimitMs, boolean expired) {
            this.packageName = packageName;
            this.appName = appName;
            this.icon = icon;
            this.remainingMs = remainingMs;
            this.dailyLimitMs = dailyLimitMs;
            this.expired = expired;
        }

        public int getProgressPercent() {
            if (dailyLimitMs <= 0)
                return 0;
            return (int) ((remainingMs * 100) / dailyLimitMs);
        }
    }

    // ========== ADAPTER ==========

    public static class TimerStatusAdapter extends RecyclerView.Adapter<TimerStatusAdapter.ViewHolder> {
        private Context context;
        private List<TimerAppInfo> apps;

        public TimerStatusAdapter(Context context, List<TimerAppInfo> apps) {
            this.context = context;
            this.apps = apps;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context)
                    .inflate(R.layout.item_timer_status, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            TimerAppInfo app = apps.get(position);

            // Set app info
            holder.tvAppName.setText(app.appName);
            if (app.icon != null) {
                holder.ivAppIcon.setImageDrawable(app.icon);
            }

            // Daily limit text
            holder.tvDailyLimit.setText("Daily limit: " + formatTime(app.dailyLimitMs));

            // Progress bar
            holder.progressTimer.setProgress(app.getProgressPercent());

            // Status indicator and time
            if (app.expired) {
                // EXPIRED - Show red
                holder.statusIndicator.setBackgroundResource(R.drawable.bg_circle_red);
                holder.tvTimeRemaining.setText("Expired");
                holder.tvTimeRemaining.setTextColor(context.getColor(android.R.color.holo_red_dark));
                holder.tvStatusLabel.setText("for today");
                holder.progressTimer.setProgress(0);
            } else {
                // ACTIVE - Show green
                holder.statusIndicator.setBackgroundResource(R.drawable.bg_circle_green);
                holder.tvTimeRemaining.setText(formatTime(app.remainingMs));
                holder.tvTimeRemaining.setTextColor(context.getColor(android.R.color.holo_green_dark));
                holder.tvStatusLabel.setText("left");
            }
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }

        private String formatTime(long millis) {
            long totalSeconds = millis / 1000;
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;

            if (hours > 0) {
                return String.format(Locale.getDefault(), "%dh %dm", hours, minutes);
            } else if (minutes > 0) {
                return String.format(Locale.getDefault(), "%dm", minutes);
            } else {
                return "<1m";
            }
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            ImageView ivAppIcon;
            TextView tvAppName;
            TextView tvDailyLimit;
            ProgressBar progressTimer;
            View statusIndicator;
            TextView tvTimeRemaining;
            TextView tvStatusLabel;

            ViewHolder(View itemView) {
                super(itemView);
                ivAppIcon = itemView.findViewById(R.id.ivAppIcon);
                tvAppName = itemView.findViewById(R.id.tvAppName);
                tvDailyLimit = itemView.findViewById(R.id.tvDailyLimit);
                progressTimer = itemView.findViewById(R.id.progressTimer);
                statusIndicator = itemView.findViewById(R.id.statusIndicator);
                tvTimeRemaining = itemView.findViewById(R.id.tvTimeRemaining);
                tvStatusLabel = itemView.findViewById(R.id.tvStatusLabel);
            }
        }
    }
}
