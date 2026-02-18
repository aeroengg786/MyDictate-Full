package net.devemperor.dictate.core;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.icu.text.BreakIterator;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.emoji2.emojipicker.EmojiPickerView;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;

import com.google.android.material.button.MaterialButton;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.audio.AudioResponseFormat;
import com.openai.models.audio.transcriptions.Transcription;
import com.openai.models.audio.transcriptions.TranscriptionCreateParams;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import net.devemperor.dictate.BuildConfig;
import net.devemperor.dictate.DictateUtils;
import net.devemperor.dictate.R;
import net.devemperor.dictate.clipboard.ClipboardHistoryAdapter;
import net.devemperor.dictate.clipboard.ClipboardHistoryDatabaseHelper;
import net.devemperor.dictate.clipboard.ClipboardHistoryModel;
import net.devemperor.dictate.rewording.PromptEditActivity;
import net.devemperor.dictate.rewording.PromptModel;
import net.devemperor.dictate.rewording.PromptsDatabaseHelper;
import net.devemperor.dictate.rewording.PromptsKeyboardAdapter;
import net.devemperor.dictate.rewording.PromptsOverviewActivity;
import net.devemperor.dictate.settings.DictateSettingsActivity;
import net.devemperor.dictate.history.TranscriptionHistoryAdapter;
import net.devemperor.dictate.history.TranscriptionHistoryDatabaseHelper;
import net.devemperor.dictate.history.TranscriptionHistoryModel;
import net.devemperor.dictate.usage.UsageDatabaseHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// MAIN CLASS
public class DictateInputMethodService extends InputMethodService {

    // define handlers and runnables for background tasks
    private static final int DELETE_LOOKBACK_CHARACTERS = 64;
    private static final float KEY_PRESS_SCALE = 0.92f;
    private static final long KEY_PRESS_ANIM_DURATION = 80L;
    private static final TimeInterpolator KEY_PRESS_INTERPOLATOR = new DecelerateInterpolator();
    private static final String AUTO_FORMATTING_PROMPT =
            "You are an attentive, adaptive formatting assistant. Clean up speech transcripts that may contain spoken formatting instructions. Apply changes only when the speaker explicitly asks for them; "
                    + "otherwise return the transcript exactly as provided. Keep the output strictly in the transcript's language. Follow these rules:\n"
                    + "- Follow explicit commands such as \"new paragraph\", \"paragraph break\", or \"line break\" by inserting a blank line.\n"
                    + "- Convert spoken punctuation cues like \"period\", \"comma\", \"question mark\", \"exclamation mark\", \"open quote\", or \"close quote\" into their symbols and remove the cue words.\n"
                    + "- Handle spelling and replacement instructions such as \"Henry with i becomes Henri\" or \"replace beta with β\" by adjusting only the targeted words.\n"
                    + "- Treat list cues like \"bullet\", \"list item\", \"number one\", or \"next bullet\" as requests to format list items with dashes or numbers.\n"
                    + "- Apply text styling commands such as \"bold\", \"make this bold\", \"italic\", or \"italicize\" by wrapping only the requested span with Markdown (**bold** / _italic_).\n"
                    + "- Interpret the user's intent intelligently, accommodating paraphrased or partial cues, and always favour the most reasonable formatting that matches the latest request.\n"
                    + "- Leave all other wording untouched except for spacing needed to apply the commands.\n"
                    + "- If commands conflict, apply the most recent one.\n"
                    + "- Never translate, summarise, or add commentary. Output only the final formatted text.\n"
                    + "Examples:\n"
                    + "1) Input: Hello new paragraph how are you question mark -> Output: Hello\\n\\nHow are you?\n"
                    + "2) Input: Please write Henry with i Henri period that's it -> Output: Please write Henri. That's it.\n"
                    + "3) Input: Agenda colon bullet first item bullet second item -> Output: Agenda:\\n- first item\\n- second item\n"
                    + "4) Input: Outline colon number one introduction number two results number three conclusion -> Output: Outline:\\n1. Introduction\\n2. Results\\n3. Conclusion\n"
                    + "5) Input: Please make the words mission critical bold period that's it -> Output: Please make the words **mission critical**. That's it.\n"
                    + "6) Input: Mention italicize needs review before sending -> Output: Mention _needs review_ before sending.\n"
                    + "7) Input: Just checking in with you today -> Output: Just checking in with you today.";

    private Handler mainHandler;
    private Handler deleteHandler;
    private Handler recordTimeHandler;
    private Runnable deleteRunnable;
    private Runnable recordTimeRunnable;

    // define variables and objects
    private long elapsedTime;
    private boolean isDeleting = false;
    private long startDeleteTime = 0;
    private int currentDeleteDelay = 50;
    private boolean isRecording = false;
    private boolean isPaused = false;
    private boolean livePrompt = false;
    private boolean vibrationEnabled = true;
    private boolean audioFocusEnabled = true;
    private TextView selectedCharacter = null;
    private boolean spaceButtonUserHasSwiped = false;
    private int currentInputLanguagePos;
    private String currentInputLanguageValue;
    private boolean autoSwitchKeyboard = false;

    // Swipe-to-select-words state
    private boolean isSwipeSelectingWords = false;
    private float backspaceStartX = 0f;
    private int swipeBaseCursor = -1;
    private List<Integer> swipeWordBoundaries = null;
    private int swipeSelectedSteps = 0;

    private ElevenLabsScribeManager scribeManager;
    private MediaRecorder recorder;
    private ExecutorService speechApiThread;
    private ExecutorService rewordingApiThread;
    private File audioFile;
    private Vibrator vibrator;
    private SharedPreferences sp;
    private AudioManager am;
    private AudioFocusRequest audioFocusRequest;
    private BroadcastReceiver bluetoothScoReceiver;

    // Bluetooth/SCO state
    private boolean isBluetoothScoStarted = false; // true only when SCO is CONNECTED
    private boolean isPreparingRecording = false; // true while we wait for SCO before starting recorder
    private boolean recordingPending = false;     // flag to start recording after SCO connected
    private boolean waitingForSco = false;        // we're actively waiting for SCO
    private boolean recordingUsesBluetooth = false; // current recording actually uses BT mic
    private Handler bluetoothHandler;             // handler for timeouts
    private Runnable scoTimeoutRunnable;

    // define views
    private ConstraintLayout dictateKeyboardView;
    private MaterialButton settingsButton;
    private MaterialButton recordButton;
    private MaterialButton resendButton;
    private MaterialButton backspaceButton;
    private MaterialButton switchButton;
    private MaterialButton trashButton;
    private MaterialButton spaceButton;
    private MaterialButton pauseButton;
    private MaterialButton enterButton;
    private ConstraintLayout infoCl;
    private TextView infoTv;
    private Button infoYesButton;
    private Button infoNoButton;
    private ConstraintLayout promptsCl;
    private RecyclerView promptsRv;
    private TextView runningPromptTv;
    private ProgressBar runningPromptPb;
    private MaterialButton editUndoButton;
    private MaterialButton editRedoButton;
    private MaterialButton editHistoryButton;
    private MaterialButton editCopyButton;
    private MaterialButton editPasteButton;
    private MaterialButton editEmojiButton;
    private ConstraintLayout emojiPickerCl;
    private TextView emojiPickerTitleTv;
    private MaterialButton emojiPickerCloseButton;
    private EmojiPickerView emojiPickerView;
    private MaterialButton editNumbersButton;
    private ConstraintLayout numbersPanelCl;
    private TextView numbersPanelTitleTv;
    private MaterialButton numbersPanelCloseButton;
    private final List<MaterialButton> numberPanelButtons = new ArrayList<>();
    private LinearLayout overlayCharactersLl;

    // Recording visuals (pulsing)
    private ObjectAnimator recordPulseX;
    private ObjectAnimator recordPulseY;

    // Keep screen awake while recording
    private boolean keepScreenAwakeApplied = false;

    PromptsDatabaseHelper promptsDb;
    PromptsKeyboardAdapter promptsAdapter;
    private final List<Integer> queuedPromptIds = new ArrayList<>();
    private boolean disableNonSelectionPrompts = false;

    UsageDatabaseHelper usageDb;

    // History
    TranscriptionHistoryDatabaseHelper historyDb;
    private ConstraintLayout historyPanelCl;
    private TextView historyPanelTitleTv;
    private MaterialButton historyPanelCloseBtn;
    private RecyclerView historyPanelRv;
    private TextView historyPanelEmptyTv;
    private MediaPlayer historyMediaPlayer;
    private TranscriptionHistoryAdapter historyAdapter;

    // Clipboard
    private ClipboardManager clipboardManager;
    private ClipboardManager.OnPrimaryClipChangedListener clipListener;
    private boolean clipListenerRegistered = false;
    private ClipboardHistoryDatabaseHelper clipboardDb;
    private ClipboardHistoryAdapter clipboardAdapter;
    private ConstraintLayout clipboardPanelCl;
    private TextView clipboardPanelTitleTv;
    private MaterialButton clipboardPanelCloseBtn;
    private RecyclerView clipboardPanelRv;
    private TextView clipboardPanelEmptyTv;

    private interface PromptResultCallback {
        void onSuccess(String text);
        void onFailure();
    }

    // start method that is called when user opens the keyboard
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {
        Context context = new ContextThemeWrapper(this, R.style.Theme_Dictate);

        // Clean up old timer before creating new handlers/runnables (rotation recreates the view
        // but old timer callbacks remain in the main looper queue referencing the old runnable)
        if (recordTimeRunnable != null && recordTimeHandler != null) {
            recordTimeHandler.removeCallbacks(recordTimeRunnable);
        }

        // Clean up ElevenLabs scribe manager on rotation
        if (scribeManager != null) {
            scribeManager.cleanup();
            scribeManager = null;
        }

        // initialize some stuff
        mainHandler = new Handler(Looper.getMainLooper());
        deleteHandler = new Handler();
        recordTimeHandler = new Handler(Looper.getMainLooper());
        bluetoothHandler = new Handler(Looper.getMainLooper());

        scribeManager = new ElevenLabsScribeManager(mainHandler);

        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        sp = getSharedPreferences("net.devemperor.dictate", MODE_PRIVATE);

        // Migrate old boolean elevenlabs_mode to new string ListPreference
        if (sp.getAll().get("net.devemperor.dictate.elevenlabs_mode") instanceof Boolean) {
            boolean oldValue = sp.getBoolean("net.devemperor.dictate.elevenlabs_mode", false);
            sp.edit().remove("net.devemperor.dictate.elevenlabs_mode")
                    .putString("net.devemperor.dictate.elevenlabs_mode", oldValue ? "realtime" : "off")
                    .commit();
        }

        promptsDb = new PromptsDatabaseHelper(this);
        usageDb = new UsageDatabaseHelper(this);
        vibrationEnabled = sp.getBoolean("net.devemperor.dictate.vibration", true);
        currentInputLanguagePos = sp.getInt("net.devemperor.dictate.input_language_pos", 0);

        dictateKeyboardView = (ConstraintLayout) LayoutInflater.from(context).inflate(R.layout.activity_dictate_keyboard_view, null);
        dictateKeyboardView.setKeepScreenOn(false);
        keepScreenAwakeApplied = false;
        ViewCompat.setOnApplyWindowInsetsListener(dictateKeyboardView, (v, insets) -> {
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom);
            return insets;  // fix for overlapping with navigation bar on Android 15+
        });

        settingsButton = dictateKeyboardView.findViewById(R.id.settings_btn);
        recordButton = dictateKeyboardView.findViewById(R.id.record_btn);
        resendButton = dictateKeyboardView.findViewById(R.id.resend_btn);
        backspaceButton = dictateKeyboardView.findViewById(R.id.backspace_btn);
        switchButton = dictateKeyboardView.findViewById(R.id.switch_btn);
        trashButton = dictateKeyboardView.findViewById(R.id.trash_btn);
        spaceButton = dictateKeyboardView.findViewById(R.id.space_btn);
        pauseButton = dictateKeyboardView.findViewById(R.id.pause_btn);
        enterButton = dictateKeyboardView.findViewById(R.id.enter_btn);

        infoCl = dictateKeyboardView.findViewById(R.id.info_cl);
        infoTv = dictateKeyboardView.findViewById(R.id.info_tv);
        infoYesButton = dictateKeyboardView.findViewById(R.id.info_yes_btn);
        infoNoButton = dictateKeyboardView.findViewById(R.id.info_no_btn);

        promptsCl = dictateKeyboardView.findViewById(R.id.prompts_keyboard_cl);
        promptsRv = dictateKeyboardView.findViewById(R.id.prompts_keyboard_rv);
        runningPromptPb = dictateKeyboardView.findViewById(R.id.prompts_keyboard_running_pb);
        runningPromptTv = dictateKeyboardView.findViewById(R.id.prompts_keyboard_running_prompt_tv);

        editUndoButton = dictateKeyboardView.findViewById(R.id.edit_undo_btn);
        editRedoButton = dictateKeyboardView.findViewById(R.id.edit_redo_btn);
        editHistoryButton = dictateKeyboardView.findViewById(R.id.edit_history_btn);
        editCopyButton = dictateKeyboardView.findViewById(R.id.edit_copy_btn);
        editPasteButton = dictateKeyboardView.findViewById(R.id.edit_paste_btn);
        editEmojiButton = dictateKeyboardView.findViewById(R.id.edit_emoji_btn);
        editNumbersButton = dictateKeyboardView.findViewById(R.id.edit_numbers_btn);
        emojiPickerCl = dictateKeyboardView.findViewById(R.id.emoji_picker_cl);
        emojiPickerTitleTv = dictateKeyboardView.findViewById(R.id.emoji_picker_title_tv);
        emojiPickerCloseButton = dictateKeyboardView.findViewById(R.id.emoji_picker_close_btn);
        emojiPickerView = dictateKeyboardView.findViewById(R.id.emoji_picker_view);
        numbersPanelCl = dictateKeyboardView.findViewById(R.id.numbers_panel_cl);
        numbersPanelTitleTv = dictateKeyboardView.findViewById(R.id.numbers_panel_title_tv);
        numbersPanelCloseButton = dictateKeyboardView.findViewById(R.id.numbers_panel_close_btn);
        LinearLayout numbersPanelKeysContainer = dictateKeyboardView.findViewById(R.id.numbers_panel_keys_container);
        numberPanelButtons.clear();
        collectNumberPanelButtons(numbersPanelKeysContainer);
        initializeKeyPressAnimations();

        historyPanelCl = dictateKeyboardView.findViewById(R.id.history_panel_cl);
        historyPanelTitleTv = dictateKeyboardView.findViewById(R.id.history_panel_title_tv);
        historyPanelCloseBtn = dictateKeyboardView.findViewById(R.id.history_panel_close_btn);
        historyPanelRv = dictateKeyboardView.findViewById(R.id.history_panel_rv);
        historyPanelEmptyTv = dictateKeyboardView.findViewById(R.id.history_panel_empty_tv);
        historyDb = new TranscriptionHistoryDatabaseHelper(this);

        // Clipboard panel
        clipboardPanelCl = dictateKeyboardView.findViewById(R.id.clipboard_panel_cl);
        clipboardPanelTitleTv = dictateKeyboardView.findViewById(R.id.clipboard_panel_title_tv);
        clipboardPanelCloseBtn = dictateKeyboardView.findViewById(R.id.clipboard_panel_close_btn);
        clipboardPanelRv = dictateKeyboardView.findViewById(R.id.clipboard_panel_rv);
        clipboardPanelEmptyTv = dictateKeyboardView.findViewById(R.id.clipboard_panel_empty_tv);
        clipboardDb = new ClipboardHistoryDatabaseHelper(this);

        // Register clipboard listener (guard against double-registration on rotation)
        if (!clipListenerRegistered) {
            clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipListener = this::onClipboardChanged;
            clipboardManager.addPrimaryClipChangedListener(clipListener);
            clipListenerRegistered = true;
        }

        overlayCharactersLl = dictateKeyboardView.findViewById(R.id.overlay_characters_ll);

        StaggeredGridLayoutManager promptsLayoutManager =
                new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.HORIZONTAL);
        promptsLayoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        promptsRv.setLayoutManager(promptsLayoutManager);

        // if user id is not set, set a random number as user id
        if (sp.getString("net.devemperor.dictate.user_id", "null").equals("null")) {
            sp.edit().putString("net.devemperor.dictate.user_id", String.valueOf((int) (Math.random() * 1000000))).apply();
        }

        recordTimeRunnable = new Runnable() {  // runnable to update the record button time text
            @Override
            public void run() {
                elapsedTime += 100;
                recordButton.setText(getString(R.string.dictate_send,
                        String.format(Locale.getDefault(), "%02d:%02d", (int) (elapsedTime / 60000), (int) (elapsedTime / 1000) % 60)));
                recordTimeHandler.postDelayed(this, 100);
            }
        };

        // initialize audio manager to stop and start background audio
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChange -> {
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                        if (isRecording) pauseButton.performClick();
                    }
                })
                .build();
        initAndRegisterBluetoothReceiver();

        settingsButton.setOnClickListener(v -> {
            if (isRecording) trashButton.performClick();
            infoCl.setVisibility(View.GONE);
            openSettingsActivity();
        });

        // initial state: mic left, folder-open right
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
        recordButton.setOnClickListener(v -> {
            vibrate();

            infoCl.setVisibility(View.GONE);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                openSettingsActivity();
            } else if (!isRecording && !isPreparingRecording) {
                startRecording();
            } else if (isRecording) {
                stopRecording();
            }
        });

        recordButton.setOnLongClickListener(v -> {
            vibrate();

            if (!isRecording && !isPreparingRecording) {  // open real settings activity to start file picker
                Intent intent = new Intent(this, DictateSettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("net.devemperor.dictate.open_file_picker", true);
                startActivity(intent);
            } else if (!livePrompt && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {  // long press during recording automatically switches keyboard after transcription
                autoSwitchKeyboard= true;
                stopRecording();
            }
            return true;
        });

        resendButton.setOnClickListener(v -> {
            vibrate();
            // if user clicked on resendButton without error before, audioFile is default audio
            if (audioFile == null) audioFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a"));
            String elMode = sp.getString("net.devemperor.dictate.elevenlabs_mode", "off");
            if ("batch".equals(elMode) && !sp.getString("net.devemperor.dictate.elevenlabs_api_key", "").isEmpty()) {
                startElevenLabsApiRequest();
            } else {
                startWhisperApiRequest();
            }
        });

        resendButton.setOnLongClickListener(v -> {
            vibrate();
            showHistoryPanel();
            return true;
        });

        historyPanelCloseBtn.setOnClickListener(v -> {
            vibrate();
            hideHistoryPanel();
        });

        clipboardPanelCloseBtn.setOnClickListener(v -> {
            vibrate();
            hideClipboardPanel();
        });

        backspaceButton.setOnClickListener(v -> {
            vibrate();
            deleteOneCharacter();
        });

        backspaceButton.setOnLongClickListener(v -> {
            isDeleting = true;
            startDeleteTime = System.currentTimeMillis();
            currentDeleteDelay = 50;
            deleteRunnable = new Runnable() {
                @Override
                public void run() {
                    if (isDeleting) {
                        deleteOneCharacter();
                        long diff = System.currentTimeMillis() - startDeleteTime;
                        if (diff > 1500 && currentDeleteDelay == 50) {
                            vibrate();
                            currentDeleteDelay = 25;
                        } else if (diff > 3000 && currentDeleteDelay == 25) {
                            vibrate();
                            currentDeleteDelay = 10;
                        } else if (diff > 5000 && currentDeleteDelay == 10) {
                            vibrate();
                            currentDeleteDelay = 5;
                        }
                        deleteHandler.postDelayed(this, currentDeleteDelay);
                    }
                }
            };
            deleteHandler.post(deleteRunnable);
            return true;
        });

        // Enhanced touch handling: swipe left while holding to select words progressively
        backspaceButton.setOnTouchListener((v, event) -> {
            handlePressAnimationEvent(v, event);
            InputConnection ic = getCurrentInputConnection();
            final float density = getResources().getDisplayMetrics().density;
            final int stepPx = (int) (24f * density + 0.5f);
            final int activationPx = Math.max(ViewConfiguration.get(getApplicationContext()).getScaledTouchSlop(),
                    (int) (8f * density + 0.5f)); // small threshold to enter swipe-select and cancel long-press early

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // reset states; allow click/long-press detection
                    isDeleting = false;
                    if (deleteRunnable != null) deleteHandler.removeCallbacks(deleteRunnable);

                    isSwipeSelectingWords = false;
                    swipeSelectedSteps = 0;
                    swipeWordBoundaries = null;
                    swipeBaseCursor = -1;
                    backspaceStartX = event.getX();
                    return false;

                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getX() - backspaceStartX;

                    // if the user moves left beyond activation threshold, start swipe-select and cancel long-press
                    if (dx < -activationPx) {
                        if (!isSwipeSelectingWords) {
                            isSwipeSelectingWords = true;

                            // cancel system long-press to avoid auto-delete kick-in
                            v.cancelLongPress();
                            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);

                            // stop auto-delete if it was started via long-press (safety)
                            isDeleting = false;
                            if (deleteRunnable != null) deleteHandler.removeCallbacks(deleteRunnable);

                            if (ic != null) {
                                ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
                                if (et != null && et.text != null) {
                                    swipeBaseCursor = Math.max(et.selectionStart, et.selectionEnd);
                                    String before = et.text.subSequence(0, swipeBaseCursor).toString();
                                    swipeWordBoundaries = computeWordBoundaries(before);
                                }
                            }
                            if (swipeWordBoundaries == null) {
                                swipeWordBoundaries = Collections.singletonList(0);
                                swipeBaseCursor = 0;
                            }
                        }

                        // step size defines when next word gets added to selection
                        if (ic != null && swipeWordBoundaries != null && !swipeWordBoundaries.isEmpty()) {
                            int maxSteps = swipeWordBoundaries.size() - 1;
                            int steps = Math.min((int) ((-dx) / stepPx), maxSteps);
                            steps = Math.max(0, steps);

                            if (steps != swipeSelectedSteps) {
                                swipeSelectedSteps = steps;
                                int newStart = swipeWordBoundaries.get(steps);
                                ic.setSelection(newStart, swipeBaseCursor);
                                vibrate();
                            }
                        }
                        return true; // consume while swipe-selecting
                    } else if (isSwipeSelectingWords) {
                        // moving back right reduces selection
                        if (ic != null && swipeWordBoundaries != null && !swipeWordBoundaries.isEmpty()) {
                            int steps = Math.max(0, (int) ((-dx) / stepPx));
                            steps = Math.min(steps, swipeWordBoundaries.size() - 1);

                            if (steps != swipeSelectedSteps) {
                                swipeSelectedSteps = steps;
                                int newStart = swipeWordBoundaries.get(steps);
                                ic.setSelection(newStart, swipeBaseCursor);
                                vibrate();
                            }
                            if (steps == 0) {
                                ic.setSelection(swipeBaseCursor, swipeBaseCursor);
                            }
                        }
                        return true;
                    }

                    return false; // not yet swiping -> keep default handling for click/long press
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // always stop auto-delete
                    isDeleting = false;
                    if (deleteRunnable != null) deleteHandler.removeCallbacks(deleteRunnable);

                    if (isSwipeSelectingWords) {
                        if (ic != null) {
                            if (swipeSelectedSteps > 0) {
                                ic.commitText("", 1);
                                vibrate();
                            } else {
                                ic.setSelection(swipeBaseCursor, swipeBaseCursor);
                            }
                        }
                        isSwipeSelectingWords = false;
                        return true; // consume
                    }
                    return false; // no swipe-select -> allow click/long-press outcomes

                default:
                    return false;
            }
        });

        switchButton.setOnClickListener(v -> {
            vibrate();
            switchToPreviousKeyboard();
        });

        switchButton.setOnLongClickListener(v -> {
            vibrate();

            currentInputLanguagePos++;
            recordButton.setText(getDictateButtonText());
            return true;
        });

        // trash button to abort the recording and reset all variables and views
        trashButton.setOnClickListener(v -> {
            vibrate();
            sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", false).apply();

            cancelScoWaitIfAny();  // cancel any pending SCO wait

            // Cancel ElevenLabs streaming if active
            if (scribeManager != null && scribeManager.isStreaming()) {
                scribeManager.cancel();
            }

            if (recorder != null) {
                try { recorder.stop(); } catch (RuntimeException ignored) {}
                recorder.release();
                recorder = null;
            }
            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }
            if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
            if (isBluetoothScoStarted) am.stopBluetoothSco();

            // enable resend button if previous audio file still exists in cache
            if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                    && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
                resendButton.setVisibility(View.VISIBLE);
            }

            isRecording = false;
            isPaused = false;
            livePrompt = false;
            clearQueuedPrompts();
            recordingUsesBluetooth = false;
            updatePromptButtonsEnabledState();
            recordButton.setText(getDictateButtonText());
            applyRecordingIconState(false);
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
            recordButton.setEnabled(true);
            pauseButton.setVisibility(View.GONE);
            pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24));
            trashButton.setVisibility(View.GONE);
            updateKeepScreenAwake(false);
        });

        // space button that changes cursor position if user swipes over it
        spaceButton.setOnTouchListener((v, event) -> {
            handlePressAnimationEvent(v, event);
            InputConnection inputConnection = getCurrentInputConnection();
            int action = event.getActionMasked();
            if (inputConnection != null) {
                spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_keyboard_double_arrow_left_24,
                        0, R.drawable.ic_baseline_keyboard_double_arrow_right_24, 0);
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        spaceButtonUserHasSwiped = false;
                        spaceButton.setTag(event.getX());
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float x = (float) spaceButton.getTag();
                        if (event.getX() - x > 30) {
                            vibrate();
                            inputConnection.commitText("", 2);
                            spaceButton.setTag(event.getX());
                            spaceButtonUserHasSwiped = true;
                        } else if (x - event.getX() > 30) {
                            vibrate();
                            inputConnection.commitText("", -1);
                            spaceButton.setTag(event.getX());
                            spaceButtonUserHasSwiped = true;
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                        if (!spaceButtonUserHasSwiped) {
                            vibrate();
                            inputConnection.commitText(" ", 1);
                        }
                        spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
                        break;
                }
            } else {
                spaceButton.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
            }
            return false;
        });

        pauseButton.setOnClickListener(v -> {
            vibrate();
            if (recorder != null) {
                if (isPaused) {
                    if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);
                    recorder.resume();
                    recordTimeHandler.post(recordTimeRunnable);
                    pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_pause_24));
                    isPaused = false;
                    if (recordPulseX != null && recordPulseX.isPaused()) recordPulseX.resume();
                    if (recordPulseY != null && recordPulseY.isPaused()) recordPulseY.resume();
                } else {
                    if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
                    recorder.pause();
                    recordTimeHandler.removeCallbacks(recordTimeRunnable);
                    pauseButton.setForeground(AppCompatResources.getDrawable(context, R.drawable.ic_baseline_mic_24));
                    isPaused = true;
                    if (recordPulseX != null && recordPulseX.isRunning()) recordPulseX.pause();
                    if (recordPulseY != null && recordPulseY.isRunning()) recordPulseY.pause();
                }
            }
        });

        enterButton.setOnClickListener(v -> {
            vibrate();
            performEnterAction();
        });

        enterButton.setOnLongClickListener(v -> {
            vibrate();
            overlayCharactersLl.setVisibility(View.VISIBLE);
            return true;
        });

        enterButton.setOnTouchListener((v, event) -> {
            handlePressAnimationEvent(v, event);
            if (overlayCharactersLl.getVisibility() == View.VISIBLE) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
                            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
                            if (isPointInsideView(event.getRawX(), charView)) {
                                if (selectedCharacter != charView) {
                                    selectedCharacter = charView;
                                    highlightSelectedCharacter(selectedCharacter);
                                }
                                break;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (selectedCharacter != null) {
                            InputConnection inputConnection = getCurrentInputConnection();
                            if (inputConnection != null) {
                                inputConnection.commitText(selectedCharacter.getText(), 1);
                            }
                            selectedCharacter = null;
                        }
                        overlayCharactersLl.setVisibility(View.GONE);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        overlayCharactersLl.setVisibility(View.GONE);
                        return true;
                }
            }
            return false;
        });

        // initialize history button (replaces cut button)
        editHistoryButton.setOnClickListener(v -> {
            vibrate();
            showHistoryPanel();
        });

        // initialize all edit buttons
        Object[][] buttonsActions = {
                { editUndoButton, android.R.id.undo },
                { editRedoButton, android.R.id.redo },
                { editCopyButton, android.R.id.copy }
        };

        for (Object[] pair : buttonsActions) {
            ((Button) pair[0]).setOnClickListener(v -> {
                vibrate();
                InputConnection inputConnection = getCurrentInputConnection();
                if (inputConnection != null) {
                    inputConnection.performContextMenuAction((int) pair[1]);
                }
            });
        }

        // Paste button: short press = normal paste, long press = clipboard panel
        editPasteButton.setOnClickListener(v -> {
            vibrate();
            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null) {
                inputConnection.performContextMenuAction(android.R.id.paste);
            }
        });
        editPasteButton.setOnLongClickListener(v -> {
            vibrate();
            showClipboardPanel();
            return true;
        });

        editEmojiButton.setOnClickListener(v -> {
            vibrate();
            toggleEmojiPicker();
        });

        editNumbersButton.setOnClickListener(v -> {
            vibrate();
            toggleNumberPanel();
        });

        emojiPickerCloseButton.setOnClickListener(v -> {
            vibrate();
            hideEmojiPicker();
        });

        numbersPanelCloseButton.setOnClickListener(v -> {
            vibrate();
            hideNumberPanel();
        });

        emojiPickerView.setOnEmojiPickedListener(emoji -> {
            vibrate();
            InputConnection inputConnection = getCurrentInputConnection();
            if (inputConnection != null && emoji != null) {
                inputConnection.commitText(emoji.getEmoji(), 1);
            }
        });

        // initialize overlay characters
        for (int i = 0; i < 8; i++) {
            TextView charView = (TextView) LayoutInflater.from(context).inflate(R.layout.item_overlay_characters, overlayCharactersLl, false);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setCornerRadius((int) (4 * context.getResources().getDisplayMetrics().density + 0.5f));
            bg.setStroke((int) (1 * context.getResources().getDisplayMetrics().density + 0.5f), Color.BLACK);
            charView.setBackground(bg);
            overlayCharactersLl.addView(charView);
        }

        prepareRecordPulseAnimation();  // prepare pulsing animation for record button (used while recording)

        return dictateKeyboardView;
    }

    private void initAndRegisterBluetoothReceiver() {
        if (bluetoothScoReceiver != null) return;

        bluetoothScoReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(intent.getAction())) return;

                int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR);
                if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                    isBluetoothScoStarted = true;

                    // If we were waiting to start the recording until SCO connects, start now
                    if (recordingPending && waitingForSco) {
                        waitingForSco = false;
                        if (bluetoothHandler != null && scoTimeoutRunnable != null) {
                            bluetoothHandler.removeCallbacks(scoTimeoutRunnable);
                        }
                        proceedStartRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true);
                    }

                    // Update icon if we are recording and currently using BT
                    if (isRecording) {
                        updateRecordButtonIconWhileRecording();
                    }
                } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                    isBluetoothScoStarted = false;

                    // If we were recording using BT and it got disconnected, keep recording and switch icon
                    if (isRecording && recordingUsesBluetooth) {
                        recordingUsesBluetooth = false;
                        updateRecordButtonIconWhileRecording();
                    }
                }
            }
        };
        registerReceiver(bluetoothScoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
    }

    // method is called if the user closed the keyboard
    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);

        cancelScoWaitIfAny();  // cancel any pending SCO wait

        // Cancel ElevenLabs streaming if active
        if (scribeManager != null && scribeManager.isStreaming()) {
            scribeManager.cancel();
            sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", true).apply();
        }

        if (recorder != null) {
            boolean stoppedSuccessfully = false;
            try {
                recorder.stop();
                stoppedSuccessfully = true;
            } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;

            if (stoppedSuccessfully && isRecording) {
                sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", true).apply();
            }

            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }
        }

        if (speechApiThread != null) speechApiThread.shutdownNow();
        if (rewordingApiThread != null) rewordingApiThread.shutdownNow();

        if (bluetoothScoReceiver != null) {
            unregisterReceiver(bluetoothScoReceiver);
            bluetoothScoReceiver = null;
        }
        if (isBluetoothScoStarted) am.stopBluetoothSco();

        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        resendButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        emojiPickerCl.setVisibility(View.GONE);
        numbersPanelCl.setVisibility(View.GONE);
        historyPanelCl.setVisibility(View.GONE);
        clipboardPanelCl.setVisibility(View.GONE);
        releaseHistoryMediaPlayer();
        isRecording = false;
        isPaused = false;
        livePrompt = false;
        clearQueuedPrompts();
        recordingUsesBluetooth = false;
        updatePromptButtonsEnabledState();
        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
        recordButton.setText(R.string.dictate_record);
        applyRecordingIconState(false);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
        recordButton.setEnabled(true);
        updateKeepScreenAwake(false);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (clipListenerRegistered && clipboardManager != null && clipListener != null) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
            clipListenerRegistered = false;
        }
        if (clipboardDb != null) {
            clipboardDb.close();
        }
    }

    // method is called if the keyboard appears again
    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        updateEnterButtonIcon(info);
        initAndRegisterBluetoothReceiver();

        if (sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
            promptsCl.setVisibility(View.VISIBLE);

            // collect all prompts from database
            final List<PromptModel> data = promptsDb.getAllForKeyboard();
            InputConnection inputConnection = getCurrentInputConnection();
            boolean hasSelection = inputConnection != null && inputConnection.getSelectedText(0) != null;

            promptsAdapter = new PromptsKeyboardAdapter(sp, data, new PromptsKeyboardAdapter.AdapterCallback() {
                @Override
                public void onItemClicked(Integer position) {
                    vibrate();
                    PromptModel model = data.get(position);

                    if (model.getId() == -1) {  // instant prompt clicked
                        livePrompt = true;
                        if (ContextCompat.checkSelfPermission(DictateInputMethodService.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            openSettingsActivity();
                        } else if (!isRecording && !isPreparingRecording) {
                            startRecording();
                        } else if (isRecording) {
                            stopRecording();
                        }
                    } else if (model.getId() == -3) {  // select all clicked
                        handleSelectAllToggle();
                    } else if (model.getId() == -2) {  // add prompt clicked
                        Intent intent = new Intent(DictateInputMethodService.this, PromptsOverviewActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } else {
                        if ((isRecording || isPreparingRecording) && !livePrompt) {
                            toggleQueuedPrompt(model);
                            return;
                        }
                        InputConnection currentConnection = getCurrentInputConnection();
                        if (model.requiresSelection()) {
                            if (currentConnection == null) {
                                return;
                            }
                            ExtractedText extractedText = currentConnection.getExtractedText(new ExtractedTextRequest(), 0);
                            if (extractedText == null || extractedText.text == null || extractedText.text.length() == 0) {
                                return;  // nothing to edit
                            }
                            CharSequence selectedText = currentConnection.getSelectedText(0);
                            if (selectedText == null || selectedText.length() == 0) {
                                currentConnection.performContextMenuAction(android.R.id.selectAll);
                                selectedText = currentConnection.getSelectedText(0);
                                if (selectedText == null || selectedText.length() == 0) {
                                    return;
                                }
                            }
                        }
                        startGPTApiRequest(model);  // another normal prompt clicked
                    }
                }

                @Override
                public void onItemLongClicked(Integer position) {
                    PromptModel model = data.get(position);
                    if (model.getId() >= 0) {
                        vibrate();
                        Intent intent = new Intent(DictateInputMethodService.this, PromptEditActivity.class);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        intent.putExtra("net.devemperor.dictate.prompt_edit_activity_id", model.getId());
                        startActivity(intent);
                    }
                }
            });
            promptsRv.setAdapter(promptsAdapter);
            promptsAdapter.setDisableNonSelectionPrompts(disableNonSelectionPrompts);
            promptsAdapter.setSelectAllActive(hasSelection);
            updateQueuedPromptsUi();
            updateSelectAllPromptState();
        } else {
            promptsCl.setVisibility(View.GONE);
        }

        if (shouldAutomaticallyShowNumberPanel(info)) {
            showNumberPanel();
        } else {
            hideNumberPanel();
        }

        // enable resend button if previous audio file still exists in cache
        if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
            resendButton.setVisibility(View.VISIBLE);
        } else {
            resendButton.setVisibility(View.GONE);
        }

        // Recovery: if there's a pending transcription from a rotation or crash,
        // show the resend button so the user can send when the connection is stable.
        // Do NOT clear the flag here — let it persist so it survives subsequent rotations.
        // Flag is cleared by: successful transcription, trash button, or starting a new recording.
        if (sp.getBoolean("net.devemperor.dictate.pending_transcription", false)) {
            File pendingFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a"));
                    + " exists=" + pendingFile.exists() + " size=" + pendingFile.length());
            if (pendingFile.exists() && pendingFile.length() > 0) {
                audioFile = pendingFile;
                resendButton.setVisibility(View.VISIBLE);
            } else {
                // File is gone, clear the stale flag
                sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", false).apply();
            }
        }

        // get the currently selected input language
        recordButton.setText(getDictateButtonText());

        // check if user enabled audio focus
        audioFocusEnabled = sp.getBoolean("net.devemperor.dictate.audio_focus", true);

        // fill all overlay characters
        int accentColor = sp.getInt("net.devemperor.dictate.accent_color", -14700810);
        String charactersString = sp.getString("net.devemperor.dictate.overlay_characters", "()-:!?,.");
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            if (i >= charactersString.length()) {
                charView.setVisibility(View.GONE);
            } else {
                charView.setVisibility(View.VISIBLE);
                charView.setText(charactersString.substring(i, i + 1));
                GradientDrawable bg = (GradientDrawable) charView.getBackground();
                bg.setColor(accentColor);
            }
        }

        // update theme
        String theme = sp.getString("net.devemperor.dictate.theme", "system");
        int keyboardBackgroundColor;
        if ("dark".equals(theme) || ("system".equals(theme) && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)) {
            keyboardBackgroundColor = getResources().getColor(R.color.dictate_keyboard_background_dark, getTheme());
        } else {
            keyboardBackgroundColor = getResources().getColor(R.color.dictate_keyboard_background_light, getTheme());
        }
        dictateKeyboardView.setBackgroundColor(keyboardBackgroundColor);
        emojiPickerCl.setBackgroundColor(keyboardBackgroundColor);
        numbersPanelCl.setBackgroundColor(keyboardBackgroundColor);
        historyPanelCl.setBackgroundColor(keyboardBackgroundColor);
        clipboardPanelCl.setBackgroundColor(keyboardBackgroundColor);

        int accentColorMedium = DictateUtils.darkenColor(accentColor, 0.18f);
        int accentColorDark = DictateUtils.darkenColor(accentColor, 0.35f);
        TextView[] textColorViews = { infoTv, runningPromptTv, emojiPickerTitleTv, numbersPanelTitleTv, historyPanelTitleTv, clipboardPanelTitleTv };
        for (TextView tv : textColorViews) tv.setTextColor(accentColor);
        applyButtonColor(settingsButton, accentColorDark);
        applyButtonColor(recordButton, accentColor);
        applyButtonColor(resendButton, accentColorMedium);
        applyButtonColor(backspaceButton, accentColorDark);
        applyButtonColor(switchButton, accentColorDark);
        applyButtonColor(trashButton, accentColorMedium);
        applyButtonColor(spaceButton, accentColorMedium);
        applyButtonColor(pauseButton, accentColorMedium);
        applyButtonColor(enterButton, accentColorDark);
        applyButtonColor(editUndoButton, accentColorMedium);
        applyButtonColor(editRedoButton, accentColorMedium);
        applyButtonColor(editHistoryButton, accentColorMedium);
        applyButtonColor(editCopyButton, accentColorMedium);
        applyButtonColor(editPasteButton, accentColorMedium);
        applyButtonColor(editEmojiButton, accentColorMedium);
        applyButtonColor(editNumbersButton, accentColorMedium);
        applyButtonColor(emojiPickerCloseButton, accentColor);
        applyButtonColor(numbersPanelCloseButton, accentColor);
        applyButtonColor(historyPanelCloseBtn, accentColor);
        applyButtonColor(clipboardPanelCloseBtn, accentColor);
        for (MaterialButton button : numberPanelButtons) {
            Object tag = button.getTag();
            CharSequence text = button.getText();
            boolean isEnter = tag != null && "ENTER".equalsIgnoreCase(tag.toString());
            boolean isDigit = text != null && text.length() == 1 && Character.isDigit(text.charAt(0));
            int background = isEnter ? accentColor : (isDigit ? accentColorMedium : accentColorDark);
            applyButtonColor(button, background);
        }
        runningPromptPb.getIndeterminateDrawable().setColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN);

        // show infos for updates, ratings or donations
        long totalAudioTime = usageDb.getTotalAudioTime();
        if (sp.getInt("net.devemperor.dictate.last_version_code", 0) < BuildConfig.VERSION_CODE) {
            showInfo("update");
        } else if (totalAudioTime > 180 && totalAudioTime <= 600 && !sp.getBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", false)) {
            showInfo("rate");  // in case someone had Dictate installed before, he shouldn't get both messages
        } else if (totalAudioTime > 600 && !sp.getBoolean("net.devemperor.dictate.flag_has_donated", false)) {
            showInfo("donate");
        }

        // start audio file transcription if user selected an audio file
        if (!sp.getString("net.devemperor.dictate.transcription_audio_file", "").isEmpty()) {
            audioFile = new File(getCacheDir(), sp.getString("net.devemperor.dictate.transcription_audio_file", ""));
            sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

            sp.edit().remove("net.devemperor.dictate.transcription_audio_file").apply();
            startWhisperApiRequest();

        } else if (sp.getBoolean("net.devemperor.dictate.instant_recording", false)) {
            recordButton.performClick();
        }
    }

    // method is called if user changed text selection
    @Override
    public void onUpdateSelection (int oldSelStart, int oldSelEnd, int newSelStart, int newSelEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd);

        // refill all prompts
        if (sp != null && sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
            updateSelectAllPromptState();
        }
    }

    private void vibrate() {
        if (vibrationEnabled) if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }

    private void toggleEmojiPicker() {
        if (emojiPickerCl.getVisibility() == View.VISIBLE) {
            hideEmojiPicker();
        } else {
            showEmojiPicker();
        }
    }

    private void showEmojiPicker() {
        hideNumberPanel();
        hideClipboardPanel();
        overlayCharactersLl.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        emojiPickerCl.setVisibility(View.VISIBLE);
        emojiPickerCl.bringToFront();
    }

    private void hideEmojiPicker() {
        emojiPickerCl.setVisibility(View.GONE);
    }

    private void handleSelectAllToggle() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        ExtractedText extractedText = inputConnection.getExtractedText(new ExtractedTextRequest(), 0);
        CharSequence selectedText = inputConnection.getSelectedText(0);

        if ((selectedText == null || selectedText.length() == 0)
                && extractedText != null && extractedText.text != null && extractedText.text.length() > 0) {
            inputConnection.performContextMenuAction(android.R.id.selectAll);
        } else {
            inputConnection.clearMetaKeyStates(0);
            if (extractedText == null || extractedText.text == null) {
                inputConnection.setSelection(0, 0);
            } else {
                int length = extractedText.text.length();
                inputConnection.setSelection(length, length);
            }
        }

        updateSelectAllPromptState();
    }

    private void updateSelectAllPromptState() {
        if (promptsAdapter == null) return;
        InputConnection inputConnection = getCurrentInputConnection();
        boolean hasSelection = inputConnection != null && inputConnection.getSelectedText(0) != null;
        promptsAdapter.setSelectAllActive(hasSelection);
    }

    private void toggleNumberPanel() {
        if (numbersPanelCl == null) return;
        if (numbersPanelCl.getVisibility() == View.VISIBLE) {
            hideNumberPanel();
        } else {
            showNumberPanel();
        }
    }

    private void showNumberPanel() {
        if (numbersPanelCl == null) return;
        hideEmojiPicker();
        hideClipboardPanel();
        overlayCharactersLl.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        numbersPanelCl.setVisibility(View.VISIBLE);
        numbersPanelCl.bringToFront();
    }

    private void hideNumberPanel() {
        if (numbersPanelCl == null) return;
        numbersPanelCl.setVisibility(View.GONE);
    }

    private void collectNumberPanelButtons(ViewGroup parent) {
        if (parent == null) return;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof ViewGroup) {
                collectNumberPanelButtons((ViewGroup) child);
            } else if (child instanceof MaterialButton) {
                MaterialButton button = (MaterialButton) child;
                Object tag = button.getTag();
                final String value;
                if (tag != null) {
                    value = tag.toString();
                } else if (button.getText() != null) {
                    value = button.getText().toString();
                } else {
                    value = "";
                }
                button.setOnClickListener(v -> {
                    vibrate();
                    if ("BACKSPACE".equalsIgnoreCase(value)) {
                        deleteOneCharacter();
                    } else if ("ENTER".equalsIgnoreCase(value)) {
                        performEnterAction();
                    } else {
                        commitNumberPanelValue(value);
                    }
                });
                applyPressAnimation(button);
                numberPanelButtons.add(button);
            }
        }
    }

    private void applyButtonColor(MaterialButton button, int backgroundColor) {
        if (button == null) return;
        button.setBackgroundColor(backgroundColor);
    }

    private void initializeKeyPressAnimations() {
        View[] animatedViews = {
                settingsButton, recordButton, resendButton, switchButton, trashButton,
                pauseButton, emojiPickerCloseButton, numbersPanelCloseButton,
                editUndoButton, editRedoButton, editHistoryButton, editCopyButton,
                editPasteButton, editEmojiButton, editNumbersButton,
                infoYesButton, infoNoButton
        };
        for (View view : animatedViews) {
            applyPressAnimation(view);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void applyPressAnimation(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            handlePressAnimationEvent(v, event);
            return false;
        });
    }

    private void handlePressAnimationEvent(View view, MotionEvent event) {
        if (view == null || event == null) return;
        if (!sp.getBoolean("net.devemperor.dictate.animations", true)) {
            view.animate().cancel();
            view.setScaleX(1f);
            view.setScaleY(1f);
            return;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animateKeyPress(view, true);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                animateKeyPress(view, false);
                break;
        }
    }

    private void animateKeyPress(View view, boolean pressed) {
        if (!sp.getBoolean("net.devemperor.dictate.animations", true) || view == null) {
            if (view != null) {
                view.animate().cancel();
                if (view.getScaleX() != 1f) view.setScaleX(1f);
                if (view.getScaleY() != 1f) view.setScaleY(1f);
            }
            return;
        }
        float targetScale = pressed ? KEY_PRESS_SCALE : 1f;
        view.animate()
                .scaleX(targetScale)
                .scaleY(targetScale)
                .setDuration(KEY_PRESS_ANIM_DURATION)
                .setInterpolator(KEY_PRESS_INTERPOLATOR)
                .start();
    }

    private void commitNumberPanelValue(String value) {
        if (value == null || value.isEmpty()) return;
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            inputConnection.commitText(value, 1);
        }
    }

    private boolean shouldAutomaticallyShowNumberPanel(EditorInfo info) {
        if (info == null) return false;
        int inputType = info.inputType;
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        if (inputClass == InputType.TYPE_CLASS_NUMBER || inputClass == InputType.TYPE_CLASS_PHONE) {
            return true;
        }
        return inputClass == InputType.TYPE_CLASS_DATETIME;
    }

    private void performEnterAction() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;
        EditorInfo editorInfo = getCurrentInputEditorInfo();

        if (editorInfo == null) {
            inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            return;
        }

        int imeAction = editorInfo.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = (editorInfo.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

        if (noEnterAction) {
            inputConnection.commitText("\n", 1);
        } else {
            switch (imeAction) {
                case EditorInfo.IME_ACTION_GO:
                case EditorInfo.IME_ACTION_SEARCH:
                case EditorInfo.IME_ACTION_SEND:
                case EditorInfo.IME_ACTION_NEXT:
                case EditorInfo.IME_ACTION_DONE:
                    inputConnection.performEditorAction(imeAction);
                    break;
                default:
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    inputConnection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                    break;
            }
        }
    }

    private void updateEnterButtonIcon(EditorInfo info) {
        if (info == null || enterButton == null) return;

        int imeAction = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

        if (noEnterAction) {
            enterButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_subdirectory_arrow_left_24));
        } else {
            switch (imeAction) {
                case EditorInfo.IME_ACTION_GO:
                case EditorInfo.IME_ACTION_SEARCH:
                case EditorInfo.IME_ACTION_SEND:
                case EditorInfo.IME_ACTION_NEXT:
                    enterButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_send_20));
                    break;
                case EditorInfo.IME_ACTION_DONE:
                    enterButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_check_24));
                    break;
                default:
                    enterButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_subdirectory_arrow_left_24));
                    break;
            }
        }
    }

    private void openSettingsActivity() {
        Intent intent = new Intent(this, DictateSettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void startRecording() {
        if (isRecording || isPreparingRecording) return;  // prevent re-entrance

        prepareAutoApplyQueue();

        // Clear any pending recovery — user is starting a fresh recording
        sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", false).apply();

        // Check if ElevenLabs realtime mode is enabled
        String elevenLabsMode = sp.getString("net.devemperor.dictate.elevenlabs_mode", "off");
        String elevenLabsKey = sp.getString("net.devemperor.dictate.elevenlabs_api_key", "");
        if ("realtime".equals(elevenLabsMode) && !elevenLabsKey.isEmpty()) {
            startElevenLabsRecording();
            return;
        }

        audioFile = new File(getCacheDir(), "audio.m4a");
        sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

        boolean useBluetoothMic = sp.getBoolean("net.devemperor.dictate.use_bluetooth_mic", false);  // read preference: only use BT mic if enabled
        boolean btAvailable = useBluetoothMic && am.isBluetoothScoAvailableOffCall() && hasBluetoothInputDevice();  // Check if BT SCO is available and (likely) an input device is present

        if (btAvailable) {
            if (am.isBluetoothScoOn()) {
                proceedStartRecording(MediaRecorder.AudioSource.VOICE_COMMUNICATION, true);
            } else {
                // Prepare to wait for SCO connection before starting the recorder
                isPreparingRecording = true;
                recordingPending = true;
                waitingForSco = true;
                updatePromptButtonsEnabledState();
                mainHandler.post(() -> recordButton.setEnabled(false));

                am.startBluetoothSco();  // initiate SCO connection

                scoTimeoutRunnable = () -> {  // Timeout: if SCO not connected in time, fall back to MIC to avoid gaps
                    if (recordingPending && waitingForSco) {
                        waitingForSco = false;
                        try { am.stopBluetoothSco(); } catch (Exception ignored) {}
                        proceedStartRecording(MediaRecorder.AudioSource.MIC, false);
                    }
                };
                bluetoothHandler.postDelayed(scoTimeoutRunnable, 2500); // 2.5s timeout
            }
        } else {
            proceedStartRecording(MediaRecorder.AudioSource.MIC, false);  // Start immediately with local MIC
        }
    }

    private void proceedStartRecording(int audioSource, boolean useBtForThisRecording) {
        // Hide history panel if open
        historyPanelCl.setVisibility(View.GONE);
        releaseHistoryMediaPlayer();

        // Build and start MediaRecorder with the decided audio source
        recorder = new MediaRecorder();
        recorder.setAudioSource(audioSource);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioEncodingBitRate(64000);
        recorder.setAudioSamplingRate(44100);
        recorder.setOutputFile(audioFile);

        if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);

        try {
            recorder.prepare();
            recorder.start();
        } catch (IOException e) {
            sendLogToCrashlytics(e);
            // reset UI/state on failure
            isRecording = false;
            isPreparingRecording = false;
            recordingPending = false;
            waitingForSco = false;
            recordingUsesBluetooth = false;
            updatePromptButtonsEnabledState();
            if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
            mainHandler.post(() -> {
                recordButton.setText(getDictateButtonText());
                applyRecordingIconState(false);
                recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                recordButton.setEnabled(true);
            });
            return;
        }

        // success -> update state and UI
        isRecording = true;
        isPreparingRecording = false;
        recordingPending = false;
        waitingForSco = false;
        recordingUsesBluetooth = useBtForThisRecording;
        updatePromptButtonsEnabledState();

        mainHandler.post(() -> {
            recordButton.setEnabled(true);
            recordButton.setText(R.string.dictate_send);
            applyRecordingIconState(true);
            updateRecordButtonIconWhileRecording();
            updateKeepScreenAwake(true);
            pauseButton.setVisibility(View.VISIBLE);
            trashButton.setVisibility(View.VISIBLE);
            resendButton.setVisibility(View.GONE);
            elapsedTime = 0;
            recordTimeHandler.post(recordTimeRunnable);
        });
    }

    private void stopRecording() {
        // Check if ElevenLabs streaming is active
        if (scribeManager != null && scribeManager.isStreaming()) {
            stopElevenLabsRecording();
            return;
        }

        cancelScoWaitIfAny();  // cancel any pending SCO wait

        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException ignored) { }
            recorder.release();
            recorder = null;

            if (recordTimeRunnable != null) {
                recordTimeHandler.removeCallbacks(recordTimeRunnable);
            }
        }

        updateKeepScreenAwake(false);

        if (isBluetoothScoStarted) am.stopBluetoothSco();

        // Mark as pending so recovery works if the process is killed during transcription
        // Using commit() (synchronous) instead of apply() to guarantee the flag is on disk
        // before the API call starts — if the process is killed during transcription, the flag survives.
        sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", true).commit();

        String elevenLabsMode = sp.getString("net.devemperor.dictate.elevenlabs_mode", "off");
        if ("batch".equals(elevenLabsMode) && !sp.getString("net.devemperor.dictate.elevenlabs_api_key", "").isEmpty()) {
            startElevenLabsApiRequest();
        } else {
            startWhisperApiRequest();
        }
    }

    private void startElevenLabsRecording() {
        // Hide history panel if open
        historyPanelCl.setVisibility(View.GONE);
        releaseHistoryMediaPlayer();

        audioFile = new File(getCacheDir(), "audio.wav");
        sp.edit().putString("net.devemperor.dictate.last_file_name", audioFile.getName()).apply();

        String apiKey = sp.getString("net.devemperor.dictate.elevenlabs_api_key", "");

        if (audioFocusEnabled) am.requestAudioFocus(audioFocusRequest);

        isRecording = true;
        isPreparingRecording = false;
        updatePromptButtonsEnabledState();

        recordButton.setEnabled(true);
        recordButton.setText(R.string.dictate_send);
        applyRecordingIconState(true);
        updateRecordButtonIconWhileRecording();
        updateKeepScreenAwake(true);
        pauseButton.setVisibility(View.GONE); // no pause for real-time streaming
        trashButton.setVisibility(View.VISIBLE);
        resendButton.setVisibility(View.GONE);
        elapsedTime = 0;
        recordTimeHandler.post(recordTimeRunnable);

        infoCl.setVisibility(View.VISIBLE);
        infoTv.setText(R.string.dictate_elevenlabs_listening);
        infoYesButton.setVisibility(View.GONE);
        infoNoButton.setVisibility(View.GONE);

        scribeManager.start(apiKey, currentInputLanguageValue, audioFile, new ElevenLabsScribeManager.ScribeCallback() {
            @Override
            public void onSessionStarted() {
            }

            @Override
            public void onPartialTranscript(String text) {
                // No preview — text will be delivered all at once in onComplete
            }

            @Override
            public void onCommittedTranscript(String text) {
                // No progressive commit — text will be delivered all at once in onComplete
            }

            @Override
            public void onError(String errorType, String message) {
                mainHandler.post(() -> {
                    if (vibrationEnabled) vibrator.vibrate(android.os.VibrationEffect.createOneShot(300, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    if (recordTimeRunnable != null) recordTimeHandler.removeCallbacks(recordTimeRunnable);
                    isRecording = false;
                    updatePromptButtonsEnabledState();
                    if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

                    if ("auth".equals(errorType) || "auth_error".equals(errorType)) {
                        infoTv.setText(R.string.dictate_elevenlabs_error_auth);
                    } else {
                        infoTv.setText(R.string.dictate_elevenlabs_error_connection);
                    }
                    infoCl.setVisibility(View.VISIBLE);
                    infoYesButton.setVisibility(View.GONE);
                    infoNoButton.setVisibility(View.GONE);
                    resendButton.setVisibility(View.VISIBLE);
                    trashButton.setVisibility(View.GONE);

                    recordButton.setText(getDictateButtonText());
                    applyRecordingIconState(false);
                    recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                    recordButton.setEnabled(true);
                    updateKeepScreenAwake(false);
                });
            }

            @Override
            public void onComplete(String fullText, File audioFile, float durationSeconds) {
                mainHandler.post(() -> {
                    if (recordTimeRunnable != null) recordTimeHandler.removeCallbacks(recordTimeRunnable);
                    isRecording = false;
                    updatePromptButtonsEnabledState();
                    if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);
                    infoCl.setVisibility(View.GONE);

                    // Track usage
                    usageDb.edit("scribe_v2_realtime", (long) durationSeconds, 0, 0, 3);

                    // Strip audio event tags if setting is off
                    String resultText = sp.getBoolean("net.devemperor.dictate.elevenlabs_tag_audio_events", false)
                            ? fullText : ElevenLabsScribeManager.stripAudioEventTags(fullText);
                    resultText = applyAutoFormattingIfEnabled(resultText);

                    // Process rewording prompts (now supported since text is delivered all at once)
                    boolean processedByQueuedPrompts = false;
                    java.util.List<Integer> promptsToApply;
                    synchronized (queuedPromptIds) {
                        promptsToApply = new ArrayList<>(queuedPromptIds);
                    }
                    if (!promptsToApply.isEmpty()) {
                        clearQueuedPrompts();
                        if (!livePrompt) {
                            processQueuedPrompts(resultText, promptsToApply);
                            processedByQueuedPrompts = true;
                        }
                    }

                    if (!processedByQueuedPrompts && !livePrompt) {
                        commitTextToInputConnection(resultText);
                    } else if (livePrompt) {
                        livePrompt = false;
                        startGPTApiRequest(new PromptModel(-1, Integer.MIN_VALUE, "", resultText, true, false));
                    }

                    // Save to history (skip if resending from history)
                    if (!audioFile.getAbsolutePath().startsWith(getHistoryDir().getAbsolutePath())) {
                        saveToHistory(resultText, durationSeconds);
                    }

                    sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", false).apply();

                    if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.wav")).exists()
                            && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
                        resendButton.setVisibility(View.VISIBLE);
                    }

                    recordButton.setText(getDictateButtonText());
                    applyRecordingIconState(false);
                    recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                    recordButton.setEnabled(true);
                    updateKeepScreenAwake(false);

                    if (autoSwitchKeyboard) {
                        autoSwitchKeyboard = false;
                        switchToPreviousKeyboard();
                    }
                });
            }

            @Override
            public void onConnectionClosed() {
            }
        });
    }

    private void stopElevenLabsRecording() {
        if (recordTimeRunnable != null) {
            recordTimeHandler.removeCallbacks(recordTimeRunnable);
        }
        updateKeepScreenAwake(false);

        recordButton.setText(R.string.dictate_elevenlabs_finalizing);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
        recordButton.setEnabled(false);
        trashButton.setVisibility(View.GONE);
        applyRecordingIconState(false);

        infoTv.setText(R.string.dictate_elevenlabs_finalizing);
        infoCl.setVisibility(View.VISIBLE);

        sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", true).commit();

        scribeManager.stop(); // triggers onComplete callback
    }

    private void startElevenLabsApiRequest() {
        applyRecordingIconState(false);

        recordButton.setText(R.string.dictate_elevenlabs_transcribing);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
        recordButton.setEnabled(false);
        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        resendButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        isRecording = false;
        isPaused = false;
        recordingUsesBluetooth = false;
        updatePromptButtonsEnabledState();

        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

        String apiKey = sp.getString("net.devemperor.dictate.elevenlabs_api_key", "");
        String langCode = ElevenLabsScribeManager.mapLanguageCodePublic(currentInputLanguageValue);

        speechApiThread = java.util.concurrent.Executors.newSingleThreadExecutor();
        speechApiThread.execute(() -> {
            try {
                OkHttpClient httpClient = new OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                        .build();

                String mimeType = audioFile.getName().endsWith(".wav") ? "audio/wav" : "audio/mp4";
                boolean tagAudioEvents = sp.getBoolean("net.devemperor.dictate.elevenlabs_tag_audio_events", false);
                MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("model_id", "scribe_v2")
                        .addFormDataPart("tag_audio_events", String.valueOf(tagAudioEvents))
                        .addFormDataPart("file", audioFile.getName(),
                                RequestBody.create(audioFile, MediaType.parse(mimeType)));

                if (langCode != null) {
                    bodyBuilder.addFormDataPart("language_code", langCode);
                }

                Request request = new Request.Builder()
                        .url("https://api.elevenlabs.io/v1/speech-to-text")
                        .addHeader("xi-api-key", apiKey)
                        .post(bodyBuilder.build())
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "";

                    if (!response.isSuccessful()) {
                        String errorMsg;
                        if (response.code() == 401 || response.code() == 403) {
                            errorMsg = "api key";
                        } else if (response.code() == 429) {
                            errorMsg = "quota";
                        } else {
                            errorMsg = "ElevenLabs API error " + response.code() + ": " + responseBody;
                        }
                        throw new RuntimeException(errorMsg);
                    }

                    JSONObject json = new JSONObject(responseBody);
                    String resultText = json.optString("text", "").strip();
                    resultText = applyAutoFormattingIfEnabled(resultText);

                    float duration = DictateUtils.getAudioDuration(audioFile);
                    usageDb.edit("scribe_v2", (long) duration, 0, 0, 3);

                    boolean processedByQueuedPrompts = false;
                    java.util.List<Integer> promptsToApply;
                    synchronized (queuedPromptIds) {
                        promptsToApply = new ArrayList<>(queuedPromptIds);
                    }
                    if (!promptsToApply.isEmpty()) {
                        clearQueuedPrompts();
                        if (!livePrompt) {
                            processQueuedPrompts(resultText, promptsToApply);
                            processedByQueuedPrompts = true;
                        }
                    }

                    if (!processedByQueuedPrompts && !livePrompt) {
                        commitTextToInputConnection(resultText);
                    } else if (livePrompt) {
                        livePrompt = false;
                        startGPTApiRequest(new PromptModel(-1, Integer.MIN_VALUE, "", resultText, true, false));
                    }

                    if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                            && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
                        mainHandler.post(() -> resendButton.setVisibility(View.VISIBLE));
                    }

                    if (!audioFile.getAbsolutePath().startsWith(getHistoryDir().getAbsolutePath())) {
                        saveToHistory(resultText, duration);
                    }

                    sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", false).apply();

                    if (autoSwitchKeyboard) {
                        autoSwitchKeyboard = false;
                        mainHandler.post(this::switchToPreviousKeyboard);
                    }
                }
            } catch (RuntimeException | IOException | JSONException e) {
                sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", false).apply();
                if (!(e instanceof InterruptedIOException)) {
                    sendLogToCrashlytics(e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e));
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        String message = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        if (message.contains("api key")) {
                            showInfo("invalid_api_key");
                        } else if (message.contains("quota")) {
                            showInfo("quota_exceeded");
                        } else {
                            showInfo("internet_error");
                        }
                    });
                }
            }

            mainHandler.post(() -> {
                recordButton.setText(getDictateButtonText());
                applyRecordingIconState(false);
                recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0);
                recordButton.setEnabled(true);
            });
        });
    }

    private void updateKeepScreenAwake(boolean keepAwake) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (mainHandler != null) {
                mainHandler.post(() -> updateKeepScreenAwake(keepAwake));
            }
            return;
        }

        if (dictateKeyboardView != null) {
            dictateKeyboardView.setKeepScreenOn(keepAwake);
        }

        if (keepScreenAwakeApplied == keepAwake) return;

        Dialog windowDialog = getWindow();
        if (windowDialog == null) {
            if (!keepAwake) keepScreenAwakeApplied = false;
            return;
        }

        Window window = windowDialog.getWindow();
        if (window == null) {
            if (!keepAwake) keepScreenAwakeApplied = false;
            return;
        }

        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        keepScreenAwakeApplied = keepAwake;
    }

    private void startWhisperApiRequest() {
        applyRecordingIconState(false);  // recording finished -> stop pulsing

        recordButton.setText(R.string.dictate_sending);
        recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0); // keep send icon while sending
        recordButton.setEnabled(false);
        pauseButton.setForeground(AppCompatResources.getDrawable(this, R.drawable.ic_baseline_pause_24));
        pauseButton.setVisibility(View.GONE);
        trashButton.setVisibility(View.GONE);
        resendButton.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);
        isRecording = false;
        isPaused = false;
        recordingUsesBluetooth = false;
        updatePromptButtonsEnabledState();

        if (audioFocusEnabled) am.abandonAudioFocusRequest(audioFocusRequest);

        String stylePrompt;
        switch (sp.getInt("net.devemperor.dictate.style_prompt_selection", 1)) {
            case 1:
                stylePrompt = DictateUtils.getPunctuationPromptForLanguage(currentInputLanguageValue);
                break;
            case 2:
                stylePrompt = sp.getString("net.devemperor.dictate.style_prompt_custom_text", "");
                break;
            default:
                stylePrompt = "";
        }

        speechApiThread = Executors.newSingleThreadExecutor();
        speechApiThread.execute(() -> {
            try {
                int transcriptionProvider = sp.getInt("net.devemperor.dictate.transcription_provider", 0);
                String apiHost = getResources().getStringArray(R.array.dictate_api_providers_values)[transcriptionProvider];
                if (apiHost.equals("custom_server")) apiHost = sp.getString("net.devemperor.dictate.transcription_custom_host", getString(R.string.dictate_custom_server_host_hint));

                String apiKey = sp.getString("net.devemperor.dictate.transcription_api_key", sp.getString("net.devemperor.dictate.api_key", "NO_API_KEY")).replaceAll("[^ -~]", "");
                String proxyHost = sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint));

                String transcriptionModel = "";
                switch (transcriptionProvider) {  // for upgrading: use old transcription_model preference
                    case 0: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_openai_model", sp.getString("net.devemperor.dictate.transcription_model", "gpt-4o-mini-transcribe")); break;
                    case 1: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_groq_model", "whisper-large-v3-turbo"); break;
                    case 2: transcriptionModel = sp.getString("net.devemperor.dictate.transcription_custom_model", getString(R.string.dictate_custom_transcription_model_hint));
                }

                OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .baseUrl(apiHost)
                        .timeout(Duration.ofSeconds(120));

                TranscriptionCreateParams.Builder transcriptionBuilder = TranscriptionCreateParams.builder()
                        .file(audioFile.toPath())
                        .model(transcriptionModel)
                        .responseFormat(AudioResponseFormat.JSON);  // gpt-4o-transcribe only supports json

                if (!currentInputLanguageValue.equals("detect")) transcriptionBuilder.language(currentInputLanguageValue);
                if (!stylePrompt.isEmpty()) transcriptionBuilder.prompt(stylePrompt);
                if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
                    if (DictateUtils.isValidProxy(proxyHost)) DictateUtils.applyProxy(clientBuilder, sp);
                }
                Log.d("DictateKeyboardSerice", "Style-Prompt: " + stylePrompt);

                Transcription transcription;
                int retryCount = 0;
                while (true) {
                    try {
                        transcription = clientBuilder.build().audio().transcriptions().create(transcriptionBuilder.build()).asTranscription();
                        break;
                    } catch (RuntimeException e) {
                        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                        boolean isRetryable = !msg.contains("api key") && !msg.contains("quota") && !msg.contains("audio duration")
                                && !msg.contains("content size limit") && !msg.contains("format");

                        if (isRetryable && retryCount < 3) {
                            retryCount++;
                            try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        } else {
                            throw e;
                        }
                    }
                }
                String resultText = transcription.text().strip();  // Groq sometimes adds leading whitespace
                resultText = applyAutoFormattingIfEnabled(resultText);

                usageDb.edit(transcriptionModel, DictateUtils.getAudioDuration(audioFile), 0, 0, transcriptionProvider);

                boolean processedByQueuedPrompts = false;
                List<Integer> promptsToApply;
                synchronized (queuedPromptIds) {
                    promptsToApply = new ArrayList<>(queuedPromptIds);
                }
                if (!promptsToApply.isEmpty()) {
                    clearQueuedPrompts();
                    if (!livePrompt) {
                        processQueuedPrompts(resultText, promptsToApply);
                        processedByQueuedPrompts = true;
                    }
                }

                if (!processedByQueuedPrompts && !livePrompt) {
                    commitTextToInputConnection(resultText);
                } else if (livePrompt) {
                    // continue with ChatGPT API request
                    livePrompt = false;
                    startGPTApiRequest(new PromptModel(-1, Integer.MIN_VALUE, "", resultText, true, false));
                }

                if (new File(getCacheDir(), sp.getString("net.devemperor.dictate.last_file_name", "audio.m4a")).exists()
                        && sp.getBoolean("net.devemperor.dictate.resend_button", false)) {
                    mainHandler.post(() -> resendButton.setVisibility(View.VISIBLE));
                }

                // Save to transcription history (skip if resending from history)
                if (!audioFile.getAbsolutePath().startsWith(getHistoryDir().getAbsolutePath())) {
                    saveToHistory(resultText, DictateUtils.getAudioDuration(audioFile));
                }

                sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", false).apply();

                if (autoSwitchKeyboard) {
                    autoSwitchKeyboard = false;
                    mainHandler.post(this::switchToPreviousKeyboard);
                }

            } catch (RuntimeException e) {
                sp.edit().putBoolean("net.devemperor.dictate.pending_transcription", false).apply();
                if (!(e.getCause() instanceof InterruptedIOException)) {
                    sendLogToCrashlytics(e);
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        String message = Objects.requireNonNull(e.getMessage()).toLowerCase();
                        if (message.contains("api key")) {
                            showInfo("invalid_api_key");
                        } else if (message.contains("quota")) {
                            showInfo("quota_exceeded");
                        } else if (message.contains("audio duration") || message.contains("content size limit")) {  // gpt-o-transcribe and whisper have different limits
                            showInfo("content_size_limit");
                        } else if (message.contains("format")) {
                            showInfo("format_not_supported");
                        } else {
                            showInfo("internet_error");
                        }
                    });
                } else if (e.getCause().getMessage() != null && (e.getCause().getMessage().contains("timeout") || e.getCause().getMessage().contains("failed to connect"))) {
                    sendLogToCrashlytics(e);
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        showInfo("timeout");
                    });
                }
            }


            mainHandler.post(() -> {
                recordButton.setText(getDictateButtonText());
                applyRecordingIconState(false);
                recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_mic_20, 0, R.drawable.ic_baseline_folder_open_20, 0); // back to original icons
                recordButton.setEnabled(true);
            });
        });
    }

    // ======================== HISTORY METHODS ========================

    private File getHistoryDir() {
        File dir = new File(getFilesDir(), "transcription_history");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void saveToHistory(String transcriptionText, float durationSeconds) {
        try {
            String ext = audioFile.getName().endsWith(".wav") ? ".wav" : ".m4a";
            String fileName = "transcription_" + System.currentTimeMillis() + ext;
            File destFile = new File(getHistoryDir(), fileName);
            copyFile(audioFile, destFile);

            TranscriptionHistoryModel model = new TranscriptionHistoryModel(
                    0, fileName, transcriptionText, durationSeconds,
                    System.currentTimeMillis(), currentInputLanguageValue);
            historyDb.add(model);
            cleanupHistory();
        } catch (IOException e) {
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    private void cleanupHistory() {
        try {
            int maxEntries = Integer.parseInt(sp.getString("net.devemperor.dictate.history_max_entries", "30"));
            int retentionDays = Integer.parseInt(sp.getString("net.devemperor.dictate.history_retention_days", "7"));
            long retentionMillis = retentionDays * 86400000L;

            List<TranscriptionHistoryModel> toDelete = historyDb.getOldEntries(maxEntries, retentionMillis);
            for (TranscriptionHistoryModel entry : toDelete) {
                File file = new File(getHistoryDir(), entry.getAudioFileName());
                if (file.exists()) file.delete();
                historyDb.delete(entry.getId());
            }
        } catch (Exception e) {
        }
    }

    private void showHistoryPanel() {
        emojiPickerCl.setVisibility(View.GONE);
        numbersPanelCl.setVisibility(View.GONE);
        hideClipboardPanel();

        List<TranscriptionHistoryModel> historyItems = historyDb.getAll();
        if (historyItems.isEmpty()) {
            historyPanelEmptyTv.setVisibility(View.VISIBLE);
            historyPanelRv.setVisibility(View.GONE);
        } else {
            historyPanelEmptyTv.setVisibility(View.GONE);
            historyPanelRv.setVisibility(View.VISIBLE);
        }

        int accentColor = sp.getInt("net.devemperor.dictate.accent_color", -14700810);
        int accentColorMedium = DictateUtils.darkenColor(accentColor, 0.18f);
        historyAdapter = new TranscriptionHistoryAdapter(historyItems, new TranscriptionHistoryAdapter.HistoryCallback() {
            @Override
            public void onItemClicked(TranscriptionHistoryModel model) {
                vibrate();
                handleHistoryItemResend(model);
            }

            @Override
            public void onPlayClicked(TranscriptionHistoryModel model, int position) {
                vibrate();
                handleHistoryItemPlay(model, position);
            }
        }, accentColorMedium);
        historyPanelRv.setLayoutManager(new LinearLayoutManager(this));
        historyPanelRv.setAdapter(historyAdapter);
        historyPanelCl.setVisibility(View.VISIBLE);
    }

    private void hideHistoryPanel() {
        historyPanelCl.setVisibility(View.GONE);
        releaseHistoryMediaPlayer();
    }

    private void releaseHistoryMediaPlayer() {
        if (historyMediaPlayer != null) {
            if (historyMediaPlayer.isPlaying()) historyMediaPlayer.stop();
            historyMediaPlayer.release();
            historyMediaPlayer = null;
        }
        if (historyAdapter != null) {
            historyAdapter.updatePlayButton(-1, false);
        }
    }

    private void handleHistoryItemPlay(TranscriptionHistoryModel model, int position) {
        // If tapping the same item that's playing, stop it
        if (historyMediaPlayer != null && historyAdapter != null
                && historyAdapter.currentlyPlayingPosition == position) {
            releaseHistoryMediaPlayer();
            return;
        }

        // Stop any current playback
        releaseHistoryMediaPlayer();

        File historyFile = new File(getHistoryDir(), model.getAudioFileName());
        if (!historyFile.exists()) return;

        try {
            historyMediaPlayer = new MediaPlayer();
            historyMediaPlayer.setDataSource(historyFile.getAbsolutePath());
            historyMediaPlayer.setOnPreparedListener(mp -> {
                mp.start();
                if (historyAdapter != null) historyAdapter.updatePlayButton(position, true);
            });
            historyMediaPlayer.setOnCompletionListener(mp -> {
                releaseHistoryMediaPlayer();
            });
            historyMediaPlayer.prepareAsync();
        } catch (IOException e) {
            releaseHistoryMediaPlayer();
        }
    }

    private void handleHistoryItemResend(TranscriptionHistoryModel model) {
        File historyFile = new File(getHistoryDir(), model.getAudioFileName());
        if (!historyFile.exists()) return;

        hideHistoryPanel();
        audioFile = historyFile;
        String elMode = sp.getString("net.devemperor.dictate.elevenlabs_mode", "off");
        if ("batch".equals(elMode) && !sp.getString("net.devemperor.dictate.elevenlabs_api_key", "").isEmpty()) {
            startElevenLabsApiRequest();
        } else {
            startWhisperApiRequest();
        }
    }

    // ======================== END HISTORY METHODS ========================

    // ======================== CLIPBOARD METHODS ========================

    private void onClipboardChanged() {
        if (clipboardManager == null) return;
        try {
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;

            // Skip sensitive content (passwords, OTPs)
            ClipDescription desc = clip.getDescription();
            if (desc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PersistableBundle extras = desc.getExtras();
                    if (extras != null && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false)) {
                        return;
                    }
                }
            }

            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || text.length() == 0) return;

            String clipText = text.toString().trim();
            if (clipText.isEmpty()) return;

            // Save on background thread
            Executors.newSingleThreadExecutor().execute(() -> {
                clipboardDb.add(clipText);
                cleanupClipboard();
            });
        } catch (Exception e) {
        }
    }

    private void showClipboardPanel() {
        emojiPickerCl.setVisibility(View.GONE);
        numbersPanelCl.setVisibility(View.GONE);
        hideHistoryPanel();
        overlayCharactersLl.setVisibility(View.GONE);
        infoCl.setVisibility(View.GONE);

        List<ClipboardHistoryModel> items = clipboardDb.getAll();
        if (items.isEmpty()) {
            clipboardPanelEmptyTv.setVisibility(View.VISIBLE);
            clipboardPanelRv.setVisibility(View.GONE);
        } else {
            clipboardPanelEmptyTv.setVisibility(View.GONE);
            clipboardPanelRv.setVisibility(View.VISIBLE);
        }

        int accentColor = sp.getInt("net.devemperor.dictate.accent_color", -14700810);
        int accentColorMedium = DictateUtils.darkenColor(accentColor, 0.18f);

        // Determine card and text colors based on theme
        String theme = sp.getString("net.devemperor.dictate.theme", "system");
        boolean isDark = "dark".equals(theme) || ("system".equals(theme) && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        int cardColor = isDark ? Color.parseColor("#2C2C2C") : Color.parseColor("#F0F0F0");
        int textColor = isDark ? Color.parseColor("#E0E0E0") : Color.parseColor("#1C1C1C");

        clipboardAdapter = new ClipboardHistoryAdapter(items, new ClipboardHistoryAdapter.ClipboardCallback() {
            @Override
            public void onItemClicked(ClipboardHistoryModel model) {
                vibrate();
                handleClipboardItemPaste(model);
            }

            @Override
            public void onItemLongClicked(ClipboardHistoryModel model, int position, View anchor) {
                vibrate();
                showClipboardItemMenu(model, position, anchor);
            }
        }, cardColor, textColor);

        StaggeredGridLayoutManager layoutManager = new StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL);
        layoutManager.setGapStrategy(StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS);
        clipboardPanelRv.setLayoutManager(layoutManager);
        clipboardPanelRv.setAdapter(clipboardAdapter);

        // Swipe-to-delete
        ItemTouchHelper.SimpleCallback swipeCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getAdapterPosition();
                ClipboardHistoryModel item = clipboardAdapter.getItem(pos);
                if (item != null) {
                    clipboardDb.delete(item.getId());
                    clipboardAdapter.removeItem(pos);
                    if (clipboardAdapter.getItemCount() == 0) {
                        clipboardPanelEmptyTv.setVisibility(View.VISIBLE);
                        clipboardPanelRv.setVisibility(View.GONE);
                    }
                }
            }
        };
        new ItemTouchHelper(swipeCallback).attachToRecyclerView(clipboardPanelRv);

        clipboardPanelCl.setVisibility(View.VISIBLE);
        clipboardPanelCl.bringToFront();
    }

    private void hideClipboardPanel() {
        if (clipboardPanelCl != null) {
            clipboardPanelCl.setVisibility(View.GONE);
        }
    }

    private void handleClipboardItemPaste(ClipboardHistoryModel model) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.commitText(model.getClipText(), 1);
        }
        hideClipboardPanel();
    }

    private void showClipboardItemMenu(ClipboardHistoryModel model, int position, View anchor) {
        Context wrapper = new ContextThemeWrapper(this, R.style.Theme_Dictate);
        PopupMenu popup = new PopupMenu(wrapper, anchor);
        popup.getMenu().add(0, 1, 0, R.string.dictate_clipboard_paste);
        popup.getMenu().add(0, 2, 1, model.isPinned() ? R.string.dictate_clipboard_unpin : R.string.dictate_clipboard_pin);
        popup.getMenu().add(0, 3, 2, R.string.dictate_clipboard_delete);

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: // Paste
                    handleClipboardItemPaste(model);
                    return true;
                case 2: // Pin/Unpin
                    clipboardDb.pin(model.getId(), !model.isPinned());
                    showClipboardPanel(); // refresh to reorder
                    return true;
                case 3: // Delete
                    clipboardDb.delete(model.getId());
                    clipboardAdapter.removeItem(position);
                    if (clipboardAdapter.getItemCount() == 0) {
                        clipboardPanelEmptyTv.setVisibility(View.VISIBLE);
                        clipboardPanelRv.setVisibility(View.GONE);
                    }
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    private void cleanupClipboard() {
        try {
            int maxItems = Integer.parseInt(sp.getString("net.devemperor.dictate.clipboard_max_items", "30"));
            int retentionHours = Integer.parseInt(sp.getString("net.devemperor.dictate.clipboard_retention_hours", "24"));
            long retentionMillis = retentionHours * 3600000L;
            clipboardDb.deleteOldUnpinned(retentionMillis, maxItems);
        } catch (Exception e) {
        }
    }

    // ======================== END CLIPBOARD METHODS ========================

    private void startGPTApiRequest(PromptModel model) {
        startGPTApiRequest(model, null, null, true);
    }

    private void startGPTApiRequest(PromptModel model, String overrideSelection, PromptResultCallback callback, boolean restorePromptsOnFinish) {
        mainHandler.post(() -> {
            promptsRv.setVisibility(View.GONE);
            runningPromptTv.setVisibility(View.VISIBLE);
            runningPromptTv.setText(model.getId() == -1 ? getString(R.string.dictate_live_prompt) : model.getName());
            runningPromptPb.setVisibility(View.VISIBLE);
            infoCl.setVisibility(View.GONE);
        });

        String systemPrompt;
        switch (sp.getInt("net.devemperor.dictate.system_prompt_selection", 1)) {
            case 1:
                systemPrompt = DictateUtils.PROMPT_REWORDING_BE_PRECISE;
                break;
            case 2:
                systemPrompt = sp.getString("net.devemperor.dictate.system_prompt_custom_text", "");
                break;
            default:
                systemPrompt = "";
        }

        rewordingApiThread = Executors.newSingleThreadExecutor();
        rewordingApiThread.execute(() -> {
            try {
                String prompt = model.getPrompt();
                String rewordedText;
                if (prompt.startsWith("[") && prompt.endsWith("]")) {
                    rewordedText = prompt.substring(1, prompt.length() - 1);
                } else {
                    CharSequence selectedText = null;
                    if (overrideSelection != null) {
                        selectedText = overrideSelection;
                    } else if (model.requiresSelection()) {
                        InputConnection selectedTextConnection = getCurrentInputConnection();
                        if (selectedTextConnection != null) {
                            selectedText = selectedTextConnection.getSelectedText(0);
                        }
                    }
                    prompt += "\n\n" + systemPrompt;
                    if (selectedText != null && selectedText.length() > 0) {
                        prompt += "\n\n" + selectedText;
                    }

                    rewordedText = requestRewordingFromApi(prompt);
                }

                if (callback != null) {
                    callback.onSuccess(rewordedText);
                } else {
                    commitTextToInputConnection(rewordedText);
                }
            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof InterruptedIOException)) {
                    sendLogToCrashlytics(e);
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        String message = Objects.requireNonNull(e.getMessage()).toLowerCase();
                        if (message.contains("api key")) {
                            showInfo("invalid_api_key");
                        } else if (message.contains("quota")) {
                            showInfo("quota_exceeded");
                        } else {
                            showInfo("internet_error");
                        }
                    });
                } else if (e.getCause().getMessage() != null && e.getCause().getMessage().contains("timeout")) {
                    sendLogToCrashlytics(e);
                    if (vibrationEnabled) vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
                    mainHandler.post(() -> {
                        resendButton.setVisibility(View.VISIBLE);
                        showInfo("timeout");
                    });
                }

                if (callback != null) {
                    callback.onFailure();
                }
                if (!restorePromptsOnFinish) {
                    restorePromptUi();
                }
            }

            if (restorePromptsOnFinish || callback == null) {
                restorePromptUi();
            }
        });
    }

    private String requestRewordingFromApi(String prompt) {
        if (sp == null) throw new IllegalStateException("Preferences unavailable");

        int rewordingProvider = sp.getInt("net.devemperor.dictate.rewording_provider", 0);
        String[] providerValues = getResources().getStringArray(R.array.dictate_api_providers_values);
        if (rewordingProvider < 0 || rewordingProvider >= providerValues.length) {
            throw new IllegalStateException("Invalid rewording provider");
        }

        String apiHost = providerValues[rewordingProvider];
        if ("custom_server".equals(apiHost)) {
            apiHost = sp.getString("net.devemperor.dictate.rewording_custom_host", getString(R.string.dictate_custom_server_host_hint));
        }

        String apiKey = sp.getString("net.devemperor.dictate.rewording_api_key",
                sp.getString("net.devemperor.dictate.api_key", "NO_API_KEY"));
        if (TextUtils.isEmpty(apiKey)) throw new IllegalStateException("API key missing");
        apiKey = apiKey.replaceAll("[^ -~]", "");
        if ("NO_API_KEY".equals(apiKey) || apiKey.isEmpty()) throw new IllegalStateException("API key missing");

        String rewordingModel;
        switch (rewordingProvider) {
            case 0:
                rewordingModel = sp.getString("net.devemperor.dictate.rewording_openai_model",
                        sp.getString("net.devemperor.dictate.rewording_model", "gpt-4o-mini"));
                break;
            case 1:
                rewordingModel = sp.getString("net.devemperor.dictate.rewording_groq_model", "llama-3.3-70b-versatile");
                break;
            case 2:
                rewordingModel = sp.getString("net.devemperor.dictate.rewording_custom_model",
                        getString(R.string.dictate_custom_rewording_model_hint));
                break;
            default:
                rewordingModel = "";
        }
        if (TextUtils.isEmpty(rewordingModel)) throw new IllegalStateException("Rewording model missing");

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(apiHost)
                .timeout(Duration.ofSeconds(120));

        if (sp.getBoolean("net.devemperor.dictate.proxy_enabled", false)) {
            String proxyHost = sp.getString("net.devemperor.dictate.proxy_host", getString(R.string.dictate_settings_proxy_hint));
            if (DictateUtils.isValidProxy(proxyHost)) {
                DictateUtils.applyProxy(clientBuilder, sp);
            }
        }

        ChatCompletionCreateParams chatCompletionCreateParams = ChatCompletionCreateParams.builder()
                .addUserMessage(prompt)
                .model(rewordingModel)
                .build();
        ChatCompletion chatCompletion;
        int retryCount = 0;
        while (true) {
            try {
                chatCompletion = clientBuilder.build().chat().completions().create(chatCompletionCreateParams);
                break;
            } catch (RuntimeException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean isRetryable = !msg.contains("api key") && !msg.contains("quota");

                if (isRetryable && retryCount < 3) {
                    retryCount++;
                    try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                } else {
                    throw e;
                }
            }
        }
        if (chatCompletion.usage().isPresent() && usageDb != null) {
            usageDb.edit(rewordingModel, 0, chatCompletion.usage().get().promptTokens(),
                    chatCompletion.usage().get().completionTokens(), rewordingProvider);
        }
        return chatCompletion.choices().get(0).message().content().orElse("");
    }

    private String applyAutoFormattingIfEnabled(String transcript) {
        if (TextUtils.isEmpty(transcript) || sp == null
                || !sp.getBoolean("net.devemperor.dictate.auto_formatting_enabled", false)
                || !sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) {
            return transcript;
        }

        try {
            String promptBuilder = AUTO_FORMATTING_PROMPT + "\n\nLanguage hint: " +
                    (currentInputLanguageValue == null ? "unknown" : currentInputLanguageValue) +
                    "\n\nTranscript:\n" +
                    transcript;

            String formattedText = requestRewordingFromApi(promptBuilder);
            if (!TextUtils.isEmpty(formattedText)) {
                return formattedText.trim();
            }
        } catch (Exception e) {
            Log.w("DictateInputMethodService", "Auto-formatting failed", e);
        }
        return transcript;
    }

    private void commitTextToInputConnection(String text) {
        // Ensure commits run on the main thread — InputConnection can be stale on background threads after rotation
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> commitTextToInputConnection(text));
            return;
        }

        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        String output = text == null ? "" : text;
        if (sp.getBoolean("net.devemperor.dictate.instant_output", true)) {
            boolean committed = inputConnection.commitText(output, 1);
            if (sp.getBoolean("net.devemperor.dictate.auto_enter", false)) {
                performEnterAction();
            }
        } else if (mainHandler != null) {
            int speed = sp.getInt("net.devemperor.dictate.output_speed", 5);
            for (int i = 0; i < output.length(); i++) {
                char character = output.charAt(i);
                String characterString = String.valueOf(character);
                long delay = (long) (i * (20L / (speed / 5f)));
                boolean isLastChar = i == output.length() - 1;
                mainHandler.postDelayed(() -> {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(characterString, 1);
                        if (isLastChar && sp.getBoolean("net.devemperor.dictate.auto_enter", false)) {
                            performEnterAction();
                        }
                    }
                }, delay);
            }
        } else {
            inputConnection.commitText(output, 1);
            if (sp.getBoolean("net.devemperor.dictate.auto_enter", false)) {
                performEnterAction();
            }
        }
    }

    private void processQueuedPrompts(String initialText, List<Integer> promptIds) {
        if (promptIds == null || promptIds.isEmpty()) {
            commitTextToInputConnection(initialText);
            return;
        }
        applyQueuedPromptAtIndex(initialText, promptIds, 0);
    }

    private void applyQueuedPromptAtIndex(String currentText, List<Integer> promptIds, int index) {
        if (index >= promptIds.size()) {
            commitTextToInputConnection(currentText);
            return;
        }

        PromptModel prompt = promptsDb.get(promptIds.get(index));
        if (prompt == null) {
            applyQueuedPromptAtIndex(currentText, promptIds, index + 1);
            return;
        }

        if (prompt.requiresSelection() && (currentText == null || currentText.isEmpty())) {
            applyQueuedPromptAtIndex(currentText, promptIds, index + 1);
            return;
        }

        String inputForPrompt = prompt.requiresSelection() ? currentText : null;
        boolean restoreUiAfter = index == promptIds.size() - 1;

        startGPTApiRequest(prompt, inputForPrompt, new PromptResultCallback() {
            @Override
            public void onSuccess(String text) {
                applyQueuedPromptAtIndex(text, promptIds, index + 1);
            }

            @Override
            public void onFailure() {
                commitTextToInputConnection(currentText == null ? "" : currentText);
            }
        }, restoreUiAfter);
    }

    private void toggleQueuedPrompt(PromptModel model) {
        if (model.getId() < 0) return;

        synchronized (queuedPromptIds) {
            Integer promptId = model.getId();
            if (queuedPromptIds.contains(promptId)) {
                queuedPromptIds.remove(promptId);
            } else {
                queuedPromptIds.add(promptId);
            }
        }
        updateQueuedPromptsUi();
    }

    private void updateQueuedPromptsUi() {
        if (promptsAdapter == null || mainHandler == null) return;
        List<Integer> snapshot;
        synchronized (queuedPromptIds) {
            snapshot = new ArrayList<>(queuedPromptIds);
        }
        mainHandler.post(() -> promptsAdapter.setQueuedPromptOrder(snapshot));
    }

    private void clearQueuedPrompts() {
        synchronized (queuedPromptIds) {
            queuedPromptIds.clear();
        }
        updateQueuedPromptsUi();
    }

    private void prepareAutoApplyQueue() {
        if (promptsDb == null || sp == null || !sp.getBoolean("net.devemperor.dictate.rewording_enabled", true)) return;
        List<Integer> autoApplyIds = promptsDb.getAutoApplyIds();
        synchronized (queuedPromptIds) {
            List<Integer> manualQueue = new ArrayList<>();
            for (Integer id : queuedPromptIds) {
                if (!autoApplyIds.contains(id)) {
                    manualQueue.add(id);
                }
            }
            queuedPromptIds.clear();
            queuedPromptIds.addAll(autoApplyIds);
            queuedPromptIds.addAll(manualQueue);
        }
        updateQueuedPromptsUi();
    }

    private void updatePromptButtonsEnabledState() {
        disableNonSelectionPrompts = isRecording || isPreparingRecording;
        if (promptsAdapter == null) return;
        if (mainHandler != null) {
            mainHandler.post(() -> {
                promptsAdapter.setDisableNonSelectionPrompts(disableNonSelectionPrompts);
                updateSelectAllPromptState();
            });
        } else {
            promptsAdapter.setDisableNonSelectionPrompts(disableNonSelectionPrompts);
            updateSelectAllPromptState();
        }
    }

    private void restorePromptUi() {
        if (mainHandler == null) return;
        mainHandler.post(() -> {
            if (promptsRv != null) promptsRv.setVisibility(View.VISIBLE);
            if (runningPromptTv != null) runningPromptTv.setVisibility(View.GONE);
            if (runningPromptPb != null) runningPromptPb.setVisibility(View.GONE);
        });
    }

    private void switchToPreviousKeyboard() {
        boolean success = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                success = switchToNextInputMethod(false);
            } else {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                success = imm.switchToLastInputMethod(getWindow().getWindow().getAttributes().token);
            }
        } catch (Exception ignored) {}

        if (!success) {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showInputMethodPicker();
        }
    }

    private void sendLogToCrashlytics(Exception e) {
        // get all values from SharedPreferences and add them as custom keys to crashlytics
        FirebaseCrashlytics crashlytics = FirebaseCrashlytics.getInstance();
        for (String key : sp.getAll().keySet()) {
            if (key.contains("api_key") || key.contains("proxy_host")) continue;
            Object value = sp.getAll().get(key);
            if (value instanceof Boolean) {
                crashlytics.setCustomKey(key, (Boolean) value);
            } else if (value instanceof Float) {
                crashlytics.setCustomKey(key, (Float) value);
            } else if (value instanceof Integer) {
                crashlytics.setCustomKey(key, (Integer) value);
            } else if (value instanceof Long) {
                crashlytics.setCustomKey(key, (Long) value);
            } else if (value instanceof String) {
                crashlytics.setCustomKey(key, (String) value);
            }
        }
        crashlytics.setUserId(sp.getString("net.devemperor.dictate.user_id", "null"));
        crashlytics.recordException(e);
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        Log.e("DictateInputMethodService", sw.toString());
        Log.e("DictateInputMethodService", "Recorded crashlytics report");
    }

    private void showInfo(String type) {
        infoCl.setVisibility(View.VISIBLE);
        infoNoButton.setVisibility(View.VISIBLE);
        infoTv.setTextColor(getResources().getColor(R.color.dictate_red, getTheme()));
        switch (type) {
            case "update":
                infoTv.setTextColor(getResources().getColor(R.color.dictate_blue, getTheme()));
                infoTv.setText(R.string.dictate_update_installed_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    openSettingsActivity();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putInt("net.devemperor.dictate.last_version_code", BuildConfig.VERSION_CODE).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "rate":
                infoTv.setTextColor(getResources().getColor(R.color.dictate_blue, getTheme()));
                infoTv.setText(R.string.dictate_rate_app_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=net.devemperor.dictate"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "donate":
                infoTv.setTextColor(getResources().getColor(R.color.dictate_blue, getTheme()));
                infoTv.setText(R.string.dictate_donate_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://paypal.me/DevEmperor"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_donated", true)  // in case someone had Dictate installed before, he shouldn't get both messages
                            .putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> {
                    sp.edit().putBoolean("net.devemperor.dictate.flag_has_donated", true)
                            .putBoolean("net.devemperor.dictate.flag_has_rated_in_playstore", true).apply();
                    infoCl.setVisibility(View.GONE);
                });
                break;
            case "timeout":
                infoTv.setText(R.string.dictate_timeout_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "invalid_api_key":
                infoTv.setText(R.string.dictate_invalid_api_key_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    openSettingsActivity();
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "quota_exceeded":
                infoTv.setText(R.string.dictate_quota_exceeded_msg);
                infoYesButton.setVisibility(View.VISIBLE);
                infoYesButton.setOnClickListener(v -> {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/settings/organization/billing/overview"));
                    browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browserIntent);
                    infoCl.setVisibility(View.GONE);
                });
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "content_size_limit":
                infoTv.setText(R.string.dictate_content_size_limit_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "format_not_supported":
                infoTv.setText(R.string.dictate_format_not_supported_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
            case "internet_error":
                infoTv.setText(R.string.dictate_internet_error_msg);
                infoYesButton.setVisibility(View.GONE);
                infoNoButton.setOnClickListener(v -> infoCl.setVisibility(View.GONE));
                break;
        }
    }

    private String getDictateButtonText() {
        List<String> allLanguagesValues = Arrays.asList(getResources().getStringArray(R.array.dictate_input_languages_values));
        List<String> recordDifferentLanguages = Arrays.asList(getResources().getStringArray(R.array.dictate_record_different_languages));

        LinkedHashSet<String> defaultLanguages = new LinkedHashSet<>(Arrays.asList(getResources().getStringArray(R.array.dictate_default_input_languages)));
        Set<String> storedLanguages = sp.getStringSet("net.devemperor.dictate.input_languages", defaultLanguages);
        LinkedHashSet<String> sanitizedLanguages = new LinkedHashSet<>();
        for (String language : storedLanguages) {
            if (allLanguagesValues.contains(language)) sanitizedLanguages.add(language);
        }
        if (sanitizedLanguages.isEmpty()) sanitizedLanguages.addAll(defaultLanguages);
        if (!sanitizedLanguages.equals(storedLanguages)) {
            sp.edit().putStringSet("net.devemperor.dictate.input_languages", sanitizedLanguages).apply();
        }

        List<String> languagesList = new ArrayList<>(sanitizedLanguages);
        if (currentInputLanguagePos >= languagesList.size()) currentInputLanguagePos = 0;
        sp.edit().putInt("net.devemperor.dictate.input_language_pos", currentInputLanguagePos).apply();

        currentInputLanguageValue = languagesList.get(currentInputLanguagePos);
        int languageIndex = allLanguagesValues.indexOf(currentInputLanguageValue);
        if (languageIndex < 0) {
            currentInputLanguageValue = allLanguagesValues.get(0);
            languageIndex = 0;
        }
        return recordDifferentLanguages.get(languageIndex);
    }

    private void deleteOneCharacter() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) return;

        CharSequence selectedText = inputConnection.getSelectedText(0);
        if (selectedText != null && selectedText.length() > 0) {
            inputConnection.commitText("", 1);
            return;
        }

        CharSequence textBeforeCursor = inputConnection.getTextBeforeCursor(DELETE_LOOKBACK_CHARACTERS, 0);
        if (textBeforeCursor == null || textBeforeCursor.length() == 0) {
            inputConnection.deleteSurroundingText(1, 0);
            return;
        }

        String before = textBeforeCursor.toString();
        BreakIterator breakIterator = BreakIterator.getCharacterInstance(Locale.getDefault());
        breakIterator.setText(before);

        int end = before.length();
        int start = breakIterator.preceding(end);
        if (start == BreakIterator.DONE) {
            try {
                start = before.offsetByCodePoints(end, -1);
            } catch (IndexOutOfBoundsException ignored) {
                start = Math.max(0, end - 1);
            }
        }

        int charsToDelete = Math.max(1, end - start);
        inputConnection.deleteSurroundingText(charsToDelete, 0);
    }

    // checks whether a point is inside a view based on its horizontal position
    private boolean isPointInsideView(float x, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        return x > location[0] && x < location[0] + view.getWidth();
    }

    private void highlightSelectedCharacter(TextView selectedView) {
        int accentColor = sp.getInt("net.devemperor.dictate.accent_color", -14700810);
        int accentColorDark = Color.argb(
                Color.alpha(accentColor),
                (int) (Color.red(accentColor) * 0.8f),
                (int) (Color.green(accentColor) * 0.8f),
                (int) (Color.blue(accentColor) * 0.8f)
        );
        for (int i = 0; i < overlayCharactersLl.getChildCount(); i++) {
            TextView charView = (TextView) overlayCharactersLl.getChildAt(i);
            GradientDrawable bg = (GradientDrawable) charView.getBackground();
            if (charView == selectedView) {
                bg.setColor(accentColorDark);
            } else {
                bg.setColor(accentColor);
            }
        }
    }

    // Compute progressive word boundaries to the left of the cursor for swipe selection
    private List<Integer> computeWordBoundaries(String before) {
        // returns absolute start indices (0..cursor) for selection:
        // boundaries[0] = cursor, boundaries[1] = start of previous "word incl. preceding spaces", etc.
        java.util.ArrayList<Integer> res = new java.util.ArrayList<>();
        int pos = before.length();
        res.add(pos);

        while (pos > 0) {
            int i = pos;

            while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;  // 1) skip whitespace to the left

            while (i > 0) {  // 2) skip non-alnum punctuation to the left
                char c = before.charAt(i - 1);
                if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) break;
                i--;
            }

            while (i > 0 && Character.isLetterOrDigit(before.charAt(i - 1))) i--;  // 3) skip letters/digits (the word)

            while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;  // 4) also include preceding spaces so each step removes "space + word"

            if (i == pos) i--;
            pos = i;
            res.add(pos);
        }

        return res;
    }

    // Recording visuals helpers (pulsing only; icons handled separately)
    private void prepareRecordPulseAnimation() {
        if (recordButton == null) return;
        recordPulseX = ObjectAnimator.ofFloat(recordButton, View.SCALE_X, 1f, 1.12f);
        recordPulseX.setDuration(600);
        recordPulseX.setRepeatMode(ValueAnimator.REVERSE);
        recordPulseX.setRepeatCount(ValueAnimator.INFINITE);
        recordPulseX.setInterpolator(new LinearInterpolator());

        recordPulseY = ObjectAnimator.ofFloat(recordButton, View.SCALE_Y, 1f, 1.12f);
        recordPulseY.setDuration(600);
        recordPulseY.setRepeatMode(ValueAnimator.REVERSE);
        recordPulseY.setRepeatCount(ValueAnimator.INFINITE);
        recordPulseY.setInterpolator(new LinearInterpolator());
    }

    private void applyRecordingIconState(boolean active) {
        if (recordButton == null || !sp.getBoolean("net.devemperor.dictate.animations", true)) return;

        if (active) {
            if (recordPulseX == null || recordPulseY == null) {
                prepareRecordPulseAnimation();
            }
            if (recordPulseX != null && !recordPulseX.isRunning()) recordPulseX.start();
            if (recordPulseY != null && !recordPulseY.isRunning()) recordPulseY.start();
        } else {
            if (recordPulseX != null) recordPulseX.cancel();
            if (recordPulseY != null) recordPulseY.cancel();
            recordButton.setScaleX(1f);
            recordButton.setScaleY(1f);
        }
    }

    // Helpers for Bluetooth/SCO availability
    private boolean hasBluetoothInputDevice() {
        try {
            AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
            for (AudioDeviceInfo info : inputs) {
                if (info.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {}
        return am.isBluetoothScoOn();  // fallback heuristic
    }

    private void updateRecordButtonIconWhileRecording() {
        if (!isRecording) return;
        if (recordingUsesBluetooth) {
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, R.drawable.ic_baseline_bluetooth_20, 0);
        } else {
            recordButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_baseline_send_20, 0, 0, 0);
        }
    }

    private void cancelScoWaitIfAny() {
        recordingPending = false;
        waitingForSco = false;
        isPreparingRecording = false;
        if (bluetoothHandler != null && scoTimeoutRunnable != null) {
            bluetoothHandler.removeCallbacks(scoTimeoutRunnable);
        }
        updatePromptButtonsEnabledState();
    }
}
