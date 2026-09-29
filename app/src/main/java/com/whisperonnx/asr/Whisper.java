package com.whisperonnx.asr;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.whisperonnx.BuildConfig;

import com.whisperonnx.SetupActivity;
import com.whisperonnx.voice_translation.neural_networks.NeuralNetworkApi;
import com.whisperonnx.voice_translation.neural_networks.voice.Recognizer;
import com.whisperonnx.voice_translation.neural_networks.voice.RecognizerListener;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Whisper {

    public interface WhisperListener {
        void onUpdateReceived(String message);
        void onResultReceived(WhisperResult result);
    }

    private static final String TAG = "Whisper";
    public static final String MSG_PROCESSING = "Processing...";
    public static final String MSG_PROCESSING_DONE = "Processing done...!";

    private final AtomicBoolean mInProgress = new AtomicBoolean(false);

    private Recognizer.Action mAction;
    private String mLangCode = "";
    private WhisperListener mUpdateListener;

    private final Lock taskLock = new ReentrantLock();
    private final Condition hasTask = taskLock.newCondition();
    private volatile boolean taskAvailable = false;
    private Recognizer recognizer = null;
    private volatile boolean engineReady = false;
    private volatile boolean engineInitFailed = false;
    private Context mContext;
    private long startTime;
    private android.content.SharedPreferences sp;
    private boolean remoteMode = false;

    public Whisper(Context context) {
        mContext = context;

        sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(mContext);
        remoteMode = sp.getBoolean("remoteMode", false);
        Log.i(TAG, "Whisper created: remoteMode=" + remoteMode);

        //check if model is installed (local mode only)
        File sdcardDataFolder = mContext.getExternalFilesDir(null);

        if (sdcardDataFolder != null && !sdcardDataFolder.exists() && !sdcardDataFolder.mkdirs()) {
            Log.e(TAG, "Failed to make directory: " + sdcardDataFolder);
            return;
        }

        if (remoteMode) { // remote ASR: no local model, no setup gate
            Thread threadProcessRecordBuffer = new Thread(this::processRecordBufferLoop);
            threadProcessRecordBuffer.start();
        } else {
            File[] files = sdcardDataFolder.listFiles();
            if (files == null) files = new File[0];

            int fileCount = 0;
            for (File file : files) {
                if (file.isFile()) {
                    fileCount++;
                }
            }
            if (fileCount != 6) { //install model
                Intent intent = new Intent(mContext, SetupActivity.class);
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            } else { // Start thread for RecordBuffer transcription
                Thread threadProcessRecordBuffer = new Thread(this::processRecordBufferLoop);
                threadProcessRecordBuffer.start();
            }
        }

    }

    public void setListener(WhisperListener listener) {
        this.mUpdateListener = listener;
    }

    public synchronized void loadModel() {
        loadModel(false);
    }

    /**
     * Load the on-device engine. With ignoreRemoteMode=true the live "remoteMode" setting is
     * bypassed - required for the remote->local fallback, which must be able to start the local
     * engine even though the user is (by definition) still in remote mode when it triggers.
     */
    private synchronized void loadModel(boolean ignoreRemoteMode) {
        if (recognizer != null) {
            return; // already loaded (idempotent - safe to call on switch)
        }
        if (!ignoreRemoteMode && sp.getBoolean("remoteMode", false)) {
            Log.d(TAG, "Remote mode: no local model to load");
            return;
        }
        recognizer = new Recognizer(mContext, false, new NeuralNetworkApi.InitListener() {
            @Override
            public void onInitializationFinished() {
                Log.d(TAG, "Recognizer initialized");
                synchronized (Whisper.this) {
                    engineReady = true;
                    Whisper.this.notifyAll();
                }
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "Recognizer init error");
                synchronized (Whisper.this) {
                    engineReady = false;
                    engineInitFailed = true;
                    Whisper.this.notifyAll();
                }
            }
        });


        recognizer.addCallback(new RecognizerListener() {
            @Override
            public void onSpeechRecognizedResult(String text, String languageCode, double confidenceScore, boolean isFinal) {
                if (BuildConfig.DEBUG) Log.d(TAG, languageCode + " " + text);
                WhisperResult whisperResult = new WhisperResult(text,languageCode, mAction);

                sendResult(whisperResult);

                long timeTaken = System.currentTimeMillis() - startTime;
                Log.d(TAG, "Time Taken for transcription: " + timeTaken + "ms");
                sendUpdate(MSG_PROCESSING_DONE);
                releaseAsyncLocal(); // N1: re-enable the mic button only now
            }

            @Override
            public void onError(int[] reasons, long value) {
                Log.d(TAG, "ERROR during recognition");
                releaseAsyncLocal(); // N1: MUST also reset on error, or the keyboard hangs
                sendUpdate("Local transcription failed - try again");
            }
        });
    }

    public void unloadModel() {
        releaseAsyncLocal();
        synchronized (this) {
            if (recognizer != null) {
                recognizer.destroy();
                recognizer = null;
            }
            engineReady = false;
            engineInitFailed = false;
            this.notifyAll();
        }
    }

    /**
     * Block the calling (worker) thread until the on-device engine reports ready/failed, or up
     * to timeoutMs. Returns true if the engine is ready to accept an utterance. Guards against
     * the async ONNX load (Recognizer's constructor loads models on a background thread) so we
     * never hand a buffer to a half-loaded engine (which would be silently dropped).
     */
    private boolean waitForEngine(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (this) {
            while (!engineReady && !engineInitFailed) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    this.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return engineReady;
        }
    }

    public void setAction(Recognizer.Action action) {
        this.mAction = action;
    }

    public void setLanguage(String language){
        this.mLangCode = language;
    }

    /**
     * Whether the current input field is sensitive (password/PIN/credit-card, or an
     * incognito/privacy-flagged field). When set, the remote engine is not used for the next
     * utterance even if remote mode is on - audio would otherwise be sent off-device for a
     * field that must stay local.
     */
    private volatile boolean fieldSensitive = false;

    // N1: local recognize() is async (its own thread) while processRecordBuffer() returns
    // immediately. Keep mInProgress set until the result/error callback fires, so the mic
    // button cannot start a second recording that would steal this result's field
    // attribution. A safety timeout resets it in case a callback never arrives.
    private volatile boolean asyncLocalPending = false;
    private android.os.Handler localResetHandler = null;
    private android.os.Message localResetToken = null;
    private Runnable localResetRunnable = null;

    private void releaseAsyncLocal() {
        if (asyncLocalPending) {
            asyncLocalPending = false;
            if (localResetHandler != null) {
                if (localResetRunnable != null) localResetHandler.removeCallbacks(localResetRunnable);
                if (localResetToken != null) localResetHandler.removeCallbacksAndMessages(localResetToken);
            }
            mInProgress.set(false);
        }
    }

    public void setFieldSensitive(boolean sensitive) {
        this.fieldSensitive = sensitive;
    }

    public void start() {
        if (!mInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Execution is already in progress...");
            return;
        }
        taskLock.lock();
        try {
            taskAvailable = true;
            hasTask.signal();
        } finally {
            taskLock.unlock();
        }
    }

    public void stop() {
        mInProgress.set(false);
    }

    public boolean isInProgress() {
        return mInProgress.get();
    }

    private void processRecordBufferLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            taskLock.lock();
            try {
                while (!taskAvailable) {
                    hasTask.await();
                }
                processRecordBuffer();
                taskAvailable = false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                taskLock.unlock();
            }
        }
    }

    private void processRecordBuffer() {
        try {
            // read live: switching the engine in Settings takes effect immediately
            // Live remote-mode read; sensitive fields (passwords, PIN, incognito) force local
            boolean sensitive = fieldSensitive;
            fieldSensitive = false; // consume for this utterance
            boolean remote = sp.getBoolean("remoteMode", false) && !sensitive;
            if (RecordBuffer.getOutputBuffer() != null) {
                startTime = System.currentTimeMillis();
                sendUpdate(MSG_PROCESSING);
                if (remote) {
                    processRemote();
                } else {
                    // Local mode: lazily load the engine if the instance started in remote
                    // mode (loadModel() is idempotent and reads the live setting).
                    // A sensitive field forced local processing while the live setting is still
                    // remote -> bypass the remoteMode gate exactly like the fallback does,
                    // otherwise the engine would never start and dictation dead-ends.
                    if (recognizer == null) {
                        loadModel(sensitive);
                    }
                    if (recognizer != null && (engineReady || waitForEngine(15000))) {
                        recognizer.recognize(RecordBuffer.getSamples(),1, mLangCode, mAction );
                        // recognize() returns immediately (own thread): keep mInProgress set
                        // until the result/error callback resets it, plus a safety timeout.
                        asyncLocalPending = true;
                        localResetHandler = new android.os.Handler(android.os.Looper.getMainLooper());
                        localResetToken = android.os.Message.obtain();
                        localResetRunnable = () -> {
                            if (asyncLocalPending) {
                                asyncLocalPending = false;
                                mInProgress.set(false);
                                sendUpdate("Transcription timed out - try again");
                            }
                        };
                        // postAtTime with a token: the timeout is cancellable in releaseAsyncLocal()
                        localResetHandler.postAtTime(localResetRunnable, localResetToken, 60000);
                        return; // finally must NOT clear mInProgress for the async path
                    } else {
                        sendUpdate(engineInitFailed ? "Local engine failed to load" : "Local engine not ready yet - try again");
                    }
                }
            } else {
                sendUpdate("Engine not initialized or file path not set");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during transcription", e);
            sendUpdate("Transcription failed");
        } finally {
            // Async local path: mInProgress stays set until the result/error callback
            // (releaseAsyncLocal) or the safety timeout; everything else clears now.
            if (!asyncLocalPending) {
                mInProgress.set(false);
            }
        }
    }

    /** Remote ASR: raw PCM16 → WAV → POST /v1/audio/transcriptions (+ optional LLM cleanup). Runs on the worker thread. */
    private void processRemote() {
        byte[] pcm = RecordBuffer.getOutputBuffer();
        Log.i(TAG, "processRemote: " + pcm.length + " bytes");
        sendUpdate("Remote: uploading " + (pcm.length / 1024) + " KB…");
        new RemoteAsrBackend().transcribe(
                pcm,
                mLangCode,
                sp.getString("remoteEndpoint", ""),
                sp.getString("remoteToken", ""),
                sp.getString("remoteModel", ""),
                sp.getBoolean("remoteCleanup", false),
                sp.getString("cleanupEndpoint", ""),
                sp.getString("cleanupToken", ""),
                sp.getString("cleanupTerms", ""),
                new RemoteAsrBackend.RemoteListener() {
                    @Override
                    public void onResult(String text, String languageCode) {
                        long timeTaken = System.currentTimeMillis() - startTime;
                        Log.d(TAG, "Time Taken for remote transcription: " + timeTaken + "ms");
                        sendResult(new WhisperResult(text, languageCode, mAction));
                        sendUpdate(MSG_PROCESSING_DONE);
                    }

                    @Override
                    public void onError(String message) {
                        Log.e(TAG, "Remote ASR error: " + message);
                        if (!tryLocalFallback()) {
                            sendUpdate(message);
                        }
                    }

                    @Override
                    public void onStatus(String message) {
                        Log.d(TAG, "Remote status: " + message);
                        sendUpdate(message);
                    }
                });
    }

    /**
     * Remote ASR failed: retry the same utterance on the on-device engine when it's usable.
     * Runs on the worker thread, so the audio buffer is still valid (transcribe() is
     * synchronous). Returns true if the local engine accepted the utterance (or there's no
     * local model to fall back to, in which case a specific message is posted); false to let
     * the caller post the original remote error.
     */
    private boolean tryLocalFallback() {
        File dir = mContext.getExternalFilesDir(null);
        if (dir == null) return false;
        File[] files = dir.listFiles();
        if (files == null) return false;
        int modelFiles = 0;
        for (File f : files) if (f.isFile()) modelFiles++;
        if (modelFiles < 6) {
            sendUpdate("Remote ASR failed - no local model installed to fall back to");
            return true;
        }
        if (recognizer == null) {
            loadModel(true); // bypass the remoteMode gate - we ARE in remote mode, that's why we're falling back
        }
        if (recognizer != null && (engineReady || waitForEngine(20000))) {
            float[] samples = RecordBuffer.getSamples();
            if (samples == null || samples.length == 0) {
                return false;
            }
            sendUpdate("Remote ASR failed - retrying on-device...");
            recognizer.recognize(samples, 1, mLangCode, mAction);
            // N1: same async gap on the fallback path - keep mInProgress set until the
            // result/error callback (or the safety timeout) fires.
            asyncLocalPending = true;
            localResetHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            localResetToken = android.os.Message.obtain();
            localResetRunnable = () -> {
                if (asyncLocalPending) {
                    asyncLocalPending = false;
                    mInProgress.set(false);
                    sendUpdate("Transcription timed out - try again");
                }
            };
            localResetHandler.postAtTime(localResetRunnable, localResetToken, 60000);
            return true;
        }
        sendUpdate("Remote ASR failed - local engine unavailable");
        return true;
    }

    private void sendUpdate(String message) {
        if (mUpdateListener != null) {
            mUpdateListener.onUpdateReceived(message);
        }
    }

    private void sendResult(WhisperResult whisperResult) {
        if (mUpdateListener != null) {
            mUpdateListener.onResultReceived(whisperResult);
        }
    }

}
