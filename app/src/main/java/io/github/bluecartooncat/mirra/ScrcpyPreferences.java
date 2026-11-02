package io.github.bluecartooncat.mirra;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings are read once when a new external display is created. */
final class ScrcpyPreferences {
    final int width, height, fps, bitrateMbps, dpi;
    final String defaultApp;

    ScrcpyPreferences(Context context) {
        SharedPreferences p = context.getSharedPreferences("scrcpy", Context.MODE_PRIVATE);
        width = p.getInt("width", 1920);
        height = p.getInt("height", 1080);
        fps = p.getInt("fps", 60);
        bitrateMbps = p.getInt("bitrate", 8);
        dpi = p.getInt("dpi", 140);
        defaultApp = p.getString("default_app", "");
    }

    static void save(Context context, int width, int height, int fps, int bitrate, int dpi, String defaultApp) {
        context.getSharedPreferences("scrcpy", Context.MODE_PRIVATE).edit()
                .putInt("width", width).putInt("height", height).putInt("fps", fps)
                .putInt("bitrate", bitrate).putInt("dpi", dpi).putString("default_app", defaultApp).apply();
    }
}
