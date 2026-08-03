package com.example.callsaver;

import android.app.Application;

/**
 * Installs a global uncaught-exception handler that writes the full crash stack trace to
 * the same debug log viewable in Settings > Diagnostic Logs, before letting the crash
 * proceed as normal (the "keeps stopping" dialog still shows - this just makes sure the
 * cause is recorded somewhere, since the app has no other crash reporting).
 */
public class CallSaverApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        final Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                throwable.printStackTrace(new java.io.PrintWriter(sw));
                DebugLogger.log(getApplicationContext(), "[CRASH] Uncaught exception on thread " + thread.getName() + ":\n" + sw);
            } catch (Exception ignored) {
                // Never let logging the crash cause a second crash.
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            } else {
                System.exit(1);
            }
        });
    }
}
