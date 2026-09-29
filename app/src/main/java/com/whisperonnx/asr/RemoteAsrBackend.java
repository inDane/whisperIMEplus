package com.whisperonnx.asr;

import android.util.Log;
import com.whisperonnx.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Remote ASR backend: wraps raw 16 kHz mono PCM16 in a WAV file and POSTs it to an
 * OpenAI-compatible /v1/audio/transcriptions endpoint (vLLM Qwen3-ASR).
 * Optional LLM cleanup pass via /v1/chat/completions.
 */
public class RemoteAsrBackend {

    public interface RemoteListener {
        void onResult(String text, String language);
        void onError(String message);
        void onStatus(String message);
    }

    private static final String TAG = "RemoteAsrBackend";
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType WAV_MEDIA = MediaType.parse("audio/wav");

    private static final String DEFAULT_MODEL = "Qwen/Qwen3-ASR-1.7B";

    private final OkHttpClient http;

    public RemoteAsrBackend() {
        http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    /** Sanitized user-facing error: generic reason only, no server/exception details (rendered in the IME view). */
    private static String safeError(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "Server not reachable — check the endpoint and your connection";
        if (e instanceof java.net.SocketTimeoutException) return "Server timed out — try again";
        if (e instanceof javax.net.ssl.SSLException) return "TLS handshake failed — check the endpoint certificate";
        return "Remote ASR failed";
    }

    /** Transcribe raw 16 kHz mono PCM16 bytes via the batch endpoint (synchronous; call off main thread). */
    public void transcribe(byte[] pcm16, String language, String endpoint, String token, String model,
                           boolean cleanup, String cleanupEndpoint, String cleanupToken, String cleanupTerms,
                           RemoteListener listener) {
        long t0 = System.currentTimeMillis();
        try {
            if (endpoint == null || endpoint.trim().isEmpty()) {
                listener.onError("No ASR endpoint configured — open Settings and set the endpoint");
                return;
            }
            String base = endpoint.trim();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            String m = (model == null || model.trim().isEmpty()) ? DEFAULT_MODEL : model.trim();

            if (!base.startsWith("https://")) {
                listener.onError("Remote ASR endpoint must use https:// — plaintext HTTP is not allowed");
                return;
            }
            byte[] wav = pcm16ToWav(pcm16);
            Log.i(TAG, "transcribe: wav=" + wav.length + " bytes, cleanup=" + cleanup);

            MultipartBody.Builder mb = new MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", RequestBody.create(wav, WAV_MEDIA))
                    .addFormDataPart("model", m)
                    .addFormDataPart("response_format", "json");
            if (language != null && !language.isEmpty() && !language.equals("auto")) {
                mb.addFormDataPart("language", language);
            }

            Request request = new Request.Builder()
                    .url(base + "/v1/audio/transcriptions")
                    .header("Authorization", "Bearer " + token)
                    .post(mb.build())
                    .build();

            try (Response response = http.newCall(request).execute()) {
                long t1 = System.currentTimeMillis();
                String body = response.body() != null ? response.body().string() : "";
                Log.i(TAG, "transcribe: HTTP " + response.code() + " in " + (t1 - t0) + " ms, " + body.length() + " bytes");
                if (!response.isSuccessful()) {
                    Log.e(TAG, "ASR HTTP " + response.code());
                    listener.onError("ASR server error " + response.code());
                    return;
                }
                JSONObject json = new JSONObject(body);
                String text = json.optString("text", "").trim();
                String lang = mapLanguage(language);

                if (text.isEmpty()) {
                    listener.onError("Empty transcription");
                    return;
                }

                if (cleanup) {
                    text = cleanUp(text, cleanupEndpoint, cleanupToken, cleanupTerms, t1, listener);
                }
                Log.d(TAG, "Remote transcription done in " + (System.currentTimeMillis() - t0) + " ms, " + text.length() + " chars");
                listener.onResult(text, lang);
            }
        } catch (Exception e) {
            Log.e(TAG, "Remote transcription failed after " + (System.currentTimeMillis() - t0) + " ms", e);
            listener.onError(safeError(e));
        }
    }

    /** LLM cleanup pass: fix punctuation/capitalization. Falls back to original text on any failure. */
    private String cleanUp(String text, String cleanupEndpoint, String cleanupToken, String cleanupTerms,
                           long t0, RemoteListener listener) {
        try {
            if (cleanupEndpoint == null || cleanupEndpoint.trim().isEmpty()) {
                Log.w(TAG, "Cleanup enabled but no endpoint configured; skipping");
                listener.onStatus("Cleanup skipped (no endpoint)");
                return text;
            }
            String url = cleanupEndpoint.trim();
            if (!url.startsWith("https://")) {
                Log.w(TAG, "Cleanup endpoint is not https; skipping");
                listener.onStatus("Cleanup skipped (endpoint must use https)");
                return text;
            }
            if (!url.contains("/v1/chat/completions")) {
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                url = url + "/v1/chat/completions";
            }
            String terms = (cleanupTerms == null) ? "" : cleanupTerms.trim();
            String systemPrompt = "You fix punctuation and capitalization of raw speech transcriptions. "
                    + "Do not add, remove or rephrase any content. Reply with only the corrected text."
                    + (terms.isEmpty() ? "" : " These terms are spelled exactly: " + terms + ".");

            JSONObject msg = new JSONObject().put("role", "user").put("content", text);
            JSONObject sys = new JSONObject().put("role", "system").put("content", systemPrompt);
            JSONObject payload = new JSONObject()
                    .put("messages", new org.json.JSONArray().put(sys).put(msg))
                    .put("max_tokens", 2048)
                    .put("temperature", 0)
                    .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false));

            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + cleanupToken)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(payload.toString(), JSON_MEDIA))
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                long ms = System.currentTimeMillis() - t0;
                if (!response.isSuccessful()) {
                    Log.w(TAG, "Cleanup HTTP " + response.code() + " in " + ms + " ms, keeping raw text");
                    listener.onStatus("Cleanup failed (" + response.code() + "), raw text kept");
                    return text;
                }
                JSONObject json = new JSONObject(body);
                String cleaned = json.getJSONArray("choices")
                        .getJSONObject(0).getJSONObject("message").optString("content", "").trim();
                if (cleaned.isEmpty()) {
                    listener.onStatus("Cleanup empty, raw text kept");
                    return text;
                }
                Log.d(TAG, "Cleanup took " + ms + " ms, " + cleaned.length() + " chars");
                listener.onStatus("Cleanup done (" + ms + " ms)");
                return cleaned;
            }
        } catch (Exception e) {
            Log.w(TAG, "Cleanup failed, keeping raw text: " + e.getMessage());
            listener.onStatus("Cleanup error: " + e.getClass().getSimpleName() + " — raw text kept");
            return text;
        }
    }

    /** Qwen3-ASR response prefix "language <Name><asr_text>" → map to a 2-letter code. */
    private static String mapLanguage(String requested) {
        if (requested != null && !requested.isEmpty() && !requested.equals("auto")) return requested;
        return "en"; // unknown detected language; only "zh" is special-cased downstream
    }

    /** Wrap 16 kHz mono PCM16 LE bytes in a canonical 44-byte WAV header. */
    private static byte[] pcm16ToWav(byte[] pcm) {
        int dataSize = pcm.length;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        bb.putInt(36 + dataSize);
        bb.put((byte) 'W').put((byte) 'A').put((byte) 'V').put((byte) 'E');
        bb.put((byte) 'f').put((byte) 'm').put((byte) 't').put((byte) ' ');
        bb.putInt(16);              // fmt chunk size
        bb.putShort((short) 1);     // PCM
        bb.putShort((short) 1);     // mono
        bb.putInt(16000);           // sample rate
        bb.putInt(32000);           // byte rate
        bb.putShort((short) 2);     // block align
        bb.putShort((short) 16);    // bits per sample
        bb.put((byte) 'd').put((byte) 'a').put((byte) 't').put((byte) 'a');
        bb.putInt(dataSize);
        bb.put(pcm);
        return bb.array();
    }
}
