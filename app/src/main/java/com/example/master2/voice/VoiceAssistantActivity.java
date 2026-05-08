package com.example.master2.voice;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.master2.R;
import com.example.master2.SessionManager;
import com.example.master2.adapters.AssistantChatAdapter;
import com.example.master2.voice.model.AssistantChatMessage;
import com.example.master2.voice.model.AssistantRule;
import com.example.master2.voice.model.ConflictResult;
import com.example.master2.voice.model.ResolvedAppTarget;
import com.example.master2.voice.model.ScheduleSpec;
import com.example.master2.voice.model.VoiceCommandIntent;
import com.example.master2.voice.parser.RuleBasedCommandParser;
import com.example.master2.voice.resolver.VoiceAppResolver;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Voice Assistant Activity with chat-style UI.
 * Uses Final's existing Rule-based backend for parsing and execution.
 */
public class VoiceAssistantActivity extends AppCompatActivity implements AssistantChatAdapter.ActionCallback {

    private static final String TAG = "VoiceAssistantActivity";
    private static final int RECORD_AUDIO_PERMISSION_CODE = 1;

    // Views
    private RecyclerView rvMessages;
    private EditText etCommand;
    private ImageButton btnMic, btnSend;

    // Adapter
    private AssistantChatAdapter adapter;
    private final List<AssistantChatMessage> messageList = new ArrayList<>();

    // Parser (existing Final logic)
    private final RuleBasedCommandParser parser = new RuleBasedCommandParser();
    private final VoiceAppResolver appResolver = new VoiceAppResolver();
    private final VoiceAssistantConflictDetector conflictDetector = new VoiceAssistantConflictDetector();

    // Session & state
    private SessionManager sessionManager;
    private String parentUserId;
    private String currentChildId;
    private String childDeviceName;

    // Voice flow state
    private TextToSpeech textToSpeech;
    private ActivityResultLauncher<Intent> speechResultLauncher;
    private boolean wasLastInputVoice = false;
    private boolean expectingVoiceConfirmation = false;
    private VoiceCommandIntent pendingIntent = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_assistant);

        sessionManager = new SessionManager(this);
        parentUserId = sessionManager.getUserId();
        currentChildId = getIntent().getStringExtra("childDeviceId");
        childDeviceName = getIntent().getStringExtra("deviceName");
        if (TextUtils.isEmpty(currentChildId)) {
            currentChildId = sessionManager.getChildDeviceId();
        }
        if (TextUtils.isEmpty(childDeviceName)) {
            childDeviceName = "Child";
        }

        if (TextUtils.isEmpty(currentChildId)) {
            Toast.makeText(this, "No child device selected", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        initViews();
        setupSpeechLauncher();
        setupTextToSpeech();

        addBotMessage("Hi! I'm your Voice Assistant. I can block or unblock apps on "
                + childDeviceName + "'s device. Try saying \"Block Instagram\" or tap a suggestion below.");
    }

    // ── Views ──────────────────────────────────────────────────────────────

    private void initViews() {
        Toolbar toolbar = findViewById(R.id.toolbarAssistant);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Voice Assistant — " + childDeviceName);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        rvMessages = findViewById(R.id.rvMessages);
        etCommand = findViewById(R.id.etCommand);
        btnMic = findViewById(R.id.btnMic);
        btnSend = findViewById(R.id.btnSend);

        adapter = new AssistantChatAdapter(messageList, this);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(adapter);

        // Toggle mic / send button
        etCommand.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                boolean hasText = s != null && s.toString().trim().length() > 0;
                btnSend.setVisibility(hasText ? View.VISIBLE : View.GONE);
                btnMic.setVisibility(hasText ? View.GONE : View.VISIBLE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        btnSend.setOnClickListener(v -> {
            wasLastInputVoice = false;
            expectingVoiceConfirmation = false;
            pendingIntent = null;
            String text = etCommand.getText().toString().trim();
            if (!text.isEmpty()) {
                processCommand(text);
                etCommand.setText("");
            }
        });

        btnMic.setOnClickListener(v -> {
            wasLastInputVoice = true;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, RECORD_AUDIO_PERMISSION_CODE);
            } else {
                startListening();
            }
        });

        // Chip shortcuts
        findViewById(R.id.chipBlockInstagram).setOnClickListener(v -> {
            wasLastInputVoice = false;
            processCommand("block instagram");
        });
        findViewById(R.id.chipBlockYoutube).setOnClickListener(v -> {
            wasLastInputVoice = false;
            processCommand("block youtube");
        });
        findViewById(R.id.chipNightBlock).setOnClickListener(v -> {
            wasLastInputVoice = false;
            processCommand("whatsapp ko raat 10 se subah 7 tak block karo");
        });
    }

    // ── Speech ─────────────────────────────────────────────────────────────

    private void setupSpeechLauncher() {
        speechResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        ArrayList<String> matches = result.getData()
                                .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                        if (matches != null && !matches.isEmpty()) {
                            processCommand(matches.get(0));
                        }
                    } else {
                        if (expectingVoiceConfirmation) {
                            expectingVoiceConfirmation = false;
                            pendingIntent = null;
                            addBotMessage("Voice timed out. Command cancelled.");
                        }
                    }
                });
    }

    private void setupTextToSpeech() {
        textToSpeech = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int langResult = textToSpeech.setLanguage(new Locale("en", "IN"));
                if (langResult == TextToSpeech.LANG_MISSING_DATA
                        || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech.setLanguage(Locale.US);
                }
                textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String id) {}
                    @Override public void onDone(String id) {
                        if ("confirm_prompt".equals(id) && expectingVoiceConfirmation) {
                            mainHandler.post(() -> startListening());
                        }
                    }
                    @Override public void onError(String id) {}
                });
            }
        });
    }

    private void startListening() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening...");
        try {
            speechResultLauncher.launch(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Voice recognition not available", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Command Processing ─────────────────────────────────────────────────

    private void processCommand(String text) {
        addUserMessage(text);

        // Handle voice confirmation flow
        if (expectingVoiceConfirmation && pendingIntent != null) {
            String lower = text.toLowerCase();
            if (lower.contains("yes") || lower.contains("confirm") || lower.contains("haan") || lower.contains("ok")) {
                expectingVoiceConfirmation = false;
                onConfirm(pendingIntent);
                pendingIntent = null;
            } else if (lower.contains("no") || lower.contains("cancel") || lower.contains("nahi") || lower.contains("stop")) {
                expectingVoiceConfirmation = false;
                onCancel();
                pendingIntent = null;
            } else {
                addBotMessage("Say yes to confirm or no to cancel.", "confirm_prompt");
            }
            return;
        }

        // Parse using Final's existing parser
        VoiceCommandIntent intent = parser.parse(text);

        if (!intent.valid) {
            addBotMessage("I didn't quite understand that. Try saying \"Block Instagram\" or \"Lock YouTube at 10 pm\".");
            return;
        }

        if (intent.appAliases.isEmpty()) {
            addBotMessage("I understood you want to " + intent.action + " but couldn't find which app. Try again with an app name.");
            return;
        }

        // Build schedule label for the card
        String scheduleLabel = buildScheduleLabel(intent);

        // Show a clean confirmation card (no raw parse data exposed)
        String appsText = TextUtils.join(", ", intent.appAliases);
        AssistantChatMessage card = new AssistantChatMessage(
                AssistantChatMessage.Sender.ASSISTANT,
                intent.action,
                appsText,
                null,
                scheduleLabel,
                intent
        );
        messageList.add(card);
        adapter.notifyItemInserted(messageList.size() - 1);
        rvMessages.smoothScrollToPosition(messageList.size() - 1);

        if (wasLastInputVoice) {
            pendingIntent = intent;
            expectingVoiceConfirmation = true;
            String confirmSpeech = "Do you want to " + intent.action + " " + appsText + "? Say yes or no.";
            speak(confirmSpeech, "confirm_prompt");
        } else {
            pendingIntent = intent;
        }
    }

    private String buildScheduleLabel(VoiceCommandIntent intent) {
        SimpleDateFormat sdf = new SimpleDateFormat("hh:mm a, MMM dd", Locale.getDefault());
        ScheduleSpec spec = intent.scheduleSpec;

        if (ScheduleSpec.TYPE_TIME_RANGE.equals(spec.scheduleType)) {
            return "From " + sdf.format(new Date(spec.startEpochMs))
                    + " to " + sdf.format(new Date(spec.endEpochMs));
        } else if (ScheduleSpec.TYPE_AT_TIME.equals(spec.scheduleType)) {
            return "Scheduled for " + sdf.format(new Date(spec.startEpochMs));
        } else if (ScheduleSpec.TYPE_AFTER_DURATION.equals(spec.scheduleType)) {
            long mins = spec.delayMs / 60000;
            return "After " + mins + " minute" + (mins != 1 ? "s" : "");
        } else {
            return "Will execute immediately";
        }
    }

    // ── Adapter Callbacks ──────────────────────────────────────────────────

    @Override
    public void onConfirm(VoiceCommandIntent intent) {
        if (intent == null) return;
        addBotMessage("Processing command...");

        List<String> childIds = new ArrayList<>();
        childIds.add(currentChildId);

        appResolver.resolveAppsForChildren(parentUserId, childIds, intent.appAliases,
                new VoiceAppResolver.ResolveCallback() {
                    @Override
                    public void onResolved(VoiceAppResolver.ResolveResult result) {
                        if (result.resolvedTargets.isEmpty()) {
                            addBotMessage("Could not find those apps on " + childDeviceName + "'s device.");
                            return;
                        }
                        if (!result.unresolvedAliasesByChild.isEmpty()) {
                            addBotMessage("Some apps couldn't be found: " + result.unresolvedAliasesByChild);
                        }
                        AssistantRule rule = buildRule(intent, childIds, result.resolvedTargets);
                        detectConflictAndSave(rule);
                    }

                    @Override
                    public void onError(String error) {
                        addBotMessage("Error resolving apps: " + error);
                    }
                });
    }

    @Override
    public void onCancel() {
        addBotMessage("Command cancelled.");
    }

    // ── Rule Building (uses Final's existing model) ────────────────────────

    private AssistantRule buildRule(VoiceCommandIntent intent, List<String> childIds,
                                    List<ResolvedAppTarget> resolved) {
        AssistantRule rule = new AssistantRule();
        rule.ruleId = "vr_" + System.currentTimeMillis();
        rule.parentUserId = parentUserId;
        rule.targetChildIds = childIds;
        rule.action = intent.action;
        rule.scheduleType = intent.scheduleSpec.scheduleType;
        rule.startEpochMs = intent.scheduleSpec.startEpochMs;
        rule.endEpochMs = intent.scheduleSpec.endEpochMs;
        rule.sourceText = intent.sourceText;
        rule.createdBy = wasLastInputVoice ? "voice" : "text";
        rule.createdAtEpochMs = System.currentTimeMillis();
        rule.createdAtIso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());

        Set<String> uniqueApps = new HashSet<>();
        for (ResolvedAppTarget t : resolved) uniqueApps.add(t.packageName);
        rule.apps = new ArrayList<>(uniqueApps);
        return rule;
    }

    private void detectConflictAndSave(AssistantRule rule) {
        conflictDetector.detect(rule, new VoiceAssistantConflictDetector.ConflictCallback() {
            @Override
            public void onResult(ConflictResult result) {
                if (!result.hasConflict) {
                    rule.conflictPolicy = ConflictResult.POLICY_OVERRIDE;
                    saveAndSchedule(rule);
                    return;
                }
                // Show a conflict dialog
                new AlertDialog.Builder(VoiceAssistantActivity.this)
                        .setTitle("Conflict Detected")
                        .setMessage(TextUtils.join("\n", result.reasons))
                        .setPositiveButton("Override", (d, w) -> {
                            rule.conflictPolicy = ConflictResult.POLICY_OVERRIDE;
                            saveAndSchedule(rule);
                        })
                        .setNegativeButton("Cancel", (d, w) ->
                                addBotMessage("Command cancelled due to conflict."))
                        .show();
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Conflict check failed: " + error);
                // Proceed anyway
                rule.conflictPolicy = ConflictResult.POLICY_OVERRIDE;
                saveAndSchedule(rule);
            }
        });
    }

    private void saveAndSchedule(AssistantRule rule) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("voice_assistant_rules")
                .child(rule.parentUserId)
                .child(rule.ruleId);

        ref.setValue(rule)
                .addOnSuccessListener(unused -> {
                    writeAudit(rule);

                    if (ScheduleSpec.TYPE_IMMEDIATE.equals(rule.scheduleType)) {
                        // ── IMMEDIATE: Dispatch BlockCommands directly (same as dashboard) ──
                        dispatchBlockCommandsDirectly(rule, "block".equals(rule.action));
                        addBotMessage("✅ Done! " + (rule.action.equals("block") ? "Blocked" : "Unblocked")
                                + " on " + childDeviceName + "'s device.");
                    } else if (ScheduleSpec.TYPE_TIME_RANGE.equals(rule.scheduleType)) {
                        // ── RANGE: Dispatch start action now, schedule end via alarm ──
                        dispatchBlockCommandsDirectly(rule, "block".equals(rule.action));
                        // Schedule the reverse action at the end time
                        VoiceAssistantAlarmScheduler.scheduleRule(this, rule,
                                VoiceAssistantAlarmScheduler.PHASE_END, rule.endEpochMs);
                        addBotMessage("✅ Done! Rule active on " + childDeviceName
                                + "'s device. Will auto-reverse at the end time.");
                    } else {
                        // ── SCHEDULED (at_time, after_duration): Use AlarmManager ──
                        VoiceAssistantAlarmScheduler.scheduleRule(this, rule,
                                VoiceAssistantAlarmScheduler.PHASE_START, rule.startEpochMs);
                        addBotMessage("✅ Scheduled! Will execute on " + childDeviceName + "'s device.");
                    }
                })
                .addOnFailureListener(e ->
                        addBotMessage("Failed to save rule: " + e.getMessage()));
    }

    /**
     * Dispatch BlockCommand objects directly to Firebase — same path the dashboard uses.
     * The child's RemoteBlockService listens on block_commands/{childId} and picks these up.
     */
    private void dispatchBlockCommandsDirectly(AssistantRule rule, boolean block) {
        DatabaseReference root = FirebaseDatabase.getInstance().getReference("block_commands");
        for (String childId : rule.targetChildIds) {
            for (String packageName : rule.apps) {
                String commandId = "cmd_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000);

                java.util.Map<String, Object> blockCommand = new java.util.HashMap<>();
                blockCommand.put("commandId", commandId);
                blockCommand.put("targetDeviceId", childId);
                blockCommand.put("controllerDeviceId", "parent");
                blockCommand.put("packageName", packageName);
                blockCommand.put("appName", packageName);
                blockCommand.put("blockStatus", block);
                blockCommand.put("timestamp", System.currentTimeMillis());
                blockCommand.put("executed", false);
                blockCommand.put("source", "voice_assistant");
                blockCommand.put("sourceRuleId", rule.ruleId);

                root.child(childId).child(commandId).setValue(blockCommand)
                        .addOnSuccessListener(v -> Log.d(TAG, "✅ BlockCommand dispatched: " + packageName))
                        .addOnFailureListener(e -> Log.e(TAG, "❌ Failed to dispatch: " + e.getMessage()));
            }
        }
    }

    private void writeAudit(AssistantRule rule) {
        DatabaseReference auditRef = FirebaseDatabase.getInstance()
                .getReference("voice_assistant_audit")
                .child(rule.parentUserId)
                .child("audit_" + System.currentTimeMillis());
        auditRef.child("ruleId").setValue(rule.ruleId);
        auditRef.child("eventType").setValue("created");
        auditRef.child("sourceText").setValue(rule.sourceText);
        auditRef.child("action").setValue(rule.action);
        auditRef.child("timestamp").setValue(System.currentTimeMillis());
    }

    // ── Chat Helpers ───────────────────────────────────────────────────────

    private void addUserMessage(String text) {
        messageList.add(new AssistantChatMessage(AssistantChatMessage.Sender.USER, text));
        adapter.notifyItemInserted(messageList.size() - 1);
        rvMessages.smoothScrollToPosition(messageList.size() - 1);
    }

    private void addBotMessage(String text) {
        addBotMessage(text, null);
    }

    private void addBotMessage(String text, String utteranceId) {
        messageList.add(new AssistantChatMessage(AssistantChatMessage.Sender.ASSISTANT, text));
        adapter.notifyItemInserted(messageList.size() - 1);
        rvMessages.smoothScrollToPosition(messageList.size() - 1);

        if (wasLastInputVoice && textToSpeech != null) {
            speak(text, utteranceId != null ? utteranceId : "msg");
        }
    }

    private void speak(String text, String utteranceId) {
        if (textToSpeech != null) {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening();
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this,
                        Manifest.permission.RECORD_AUDIO)) {
                    Toast.makeText(this, "Mic permission denied. Enable in Settings.",
                            Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                } else {
                    Toast.makeText(this, "Microphone permission required for voice commands",
                            Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
