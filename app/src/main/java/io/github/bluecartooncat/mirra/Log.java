package io.github.bluecartooncat.mirra;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** App-owned diagnostic log; disk I/O never runs on casting or UI threads. */
final class Log {
    private static final long MAX_FILE_SIZE = 1024 * 1024;
    private static final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2048), runnable -> {
                Thread thread = new Thread(runnable, "MirraDiagnosticLog");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static volatile File directory;

    static void initialize(Context context) { directory = context.getApplicationContext().getNoBackupFilesDir(); }
    static int d(String tag, String message) { return write("D", tag, message, null); }
    static int i(String tag, String message) { return write("I", tag, message, null); }
    static int w(String tag, String message) { return write("W", tag, message, null); }
    static int w(String tag, String message, Throwable error) { return write("W", tag, message, error); }
    static int e(String tag, String message) { return write("E", tag, message, null); }
    static int e(String tag, String message, Throwable error) { return write("E", tag, message, error); }

    private static int write(String level, String tag, String message, Throwable error) {
        int priority = "E".equals(level) ? android.util.Log.ERROR : "W".equals(level)
                ? android.util.Log.WARN : "D".equals(level) ? android.util.Log.DEBUG : android.util.Log.INFO;
        android.util.Log.println(priority, tag, message + (error == null ? "" : "\n" + android.util.Log.getStackTraceString(error)));
        long timestamp = System.currentTimeMillis();
        File targetDirectory = directory;
        if (targetDirectory != null) try { writer.execute(() -> {
            try {
                File current = new File(targetDirectory, "diagnostics.log");
                File previous = new File(targetDirectory, "diagnostics.previous.log");
                if (current.length() >= MAX_FILE_SIZE) {
                    if (previous.exists() && !previous.delete()) throw new java.io.IOException("Cannot rotate old log");
                    if (!current.renameTo(previous)) throw new java.io.IOException("Cannot rotate log");
                }
                String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date(timestamp))
                        + " " + level + "/" + tag + ": " + message
                        + (error == null ? "" : "\n" + android.util.Log.getStackTraceString(error)) + "\n";
                try (FileOutputStream out = new FileOutputStream(current, true)) {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) { android.util.Log.e("MirraDiagnosticLog", "Unable to write diagnostic log", e); }
        }); } catch (java.util.concurrent.RejectedExecutionException e) {
            android.util.Log.w("MirraDiagnosticLog", "Diagnostic log queue is full");
        }
        return 0;
    }

    static byte[] snapshot() throws Exception {
        return writer.submit(() -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            File dir = directory;
            if (dir == null) return out.toByteArray();
            for (String name : new String[]{"diagnostics.previous.log", "diagnostics.log"}) {
                File file = new File(dir, name);
                if (!file.isFile()) continue;
                try (FileInputStream in = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                }
            }
            return out.toByteArray();
        }).get(10, TimeUnit.SECONDS);
    }
}
