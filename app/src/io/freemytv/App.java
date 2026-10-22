package io.freemytv;

import android.app.Application;

/** Gives Prefs and the log file a context in every process. */
public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        Prefs.init(this);
    }
}
