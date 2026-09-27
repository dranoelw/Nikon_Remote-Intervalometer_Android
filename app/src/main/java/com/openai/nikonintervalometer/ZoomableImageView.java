package com.openai.nikonintervalometer;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;


public final class ZoomableImageView extends android.widget.ImageView {
    private final Matrix matrix = new Matrix();
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private float minScale = 1f;
    private float maxScale = 8f;
    private float currentScale = 1f;
    private float lastX;
    private float lastY;
    private boolean dragging;
    private boolean centerCropOnReset = false;
    private float baseRotationDegrees = 0f;

    public ZoomableImageView(Context context) {
        this(context, null);
    }

    public ZoomableImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        float requested = currentScale * detector.getScaleFactor();
                        float target = Math.max(minScale, Math.min(maxScale, requested));
                        float factor = target / currentScale;
                        if (Math.abs(factor - 1f) < 0.0001f) return true;

                        matrix.postScale(
                                factor,
                                factor,
                                detector.getFocusX(),
                                detector.getFocusY());
                        currentScale = target;
                        constrainTranslation();
                        setImageMatrix(matrix);
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(MotionEvent e) {
                        if (currentScale > minScale * 1.05f) {
                            resetToFit();
                        } else {
                            float target = Math.min(maxScale, minScale * 2.5f);
                            float factor = target / currentScale;
                            matrix.postScale(factor, factor, e.getX(), e.getY());
                            currentScale = target;
                            constrainTranslation();
                            setImageMatrix(matrix);
                        }
                        return true;
                    }

                    @Override public boolean onDown(MotionEvent e) {
                        return true;
                    }
                });
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(currentScale > minScale * 1.05f);
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress() && currentScale > minScale * 1.001f) {
                    float dx = event.getX() - lastX;
                    float dy = event.getY() - lastY;
                    matrix.postTranslate(dx, dy);
                    constrainTranslation();
                    setImageMatrix(matrix);
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                lastX = event.getX();
                lastY = event.getY();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }

    public void setMaximumScale(float maximumScale) {
        maxScale = Math.max(1f, maximumScale);
    }

    public void setCenterCropOnReset(boolean enabled) {
        centerCropOnReset = enabled;
        fitAfterNextLayout();
    }

    public void setBaseRotationDegrees(float degrees) {
        baseRotationDegrees = degrees;
        fitAfterNextLayout();
    }

    public void resetToFit() {
        Drawable drawable = getDrawable();
        if (drawable == null || getWidth() <= 0 || getHeight() <= 0) return;

        float dw = drawable.getIntrinsicWidth();
        float dh = drawable.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) return;

        matrix.reset();

        RectF contentBounds = new RectF(0, 0, dw, dh);
        if (Math.abs(baseRotationDegrees) > 0.001f) {
            matrix.postRotate(baseRotationDegrees);
            matrix.mapRect(contentBounds);
            matrix.postTranslate(-contentBounds.left, -contentBounds.top);
        }

        float contentWidth = contentBounds.width();
        float contentHeight = contentBounds.height();
        float scale = centerCropOnReset
                ? Math.max(getWidth() / contentWidth, getHeight() / contentHeight)
                : Math.min(getWidth() / contentWidth, getHeight() / contentHeight);

        matrix.postScale(scale, scale);

        RectF fittedBounds = new RectF(0, 0, dw, dh);
        matrix.mapRect(fittedBounds);
        float dx = getWidth() * 0.5f - fittedBounds.centerX();
        float dy = getHeight() * 0.5f - fittedBounds.centerY();
        matrix.postTranslate(dx, dy);

        minScale = scale;
        currentScale = scale;
        setImageMatrix(matrix);
    }

    public void fitAfterNextLayout() {
        post(this::resetToFit);
    }

    private void constrainTranslation() {
        Drawable drawable = getDrawable();
        if (drawable == null) return;

        RectF rect = new RectF(0, 0,
                drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight());
        matrix.mapRect(rect);

        float dx = 0f;
        float dy = 0f;

        if (rect.width() <= getWidth()) {
            dx = getWidth() * 0.5f - rect.centerX();
        } else if (rect.left > 0) {
            dx = -rect.left;
        } else if (rect.right < getWidth()) {
            dx = getWidth() - rect.right;
        }

        if (rect.height() <= getHeight()) {
            dy = getHeight() * 0.5f - rect.centerY();
        } else if (rect.top > 0) {
            dy = -rect.top;
        } else if (rect.bottom < getHeight()) {
            dy = getHeight() - rect.bottom;
        }

        matrix.postTranslate(dx, dy);
    }
}
