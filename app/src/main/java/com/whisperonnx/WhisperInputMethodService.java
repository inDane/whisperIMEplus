package com.whisperonnx;


import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSCRIBE;
import static com.whisperonnx.voice_translation.neural_networks.voice.Recognizer.ACTION_TRANSLATE;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;

import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.whisperonnx.asr.Recorder;
import com.whisperonnx.asr.Whisper;
import com.whisperonnx.asr.WhisperResult;
import com.whisperonnx.utils.HapticFeedback;

public class WhisperInputMethodService extends InputMethodService {
    private static final String TAG = "WhisperInputMethodService";
    private ImageButton btnRecord;
    private ImageButton btnKeyboard;
    private ImageButton btnStop;
    private ImageButton btnTranslate;
    private ImageButton btnModeAuto;
    private ImageButton btnEnter;
    private ImageButton btnDel;
    private ImageButton btnLang1;
    private ImageButton btnLang2;
    private TextView tvStatus;
    private Recorder mRecorder = null;
    private Whisper mWhisper = null;
    private ProgressBar processingBar = null;
    private SharedPreferences sp = null;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Context mContext;
    private CountDownTimer countDownTimer;
    private static boolean translate = false;
    private android.view.inputmethod.InputConnection recordingInputConnection = null;
    private String recordingFieldId = null;
    private boolean fieldSensitivePending = false;
    private boolean recordingFieldSensitive = false;
    private boolean modeAuto = false;
    private RelativeLayout layoutButtons;

    @Override
    public void onCreate() {
        mContext = this;
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        deinitModel();
        if (mRecorder != null && mRecorder.isInProgress()) {
            mRecorder.stop();
        }
        super.onDestroy();
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        if (attribute.inputType ==  EditorInfo.TYPE_NULL) {
            // Release builds must not log which app/field the keyboard is used in
            Log.d(TAG, com.whisperonnx.BuildConfig.DEBUG
                    ? "Cancelling: onStartInput: inputType=" + attribute.inputType + ", package=" + attribute.packageName + ", fieldId=" + attribute.fieldId
                    : "Cancelling: onStartInput (empty input field)" );
            deinitModel();
            if (mRecorder != null && mRecorder.isInProgress()) {
                mRecorder.stop();
            }
        }
    }

    @Override
    public void onStartInputView(EditorInfo attribute, boolean restarting){

        if (mWhisper == null)  initModel();

    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public View onCreateInputView() {  //runs before onStartInputView
        sp = PreferenceManager.getDefaultSharedPreferences(this);

        String langCodeIME = sp.getString("language", "auto");

        if (!sp.contains("langSelected")){
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected",1);
            editor.putString("language1",langCodeIME);
            editor.putString("language2","auto");
            editor.apply();
        }

        View view = getLayoutInflater().inflate(R.layout.voice_service, null);

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = insets.left;
            mlp.bottomMargin = insets.bottom;
            mlp.rightMargin = insets.right;
            v.setLayoutParams(mlp);

            return WindowInsetsCompat.CONSUMED;
        });

        btnRecord = view.findViewById(R.id.btnRecord);
        btnKeyboard = view.findViewById(R.id.btnKeyboard);
        btnStop = view.findViewById(R.id.btnStop);
        btnTranslate = view.findViewById(R.id.btnTranslate);
        btnModeAuto = view.findViewById(R.id.btnModeAuto);
        btnEnter = view.findViewById(R.id.btnEnter);
        btnDel = view.findViewById(R.id.btnDel);
        btnLang1 = view.findViewById(R.id.btnLang1);
        btnLang2 = view.findViewById(R.id.btnLang2);
        int langSelected = sp.getInt("langSelected", 1);
        if (langSelected == 1) {
            btnLang1.setImageResource(R.drawable.ic_counter_1_on_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_off_36dp);
        } else {
            btnLang1.setImageResource(R.drawable.ic_counter_1_off_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_on_36dp);
        }

        processingBar = view.findViewById(R.id.processing_bar);
        tvStatus = view.findViewById(R.id.tv_status);
        btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        modeAuto = sp.getBoolean("imeModeAuto",false);
        btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
        layoutButtons = view.findViewById(R.id.layout_buttons);
        checkRecordPermission();

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message.equals(Recorder.MSG_RECORDING)) {
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                    startTranscription();
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) { countDownTimer.cancel();}
                    handler.post(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        tvStatus.setText(getString(R.string.error_no_input));
                        tvStatus.setVisibility(View.VISIBLE);
                        processingBar.setProgress(0);
                    });
                }
            }

        });

        if (modeAuto) {
            layoutButtons.setVisibility(View.GONE);
            btnStop.setVisibility(View.VISIBLE);
            HapticFeedback.vibrate(this);
            startRecording();
            handler.post(() -> processingBar.setProgress(100));
            countDownTimer = new CountDownTimer(30000, 1000) {
                @Override
                public void onTick(long l) {
                    handler.post(() -> processingBar.setProgress((int) (l / 300)));
                }
                @Override
                public void onFinish() {}
            };
            countDownTimer.start();
            handler.post(() -> {
                tvStatus.setText("");
                tvStatus.setVisibility(View.GONE);
            });
        }

        btnDel.setOnTouchListener(new View.OnTouchListener() {
            private Runnable initialDeleteRunnable;
            private Runnable repeatDeleteRunnable;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                    // Post the initial delay of 500ms
                    initialDeleteRunnable = new Runnable() {
                        @Override
                        public void run() {
                            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                            // Start repeating every 100ms
                            repeatDeleteRunnable = new Runnable() {
                                @Override
                                public void run() {
                                    getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                                    handler.postDelayed(this, 100);
                                }
                            };
                            handler.postDelayed(repeatDeleteRunnable, 100);
                        }
                    };
                    handler.postDelayed(initialDeleteRunnable, 500);
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    // Remove both callbacks
                    if (initialDeleteRunnable != null) {
                        handler.removeCallbacks(initialDeleteRunnable);
                    }
                    if (repeatDeleteRunnable != null) {
                        handler.removeCallbacks(repeatDeleteRunnable);
                    }
                    initialDeleteRunnable = null;
                    repeatDeleteRunnable = null;
                }
                return true;
            }
        });

        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Pressed
                handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                if (checkRecordPermission()){
                    if (!mWhisper.isInProgress()) {
                        HapticFeedback.vibrate(this);
                        startRecording();
                        handler.post(() -> processingBar.setProgress(100));
                        countDownTimer = new CountDownTimer(30000, 1000) {
                            @Override
                            public void onTick(long l) {
                                handler.post(() -> processingBar.setProgress((int) (l / 300)));
                            }
                            @Override
                            public void onFinish() {}
                        };
                        countDownTimer.start();
                        handler.post(() -> {
                            tvStatus.setText("");
                            tvStatus.setVisibility(View.GONE);
                        });
                    } else {
                        handler.post(() -> {
                            tvStatus.setText(getString(R.string.please_wait));
                            tvStatus.setVisibility(View.VISIBLE);
                        });
                    }
                }
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Released
                handler.post(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    mRecorder.stop();
                }
            }
            return true;
        });

        btnKeyboard.setOnClickListener(v -> {
            if (mWhisper != null) stopTranscription();
            switchToPreviousInputMethod();
        });

        btnStop.setOnClickListener(v -> {
            if (mRecorder!=null) mRecorder.requestStopVad();
        });

        btnTranslate.setOnClickListener(v -> {
            translate = !translate;
            btnTranslate.setImageResource(translate ? R.drawable.ic_english_on_36dp : R.drawable.ic_english_off_36dp);
        });

        btnEnter.setOnClickListener(v -> {
            getCurrentInputConnection().sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        });

        btnModeAuto.setOnClickListener(v -> {
            modeAuto = !modeAuto;
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("imeModeAuto", modeAuto);
            editor.apply();
            layoutButtons.setVisibility(modeAuto ? View.GONE : View.VISIBLE);
            btnStop.setVisibility(modeAuto ? View.VISIBLE : View.GONE);
            btnModeAuto.setImageResource(modeAuto ? R.drawable.ic_auto_on_36dp : R.drawable.ic_auto_off_36dp);
            switchToPreviousInputMethod();
        });

        btnLang1.setOnClickListener(v -> {
            String lang = sp.getString("language1", "auto");
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected", 1);
            editor.putString("language", lang);
            editor.apply();
            btnLang1.setImageResource(R.drawable.ic_counter_1_on_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_off_36dp);
        });

        btnLang2.setOnClickListener(v -> {
            String lang = sp.getString("language2", "auto");
            SharedPreferences.Editor editor = sp.edit();
            editor.putInt("langSelected", 2);
            editor.putString("language", lang);
            editor.apply();
            btnLang1.setImageResource(R.drawable.ic_counter_1_off_36dp);
            btnLang2.setImageResource(R.drawable.ic_counter_2_on_36dp);
        });

        return view;
    }

    private void startRecording() {
        markFieldSensitivity();
        captureRecordingField();
        if (modeAuto) mRecorder.initVad();
        mRecorder.start();
    }

    /**
     * Sensitive fields (password, PIN, credit card, incognito/privacy-flagged) force the
     * on-device engine even when remote mode is on - the audio of a password field must not
     * leave the device.
     *
     * In auto mode the Whisper instance is created AFTER startRecording() (Android calls
     * onCreateInputView() before onStartInputView(), where initModel() runs), so the flag is
     * stored here and applied to the instance in initModel() - otherwise auto mode would
     * silently send password-field audio to the remote server.
     */
    private void markFieldSensitivity() {
        android.view.inputmethod.EditorInfo ei = getCurrentInputEditorInfo();
        boolean sensitive = false;
        if (ei != null) {
            int t = ei.inputType;
            int classMask = android.text.InputType.TYPE_MASK_CLASS;
            int varMask = android.text.InputType.TYPE_MASK_VARIATION;
            sensitive
                = (t & classMask) == android.text.InputType.TYPE_CLASS_NUMBER       // PIN / card number
                || (t & varMask) == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                || (t & varMask) == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || (t & varMask) == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || (ei.imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0; // incognito
        }
        fieldSensitivePending = sensitive;
        if (mWhisper != null) {
            mWhisper.setFieldSensitive(sensitive);
        }
        if (sensitive && sp.getBoolean("remoteMode", false)) {
            handler.post(() -> {
                tvStatus.setText(R.string.sensitive_field_local);
                tvStatus.setVisibility(View.VISIBLE);
            });
        }
    }

    /** Remember which field is being dictated into, so a slow (remote) result never lands in a different field. */
    private void captureRecordingField() {
        recordingInputConnection = getCurrentInputConnection();
        android.view.inputmethod.EditorInfo ei = getCurrentInputEditorInfo();
        recordingFieldId = ei != null ? ei.packageName + "/" + ei.fieldId + "/" + ei.inputType : null;
        recordingFieldSensitive = fieldSensitivePending;
    }

    private boolean sameFieldAsRecording() {
        if (recordingFieldId == null) return true; // no info - keep previous behavior
        android.view.inputmethod.EditorInfo ei = getCurrentInputEditorInfo();
        String now = ei != null ? ei.packageName + "/" + ei.fieldId + "/" + ei.inputType : null;
        return recordingFieldId.equals(now) && getCurrentInputConnection() == recordingInputConnection;
    }

    // Model initialization
    private void initModel() {

        mWhisper = new Whisper(this);
        // Auto mode started recording before this instance existed (onCreateInputView runs
        // before onStartInputView): carry the field's sensitivity over, so a password field
        // is never dictated to the remote server even though the check ran pre-creation.
        mWhisper.setFieldSensitive(fieldSensitivePending);
        mWhisper.loadModel();
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                if (message != null && !message.equals(Whisper.MSG_PROCESSING) && !message.equals(Whisper.MSG_PROCESSING_DONE)) {
                    handler.post(() -> {
                        tvStatus.setText(message);
                        tvStatus.setVisibility(View.VISIBLE);
                    });
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                handler.post(() -> processingBar.setIndeterminate(false));
                handler.post(() -> {
                    tvStatus.setText("");
                    tvStatus.setVisibility(View.GONE);
                });

                String result = whisperResult.getResult();
                if (whisperResult.getLanguage().equals("zh")){
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);
                    result = simpleChinese ? ZhConverterUtil.toSimple(result) : ZhConverterUtil.toTraditional(result);
                }
                boolean commitSuccess = false;
                if (result.trim().length() > 0) {
                    if (sameFieldAsRecording()) {
                        commitSuccess = getCurrentInputConnection().commitText(result.trim() + " ",1);
                    } else if (recordingFieldSensitive) {
                        // Sensitive field: the user moved away mid-processing. Discard entirely -
                        // a dictated password must not sit in the clipboard or its history.
                        handler.post(() -> {
                            tvStatus.setText(R.string.sensitive_field_discarded);
                            tvStatus.setVisibility(View.VISIBLE);
                        });
                    } else {
                        // Field changed while (remote) processing ran: never inject the text into
                        // the new field - copy to clipboard instead and tell the user.
                        // (onResultReceived runs on a background thread; clipboard + Toast
                        // must be posted to the main thread or Android throws.)
                        final String finalResult = result.trim();
                        handler.post(() -> {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("Whisper+", finalResult));
                            Toast.makeText(WhisperInputMethodService.this, R.string.result_copied_field_changed, Toast.LENGTH_LONG).show();
                        });
                    }
                }
                if (modeAuto && commitSuccess) handler.postDelayed(() -> switchToPreviousInputMethod(), 100);  //slightly delayed, otherwise some apps, e.g. WhatsApp, do not accept the committed text (commitText on inactive InputConnection)
            }
        });
    }

    private void startTranscription() {
        if (countDownTimer!=null) { countDownTimer.cancel();}
        handler.post(() -> processingBar.setProgress(0));
        handler.post(() -> processingBar.setIndeterminate(true));
        if (mWhisper!=null){
            if (translate) mWhisper.setAction(ACTION_TRANSLATE);
            else mWhisper.setAction(ACTION_TRANSCRIBE);

            String langCode = sp.getString("language", "auto");
            Log.d("WhisperIME","default langCode " + langCode);
            mWhisper.setLanguage(langCode);
            mWhisper.start();
        }
    }

    private void stopTranscription() {
        handler.post(() -> processingBar.setIndeterminate(false));
        mWhisper.stop();
    }

    private boolean checkRecordPermission() {
        int permission = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO);
        if (permission != PackageManager.PERMISSION_GRANTED) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(getString(R.string.need_record_audio_permission));
        }
        return (permission == PackageManager.PERMISSION_GRANTED);
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }
}