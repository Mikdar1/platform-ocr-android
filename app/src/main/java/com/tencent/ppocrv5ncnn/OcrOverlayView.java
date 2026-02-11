// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.tencent.ppocrv5ncnn;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class OcrOverlayView extends ImageView {

    private List<TextBox> textBoxes = new ArrayList<>();
    private Paint boxPaint;
    private Paint fillPaint;

    // Image dimensions
    private int imageWidth = 0;
    private int imageHeight = 0;

    // Zoom and pan
    private Matrix transformMatrix = new Matrix();
    private float currentScale = 1f;
    private float currentTranslateX = 0f;
    private float currentTranslateY = 0f;
    private float minScale = 1f;
    private float maxScale = 5f;

    // Base transform (fitCenter)
    private float baseScale = 1f;
    private float baseOffsetX = 0f;
    private float baseOffsetY = 0f;

    // Gesture detectors
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    // Touch tracking
    private float lastTouchX;
    private float lastTouchY;
    private boolean isDragging = false;
    private static final float TOUCH_SLOP = 10f;

    private static class TextBox {
        String text;
        RectF rect;

        TextBox(String text, float x, float y, float w, float h) {
            this.text = text;
            this.rect = new RectF(x, y, x + w, y + h);
        }
    }

    public OcrOverlayView(Context context) {
        super(context);
        init(context);
    }

    public OcrOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public OcrOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        boxPaint = new Paint();
        boxPaint.setColor(Color.parseColor("#4CAF50"));
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2f);

        fillPaint = new Paint();
        fillPaint.setColor(Color.parseColor("#304CAF50"));
        fillPaint.setStyle(Paint.Style.FILL);

        // Set up scale gesture detector for pinch zoom
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                float newScale = currentScale * scaleFactor;

                // Clamp scale
                newScale = Math.max(minScale, Math.min(newScale, maxScale));

                if (newScale != currentScale) {
                    // Scale around the focal point
                    float focusX = detector.getFocusX();
                    float focusY = detector.getFocusY();

                    // Adjust translation to keep focal point stationary
                    currentTranslateX = focusX - (focusX - currentTranslateX) * (newScale / currentScale);
                    currentTranslateY = focusY - (focusY - currentTranslateY) * (newScale / currentScale);

                    currentScale = newScale;
                    constrainTranslation();
                    updateMatrix();
                    invalidate();
                }
                return true;
            }
        });

        // Set up gesture detector for double-tap to reset
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                // Reset zoom
                currentScale = 1f;
                currentTranslateX = 0f;
                currentTranslateY = 0f;
                updateMatrix();
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // Handle tap on bounding box
                handleTap(e.getX(), e.getY());
                return true;
            }
        });

        setScaleType(ScaleType.MATRIX);
    }

    public void setOcrResults(String ocrJsonWithBoxes, int imgWidth, int imgHeight) {
        textBoxes.clear();
        this.imageWidth = imgWidth;
        this.imageHeight = imgHeight;

        try {
            JSONArray boxes = new JSONArray(ocrJsonWithBoxes);
            for (int i = 0; i < boxes.length(); i++) {
                JSONObject box = boxes.getJSONObject(i);
                textBoxes.add(new TextBox(
                    box.getString("text"),
                    (float) box.getDouble("x"),
                    (float) box.getDouble("y"),
                    (float) box.getDouble("w"),
                    (float) box.getDouble("h")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Reset zoom when new results come in
        currentScale = 1f;
        currentTranslateX = 0f;
        currentTranslateY = 0f;

        // Recalculate base transform
        post(new Runnable() {
            @Override
            public void run() {
                calculateBaseTransform();
                updateMatrix();
                invalidate();
            }
        });
    }

    public void clearResults() {
        textBoxes.clear();
        currentScale = 1f;
        currentTranslateX = 0f;
        currentTranslateY = 0f;
        updateMatrix();
        invalidate();
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        if (bm != null) {
            imageWidth = bm.getWidth();
            imageHeight = bm.getHeight();
            post(new Runnable() {
                @Override
                public void run() {
                    calculateBaseTransform();
                    updateMatrix();
                }
            });
        }
    }

    private void calculateBaseTransform() {
        if (imageWidth == 0 || imageHeight == 0) return;

        int viewWidth = getWidth();
        int viewHeight = getHeight();

        if (viewWidth == 0 || viewHeight == 0) return;

        // Calculate scale to fit image in view (fitCenter behavior)
        float scaleW = (float) viewWidth / imageWidth;
        float scaleH = (float) viewHeight / imageHeight;
        baseScale = Math.min(scaleW, scaleH);

        // Calculate offset to center the image
        float scaledImageWidth = imageWidth * baseScale;
        float scaledImageHeight = imageHeight * baseScale;
        baseOffsetX = (viewWidth - scaledImageWidth) / 2f;
        baseOffsetY = (viewHeight - scaledImageHeight) / 2f;
    }

    private void updateMatrix() {
        transformMatrix.reset();

        // Apply base transform (fitCenter)
        transformMatrix.postScale(baseScale, baseScale);
        transformMatrix.postTranslate(baseOffsetX, baseOffsetY);

        // Apply user zoom and pan
        transformMatrix.postScale(currentScale, currentScale, getWidth() / 2f, getHeight() / 2f);
        transformMatrix.postTranslate(currentTranslateX, currentTranslateY);

        setImageMatrix(transformMatrix);
    }

    private void constrainTranslation() {
        // Get the scaled image bounds
        float scaledWidth = imageWidth * baseScale * currentScale;
        float scaledHeight = imageHeight * baseScale * currentScale;

        int viewWidth = getWidth();
        int viewHeight = getHeight();

        // Allow panning only if image is larger than view
        float maxTransX = Math.max(0, (scaledWidth - viewWidth) / 2f);
        float maxTransY = Math.max(0, (scaledHeight - viewHeight) / 2f);

        currentTranslateX = Math.max(-maxTransX, Math.min(maxTransX, currentTranslateX));
        currentTranslateY = Math.max(-maxTransY, Math.min(maxTransY, currentTranslateY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (textBoxes.isEmpty() || imageWidth == 0 || imageHeight == 0) {
            return;
        }

        // Draw bounding boxes with current transform
        for (TextBox box : textBoxes) {
            // Transform box coordinates
            float[] pts = new float[] {
                box.rect.left, box.rect.top,
                box.rect.right, box.rect.bottom
            };
            transformMatrix.mapPoints(pts);

            // Draw semi-transparent fill
            canvas.drawRect(pts[0], pts[1], pts[2], pts[3], fillPaint);

            // Draw border
            canvas.drawRect(pts[0], pts[1], pts[2], pts[3], boxPaint);
        }
    }

    private void handleTap(float touchX, float touchY) {
        // Convert touch coordinates to image coordinates using inverse matrix
        Matrix inverse = new Matrix();
        if (transformMatrix.invert(inverse)) {
            float[] pts = new float[] { touchX, touchY };
            inverse.mapPoints(pts);

            float imgX = pts[0];
            float imgY = pts[1];

            // Find which box was tapped
            for (TextBox box : textBoxes) {
                if (box.rect.contains(imgX, imgY)) {
                    Toast.makeText(getContext(), box.text, Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Let scale detector handle pinch gestures
        scaleDetector.onTouchEvent(event);

        // Let gesture detector handle taps
        gestureDetector.onTouchEvent(event);

        // Handle panning
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getX();
                lastTouchY = event.getY();
                isDragging = false;
                break;

            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && event.getPointerCount() == 1) {
                    float dx = event.getX() - lastTouchX;
                    float dy = event.getY() - lastTouchY;

                    if (!isDragging && (Math.abs(dx) > TOUCH_SLOP || Math.abs(dy) > TOUCH_SLOP)) {
                        isDragging = true;
                    }

                    if (isDragging && currentScale > 1f) {
                        currentTranslateX += dx;
                        currentTranslateY += dy;
                        constrainTranslation();
                        updateMatrix();
                        invalidate();
                    }

                    lastTouchX = event.getX();
                    lastTouchY = event.getY();
                }
                break;
        }

        return true;
    }
}
