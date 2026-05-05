package com.example.master2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 🛡️ BULLETPROOF Boot Receiver
 * 
 * Ensures all critical services start automatically when:
 * - Device boots up
 * - App is updated
 * 
 * Works with ServiceWatchdog for complete coverage.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.d(TAG, "🚀 BootReceiver triggered: " + action);
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            Intent.ACTION_PACKAGE_REPLACED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action) ||
            "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            Log.d(TAG, "📱 Device booted/app updated - starting services");
            
            try {
                // Check if user is logged in as child
                SessionManager sessionManager = new SessionManager(context);
                
                if (!sessionManager.isLoggedIn()) {
                    Log.d(TAG, "User not logged in - skipping service start");
                    return;
                }
                
                String userType = sessionManager.getUserType();
                
                if ("child".equals(userType)) {
                    Log.d(TAG, "🔧 Child device detected - starting all services");
                    
                    // 🛡️ BULLETPROOF: Use ServiceWatchdog to start all services
                    ServiceWatchdog.startAllServices(context);
                    
                    // Schedule periodic health checks
                    ServiceWatchdog.schedulePeriodicChecks(context);
                    
                    Log.d(TAG, "✅ All child services started via ServiceWatchdog");
                    
                } else if ("parent".equals(userType)) {
                    Log.d(TAG, "Parent device - starting DailyTimerResetService only");
                    
                    // Parent only needs timer reset service
                    try {
                        Intent serviceIntent = new Intent(context, DailyTimerResetService.class);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent);
                        } else {
                            context.startService(serviceIntent);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "❌ Failed to start DailyTimerResetService: " + e.getMessage());
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "❌ Error in BootReceiver: " + e.getMessage());
            }
        }
    }
}
