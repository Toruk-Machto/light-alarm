package sg.lightalarm;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

abstract class Page extends Activity {
    static final int BG = 0xfff7f7f7, INK = 0xff191919, MUTED = 0xff555555, RULE = 0xffdedede, WHITE = Color.WHITE;
    LinearLayout root;
    ScrollView scroll;

    int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    void page(String title, String trail, Runnable back, Runnable action) {
        LinearLayout frame = column();
        frame.setBackgroundColor(BG);
        LinearLayout header = row();
        header.setPadding(dp(16), dp(2), dp(16), dp(2));
        if (back != null) {
            TextView b = button("Back", false, back);
            b.setPadding(dp(10), 0, dp(12), 0);
            header.addView(b);
        }
        TextView heading = text(title, 19, true, INK);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(heading, new LinearLayout.LayoutParams(0, dp(54), 1));
        if (action != null) header.addView(button(trail, false, action));
        frame.addView(header);
        View line = new View(this);
        line.setBackgroundColor(RULE);
        frame.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root = column();
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
    }

    LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    TextView text(String content, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return view;
    }

    GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    TextView button(String label, boolean primary, Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTextColor(primary ? WHITE : INK);
        btn.setGravity(Gravity.CENTER);
        btn.setMinimumHeight(dp(48));
        btn.setPadding(dp(14), 0, dp(14), 0);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(primary ? 0x30ffffff : 0x15000000),
                shape(primary ? INK : BG, primary ? 0 : RULE, 8), null));
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    void gap(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(height)));
    }

    void label(LinearLayout parent, String title) {
        TextView view = text(title, 13, true, MUTED);
        parent.addView(view);
        gap(parent, 7);
    }

    void note(LinearLayout parent, String title) {
        parent.addView(text(title, 14, false, MUTED));
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout box = column();
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackground(shape(WHITE, RULE, 10));
        parent.addView(box, new LinearLayout.LayoutParams(-1, -2));
        gap(parent, 8);
        return box;
    }

    void line(LinearLayout parent, String title, String detail, Runnable action) {
        LinearLayout layout = card(parent);
        TextView t = text(title, 16, true, INK);
        layout.addView(t);
        if (!detail.isEmpty()) {
            gap(layout, 3);
            layout.addView(text(detail, 14, false, MUTED));
        }
        layout.setForeground(new RippleDrawable(ColorStateList.valueOf(0x17000000), null, shape(INK, 0, 10)));
        layout.setFocusable(true);
        layout.setOnClickListener(v -> action.run());
        layout.setMinimumHeight(dp(52));
    }

    void actions(LinearLayout parent, TextView first, TextView second) {
        LinearLayout horizontal = row();
        horizontal.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0, -2, 1);
        right.leftMargin = dp(8);
        horizontal.addView(second, right);
        parent.addView(horizontal);
    }
}
