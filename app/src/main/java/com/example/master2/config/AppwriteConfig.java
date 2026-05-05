package com.example.master2.config;

import android.content.Context;
import io.appwrite.Client;
import io.appwrite.services.Account;
import io.appwrite.services.Databases;
import io.appwrite.services.Functions;

/**
 * Appwrite Configuration Class
 * Sets up the Appwrite client and services for the application
 */
public class AppwriteConfig {
    
    // Appwrite Configuration Constants
    private static final String ENDPOINT = "https://nyc.cloud.appwrite.io/v1"; // Your Appwrite endpoint
    private static final String PROJECT_ID = "6954c478002421753c93"; // Your Appwrite project ID
    private static final String API_KEY = "standard_adc99311cbe951697dc88a54cafafece4d80520adf4c385389c732ccafcb526fab3104fbaeda32eeeb4fb28cfb8a6ca2ba6729f3a253994c494f6ec724c72d49d720c61943937d87c408fdce1634cc2cd681309f4092a02e66a99982c814d09381efa90dc80b0195d260e93a55b922a00c32d117ec580a033e7b2191b4e50b34"; // Server API key for function execution
    private static final String DATABASE_ID = "familyguard_db"; // Your database ID
    private static final String USERS_COLLECTION_ID = "users_collection"; // Users collection ID
    private static final String OTP_COLLECTION_ID = "otp_verification_collection"; // OTP collection ID
    private static final String EMAIL_FUNCTION_ID = "6954c61d0039f7129141"; // Email function ID
    
    // Singleton instance
    private static AppwriteConfig instance;
    private Client client;
    private Account account;
    private Databases databases;
    private Functions functions;
    private Context context;
    
    private AppwriteConfig(Context context) {
        this.context = context.getApplicationContext();
        initializeClient();
    }
    
    public static synchronized AppwriteConfig getInstance(Context context) {
        if (instance == null) {
            instance = new AppwriteConfig(context);
        }
        return instance;
    }
    
    // For backward compatibility - but requires initialization with context first
    public static synchronized AppwriteConfig getInstance() {
        if (instance == null) {
            throw new IllegalStateException("AppwriteConfig must be initialized with context first. Call getInstance(Context) first.");
        }
        return instance;
    }
    
    private void initializeClient() {
        try {
            android.util.Log.d("AppwriteConfig", "🔧 Initializing Appwrite client...");
            
            // Initialize Appwrite Client
            client = new Client(context, ENDPOINT, PROJECT_ID);
            
            // Initialize Services
            account = new Account(client);
            databases = new Databases(client);
            functions = new Functions(client);
            
            android.util.Log.d("AppwriteConfig", "✅ Appwrite client initialized successfully");
            android.util.Log.d("AppwriteConfig", "📡 Endpoint: " + ENDPOINT);
            android.util.Log.d("AppwriteConfig", "🗂️ Project ID: " + PROJECT_ID);
            
        } catch (Exception e) {
            android.util.Log.e("AppwriteConfig", "❌ Error initializing Appwrite client: " + e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Appwrite client", e);
        }
    }
    
    // Getters
    public Client getClient() {
        return client;
    }
    
    public Account getAccount() {
        return account;
    }
    
    public Databases getDatabases() {
        return databases;
    }
    
    public Functions getFunctions() {
        return functions;
    }
    
    public String getDatabaseId() {
        return DATABASE_ID;
    }
    
    public String getUsersCollectionId() {
        return USERS_COLLECTION_ID;
    }
    
    public String getOtpCollectionId() {
        return OTP_COLLECTION_ID;
    }
    
    public String getEmailFunctionId() {
        return EMAIL_FUNCTION_ID;
    }
    
    public String getApiKey() {
        return API_KEY;
    }
    
    public String getEndpoint() {
        return ENDPOINT;
    }
    
    public String getProjectId() {
        return PROJECT_ID;
    }
}