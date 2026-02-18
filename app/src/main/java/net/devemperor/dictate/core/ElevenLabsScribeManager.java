package net.devemperor.dictate.core;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class ElevenLabsScribeManager {

    private static final String TAG = "ElevenLabsScribe";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_SIZE = 8000; // ~0.25s of 16kHz 16-bit mono (4000 samples * 2 bytes)

    private static final Map<String, String> LANGUAGE_MAP;

    static {
        Map<String, String> map = new HashMap<>();
        map.put("af", "afr");
        map.put("ar", "ara");
        map.put("hy", "hye");
        map.put("az", "aze");
        map.put("be", "bel");
        map.put("bn", "ben");
        map.put("bg", "bul");
        map.put("yue-cn", "yue");
        map.put("yue-hk", "yue");
        map.put("ca", "cat");
        map.put("cs", "ces");
        map.put("da", "dan");
        map.put("nl", "nld");
        map.put("en", "eng");
        map.put("et", "est");
        map.put("fi", "fin");
        map.put("fr", "fra");
        map.put("gl", "glg");
        map.put("de", "deu");
        map.put("el", "ell");
        map.put("he", "heb");
        map.put("hi", "hin");
        map.put("hu", "hun");
        map.put("id", "ind");
        map.put("it", "ita");
        map.put("ja", "jpn");
        map.put("kk", "kaz");
        map.put("ko", "kor");
        map.put("lv", "lav");
        map.put("lt", "lit");
        map.put("mk", "mkd");
        map.put("zh-cn", "zho");
        map.put("zh-tw", "zho");
        map.put("mr", "mar");
        map.put("ne", "nep");
        map.put("nn", "nor");
        map.put("fa", "fas");
        map.put("pl", "pol");
        map.put("pt", "por");
        map.put("pa", "pan");
        map.put("ro", "ron");
        map.put("ru", "rus");
        map.put("sr", "srp");
        map.put("sk", "slk");
        map.put("sl", "slv");
        map.put("es", "spa");
        map.put("sw", "swa");
        map.put("sv", "swe");
        map.put("ta", "tam");
        map.put("th", "tha");
        map.put("tr", "tur");
        map.put("uk", "ukr");
        map.put("ur", "urd");
        map.put("vi", "vie");
        map.put("cy", "cym");
        LANGUAGE_MAP = Collections.unmodifiableMap(map);
    }

    public interface ScribeCallback {
        void onSessionStarted();
        void onPartialTranscript(String text);
        void onCommittedTranscript(String text);
        void onError(String errorType, String message);
        void onComplete(String fullText, File audioFile, float durationSeconds);
        void onConnectionClosed();
    }

    private OkHttpClient client;
    private WebSocket webSocket;
    private AudioRecord audioRecord;
    private Thread streamingThread;
    private RandomAccessFile wavWriter;
    private File audioFile;
    private volatile boolean isStreaming;
    private volatile boolean isClosing;
    private final Handler mainHandler;
    private ScribeCallback callback;
    private long totalPcmBytes;
    private final StringBuilder committedTextBuffer = new StringBuilder();
    private CountDownLatch finalCommitLatch;

    public ElevenLabsScribeManager(Handler mainHandler) {
        this.mainHandler = mainHandler;
    }

    public boolean isStreaming() {
        return isStreaming;
    }

    @SuppressLint("MissingPermission")
    public void start(String apiKey, String languageCode, File outputFile, ScribeCallback cb) {
        this.callback = cb;
        this.audioFile = outputFile;
        this.isStreaming = true;
        this.isClosing = false;
        this.totalPcmBytes = 0;
        this.committedTextBuffer.setLength(0);
        this.finalCommitLatch = new CountDownLatch(1);

        // Create WAV file with placeholder header
        try {
            wavWriter = new RandomAccessFile(outputFile, "rw");
            wavWriter.setLength(0);
            writeWavHeader(wavWriter, 0);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create WAV file", e);
            isStreaming = false;
            mainHandler.post(() -> callback.onError("file_error", "Failed to create audio file"));
            return;
        }

        // Initialize AudioRecord
        int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufSize, CHUNK_SIZE * 2);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed");
            isStreaming = false;
            closeWavWriter();
            mainHandler.post(() -> callback.onError("audio_error", "AudioRecord initialization failed"));
            return;
        }

        // Build WebSocket URL
        String langParam = mapLanguageCode(languageCode);
        String url = "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&commit_strategy=vad";
        if (langParam != null) {
            url += "&language_code=" + langParam;
        }

        client = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .addHeader("xi-api-key", apiKey)
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onSessionStarted();
                });
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JSONObject json = new JSONObject(text);
                    String messageType = json.optString("message_type", "");

                    switch (messageType) {
                        case "session_started":
                            mainHandler.post(() -> {
                                if (callback != null) callback.onSessionStarted();
                            });
                            break;

                        case "partial_transcript": {
                            String partialText = json.optString("text", "");
                            if (!partialText.isEmpty()) {
                                mainHandler.post(() -> {
                                    if (callback != null) callback.onPartialTranscript(partialText);
                                });
                            }
                            break;
                        }

                        case "committed_transcript": {
                            String committedText = json.optString("text", "");
                            if (!committedText.isEmpty()) {
                                committedTextBuffer.append(committedText);
                                mainHandler.post(() -> {
                                    if (callback != null) callback.onCommittedTranscript(committedText);
                                });
                            }
                            // If we're closing and waiting for final commit, signal it
                            if (isClosing) {
                                finalCommitLatch.countDown();
                            }
                            break;
                        }

                        case "error": {
                            String errorType = json.optString("error_type", "unknown");
                            String errorMessage = json.optString("error_message", "Unknown error");
                            Log.e(TAG, "Server error: " + errorType + " - " + errorMessage);
                            mainHandler.post(() -> {
                                if (callback != null) callback.onError(errorType, errorMessage);
                            });
                            break;
                        }
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse message: " + text, e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "WebSocket failure", t);
                if (isStreaming) {
                    isStreaming = false;
                    stopAudioRecord();
                    finalizeWav();

                    String errorMsg = t.getMessage() != null ? t.getMessage() : "Connection failed";
                    boolean isAuth = response != null && (response.code() == 401 || response.code() == 403);

                    mainHandler.post(() -> {
                        if (callback != null) {
                            if (isAuth) {
                                callback.onError("auth", errorMsg);
                            } else {
                                callback.onError("connection", errorMsg);
                            }
                        }
                    });
                }
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onConnectionClosed();
                });
            }
        });

        // Start AudioRecord and streaming thread
        audioRecord.startRecording();

        streamingThread = new Thread(() -> {
            byte[] buffer = new byte[CHUNK_SIZE];
            while (isStreaming && audioRecord != null && audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                int bytesRead = audioRecord.read(buffer, 0, CHUNK_SIZE);
                if (bytesRead > 0) {
                    // Write to WAV file
                    try {
                        if (wavWriter != null) {
                            wavWriter.write(buffer, 0, bytesRead);
                            totalPcmBytes += bytesRead;
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to write WAV data", e);
                    }

                    // Send to WebSocket
                    if (webSocket != null && isStreaming) {
                        String base64 = Base64.encodeToString(buffer, 0, bytesRead, Base64.NO_WRAP);
                        try {
                            JSONObject msg = new JSONObject();
                            msg.put("message_type", "input_audio_chunk");
                            msg.put("audio_base_64", base64);
                            msg.put("commit", false);
                            msg.put("sample_rate", SAMPLE_RATE);
                            webSocket.send(msg.toString());
                        } catch (JSONException e) {
                            Log.e(TAG, "Failed to create JSON message", e);
                        }
                    }
                }
            }
        }, "ElevenLabsStreaming");
        streamingThread.start();
    }

    public void stop() {
        if (!isStreaming) return;
        isClosing = true;

        // Stop AudioRecord first
        stopAudioRecord();

        // Send final commit message
        if (webSocket != null) {
            try {
                JSONObject msg = new JSONObject();
                msg.put("message_type", "input_audio_chunk");
                msg.put("audio_base_64", "");
                msg.put("commit", true);
                msg.put("sample_rate", SAMPLE_RATE);
                webSocket.send(msg.toString());
            } catch (JSONException e) {
                Log.e(TAG, "Failed to send commit message", e);
            }
        }

        // Wait for final committed transcript (with timeout)
        new Thread(() -> {
            try {
                finalCommitLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }

            isStreaming = false;

            // Close WebSocket
            if (webSocket != null) {
                try {
                    webSocket.close(1000, "Recording stopped");
                } catch (Exception ignored) {
                }
                webSocket = null;
            }

            // Finalize WAV
            finalizeWav();

            float durationSeconds = (float) totalPcmBytes / (SAMPLE_RATE * 2); // 16-bit = 2 bytes per sample
            String fullText = committedTextBuffer.toString();

            mainHandler.post(() -> {
                if (callback != null) callback.onComplete(fullText, audioFile, durationSeconds);
            });
        }, "ElevenLabsStop").start();
    }

    public void cancel() {
        isStreaming = false;
        isClosing = false;

        stopAudioRecord();

        if (webSocket != null) {
            try {
                webSocket.cancel();
            } catch (Exception ignored) {
            }
            webSocket = null;
        }

        finalizeWav();
    }

    public void cleanup() {
        if (isStreaming) {
            cancel();
        }
        callback = null;
        client = null;
    }

    private void stopAudioRecord() {
        isStreaming = false; // signal streaming thread to stop

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                audioRecord.release();
            } catch (Exception ignored) {
            }
            audioRecord = null;
        }

        // Wait for streaming thread to finish
        if (streamingThread != null) {
            try {
                streamingThread.join(2000);
            } catch (InterruptedException ignored) {
            }
            streamingThread = null;
        }
    }

    private void finalizeWav() {
        if (wavWriter != null) {
            try {
                updateWavHeader(wavWriter, totalPcmBytes);
                wavWriter.close();
            } catch (IOException e) {
                Log.e(TAG, "Failed to finalize WAV", e);
            }
            wavWriter = null;
        }
    }

    private void closeWavWriter() {
        if (wavWriter != null) {
            try {
                wavWriter.close();
            } catch (IOException ignored) {
            }
            wavWriter = null;
        }
    }

    private static void writeWavHeader(RandomAccessFile raf, long dataSize) throws IOException {
        long totalSize = 36 + dataSize;
        int channels = 1;
        int bitsPerSample = 16;
        int byteRate = SAMPLE_RATE * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        raf.seek(0);
        raf.writeBytes("RIFF");
        raf.writeInt(Integer.reverseBytes((int) totalSize));
        raf.writeBytes("WAVE");
        raf.writeBytes("fmt ");
        raf.writeInt(Integer.reverseBytes(16)); // chunk size
        raf.writeShort(Short.reverseBytes((short) 1)); // PCM format
        raf.writeShort(Short.reverseBytes((short) channels));
        raf.writeInt(Integer.reverseBytes(SAMPLE_RATE));
        raf.writeInt(Integer.reverseBytes(byteRate));
        raf.writeShort(Short.reverseBytes((short) blockAlign));
        raf.writeShort(Short.reverseBytes((short) bitsPerSample));
        raf.writeBytes("data");
        raf.writeInt(Integer.reverseBytes((int) dataSize));
    }

    private static void updateWavHeader(RandomAccessFile raf, long dataSize) throws IOException {
        long totalSize = 36 + dataSize;
        raf.seek(4);
        raf.writeInt(Integer.reverseBytes((int) totalSize));
        raf.seek(40);
        raf.writeInt(Integer.reverseBytes((int) dataSize));
    }

    /**
     * Strips audio event tags like (laughter), (risas), (breathing), etc. from transcription text.
     * Used for realtime mode where the server has no tag_audio_events parameter.
     * Pattern matches parenthesized text containing only Unicode letters and spaces (1-40 chars).
     */
    public static String stripAudioEventTags(String text) {
        if (text == null || text.isEmpty()) return text;
        return text.replaceAll("\\([\\p{L}\\s]{1,40}\\)", "")
                   .replaceAll("\\s{2,}", " ")
                   .trim();
    }

    public static String mapLanguageCodePublic(String appLanguageCode) {
        return mapLanguageCode(appLanguageCode);
    }

    private static String mapLanguageCode(String appLanguageCode) {
        if (appLanguageCode == null || appLanguageCode.isEmpty() || appLanguageCode.equals("detect")) {
            return null; // auto-detect
        }
        String normalized = appLanguageCode.toLowerCase().replace("_", "-");
        String mapped = LANGUAGE_MAP.get(normalized);
        if (mapped != null) return mapped;

        // Try base language
        int sep = normalized.indexOf('-');
        if (sep > 0) {
            mapped = LANGUAGE_MAP.get(normalized.substring(0, sep));
            if (mapped != null) return mapped;
        }
        return null; // unsupported -> auto-detect
    }
}
