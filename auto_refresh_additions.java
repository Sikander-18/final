// AUTO-REFRESH FUNCTIONALITY - Add these to ParentDashboardActivity.java

// Add these variables at the top of the class:
private Handler autoRefreshHandler = new Handler();
private Runnable autoRefreshRunnable;
private static final long AUTO_REFRESH_INTERVAL = 2 * 60 * 1000; // 2 minutes in milliseconds
private boolean isAutoRefreshActive = false;

// Add this method to start auto-refresh:
private void startAutoRefresh() {
    if (isAutoRefreshActive) {
        return; // Already running
    }
    
    isAutoRefreshActive = true;
    autoRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (currentChildDeviceId != null && !currentChildDeviceId.isEmpty()) {
                Log.d(TAG, "🔄 Auto-refreshing usage data for device: " + currentChildDeviceName);
                
                // Show subtle refresh indicator
                Toast.makeText(ParentDashboardActivity.this, 
                    "🔄 Auto-refreshing " + currentChildDeviceName + " usage data...", 
                    Toast.LENGTH_SHORT).show();
                
                // Trigger refresh from child device
                refreshUsageDataFromChild();
                
                // Also reload current usage display
                loadSmartUsageDataForSelectedDate();
            }
            
            // Schedule next refresh
            if (isAutoRefreshActive) {
                autoRefreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL);
            }
        }
    };
    
    // Start the auto-refresh cycle
    autoRefreshHandler.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL);
    Log.d(TAG, "✅ Auto-refresh started - will refresh every 2 minutes");
}

// Add this method to stop auto-refresh:
private void stopAutoRefresh() {
    if (autoRefreshHandler != null && autoRefreshRunnable != null) {
        autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
    }
    isAutoRefreshActive = false;
    Log.d(TAG, "🛑 Auto-refresh stopped");
}

// Add these to lifecycle methods:
@Override
protected void onResume() {
    super.onResume();
    // Start auto-refresh when activity resumes
    startAutoRefresh();
}

@Override
protected void onPause() {
    super.onPause();
    // Stop auto-refresh when activity pauses to save battery
    stopAutoRefresh();
}

@Override
protected void onDestroy() {
    super.onDestroy();
    // Make sure to stop auto-refresh
    stopAutoRefresh();
}