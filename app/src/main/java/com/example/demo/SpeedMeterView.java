package com.example.demo;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class SpeedMeterView extends View {

    private Paint backgroundPaint;
    private Paint trackPaint;
    private Paint arcPaint;
    private Paint needlePaint;
    private Paint needleCenterPaint;
    private Paint speedTextPaint;
    private Paint labelPaint;
    private Paint innerCirclePaint;
    private Paint glowPaint;

    private RectF arcRect;
    private float currentAngle = 0f;
    private float currentSpeed = 0f;
    private float displaySpeed = 0f;
    private float maxSpeed = 100f;

    private ValueAnimator speedAnimator;
    private boolean isAnimating = false;

    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;

    public SpeedMeterView(Context context) {
        super(context);
        init();
    }

    public SpeedMeterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public SpeedMeterView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setStyle(Paint.Style.FILL);
        backgroundPaint.setColor(Color.parseColor("#1A1A2E"));

        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setColor(Color.parseColor("#2D2D5E"));
        glowPaint.setStrokeWidth(12f);

        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(Color.parseColor("#2D2D5E"));
        trackPaint.setStrokeCap(Paint.Cap.ROUND);

        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        needlePaint.setStyle(Paint.Style.FILL);
        needlePaint.setColor(Color.parseColor("#FF6B6B"));

        needleCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        needleCenterPaint.setStyle(Paint.Style.FILL);
        needleCenterPaint.setColor(Color.WHITE);

        innerCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerCirclePaint.setStyle(Paint.Style.FILL);
        innerCirclePaint.setColor(Color.parseColor("#16213E"));

        speedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        speedTextPaint.setColor(Color.WHITE);
        speedTextPaint.setTextAlign(Paint.Align.CENTER);
        speedTextPaint.setFakeBoldText(true);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.parseColor("#8888BB"));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        arcRect = new RectF();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int width = getWidth();
        int height = getHeight();
        float cx = width / 2f;
        float cy = height / 2f;
        float radius = Math.min(cx, cy) * 0.85f;
        float strokeWidth = radius * 0.12f;

        // Outer glow circle
        glowPaint.setStrokeWidth(strokeWidth * 0.3f);
        canvas.drawCircle(cx, cy, radius + strokeWidth * 0.5f, glowPaint);

        // Background circle
        backgroundPaint.setColor(Color.parseColor("#1A1A2E"));
        canvas.drawCircle(cx, cy, radius + strokeWidth, backgroundPaint);

        // Arc rect
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);
        trackPaint.setStrokeWidth(strokeWidth);

        // Track arc
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint);

        // Speed arc with gradient
        if (currentAngle > 0) {
            SweepGradient gradient = new SweepGradient(cx, cy,
                    new int[]{
                            Color.parseColor("#00C9FF"),
                            Color.parseColor("#00E5FF"),
                            Color.parseColor("#00FF88"),
                            Color.parseColor("#FFEB3B"),
                            Color.parseColor("#FF6B6B")
                    },
                    new float[]{0f, 0.2f, 0.5f, 0.75f, 1f}
            );
            arcPaint.setShader(gradient);
            arcPaint.setStrokeWidth(strokeWidth);
            canvas.drawArc(arcRect, START_ANGLE, currentAngle, false, arcPaint);
        }

        // Inner circle
        canvas.drawCircle(cx, cy, radius - strokeWidth * 1.2f, innerCirclePaint);

        // Tick marks
        drawTickMarks(canvas, cx, cy, radius, strokeWidth);

        // Needle
        drawNeedle(canvas, cx, cy, radius, strokeWidth);

        // Center dot
        needleCenterPaint.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, strokeWidth * 0.4f, needleCenterPaint);
        needleCenterPaint.setColor(Color.parseColor("#1A1A2E"));
        canvas.drawCircle(cx, cy, strokeWidth * 0.2f, needleCenterPaint);

        // Speed text
        speedTextPaint.setTextSize(radius * 0.38f);
        canvas.drawText(String.format("%.1f", displaySpeed), cx, cy + radius * 0.15f, speedTextPaint);

        // Mbps label
        labelPaint.setTextSize(radius * 0.15f);
        canvas.drawText("Mbps", cx, cy + radius * 0.38f, labelPaint);

        // Min / Max labels
        labelPaint.setTextSize(radius * 0.12f);
        float labelRadius = radius + strokeWidth * 0.6f;
        double startRad = Math.toRadians(START_ANGLE);
        float lx0 = (float) (cx + labelRadius * Math.cos(startRad));
        float ly0 = (float) (cy + labelRadius * Math.sin(startRad));
        canvas.drawText("0", lx0, ly0, labelPaint);

        double endRad = Math.toRadians(START_ANGLE + SWEEP_ANGLE);
        float lx1 = (float) (cx + labelRadius * Math.cos(endRad));
        float ly1 = (float) (cy + labelRadius * Math.sin(endRad));
        canvas.drawText((int) maxSpeed + "", lx1, ly1, labelPaint);
    }

    private void drawTickMarks(Canvas canvas, float cx, float cy, float radius, float strokeWidth) {
        Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        int totalTicks = 40;
        for (int i = 0; i <= totalTicks; i++) {
            float fraction = (float) i / totalTicks;
            float angle = START_ANGLE + fraction * SWEEP_ANGLE;
            double rad = Math.toRadians(angle);

            boolean isMajor = (i % 8 == 0);
            float innerR = isMajor ? radius - strokeWidth * 1.8f : radius - strokeWidth * 1.4f;
            float outerR = radius - strokeWidth * 0.8f;

            tickPaint.setStrokeWidth(isMajor ? 3f : 1.5f);
            tickPaint.setColor(isMajor ? Color.parseColor("#6666AA") : Color.parseColor("#3A3A6A"));

            float x1 = (float) (cx + innerR * Math.cos(rad));
            float y1 = (float) (cy + innerR * Math.sin(rad));
            float x2 = (float) (cx + outerR * Math.cos(rad));
            float y2 = (float) (cy + outerR * Math.sin(rad));
            canvas.drawLine(x1, y1, x2, y2, tickPaint);
        }
    }

    private void drawNeedle(Canvas canvas, float cx, float cy, float radius, float strokeWidth) {
        float needleAngle = START_ANGLE + currentAngle;
        double rad = Math.toRadians(needleAngle);

        float needleLength = radius - strokeWidth * 1.5f;
        float basePerpLen = strokeWidth * 0.25f;

        double perpRad = rad + Math.PI / 2;
        float tipX = (float) (cx + needleLength * Math.cos(rad));
        float tipY = (float) (cy + needleLength * Math.sin(rad));

        float base1X = (float) (cx + basePerpLen * Math.cos(perpRad));
        float base1Y = (float) (cy + basePerpLen * Math.sin(perpRad));
        float base2X = (float) (cx - basePerpLen * Math.cos(perpRad));
        float base2Y = (float) (cy - basePerpLen * Math.sin(perpRad));

        android.graphics.Path needlePath = new android.graphics.Path();
        needlePath.moveTo(tipX, tipY);
        needlePath.lineTo(base1X, base1Y);
        needlePath.lineTo(base2X, base2Y);
        needlePath.close();

        needlePaint.setColor(Color.parseColor("#FF6B6B"));
        canvas.drawPath(needlePath, needlePaint);

        // Needle tail
        float tailLength = strokeWidth * 0.6f;
        double tailRad = rad + Math.PI;
        float tailX = (float) (cx + tailLength * Math.cos(tailRad));
        float tailY = (float) (cy + tailLength * Math.sin(tailRad));
        needlePaint.setColor(Color.parseColor("#CC4444"));
        float tailBase = basePerpLen * 0.6f;
        android.graphics.Path tailPath = new android.graphics.Path();
        tailPath.moveTo(tailX, tailY);
        tailPath.lineTo((float)(cx + tailBase * Math.cos(perpRad)), (float)(cy + tailBase * Math.sin(perpRad)));
        tailPath.lineTo((float)(cx - tailBase * Math.cos(perpRad)), (float)(cy - tailBase * Math.sin(perpRad)));
        tailPath.close();
        canvas.drawPath(tailPath, needlePaint);
    }

    public void setSpeed(float speed) {
        float clampedSpeed = Math.min(speed, maxSpeed);
        float targetAngle = (clampedSpeed / maxSpeed) * SWEEP_ANGLE;

        if (speedAnimator != null && speedAnimator.isRunning()) {
            speedAnimator.cancel();
        }

        final float fromAngle = currentAngle;
        final float fromSpeed = displaySpeed;

        speedAnimator = ValueAnimator.ofFloat(0f, 1f);
        speedAnimator.setDuration(600);
        speedAnimator.setInterpolator(new DecelerateInterpolator());
        speedAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            currentAngle = fromAngle + (targetAngle - fromAngle) * fraction;
            displaySpeed = fromSpeed + (clampedSpeed - fromSpeed) * fraction;
            invalidate();
        });
        speedAnimator.start();

        currentSpeed = clampedSpeed;
        isAnimating = true;
    }

    public void stopAnimation() {
        if (speedAnimator != null && speedAnimator.isRunning()) {
            speedAnimator.cancel();
        }
        isAnimating = false;
    }

    public void reset() {
        stopAnimation();
        final float fromAngle = currentAngle;
        final float fromSpeed = displaySpeed;

        speedAnimator = ValueAnimator.ofFloat(0f, 1f);
        speedAnimator.setDuration(800);
        speedAnimator.setInterpolator(new DecelerateInterpolator());
        speedAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            currentAngle = fromAngle * (1f - fraction);
            displaySpeed = fromSpeed * (1f - fraction);
            invalidate();
        });
        speedAnimator.start();
        currentSpeed = 0f;
    }

    public void setMaxSpeed(float maxSpeed) {
        this.maxSpeed = maxSpeed;
        invalidate();
    }

    public float getMaxSpeed() { return maxSpeed; }
}