package com.example.master2.voice.resolver;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.example.master2.voice.model.ResolvedAppTarget;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class VoiceAppResolver {
    private final DatabaseReference rootRef = FirebaseDatabase.getInstance().getReference();

    public interface ResolveCallback {
        void onResolved(ResolveResult result);

        void onError(String error);
    }

    public static class ResolveResult {
        public final List<ResolvedAppTarget> resolvedTargets = new ArrayList<>();
        public final Map<String, List<String>> unresolvedAliasesByChild = new HashMap<>();
    }

    public void resolveAppsForChildren(
            @NonNull String parentUserId,
            @NonNull List<String> childIds,
            @NonNull List<String> aliases,
            @NonNull ResolveCallback callback) {
        if (childIds.isEmpty() || aliases.isEmpty()) {
            callback.onError("Child and app alias are required.");
            return;
        }

        ResolveResult output = new ResolveResult();
        resolveChildSequentially(parentUserId, childIds, aliases, 0, output, callback);
    }

    private void resolveChildSequentially(String parentUserId, List<String> childIds, List<String> aliases, int index,
            ResolveResult output, ResolveCallback callback) {
        if (index >= childIds.size()) {
            callback.onResolved(output);
            return;
        }
        String childId = childIds.get(index);
        resolveForSingleChild(parentUserId, childId, aliases, new ResolveCallback() {
            @Override
            public void onResolved(ResolveResult result) {
                output.resolvedTargets.addAll(result.resolvedTargets);
                output.unresolvedAliasesByChild.putAll(result.unresolvedAliasesByChild);
                resolveChildSequentially(parentUserId, childIds, aliases, index + 1, output, callback);
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    private void resolveForSingleChild(
            String parentUserId,
            String childId,
            List<String> aliases,
            ResolveCallback callback) {
        DatabaseReference appRef = rootRef.child("device_apps").child(childId);
        DatabaseReference aliasRef = rootRef.child("voice_assistant_aliases").child(parentUserId).child(childId);

        aliasRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot aliasSnap) {
                Map<String, String> savedAliases = new HashMap<>();
                for (DataSnapshot child : aliasSnap.getChildren()) {
                    String key = child.getKey();
                    String value = child.getValue(String.class);
                    if (!TextUtils.isEmpty(key) && !TextUtils.isEmpty(value)) {
                        savedAliases.put(normalize(key), value);
                    }
                }

                appRef.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot appSnap) {
                        List<Map<String, String>> apps = flattenApps(appSnap);
                        ResolveResult result = new ResolveResult();
                        List<String> unresolved = new ArrayList<>();
                        for (String alias : aliases) {
                            ResolvedAppTarget target = resolveSingleAlias(childId, alias, apps, savedAliases);
                            if (target == null) {
                                unresolved.add(alias);
                            } else {
                                result.resolvedTargets.add(target);
                            }
                        }
                        if (!unresolved.isEmpty()) {
                            result.unresolvedAliasesByChild.put(childId, unresolved);
                        }
                        callback.onResolved(result);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        callback.onError("Failed to load apps: " + error.getMessage());
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                callback.onError("Failed to load aliases: " + error.getMessage());
            }
        });
    }

    private ResolvedAppTarget resolveSingleAlias(String childId, String aliasRaw, List<Map<String, String>> apps,
            Map<String, String> savedAliases) {
        String alias = normalize(aliasRaw);
        String seededAlias = AliasDictionary.defaultAliases().getOrDefault(alias, alias);
        if (savedAliases.containsKey(alias)) {
            String packageName = savedAliases.get(alias);
            for (Map<String, String> app : apps) {
                if (packageName.equals(app.get("packageName"))) {
                    return new ResolvedAppTarget(childId, app.get("packageName"), app.get("name"), aliasRaw, 1.0, false);
                }
            }
        }

        for (Map<String, String> app : apps) {
            if (seededAlias.equals(normalize(app.get("name"))) || seededAlias.equals(normalize(shortName(app.get("name"))))) {
                return new ResolvedAppTarget(childId, app.get("packageName"), app.get("name"), aliasRaw, 0.98, false);
            }
        }

        for (Map<String, String> app : apps) {
            String appNameNorm = normalize(app.get("name"));
            if (appNameNorm.startsWith(seededAlias) || seededAlias.startsWith(appNameNorm)) {
                return new ResolvedAppTarget(childId, app.get("packageName"), app.get("name"), aliasRaw, 0.85, false);
            }
        }

        for (Map<String, String> app : apps) {
            String packageName = app.get("packageName");
            if (!TextUtils.isEmpty(packageName) && normalize(packageName).contains(seededAlias)) {
                return new ResolvedAppTarget(childId, packageName, app.get("name"), aliasRaw, 0.75, false);
            }
        }
        return null;
    }

    private List<Map<String, String>> flattenApps(DataSnapshot appSnap) {
        List<Map<String, String>> apps = new ArrayList<>();
        for (DataSnapshot child : appSnap.getChildren()) {
            String packageName = value(child, "packageName");
            String appName = value(child, "name");
            if (!TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(appName)) {
                Map<String, String> entry = new HashMap<>();
                entry.put("packageName", packageName);
                entry.put("name", appName);
                apps.add(entry);
            }
        }
        return apps;
    }

    private String value(DataSnapshot snapshot, String key) {
        String value = snapshot.child(key).getValue(String.class);
        if (!TextUtils.isEmpty(value)) {
            return value;
        }
        if ("packageName".equals(key)) {
            return snapshot.getKey();
        }
        return null;
    }

    private String shortName(String appName) {
        if (TextUtils.isEmpty(appName)) {
            return "";
        }
        String[] parts = appName.split("\\s+");
        return parts.length == 0 ? appName : parts[0];
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }
}
