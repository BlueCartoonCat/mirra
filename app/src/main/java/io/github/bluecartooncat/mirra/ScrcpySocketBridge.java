package io.github.bluecartooncat.mirra;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Runs as the Shizuku shell to bridge scrcpy's abstract sockets to pipes. */
public final class ScrcpySocketBridge {
    private ScrcpySocketBridge() { }

    public static void main(String[] args) {
        try { run(args); }
        catch (Exception e) {
            System.err.println("Scrcpy socket bridge stopped: " + e);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected socket name and mode");
        LocalSocket socket = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                socket = new LocalSocket();
                socket.connect(new LocalSocketAddress(args[0]));
                break;
            } catch (Exception e) {
                if (socket != null) socket.close();
                socket = null;
                Thread.sleep(100);
            }
        }
        if (socket == null) throw new IllegalStateException("Scrcpy socket unavailable");
        try (LocalSocket connection = socket) {
            InputStream input = "control".equals(args[1]) ? System.in : connection.getInputStream();
            OutputStream output = "control".equals(args[1]) ? connection.getOutputStream()
                    : new FileOutputStream(FileDescriptor.out);
            if ("audio".equals(args[1])) { output.write(0); output.flush(); }
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                output.flush();
            }
        }
    }
}
