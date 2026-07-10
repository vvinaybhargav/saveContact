package com.example.callsaver;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import java.util.Locale;

public class VoiceNoteHelper {

    public interface VoiceCallback {
        void onTextReceived(String text);
        void onRecordingStateChanged(boolean isRecording);
        void onError(String errorMessage);
    }

    private final Context context;
    private final VoiceCallback callback;
    private final Handler handler;
    private SpeechRecognizer speechRecognizer;
    private Intent recognizerIntent;
    private boolean isListening = false;

    public VoiceNoteHelper(Context context, VoiceCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.handler = new Handler(Looper.getMainLooper());
    }

    private void initializeRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            callback.onError("Speech recognition not available on this device.");
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {}

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                // Ignore errors like speech timeout and automatically restart if we are still listening
                if (isListening) {
                    handler.postDelayed(() -> {
                        if (isListening && speechRecognizer != null) {
                            try {
                                speechRecognizer.cancel();
                                speechRecognizer.startListening(recognizerIntent);
                            } catch (Exception ignored) {
                            }
                        }
                    }, 600);
                }
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String text = matches.get(0);
                    if (text != null && !text.trim().isEmpty()) {
                        callback.onTextReceived(text);
                    }
                }
                if (isListening) {
                    handler.postDelayed(() -> {
                        if (isListening && speechRecognizer != null) {
                            try {
                                speechRecognizer.cancel();
                                speechRecognizer.startListening(recognizerIntent);
                            } catch (Exception ignored) {
                            }
                        }
                    }, 200);
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) {}

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });

        recognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        recognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
    }

    public void startListening() {
        if (speechRecognizer == null) {
            initializeRecognizer();
        }
        if (speechRecognizer != null) {
            isListening = true;
            try {
                speechRecognizer.cancel();
                speechRecognizer.startListening(recognizerIntent);
                callback.onRecordingStateChanged(true);
            } catch (Exception e) {
                callback.onError("Could not start recording: " + e.getMessage());
            }
        }
    }

    public void stopListening() {
        isListening = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
            } catch (Exception ignored) {}
            callback.onRecordingStateChanged(false);
        }
    }

    public boolean isListening() {
        return isListening;
    }

    public void destroy() {
        isListening = false;
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception ignored) {}
            speechRecognizer = null;
        }
    }
}
