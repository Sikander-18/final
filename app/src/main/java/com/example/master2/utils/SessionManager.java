package com.example.master2.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Session Manager for handling user sessions and preferences
 */
public class SessionManager {
    
    private static final String PREF_NAME = "SentinelSession";
    private static final String KEY_IS_LOGGED_IN = "isLoggedIn";
    private static final String KEY_USER_EMAIL = "userEmail";
    private static final String KEY_USER_TYPE = "userType";
    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PHONE = "phone";
    
    private SharedPreferences pref;
    private SharedPreferences.Editor editor;
    private Context context;
    
    public SessionManager(Context context) {
        this.context = context;
        pref = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        editor = pref.edit();
    }
    
    /**
     * Create login session
     */
    public void createLoginSession(String email, String userType, String userId, String username, String phone) {
        editor.putBoolean(KEY_IS_LOGGED_IN, true);
        editor.putString(KEY_USER_EMAIL, email);
        editor.putString(KEY_USER_TYPE, userType);
        editor.putString(KEY_USER_ID, userId);
        editor.putString(KEY_USERNAME, username);
        editor.putString(KEY_PHONE, phone);
        editor.commit();
    }
    
    /**
     * Check if user is logged in
     */
    public boolean isLoggedIn() {
        return pref.getBoolean(KEY_IS_LOGGED_IN, false);
    }
    
    /**
     * Get user email
     */
    public String getUserEmail() {
        return pref.getString(KEY_USER_EMAIL, null);
    }
    
    /**
     * Get user type
     */
    public String getUserType() {
        return pref.getString(KEY_USER_TYPE, null);
    }
    
    /**
     * Get user ID
     */
    public String getUserId() {
        return pref.getString(KEY_USER_ID, null);
    }
    
    /**
     * Get username
     */
    public String getUsername() {
        return pref.getString(KEY_USERNAME, null);
    }
    
    /**
     * Get phone number
     */
    public String getPhone() {
        return pref.getString(KEY_PHONE, null);
    }
    
    /**
     * Clear session data
     */
    public void logoutUser() {
        editor.clear();
        editor.commit();
    }
}