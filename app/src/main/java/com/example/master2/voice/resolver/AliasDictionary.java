package com.example.master2.voice.resolver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class AliasDictionary {
    private AliasDictionary() {
    }

    public static Map<String, String> defaultAliases() {
        Map<String, String> aliases = new HashMap<>();
        aliases.put("insta", "instagram");
        aliases.put("ig", "instagram");
        aliases.put("yt", "youtube");
        aliases.put("you tube", "youtube");
        aliases.put("wa", "whatsapp");
        aliases.put("snap", "snapchat");
        aliases.put("fb", "facebook");
        return aliases;
    }

    public static String normalizeAlias(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.US).trim();
    }
}
