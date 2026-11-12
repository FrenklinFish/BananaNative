package dev.nxnative.android;

import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;

/**
 * What the launcher's own process can ask of the GPU without loading a host or a Vulkan driver: GLES's vendor, renderer and version strings from a 1x1 pbuffer
 * context, once a process, off the main thread (MainActivity's start). The renderer names the GPU in the game page's chip ("Adreno 840"); vendor and version give
 * the phone's own driver stand-in facts (Recommend.glFacts) until a readiness check has answered on it, so recommended settings show before the first game start.
 */
final class GpuGl {
    private GpuGl() {}

    private static volatile String vendor, renderer, version;
    private static volatile boolean done;
    private static boolean started;

    /** The GPU's short name (Readiness.gpuName), null until known */
    static String name() { return Readiness.gpuName(renderer); }

    static boolean done() { return done; }

    /** Asks once a process; then keeps the stand-in facts (Prefs.rememberGlFacts) and runs then on the main thread */
    static synchronized void probe(Context c, Runnable then) {
        if (started) return;
        started = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            query();
            Prefs.rememberGlFacts(app, Recommend.glFacts(vendor, version));
            done = true;
            android.util.Log.i("nxnative", "GLES: " + vendor + " / " + renderer + " / " + version);
            if (then != null) new android.os.Handler(android.os.Looper.getMainLooper()).post(then);
        }, "nx-gpu-gl").start();
    }

    /** The three strings from a 1x1 pbuffer context. The display stays initialized: the launcher's own renderer may share it */
    private static void query() {
        try {
            EGLDisplay d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (d == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(d, v, 0, v, 1)) return;
            int[] attr = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            EGLConfig[] cf = new EGLConfig[1];
            int[] n = new int[1];
            if (!EGL14.eglChooseConfig(d, attr, 0, cf, 0, 1, n, 0) || n[0] < 1) return;
            EGLContext ctx = EGL14.eglCreateContext(d, cf[0], EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT) return;
            EGLSurface s = EGL14.eglCreatePbufferSurface(d, cf[0], new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (s != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(d, s, s, ctx)) {
                vendor = GLES20.glGetString(GLES20.GL_VENDOR);
                renderer = GLES20.glGetString(GLES20.GL_RENDERER);
                version = GLES20.glGetString(GLES20.GL_VERSION);
            }
            EGL14.eglMakeCurrent(d, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (s != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(d, s);
            EGL14.eglDestroyContext(d, ctx);
        } catch (RuntimeException e) {
            android.util.Log.w("nxnative", "GLES query: " + e);
        }
    }
}
