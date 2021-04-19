package com.otaliastudios.cameraview.video.encoding;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.otaliastudios.cameraview.CameraLogger;
import com.otaliastudios.cameraview.filter.Filter;
import com.otaliastudios.cameraview.internal.GlTextureDrawer;
import com.otaliastudios.cameraview.internal.Pool;
import com.otaliastudios.opengl.core.EglCore;
import com.otaliastudios.opengl.core.Egloo;
import com.otaliastudios.opengl.surface.EglWindowSurface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Default implementation for video encoding.
 */
@RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
public class TextureMediaEncoder extends VideoMediaEncoder<TextureConfig> {

    private static final String TAG = TextureMediaEncoder.class.getSimpleName();
    private static final CameraLogger LOG = CameraLogger.create(TAG);
    private static final boolean PERFORMANCE_DEBUG = false;

    public final static String FRAME_EVENT = "frame";
    public final static String FILTER_EVENT = "filter";

    private int mTransformRotation;
    private EglCore mEglCore;
    private EglWindowSurface mWindow;
    private GlTextureDrawer mDrawer;
    private Pool<Frame> mFramePool = new Pool<>(Integer.MAX_VALUE, new Pool.Factory<Frame>() {
        @Override
        public Frame create() {
            return new Frame();
        }
    });

    private long mFirstTimeUs = Long.MIN_VALUE;
    private int mDrewFrameNumber = -1;
    private OnFrameDrew onFrameDrewListener;
    private long performanceDebugTime;

    public TextureMediaEncoder(@NonNull TextureConfig config) {
        super(config.copy());
    }

    public void setOnFrameDrewListener(@Nullable OnFrameDrew onFrameDrewListener) {
        this.onFrameDrewListener = onFrameDrewListener;
    }

    /**
     * Should be acquired with {@link #acquireFrame()}, filled and then passed
     * to {@link MediaEncoderEngine#notify(String, Object)} with {@link #FRAME_EVENT}.
     */
    public static class Frame {
        private Frame() {
        }

        /**
         * Nanoseconds, in no meaningful time-base. Will be used for offsets only.
         * Typically this comes from {@link SurfaceTexture#getTimestamp()}.
         */
        public long timestampNanos;

        /**
         * Milliseconds in the {@link System#currentTimeMillis()} reference.
         * This is actually needed/read only for the first frame.
         */
        public long timestampMillis;

        /**
         * The transformation matrix for the base texture.
         */
        public float[] transform = new float[16];

        private long timestampUs() {
            return timestampNanos / 1000L;
        }
    }

    /**
     * Returns a new frame to be filled. See {@link Frame} for details.
     *
     * @return a new frame
     */
    @NonNull
    public Frame acquireFrame() {
        if (mFramePool.isEmpty()) {
            throw new RuntimeException("Need more frames than this! " +
                    "Please increase the pool size.");
        } else {
            //noinspection ConstantConditions
            return mFramePool.get();
        }
    }

    @EncoderThread
    @Override
    protected void onPrepare(@NonNull MediaEncoderEngine.Controller controller, long maxLengthUs) {
        // We rotate the texture using transformRotation. Pass rotation=0 to super so that
        // no rotation metadata is written into the output file.
        mTransformRotation = mConfig.rotation;
        mConfig.rotation = 0;
        super.onPrepare(controller, maxLengthUs);
        mEglCore = new EglCore(mConfig.eglContext, EglCore.FLAG_RECORDABLE);
        mWindow = new EglWindowSurface(mEglCore, mSurface, true);
        mWindow.makeCurrent();
        mDrawer = new GlTextureDrawer(mConfig.textureId);
    }

    /**
     * Any number of pending events greater than 1 means that we should skip this frame.
     * To avoid skipping too many frames, we'll use 2 for now, but this just means
     * that we'll be drawing the same frame twice.
     * <p>
     * When an event is posted, the textureId data has already been updated so we're
     * too late to draw the old one and it should be skipped.
     * <p>
     * This is especially important if we perform overlay drawing here, since that
     * makes this class thread busy and slows down the event dispatching.
     *
     * @param timestampUs frame timestamp
     * @return true to render
     */
    @Override
    protected boolean shouldRenderFrame(long timestampUs) {
        if (!super.shouldRenderFrame(timestampUs)) {
            LOG.i("shouldRenderFrame - Dropping frame because of super()");
            return false;
        } else if (mFrameNumber <= 10) {
            // Always render the first few frames, or muxer fails.
            return true;
        } else if (getPendingEvents(FRAME_EVENT) > 2) {
            LOG.i("shouldRenderFrame - Dropping, we already have too many pending events:",
                    getPendingEvents(FRAME_EVENT));
            return false;
        } else {
            return true;
        }
    }

    @EncoderThread
    @Override
    protected void onEvent(@NonNull String event, @Nullable Object data) {
        switch (event) {
            case FILTER_EVENT:
                //noinspection ConstantConditions
                onFilter((Filter) data);
                break;
            case FRAME_EVENT:
                //noinspection ConstantConditions
                onFrame((Frame) data);
                break;
        }
    }

    private void onFilter(@NonNull Filter filter) {
        mDrawer.setFilter(filter);
    }

    private void onFrame(@NonNull Frame frame) {
        if (!shouldRenderFrame(frame.timestampUs())) {
            mFramePool.recycle(frame);
            return;
        }

        // Notify we're got the first frame and its absolute time.
        if (mFrameNumber == 1) {
            notifyFirstFrameMillis(frame.timestampMillis);
        }

        // Notify we have reached the max length value.
        if (mFirstTimeUs == Long.MIN_VALUE) mFirstTimeUs = frame.timestampUs();
        if (!hasReachedMaxLength()) {
            boolean didReachMaxLength = (frame.timestampUs() - mFirstTimeUs) > getMaxLengthUs();
            if (didReachMaxLength) {
                LOG.w("onEvent -",
                        "frameNumber:", mFrameNumber,
                        "timestampUs:", frame.timestampUs(),
                        "firstTimeUs:", mFirstTimeUs,
                        "- reached max length! deltaUs:", frame.timestampUs() - mFirstTimeUs);
                notifyMaxLengthReached();
            }
        }

        // First, drain any previous data.
        LOG.i("onEvent -",
                "frameNumber:", mFrameNumber,
                "timestampUs:", frame.timestampUs(),
                "hasReachedMaxLength:", hasReachedMaxLength(),
                "thread:", Thread.currentThread(),
                "- draining.");
        drainOutput(false);

        // Then draw on the surface.
        LOG.i("onEvent -",
                "frameNumber:", mFrameNumber,
                "timestampUs:", frame.timestampUs(),
                "hasReachedMaxLength:", hasReachedMaxLength(),
                "thread:", Thread.currentThread(),
                "- drawing.");

        // 1. We must scale this matrix like GlCameraPreview does, because it might have some
        // cropping. Scaling takes place with respect to the (0, 0, 0) point, so we must apply
        // a Translation to compensate.
        float[] transform = frame.transform;
        float scaleX = mConfig.scaleX;
        float scaleY = mConfig.scaleY;
        float scaleTranslX = (1F - scaleX) / 2F;
        float scaleTranslY = (1F - scaleY) / 2F;
        Matrix.translateM(transform, 0, scaleTranslX, scaleTranslY, 0);
        Matrix.scaleM(transform, 0, scaleX, scaleY, 1);

        // 2. We also must rotate this matrix. In GlCameraPreview it is not needed because it is
        // a live stream, but the output video, must be correctly rotated based on the device
        // rotation at the moment. Rotation also takes place with respect to the origin
        // (the Z axis), so we must translate to origin, rotate, then back to where we were.
        Matrix.translateM(transform, 0, 0.5F, 0.5F, 0);
        Matrix.rotateM(transform, 0, mTransformRotation, 0, 0, 1);
        Matrix.translateM(transform, 0, -0.5F, -0.5F, 0);

        // 3. Do the same for overlays with their own rotation.
        if (mConfig.hasOverlay()) {
            mConfig.overlayDrawer.draw(mConfig.overlayTarget);
            Matrix.translateM(mConfig.overlayDrawer.getTransform(),
                    0, 0.5F, 0.5F, 0);
            Matrix.rotateM(mConfig.overlayDrawer.getTransform(),
                    0, mConfig.overlayRotation, 0, 0, 1);
            Matrix.translateM(mConfig.overlayDrawer.getTransform(),
                    0, -0.5F, -0.5F, 0);
        }
        LOG.i("onEvent -",
                "frameNumber:", mFrameNumber,
                "timestampUs:", frame.timestampUs(),
                "hasReachedMaxLength:", hasReachedMaxLength(),
                "thread:", Thread.currentThread(),
                "- gl rendering.");
        mDrawer.setTextureTransform(transform);
        mDrawer.draw(frame.timestampUs());
        if (mConfig.hasOverlay()) {
            mConfig.overlayDrawer.render(frame.timestampUs());
        }
        mWindow.setPresentationTime(frame.timestampNanos);
        mWindow.swapBuffers();
        mFramePool.recycle(frame);

        mDrewFrameNumber++;
        LOG.i("#onFrame", "drewFrameNumber: " + mDrewFrameNumber);

        LOG.i("onEvent -",
                "frameNumber:", mFrameNumber,
                "timestampUs:", frame.timestampUs(),
                "hasReachedMaxLength:", hasReachedMaxLength(),
                "thread:", Thread.currentThread(),
                "- gl rendered.");

        Bitmap bitmap = toBitmap(mWindow);
        if (onFrameDrewListener != null && bitmap != null) {
            onFrameDrewListener.onDrew(bitmap, mDrewFrameNumber);
        }
    }

    /**
     * Saves the EGL surface to the a ByteBuffer
     * Expects that this object's EGL surface is current.
     *
     * @return
     */
    public Bitmap toBitmap(EglWindowSurface eglWindowSurface) {
        if (!eglWindowSurface.isCurrent()) {
            LOG.e("Expected EGL context/surface is not current");
            return null;
        }

        if (PERFORMANCE_DEBUG) {
            performanceDebugTime = System.currentTimeMillis();
            LOG.i(TAG, "#toBitmap start at " + performanceDebugTime);
        }
        // glReadPixels fills in a "direct" ByteBuffer with what is essentially big-endian RGBA
        // data (i.e. a byte of red, followed by a byte of green...).  While the Bitmap
        // constructor that takes an int[] wants little-endian ARGB (blue/red swapped), the
        // Bitmap "copy pixels" method wants the same format GL provides.
        //
        // Making this even more interesting is the upside-down nature of GL, which means
        // our output will look upside down relative to what appears on screen if the
        // typical GL conventions are used.
        int width = eglWindowSurface.getWidth();
        int height = eglWindowSurface.getHeight();
        ByteBuffer buf = ByteBuffer.allocateDirect(width * height * 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        Egloo.checkGlError("glReadPixels");
        buf.rewind();

        if (PERFORMANCE_DEBUG) {
            LOG.i(TAG, "#toBitmap glReadPixels " + (System.currentTimeMillis() - performanceDebugTime));
            performanceDebugTime = System.currentTimeMillis();
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buf);

        if (PERFORMANCE_DEBUG) {
            LOG.i(TAG, "#toBitmap create bitmap " + (System.currentTimeMillis() - performanceDebugTime));
        }

        return bitmap;
    }

    @Override
    protected void onStopped() {
        super.onStopped();
        mFramePool.clear();
        if (mWindow != null) {
            mWindow.release();
            mWindow = null;
        }
        if (mDrawer != null) {
            mDrawer.release();
            mDrawer = null;
        }
        if (mEglCore != null) {
            mEglCore.release();
            mEglCore = null;
        }
    }
}
