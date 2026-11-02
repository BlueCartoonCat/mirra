package io.github.bluecartooncat.mirra;

import android.content.Context;

/** Disabled CEA modes, stored by bit rather than list position. */
final class VideoModePreferences {
    private static final String FILE = "video_modes";
    private static final String DISABLED = "disabled_cea_modes";
    private static final String FORCE_LEVELS = "force_sink_h264_levels";
    private static final String EXTENDED_INITIALIZED = "extended_modes_initialized";

    private VideoModePreferences() { }

    static int getDisabled(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int disabled = prefs.getInt(DISABLED, 0);
        // New experimental modes are opt-in for existing and new installs.
        if (!prefs.getBoolean(EXTENDED_INITIALIZED, false)) disabled |= VideoNegotiator.EXPERIMENTAL_MODES;
        return disabled;
    }

    static boolean forceSinkLevels(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(FORCE_LEVELS, false);
    }

    static void save(Context context, int modes, boolean forceLevels) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt(DISABLED, modes).putBoolean(FORCE_LEVELS, forceLevels)
                .putBoolean(EXTENDED_INITIALIZED, true).apply();
    }
}
