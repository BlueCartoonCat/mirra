package io.github.bluecartooncat.mirra;

import android.content.Context;

/** Disabled CEA modes, stored by bit rather than list position. */
final class VideoModePreferences {
    private static final String FILE = "video_modes";
    private static final String DISABLED = "disabled_cea_modes";
    private static final String FORCE_LEVELS = "force_sink_h264_levels";
    private static final String EXTENDED_INITIALIZED = "extended_modes_initialized";
    static final class CustomMode {
        final int width, height, fps;
        final boolean enabled;
        CustomMode(int width, int height, int fps, boolean enabled) {
            this.width = width; this.height = height; this.fps = fps; this.enabled = enabled;
        }
    }

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

    static CustomMode customMode(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int width = prefs.getInt("custom_width", 0), height = prefs.getInt("custom_height", 0), fps = prefs.getInt("custom_fps", 0);
        if (width <= 0 || height <= 0 || fps <= 0) return null;
        return new CustomMode(width, height, fps, prefs.getBoolean("custom_enabled", false));
    }

    static void saveCustom(Context context, int width, int height, int fps, boolean enabled) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("custom_width", width)
                .putInt("custom_height", height).putInt("custom_fps", fps).putBoolean("custom_enabled", enabled).apply();
    }
}
