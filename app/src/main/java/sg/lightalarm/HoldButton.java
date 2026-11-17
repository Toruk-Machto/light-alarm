package sg.lightalarm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Gravity;
import android.widget.TextView;

/** A continuous, cancellable hold; never responds to a short tap. */
final class HoldButton extends TextView {
    static final long HOLD_MS = 1500;
    private final Runnable action;
    private final Paint paint = new Paint();
    private long started;
    private boolean holding;
    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (!holding) return;
            invalidate();
            if (SystemClock.uptimeMillis() - started >= HOLD_MS) {
                cancelHold();
                action.run();
            } else postDelayed(this, 32);
        }
    };

    HoldButton(Context context, String label, boolean primary, Runnable action) {
        super(context);
        this.action = action;
        setText(label);
        setTextSize(18);
        setGravity(Gravity.CENTER);
        setTextColor(primary ? Page.WHITE : Page.INK);
        setBackgroundColor(primary ? Page.INK : Page.RULE);
        paint.setColor(primary ? 0x55ffffff : 0x33000000);
        setPadding(12, 12, 12, 12);
        setFocusable(true);
        setContentDescription(label + ". Hold for one and a half seconds. Release early to cancel.");
    }

    private void begin() {
        if (holding || !isEnabled()) return;
        holding = true;
        started = SystemClock.uptimeMillis();
        getParent().requestDisallowInterceptTouchEvent(true);
        post(progress);
    }

    void cancelHold() { holding = false; removeCallbacks(progress); invalidate(); }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> begin();
            case MotionEvent.ACTION_MOVE -> {
                if (event.getX() < 0 || event.getY() < 0 || event.getX() >= getWidth() || event.getY() >= getHeight()) cancelHold();
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelHold();
        }
        return true;
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_SPACE) {
            if (event.getRepeatCount() == 0) begin();
            return true;
        }
        return super.onKeyDown(code, event);
    }
    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_SPACE) {
            cancelHold(); return true;
        }
        return super.onKeyUp(code, event);
    }
    @Override protected void onFocusChanged(boolean gain, int direction, android.graphics.Rect old) {
        if (!gain) cancelHold();
        super.onFocusChanged(gain, direction, old);
    }
    @Override protected void onDetachedFromWindow() { cancelHold(); super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        if (holding) canvas.drawRect(0, 0, getWidth() * Math.min(1f,
                (SystemClock.uptimeMillis() - started) / (float) HOLD_MS), getHeight(), paint);
        super.onDraw(canvas);
    }
}
