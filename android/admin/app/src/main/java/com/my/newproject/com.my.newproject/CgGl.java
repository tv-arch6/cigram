package com.my.newproject;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Minimal OpenGL ES 2.0 plumbing for "decoder -> encoder" without touching the CPU:
 * the decoder renders into {@link OutputSurface}; each frame is drawn onto the encoder's input
 * surface held by {@link InputSurface} with the original presentation time.
 * (Same well known pattern as the AOSP media CTS "extract, decode, edit, encode, mux" test.)
 */
final class CgGl {

    private CgGl() {}

    // ------------------------------------------------------------------------------ encoder side

    static final class InputSurface {
        private static final int EGL_RECORDABLE_ANDROID = 0x3142;

        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private Surface surface;

        InputSurface(Surface surface) {
            if (surface == null) throw new NullPointerException();
            this.surface = surface;
            setup();
        }

        private void setup() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (display == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("unable to get EGL14 display");
            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                display = null;
                throw new RuntimeException("unable to initialize EGL14");
            }
            int[] attribList = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL_RECORDABLE_ANDROID, 1,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(display, attribList, 0, configs, 0, configs.length, numConfigs, 0) || numConfigs[0] <= 0) {
                throw new RuntimeException("unable to find a suitable EGLConfig");
            }
            int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
            check("eglCreateContext");
            int[] surfaceAttribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, surfaceAttribs, 0);
            check("eglCreateWindowSurface");
        }

        void makeCurrent() {
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw new RuntimeException("eglMakeCurrent failed");
        }

        void swapBuffers() {
            if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new RuntimeException("eglSwapBuffers failed");
        }

        void setPresentationTime(long nanos) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, nanos);
        }

        void release() {
            try {
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
                    EGL14.eglReleaseThread();
                    EGL14.eglTerminate(display);
                }
            } catch (Throwable ignored) { }
            display = EGL14.EGL_NO_DISPLAY;
            context = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
            try {
                if (surface != null) surface.release();
            } catch (Throwable ignored) { }
            surface = null;
        }

        private static void check(String what) {
            int error = EGL14.eglGetError();
            if (error != EGL14.EGL_SUCCESS) throw new RuntimeException(what + ": EGL error 0x" + Integer.toHexString(error));
        }
    }

    // ------------------------------------------------------------------------------ decoder side

    static final class OutputSurface implements SurfaceTexture.OnFrameAvailableListener {
        private static final long FRAME_TIMEOUT_MS = 15000;

        private final TextureRender render;
        private final SurfaceTexture texture;
        private final Surface surface;
        private final HandlerThread callbackThread;
        private final Object sync = new Object();
        private boolean frameAvailable;

        /** Must be created while the encoder-side EGL context is current. */
        OutputSurface() {
            render = new TextureRender();
            render.surfaceCreated();
            texture = new SurfaceTexture(render.textureId());
            callbackThread = new HandlerThread("cg-frame-callback");
            callbackThread.start();
            texture.setOnFrameAvailableListener(this, new Handler(callbackThread.getLooper()));
            surface = new Surface(texture);
        }

        Surface surface() {
            return surface;
        }

        /** Waits for the decoder to deliver the frame it was told to render, then latches it. */
        void awaitNewImage() {
            synchronized (sync) {
                long deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS;
                while (!frameAvailable) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) throw new RuntimeException("frame wait timed out");
                    try {
                        sync.wait(left);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                frameAvailable = false;
            }
            texture.updateTexImage();
        }

        void drawImage() {
            render.drawFrame(texture);
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            synchronized (sync) {
                frameAvailable = true;
                sync.notifyAll();
            }
        }

        void release() {
            try {
                surface.release();
            } catch (Throwable ignored) { }
            try {
                texture.release();
            } catch (Throwable ignored) { }
            try {
                callbackThread.quit();
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------------------------------------ drawing

    private static final class TextureRender {
        private static final int FLOAT_SIZE = 4;
        private static final int STRIDE = 5 * FLOAT_SIZE;
        private static final float[] TRIANGLE_VERTICES = {
                -1.0f, -1.0f, 0, 0.f, 0.f,
                1.0f, -1.0f, 0, 1.f, 0.f,
                -1.0f, 1.0f, 0, 0.f, 1.f,
                1.0f, 1.0f, 0, 1.f, 1.f,
        };
        private static final String VERTEX_SHADER =
                "uniform mat4 uMVPMatrix;\n"
                        + "uniform mat4 uSTMatrix;\n"
                        + "attribute vec4 aPosition;\n"
                        + "attribute vec4 aTextureCoord;\n"
                        + "varying vec2 vTextureCoord;\n"
                        + "void main() {\n"
                        + "  gl_Position = uMVPMatrix * aPosition;\n"
                        + "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n"
                        + "}\n";
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;\n"
                        + "varying vec2 vTextureCoord;\n"
                        + "uniform samplerExternalOES sTexture;\n"
                        + "void main() {\n"
                        + "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n"
                        + "}\n";

        private final FloatBuffer vertices;
        private final float[] mvp = new float[16];
        private final float[] st = new float[16];
        private int program;
        private int texId = -12345;
        private int uMvp;
        private int uSt;
        private int aPos;
        private int aTex;

        TextureRender() {
            vertices = ByteBuffer.allocateDirect(TRIANGLE_VERTICES.length * FLOAT_SIZE).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(TRIANGLE_VERTICES).position(0);
            Matrix.setIdentityM(st, 0);
        }

        int textureId() {
            return texId;
        }

        void drawFrame(SurfaceTexture t) {
            t.getTransformMatrix(st);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            vertices.position(0);
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE, vertices);
            GLES20.glEnableVertexAttribArray(aPos);
            vertices.position(3);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, STRIDE, vertices);
            GLES20.glEnableVertexAttribArray(aTex);
            Matrix.setIdentityM(mvp, 0);
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
            GLES20.glUniformMatrix4fv(uSt, 1, false, st, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        void surfaceCreated() {
            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
            aPos = GLES20.glGetAttribLocation(program, "aPosition");
            aTex = GLES20.glGetAttribLocation(program, "aTextureCoord");
            uMvp = GLES20.glGetUniformLocation(program, "uMVPMatrix");
            uSt = GLES20.glGetUniformLocation(program, "uSTMatrix");
            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            texId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        }

        private static int createProgram(String vertex, String fragment) {
            int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertex);
            int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragment);
            int program = GLES20.glCreateProgram();
            if (program == 0) throw new RuntimeException("glCreateProgram failed");
            GLES20.glAttachShader(program, vs);
            GLES20.glAttachShader(program, fs);
            GLES20.glLinkProgram(program);
            int[] status = new int[1];
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(program);
                throw new RuntimeException("glLinkProgram failed");
            }
            return program;
        }

        private static int loadShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] == 0) {
                GLES20.glDeleteShader(shader);
                throw new RuntimeException("glCompileShader failed");
            }
            return shader;
        }
    }
}
