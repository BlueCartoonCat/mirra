package io.github.bluecartooncat.mirra;

import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Renders the latest projected frame to the encoder on a monotonic clock. */
final class ProjectionRenderer {
    private static final String TAG = "ProjectionRenderer";
    private final MediaProjection projection;
    private final Surface encoderSurface;
    private final int width, height, fps, density;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean frameAvailable = new AtomicBoolean();
    private volatile SurfaceTexture activeTexture;
    private final CountDownLatch initialized = new CountDownLatch(1);
    private volatile Exception startupError;
    private final Object resizeLock = new Object();
    private int requestedWidth, requestedHeight;
    private Thread thread;

    ProjectionRenderer(MediaProjection projection, Surface encoderSurface, int width, int height, int fps, int density) {
        this.projection = projection; this.encoderSurface = encoderSurface;
        this.width = width; this.height = height; this.fps = fps; this.density = density;
    }

    void start() throws Exception {
        running.set(true);
        thread = new Thread(this::render, "ProjectionRenderer");
        thread.start();
        if (!initialized.await(5, TimeUnit.SECONDS)) { stop(); throw new IllegalStateException("Renderer initialization timed out"); }
        if (startupError != null) { stop(); throw startupError; }
    }

    private void render() {
        EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface window = EGL14.EGL_NO_SURFACE;
        SurfaceTexture texture = null;
        Surface captureSurface = null;
        VirtualDisplay virtualDisplay = null;
        MediaProjection.Callback resizeCallback = null;
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) throw new IllegalStateException("eglInitialize failed");
            int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142, 1, EGL14.EGL_NONE};
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
                throw new IllegalStateException("No recordable EGL config");
            context = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            window = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], encoderSurface, new int[]{EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(eglDisplay, window, window, context)) throw new IllegalStateException("eglMakeCurrent failed");
            int program = program();
            int[] textures = new int[1]; GLES20.glGenTextures(1, textures, 0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            texture = new SurfaceTexture(textures[0]);
            texture.setDefaultBufferSize(width, height);
            activeTexture = texture;
            texture.setOnFrameAvailableListener(source -> {
                if (source == activeTexture) frameAvailable.set(true);
            }, new Handler(Looper.getMainLooper()));
            captureSurface = new Surface(texture);
            if (Build.VERSION.SDK_INT >= 34) {
                resizeCallback = new MediaProjection.Callback() {
                    @Override public void onCapturedContentResize(int contentWidth, int contentHeight) {
                        if (contentWidth <= 0 || contentHeight <= 0) return;
                        synchronized (resizeLock) {
                            requestedWidth = contentWidth;
                            requestedHeight = contentHeight;
                        }
                        Log.i(TAG, "Captured content resized to " + contentWidth + "x" + contentHeight);
                    }
                    @Override public void onCapturedContentVisibilityChanged(boolean visible) {
                        Log.i(TAG, "Captured content visible=" + visible);
                    }
                };
                projection.registerCallback(resizeCallback, new Handler(Looper.getMainLooper()));
            }
            virtualDisplay = projection.createVirtualDisplay("Mirra", width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, captureSurface, null, null);
            if (virtualDisplay == null) throw new IllegalStateException("Virtual display unavailable");
            initialized.countDown();
            float[] vertices = {-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1};
            FloatBuffer buffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            buffer.put(vertices).position(0);
            int position = GLES20.glGetAttribLocation(program, "position");
            int texCoord = GLES20.glGetAttribLocation(program, "texCoord");
            int transform = GLES20.glGetUniformLocation(program, "transform");
            float[] matrix = new float[16];
            boolean haveFrame = false;
            long lastCapturedFrame = System.nanoTime(), lastReattach = 0;
            long lastResize = 0;
            int contentWidth = 0, contentHeight = 0;
            boolean resizeRecoveryAttempted = false;
            long interval = 1_000_000_000L / fps;
            long next = System.nanoTime();
            while (running.get()) {
                int newWidth, newHeight;
                synchronized (resizeLock) {
                    newWidth = requestedWidth;
                    newHeight = requestedHeight;
                    requestedWidth = requestedHeight = 0;
                }
                if (newWidth > 0 && newHeight > 0 && (newWidth != contentWidth || newHeight != contentHeight)) {
                    contentWidth = newWidth;
                    contentHeight = newHeight;
                    lastResize = System.nanoTime();
                    resizeRecoveryAttempted = false;
                    Log.i(TAG, "Content is " + newWidth + "x" + newHeight + "; using fixed " + width + "x" + height + " capture canvas");
                }
                if (frameAvailable.getAndSet(false)) {
                    texture.updateTexImage(); texture.getTransformMatrix(matrix); haveFrame = true;
                    lastCapturedFrame = System.nanoTime();
                }
                long current = System.nanoTime();
                if (lastResize != 0 && !resizeRecoveryAttempted
                        && current - lastResize < 30_000_000_000L
                        && current - lastCapturedFrame >= 8_000_000_000L && current - lastReattach >= 8_000_000_000L) {
                    // A static screen can also produce no buffers, so limit
                    // recovery to one attempt shortly after a resize.
                    Log.w(TAG, "No new capture buffers for 8 seconds; replacing capture surface");
                    int oldTexture = textures[0];
                    int[] replacement = new int[1];
                    GLES20.glGenTextures(1, replacement, 0);
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, replacement[0]);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                    SurfaceTexture replacementTexture = new SurfaceTexture(replacement[0]);
                    replacementTexture.setDefaultBufferSize(width, height);
                    replacementTexture.setOnFrameAvailableListener(source -> {
                        if (source == activeTexture) frameAvailable.set(true);
                    }, new Handler(Looper.getMainLooper()));
                    Surface replacementSurface = new Surface(replacementTexture);
                    frameAvailable.set(false);
                    activeTexture = replacementTexture;
                    virtualDisplay.setSurface(replacementSurface);
                    captureSurface.release();
                    texture.release();
                    GLES20.glDeleteTextures(1, new int[]{oldTexture}, 0);
                    captureSurface = replacementSurface;
                    texture = replacementTexture;
                    textures[0] = replacement[0];
                    haveFrame = false;
                    lastReattach = current;
                    resizeRecoveryAttempted = true;
                }
                if (haveFrame) {
                    // Android fits captured content proportionally into the fixed virtual display.
                    GLES20.glViewport(0, 0, width, height);
                    GLES20.glClearColor(0, 0, 0, 1);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                    GLES20.glUseProgram(program);
                    buffer.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, buffer);
                    buffer.position(2); GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 16, buffer);
                    GLES20.glEnableVertexAttribArray(position); GLES20.glEnableVertexAttribArray(texCoord);
                    GLES20.glUniformMatrix4fv(transform, 1, false, matrix, 0);
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                    EGLExt.eglPresentationTimeANDROID(eglDisplay, window, System.nanoTime());
                    if (!EGL14.eglSwapBuffers(eglDisplay, window)) throw new IllegalStateException("eglSwapBuffers failed");
                }
                next += interval;
                long delay = next - System.nanoTime();
                if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay);
                else next = System.nanoTime();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (initialized.getCount() > 0) startupError = e;
            Log.e(TAG, "Screen rendering failed", e);
        } finally {
            initialized.countDown();
            running.set(false);
            activeTexture = null;
            if (resizeCallback != null) projection.unregisterCallback(resizeCallback);
            if (virtualDisplay != null) virtualDisplay.release();
            if (captureSurface != null) captureSurface.release();
            if (texture != null) texture.release();
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, window);
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, context);
                EGL14.eglReleaseThread(); EGL14.eglTerminate(eglDisplay);
            }
        }
    }

    private static int shader(int type, String source) {
        int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
        int[] ok = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        return shader;
    }

    private static int program() {
        int vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec4 position; attribute vec4 texCoord; uniform mat4 transform; varying vec2 uv; void main(){ gl_Position=position; uv=(transform*texCoord).xy; }");
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 uv; uniform samplerExternalOES image; void main(){ gl_FragColor=texture2D(image,uv); }");
        int program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program);
        int[] ok = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        return program;
    }

    void stop() {
        running.set(false);
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try { thread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }
}
