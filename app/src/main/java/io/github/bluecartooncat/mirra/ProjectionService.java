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
import android.util.Log;

/** Holds a user-authorized projection in the required foreground service. */
public class ProjectionService extends Service {
    private static volatile ProjectionService instance;
    private MediaProjection projection;
    private boolean used;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel("capture", getString(R.string.notification_capture_channel), NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, "capture")
                .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(getString(R.string.notification_capture_title))
                .setContentText(getString(R.string.notification_capture_description)).build();
        startForeground(1, notification);
        instance = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        Intent data = intent.getParcelableExtra("resultData");
        String address = intent.getStringExtra("deviceAddress");
        if (data == null || address == null) { reportFailure(getString(R.string.capture_auth_missing)); stopSelf(); return START_NOT_STICKY; }
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
        Intent casting = new Intent(this, CastingService.class);
        casting.putExtra("deviceAddress", address);
        casting.putExtra("deviceName", intent.getStringExtra("deviceName"));
        casting.putExtra("captureAudio", intent.getBooleanExtra("captureAudio", false));
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, casting);
        } catch (RuntimeException e) {
            Log.e("ProjectionService", "Unable to start casting service", e);
            reportFailure(getString(R.string.casting_service_failed));
            stopSelf();
        }
        return START_NOT_STICKY;
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
        instance = null;
        if (CastingService.isSessionActive()) CastingService.stopSession(this);
        if (projection != null) { MediaProjection p = projection; projection = null; p.stop(); }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
