package io.github.bluecartooncat.mirra;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Holds a user-authorized projection in the required foreground service. */
public class ProjectionService extends Service {
    private static volatile ProjectionService instance;
    private MediaProjection projection;
    private boolean used;
    private boolean started;
    private volatile boolean closing;
    private volatile ScrcpySession scrcpy;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable scrcpyStartupTimeout;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        if (started) {
            Log.w("ProjectionService", "Ignoring duplicate casting start");
            return START_NOT_STICKY;
        }
        Intent data = intent.getParcelableExtra("resultData");
        String address = intent.getStringExtra("deviceAddress");
        boolean externalDisplay = intent.getBooleanExtra("externalDisplay", false);
        boolean captureAudio = intent.getBooleanExtra("captureAudio", false);
        boolean needsProjection = !externalDisplay;
        if (address == null || (needsProjection && data == null)) { reportFailure(getString(R.string.capture_auth_missing)); stopSelf(); return START_NOT_STICKY; }
        started = true;
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("capture", getString(R.string.notification_capture_channel), NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "capture")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(getString(R.string.notification_capture_title))
                .setContentText(getString(externalDisplay ? R.string.notification_external_description : R.string.notification_capture_description)).build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notification, needsProjection
                    ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    : android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else startForeground(1, notification);
        if (needsProjection) {
            if (projection != null) projection.stop();
            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            try {
                projection = manager.getMediaProjection(intent.getIntExtra("resultCode", 0), data);
            } catch (RuntimeException e) {
                Log.e("ProjectionService", "Screen capture authorization failed", e);
                reportFailure(getString(R.string.capture_permission_expired));
                stopSelf();
                return START_NOT_STICKY;
            }
            if (projection == null) { Log.e("ProjectionService", "Screen capture unavailable"); reportFailure(getString(R.string.capture_unavailable)); stopSelf(); return START_NOT_STICKY; }
            used = false;
            MediaProjection current = projection;
            current.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    Log.i("ProjectionService", "Screen capture stopped");
                    if (projection == current) {
                        CastingService.stopSession(ProjectionService.this);
                        stopSelf();
                    }
                }
            }, new Handler(Looper.getMainLooper()));
            Log.i("ProjectionService", "Screen capture authorized; ready for one session");
        }
        Intent casting = new Intent(this, CastingService.class);
        casting.putExtra("deviceAddress", address);
        casting.putExtra("deviceName", intent.getStringExtra("deviceName"));
        casting.putExtra("captureAudio", captureAudio);
        casting.putExtra("externalDisplay", externalDisplay);
        casting.putExtra("groupOwnerIntent", intent.getIntExtra("groupOwnerIntent", 7));
        if (externalDisplay) {
            ScrcpySession session = new ScrcpySession(this, captureAudio);
            scrcpy = session;
            scrcpyStartupTimeout = () -> {
                if (!closing && scrcpy == session) {
                    Log.e("ProjectionService", "External display startup timed out");
                    scrcpy = null;
                    session.stop();
                    reportFailure(getString(R.string.external_display_failed));
                    stopSelf();
                }
            };
            mainHandler.postDelayed(scrcpyStartupTimeout, 30000);
            new Thread(() -> {
                try {
                    session.start();
                    mainHandler.post(() -> {
                        mainHandler.removeCallbacks(scrcpyStartupTimeout);
                        if (!closing && scrcpy == session) startCasting(casting);
                        else session.stop();
                    });
                } catch (Exception e) {
                    Log.e("ProjectionService", "External display could not start", e);
                    session.stop();
                    mainHandler.post(() -> {
                        mainHandler.removeCallbacks(scrcpyStartupTimeout);
                        if (!closing && scrcpy == session) { reportFailure(getString(R.string.external_display_failed)); stopSelf(); }
                    });
                }
            }, "StartExternalDisplay").start();
        } else {
            startCasting(casting);
        }
        return START_NOT_STICKY;
    }

    private void startCasting(Intent casting) {
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, casting);
        } catch (RuntimeException e) {
            Log.e("ProjectionService", "Unable to start casting service", e);
            reportFailure(getString(R.string.casting_service_failed));
            stopSelf();
        }
    }

    private void reportFailure(String detail) {
        sendBroadcast(new Intent(CastingService.ACTION_STATUS).setPackage(getPackageName())
                .putExtra("title", getString(R.string.casting_start_failed)).putExtra("detail", detail).putExtra("active", false));
    }

    static synchronized MediaProjection takeProjection() {
        ProjectionService service = instance;
        if (service == null || service.used || service.projection == null) return null;
        service.used = true;
        return service.projection;
    }

    static int getDisplayDensity() {
        ProjectionService service = instance;
        return service == null ? 160 : service.getResources().getDisplayMetrics().densityDpi;
    }

    @Override public void onDestroy() {
        closing = true;
        mainHandler.removeCallbacksAndMessages(null);
        instance = null;
        if (CastingService.isSessionActive()) CastingService.stopSession(this);
        if (scrcpy != null) { scrcpy.stop(); scrcpy = null; }
        if (projection != null) { MediaProjection p = projection; projection = null; p.stop(); }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
