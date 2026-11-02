package io.github.bluecartooncat.mirra;

import android.app.Application;

public final class MirraApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Log.initialize(this);
        Log.i("Mirra", "Diagnostic logging started");
    }
}
