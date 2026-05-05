package com.example.master2;

import android.util.Log;
import com.example.master2.models.User;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;

public class UserDataManagerRealtime {
    private static final String TAG = "UserDataManagerRealtime";
    private DatabaseReference databaseRef;
    private FirebaseAuth mAuth;

    public UserDataManagerRealtime() {
        databaseRef = FirebaseDatabase.getInstance().getReference();
        mAuth = FirebaseAuth.getInstance();
    }

    public interface UserDataCallback {
        void onUserDataReceived(User user);
        void onError(String error);
    }

    public void fetchCurrentUserData(UserDataCallback callback) {
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;
        
        if (userId == null) {
            callback.onError("User not authenticated");
            return;
        }

        DatabaseReference userRef = databaseRef.child("users").child(userId);
        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    User user = dataSnapshot.getValue(User.class);
                    if (user != null) {
                        callback.onUserDataReceived(user);
                    } else {
                        callback.onError("Failed to parse user data");
                    }
                } else {
                    callback.onError("User data not found");
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error fetching user data: " + databaseError.getMessage());
                callback.onError("Failed to fetch user data: " + databaseError.getMessage());
            }
        });
    }

    public void fetchUserDataById(String userId, UserDataCallback callback) {
        if (userId == null || userId.isEmpty()) {
            callback.onError("Invalid user ID");
            return;
        }

        DatabaseReference userRef = databaseRef.child("users").child(userId);
        userRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    User user = dataSnapshot.getValue(User.class);
                    if (user != null) {
                        callback.onUserDataReceived(user);
                    } else {
                        callback.onError("Failed to parse user data");
                    }
                } else {
                    callback.onError("User data not found");
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error fetching user data: " + databaseError.getMessage());
                callback.onError("Failed to fetch user data: " + databaseError.getMessage());
            }
        });
    }

    public void updateUserData(User user, UserDataCallback callback) {
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;
        
        if (userId == null) {
            callback.onError("User not authenticated");
            return;
        }

        DatabaseReference userRef = databaseRef.child("users").child(userId);
        userRef.setValue(user)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "User data updated successfully");
                    callback.onUserDataReceived(user);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error updating user data: " + e.getMessage());
                    callback.onError("Failed to update user data: " + e.getMessage());
                });
    }

    public void saveUserData(String username, String email, String phone, String userType, UserDataCallback callback) {
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;
        
        if (userId == null) {
            callback.onError("User not authenticated");
            return;
        }

        User user = new User(username, email, phone, userType, userId);
        DatabaseReference userRef = databaseRef.child("users").child(userId);
        userRef.setValue(user)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "User data saved successfully");
                    callback.onUserDataReceived(user);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error saving user data: " + e.getMessage());
                    callback.onError("Failed to save user data: " + e.getMessage());
                });
    }

    public void listenToUserData(String userId, UserDataCallback callback) {
        if (userId == null || userId.isEmpty()) {
            callback.onError("Invalid user ID");
            return;
        }

        DatabaseReference userRef = databaseRef.child("users").child(userId);
        userRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot dataSnapshot) {
                if (dataSnapshot.exists()) {
                    User user = dataSnapshot.getValue(User.class);
                    if (user != null) {
                        callback.onUserDataReceived(user);
                    } else {
                        callback.onError("Failed to parse user data");
                    }
                } else {
                    callback.onError("User data not found");
                }
            }

            @Override
            public void onCancelled(DatabaseError databaseError) {
                Log.e(TAG, "Error listening to user data: " + databaseError.getMessage());
                callback.onError("Failed to listen to user data: " + databaseError.getMessage());
            }
        });
    }

    public void removeUserDataListener(String userId) {
        if (userId != null && !userId.isEmpty()) {
            DatabaseReference userRef = databaseRef.child("users").child(userId);
            userRef.removeEventListener(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot dataSnapshot) {
                    // This will be called when data changes
                }

                @Override
                public void onCancelled(DatabaseError databaseError) {
                    // This will be called when the listener is cancelled
                }
            });
        }
    }
} 