package com.example.master2.voice.dictionary;

/**
 * Central manager that initializes and provides access to all dictionaries.
 * Call init() once when the VoiceAssistantActivity starts.
 */
public class DictionaryManager {

    private static DictionaryManager instance;

    private final AppDictionary appDictionary;

    private DictionaryManager() {
        appDictionary = new AppDictionary();
    }

    public static synchronized DictionaryManager getInstance() {
        if (instance == null) {
            instance = new DictionaryManager();
        }
        return instance;
    }

    /**
     * Initialize the dynamic dictionaries. Call this once with the child device ID.
     * The static dictionaries (IntentDictionary) don't need initialization.
     */
    public void init(String childDeviceId) {
        appDictionary.syncFromFirebase(childDeviceId);
    }

    public AppDictionary getAppDictionary() {
        return appDictionary;
    }

    /**
     * Convenience: check if a token is a known keyword (not an app name).
     * Delegates to IntentDictionary.
     */
    public boolean isKeyword(String token) {
        return IntentDictionary.isKnownKeyword(token);
    }
}
