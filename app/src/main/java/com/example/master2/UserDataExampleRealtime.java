package com.example.master2;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.master2.models.User;

public class UserDataExampleRealtime extends AppCompatActivity {
    private static final String TAG = "UserDataExampleRealtime";
    private UserDataManagerRealtime userDataManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        userDataManager = new UserDataManagerRealtime();
        
        // Example 1: Fetch current user data
        fetchCurrentUserData();
        
        // Example 2: Listen to user data changes
        // listenToUserDataChanges();
        
        // Example 3: Update user data
        // updateUserData();
    }

    private void fetchCurrentUserData() {
        userDataManager.fetchCurrentUserData(new UserDataManagerRealtime.UserDataCallback() {
            @Override
            public void onUserDataReceived(User user) {
                Log.d(TAG, "User data received: " + user.getUsername());
                Toast.makeText(UserDataExampleRealtime.this, 
                    "Welcome " + user.getUsername(), Toast.LENGTH_SHORT).show();
                
                // Use the user data here
                String username = user.getUsername();
                String email = user.getEmail();
                String userType = user.getUserType();
                String phone = user.getPhone();
                
                Log.d(TAG, "User Details:");
                Log.d(TAG, "- Username: " + username);
                Log.d(TAG, "- Email: " + email);
                Log.d(TAG, "- Phone: " + phone);
                Log.d(TAG, "- User Type: " + userType);
                Log.d(TAG, "- Created: " + new java.util.Date(user.getCreatedAt()));
                Log.d(TAG, "- Last Updated: " + new java.util.Date(user.getLastUpdated()));
                
                // Update UI or perform other operations
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error fetching user data: " + error);
                Toast.makeText(UserDataExampleRealtime.this, 
                    "Error: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void fetchUserDataById(String userId) {
        userDataManager.fetchUserDataById(userId, new UserDataManagerRealtime.UserDataCallback() {
            @Override
            public void onUserDataReceived(User user) {
                Log.d(TAG, "User data received for ID " + userId + ": " + user.getUsername());
                Toast.makeText(UserDataExampleRealtime.this, 
                    "Found user: " + user.getUsername(), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error fetching user data for ID " + userId + ": " + error);
                Toast.makeText(UserDataExampleRealtime.this, 
                    "Error: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void listenToUserDataChanges() {
        String userId = "your_user_id_here"; // Replace with actual user ID
        userDataManager.listenToUserData(userId, new UserDataManagerRealtime.UserDataCallback() {
            @Override
            public void onUserDataReceived(User user) {
                Log.d(TAG, "User data updated: " + user.getUsername());
                Toast.makeText(UserDataExampleRealtime.this, 
                    "User updated: " + user.getUsername(), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error listening to user data: " + error);
                Toast.makeText(UserDataExampleRealtime.this, 
                    "Error: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateUserData() {
        // Create a user object with updated data
        User updatedUser = new User("UpdatedUsername", "updatedemail@example.com", 
            "+911234567890", "parent", "user_id");
        
        userDataManager.updateUserData(updatedUser, new UserDataManagerRealtime.UserDataCallback() {
            @Override
            public void onUserDataReceived(User user) {
                Log.d(TAG, "User data updated successfully");
                Toast.makeText(UserDataExampleRealtime.this, 
                    "User data updated", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Error updating user data: " + error);
                Toast.makeText(UserDataExampleRealtime.this, 
                    "Error updating: " + error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void saveNewUserData() {
        userDataManager.saveUserData("NewUser", "newuser@example.com", 
            "+919876543210", "parent", new UserDataManagerRealtime.UserDataCallback() {
                @Override
                public void onUserDataReceived(User user) {
                    Log.d(TAG, "New user data saved successfully");
                    Toast.makeText(UserDataExampleRealtime.this, 
                        "New user saved: " + user.getUsername(), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "Error saving new user data: " + error);
                    Toast.makeText(UserDataExampleRealtime.this, 
                        "Error saving: " + error, Toast.LENGTH_SHORT).show();
                }
            });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Remove listeners when activity is destroyed
        String userId = "your_user_id_here"; // Replace with actual user ID
        userDataManager.removeUserDataListener(userId);
    }
} 