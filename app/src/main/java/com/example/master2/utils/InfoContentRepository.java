package com.example.master2.utils;

public class InfoContentRepository {

    public static final String KEY_HELP = "help";
    public static final String KEY_PRIVACY = "privacy";
    public static final String KEY_TERMS = "terms";

    public static String getTitle(String key) {
        switch (key) {
            case KEY_HELP:
                return "Help & Support";
            case KEY_PRIVACY:
                return "Privacy Policy";
            case KEY_TERMS:
                return "Terms of Service";
            default:
                return "Information";
        }
    }

    public static String getContent(String key) {
        switch (key) {
            case KEY_HELP:
                return getHelpContent();
            case KEY_PRIVACY:
                return getPrivacyContent();
            case KEY_TERMS:
                return getTermsContent();
            default:
                return "Content not found.";
        }
    }

    private static String getHelpContent() {
        return "Welcome to SParent Help & Support.\n\n" +
                "GUIDE TO PERMISSIONS\n\n" +
                "To ensure your child's digital safety, SParent requires specific permissions on the child's device. Here is why we use them:\n\n"
                +
                "1. Accessibility Service\n" +
                "• Purpose: This is the core of our monitoring system.\n" +
                "• Usage: It allows us to detect which app is currently running, block restricted apps, and monitor web activity for safety.\n"
                +
                "• Note: We never use this to read personal messages or passwords.\n\n" +
                "2. Usage Stats Permission\n" +
                "• Purpose: To track screen time.\n" +
                "• Usage: Generates the usage reports you see on your dashboard, helping you identify healthy digital habits.\n\n"
                +
                "3. Overlay Permission (Display over other apps)\n" +
                "• Purpose: For blocking.\n" +
                "• Usage: Displays the \"Time's Up\" or \"Blocked\" screen when limits are reached.\n\n" +
                "TROUBLESHOOTING\n\n" +
                "• Device Disconnected?\n" +
                "If a child device appears offline, ensure the child has an active internet connection. If issues persist, simply reconnect using the QR code in the \"Add Device\" section.\n\n"
                +
                "• App Not Blocking?\n" +
                "Check if the Accessibility Service was disabled on the child's device. Android may disable it to save battery. Re-enable it in the device settings.\n\n"
                +
                "NEED MORE HELP?\n" +
                "Contact our support team at support@sparent.app.";
    }

    private static String getPrivacyContent() {
        return "Privacy Policy\nLast Updated: January 2026\n\n" +
                "At SParent, we prioritize the privacy and security of your family's data. We believe in transparency and protection.\n\n"
                +
                "1. DATA COLLECTION\n" +
                "We collect only the data necessary to provide parental control features:\n" +
                "• Device Information: Model, OS version, and unique identifiers for connection.\n" +
                "• App Usage Data: Time spent in applications.\n" +
                "• Installed Apps List: To allow you to manage and block specific apps.\n" +
                "• Account Info: Your email and phone number for account management.\n\n" +
                "2. HOW WE USE YOUR DATA\n" +
                "• Functionality: To execute your commands (e.g., blocking an app, setting a timer).\n" +
                "• Reporting: To generate usage statistics for your review.\n" +
                "• Improvement: To improved app performance and fix bugs.\n\n" +
                "3. DATA SECURITY\n" +
                "• Encryption: All data transmitted between devices and our servers is encrypted using industry-standard protocols.\n"
                +
                "• Storage: Your data is stored on secure Firebase servers with strict access controls.\n" +
                "• No Selling: We NEVER sell, trade, or rent your personal identification information to others.\n\n" +
                "4. CHILD SAFETY\n" +
                "We operate in compliance with COPPA (Children's Online Privacy Protection Act). Our services are intended for use by parents/guardians to monitor their children.\n\n"
                +
                "5. YOUR RIGHTS\n" +
                "You have the right to request deletion of all your data at any time. Simply use the 'Delete Account' feature request or contact support.\n\n"
                +
                "By using SParent, you trust us with your family's digital wellbeing, and we are committed to honoring that trust.";
    }

    private static String getTermsContent() {
        return "Terms of Service\n\n" +
                "1. ACCEPTANCE OF TERMS\n" +
                "By downloading or using SParent, you agree to these legal terms. If you do not agree, please do not use our services.\n\n"
                +
                "2. INTENDED USE\n" +
                "SParent is designed solely for legal parental control usage. You may only install this software on devices owned by you or your minor children.\n\n"
                +
                "• Prohibited Use: Installing this app on a device belonging to someone else (including a spouse or adult employee) without their explicit consent is strictly prohibited and may violate local laws.\n\n"
                +
                "3. USER CONDUCT\n" +
                "You agree not to misuse our services. You are responsible for maintaining the confidentiality of your account credentials.\n\n"
                +
                "4. LIMITATION OF LIABILITY\n" +
                "While we strive for reliability, SParent is provided \"as is\". We are not liable for any direct or indirect damages arising from the use or inability to use the application.\n\n"
                +
                "5. SERVICE CHANGES\n" +
                "We reserve the right to modify or discontinue features to improve the service at any time.\n\n" +
                "6. TERMINATION\n" +
                "We may terminate or suspend your account if you violate these terms or engage in illegal activity using our platform.\n\n"
                +
                "Thank you for responsible parenting with SParent.";
    }
}
