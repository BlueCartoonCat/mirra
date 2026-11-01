package io.github.bluecartooncat.mirra;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Shizuku user service: the P2P channel must be created under shell's identity. */
public class WfdShellService extends Binder {
    static final int STATUS = IBinder.FIRST_CALL_TRANSACTION;
    static final int ENABLE = STATUS + 1;
    static final int DISABLE = STATUS + 2;

    private final Context context;
    private Context shellContext;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;

    public WfdShellService(Context context) {
        this.context = context;
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == 16777115) {
            new Thread(() -> System.exit(0)).start();
            return true;
        }
        if (code != STATUS && code != ENABLE && code != DISABLE) {
            return super.onTransact(code, data, reply, flags);
        }
        String result;
        try {
            initialize();
            result = code == STATUS ? "UID=" + Process.myUid() + ", package="
                    + shellContext.getOpPackageName() + ", shell P2P channel ready"
                    : setWfd(code == ENABLE);
        } catch (Throwable t) {
            while (t.getCause() != null && t.getCause() != t) t = t.getCause();
            result = "WFD error: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        reply.writeNoException();
        reply.writeString(result);
        return true;
    }

    private synchronized void initialize() throws Exception {
        if (channel != null) return;
        if (Process.myUid() != 2000 && Process.myUid() != 0)
            throw new SecurityException("Shizuku is not running as shell/root (UID " + Process.myUid() + ")");
        shellContext = context.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        if (shellContext.checkSelfPermission("android.permission.CONFIGURE_WIFI_DISPLAY")
                != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Shell lacks CONFIGURE_WIFI_DISPLAY");
        manager = (WifiP2pManager) shellContext.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) throw new IllegalStateException("Wi-Fi P2P unavailable");
        channel = manager.initialize(shellContext, Looper.getMainLooper(), null);
        if (channel == null) throw new IllegalStateException("Shell P2P channel unavailable");
    }

    private synchronized String setWfd(boolean enabled) throws Exception {
        Class<?> type = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo");
        Object info = type.getConstructor().newInstance();
        type.getMethod("setEnabled", boolean.class).invoke(info, enabled);
        type.getMethod("setDeviceType", int.class).invoke(info, 0); // WFD source
        type.getMethod("setSessionAvailable", boolean.class).invoke(info, enabled);
        type.getMethod("setControlPort", int.class).invoke(info, 7236);
        type.getMethod("setMaxThroughput", int.class).invoke(info, 50);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> answer = new AtomicReference<>("callback timed out");
        WifiP2pManager.ActionListener listener = new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { answer.set("accepted"); latch.countDown(); }
            @Override public void onFailure(int reason) { answer.set("failed: " + reason); latch.countDown(); }
        };
        Method method = WifiP2pManager.class.getMethod("setWfdInfo",
                WifiP2pManager.Channel.class, type, WifiP2pManager.ActionListener.class);
        method.invoke(manager, channel, info, listener);
        latch.await(8, TimeUnit.SECONDS);
        return "WFD " + (enabled ? "enable" : "disable") + ": " + answer.get();
    }
}
