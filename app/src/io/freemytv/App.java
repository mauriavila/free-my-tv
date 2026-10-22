package io.freemytv;

import android.app.Application;

/** Gives Prefs and the log file a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        final Thread.UncaughtExceptionHandler system = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {   // a crash goes to the log file too
            java.io.StringWriter w = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(w));
            AppLog.crash(w.toString());
            if (system != null) system.uncaughtException(t, e);
        });
        Prefs.init(this);
    }
}
