package com.example.master2;

import android.util.Log;
import com.example.master2.models.User;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;

public class UserDataManager {
    private static final String TAG = "UserDataManager";
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;

    public UserDataManager() {
        db = FirebaseFirestore.getInstance();
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

        db.collection("users").document(userId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        User user = documentSnapshot.toObject(User.class);
                        if (user != null) {
                            callback.onUserDataReceived(user);
                        } else {
                            callback.onError("Failed to parse user data");
                        }
                    } else {
                        callback.onError("User data not found");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching user data: " + e.getMessage());
                    callback.onError("Failed to fetch user data: " + e.getMessage());
                });
    }

    public void fetchUserDataById(String userId, UserDataCallback callback) {
        if (userId == null || userId.isEmpty()) {
            callback.onError("Invalid user ID");
            return;
        }

        db.collection("users").document(userId)
                .get()
                .addOnSuccessListener(documentSnapshot -> {
                    if (documentSnapshot.exists()) {
                        User user = documentSnapshot.toObject(User.class);
                        if (user != null) {
                            callback.onUserDataReceived(user);
                        } else {
                            callback.onError("Failed to parse user data");
                        }
                    } else {
                        callback.onError("User data not found");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error fetching user data: " + e.getMessage());
                    callback.onError("Failed to fetch user data: " + e.getMessage());
                });
    }

    public void updateUserData(User user, UserDataCallback callback) {
        String userId = mAuth.getCurrentUser() != null ? mAuth.getCurrentUser().getUid() : null;
        
        if (userId == null) {
            callback.onError("User not authenticated");
            return;
        }

        db.collection("users").document(userId)
                .set(user)
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "User data updated successfully");
                    callback.onUserDataReceived(user);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Error updating user data: " + e.getMessage());
                    callback.onError("Failed to update user data: " + e.getMessage());
                });
    }
} 