package com.example.master2;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.master2.models.User;

public class UserDataExample extends AppCompatActivity {
    private static final String TAG = "UserDataExample";
    private UserDataManager userDataManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        userDataManager = new UserDataManager();
        
        // Example 1: Fetch current user data
        fetchCurrentUserData();
        
        // Example 2: Fetch user data by ID
        // fetchUserDataById("some_user_id");
    }

    private void fetchCurrentUserData() {
        userDataManager.fetchCurrentUserData(new UserDataManager.UserDataCallback() {
            @Override
            public void onUserDataReceived(User user) {
                Log.d(TAG, "User data received: " + user.getUsername());
                Toast.makeText(UserDataExample.this, 
                    "Welcome " + user.getUsername(), Toast.LENGTH_SHORT).show();
                
                // Use the user data here
                String username = user.getUsername();
                String email = user.getEmail();
                String userType = user.getUserType();
                
                // Update UI or perform other operations
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error fetching user data: " + error);
                Toast.makeText(UserDataExample.this, 
                    "Error: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchUserDataById(String userId) {
        userDataManager.fetchUserDataById(userId, new UserDataManager.UserDataCallback() {
            @Override
            public void onUserDataReceived(User user) {
                Log.d(TAG, "User data received for ID " + userId + ": " + user.getUsername());
                // Handle the user data
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error fetching user data for ID " + userId + ": " + error);
                Toast.makeText(UserDataExample.this, 
                    "Error: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateUserData() {
        // Create a user object with updated data
        User updatedUser = new User("NewUsername", "newemail@example.com", 
            "+911234567890", "parent", "user_id");
        
        userDataManager.updateUserData(updatedUser, new UserDataManager.UserDataCallback() {
            @Override
            public void onUserDataReceived(User user) {
                Log.d(TAG, "User data updated successfully");
                Toast.makeText(UserDataExample.this, 
                    "User data updated", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error updating user data: " + error);
                Toast.makeText(UserDataExample.this, 
                    "Error updating: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }
} 