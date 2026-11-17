package sg.lightalarm;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.function.IntConsumer;

public final class MainActivity extends Page {
    private static final String[] DAYS = {"Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};
    private static final String[] RULES = {"One time", "Selected weekdays", "Monthly by date", "Monthly by weekday"};
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Tone preview = new Tone();
    private AlarmVolume previewVolume;
    private boolean previewPlaying;
    private Runnable previewStop;
    private TextView previewButton;
    private String screen = "home";
    private AlarmStore.Alarm editing;
    private boolean existing;
    private TextView countdown;
    private long nextAt;
    private String snapshot = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (screen.equals("home")) {
                AlarmStore data = new AlarmStore(MainActivity.this);
                if (!snapshot.equals(homeSignature(data))) home();
                else updateCountdown();
            } else if (screen.equals("history") && !snapshot.equals(historySignature()))
                history();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_ALARM);
        AlarmStore data = new AlarmStore(this);
        if (data.ringing.isEmpty()) AlarmVolume.restoreIfUnused(this);
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (AlarmStore.Alarm a : data.alarms) {
            if (a.enabled && data.pending.stream().noneMatch(e -> e.alarm().equals(a.id) && e.type() == AlarmStore.MAIN)) {
                data.plan(a, now);
                changed = true;
            }
        }
        if (!data.pending.isEmpty() || !data.ringing.isEmpty()) {
            data.tick(now);
            changed = true;
        }
        if (changed) data.save();
        AlarmReceiver.schedule(this, data);
        if (!data.ringing.isEmpty()) AlarmReceiver.refresh(this, data);
        home();
    }

    @Override protected void onResume() {
        super.onResume();
        AlarmReceiver.finishExpired(this, new AlarmStore(this));
        handler.removeCallbacks(refresh);
        refresh.run();
    }
    @Override protected void onPause() { stopPreview(); handler.removeCallbacksAndMessages(null); super.onPause(); }

    @Override public void onBackPressed() {
        if (screen.equals("home")) super.onBackPressed();
        else if (screen.equals("schedule") || screen.equals("ringing") || screen.equals("followup")) editor();
        else home();
    }

    private boolean exact() { return getSystemService(AlarmManager.class).canScheduleExactAlarms(); }

    private String homeSignature(AlarmStore data) {
        return data.pending.toString() + data.ringing.toString() + exact()
                + getSystemService(NotificationManager.class).areNotificationsEnabled()
                + getSystemService(NotificationManager.class).canUseFullScreenIntent();
    }

    private String historySignature() {
        AlarmStore data = new AlarmStore(this);
        data.purge(System.currentTimeMillis());
        return data.history.toString();
    }

    private void home() {
        screen = "home";
        page("Light Alarm", "History", null, this::history);
        AlarmStore data = new AlarmStore(this);
        snapshot = homeSignature(data);
        long now = System.currentTimeMillis();
        AlarmStore.Event next = data.pending.stream().filter(e -> e.at() >= now)
                .min(Comparator.comparingLong(AlarmStore.Event::at)).orElse(null);
        LinearLayout hero = card(root);
        label(hero, "Next up");
        if (next != null && exact()) {
            AlarmStore.Alarm alarm = data.find(next.alarm());
            nextAt = next.at();
            TextView clock = text(time(nextAt), 43, true, INK);
            hero.addView(clock);
            String phase = next.phase().isEmpty() ? "" : " · " + next.phase();
            note(hero, day(nextAt) + " · " + alarm.title() + phase);
            gap(hero, 5);
            countdown = text("", 15, true, INK);
            hero.addView(countdown);
            updateCountdown();
        } else {
            countdown = null;
            hero.addView(text("No alarm armed", 28, true, INK));
            note(hero, exact() ? "Set an alarm or reminder." : "Exact scheduling is unavailable.");
        }
        if (!data.ringing.isEmpty())
            root.addView(button("Open ringing alarms", true, () -> startActivity(new Intent(this, RingActivity.class))));
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (!notifications.areNotificationsEnabled()) {
            line(root, "Enable alarm notifications", "Show ringing controls when the app is closed.", () -> {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 21);
                else settings(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            });
        } else if (!notifications.canUseFullScreenIntent()) {
            line(root, "Lock-screen alerts", "Allow a full-screen ringing screen when locked.",
                    () -> settings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT));
        }
        gap(root, 8);
        actions(root, button("New alarm", true, () -> newAlarm(false)),
                button("New reminder", false, () -> newAlarm(true)));
        gap(root, 20);
        if (!data.alarms.isEmpty()) label(root, "Saved");
        data.alarms.sort(Comparator.comparing((AlarmStore.Alarm a) -> !a.enabled)
                .thenComparingLong(a -> data.pending.stream().filter(e -> e.alarm().equals(a.id) && e.type() == AlarmStore.MAIN)
                        .mapToLong(AlarmStore.Event::at).findFirst().orElse(Long.MAX_VALUE)));
        for (AlarmStore.Alarm a : data.alarms) {
            LinearLayout box = card(root);
            LinearLayout item = row();
            LinearLayout words = column();
            words.addView(text(a.title(), 17, true, INK));
            note(words, scheduleSummary(a));
            words.setFocusable(true);
            words.setOnClickListener(v -> { editing = AlarmStore.Alarm.from(a.json()); existing = true; editor(); });
            item.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            Switch toggle = new Switch(this);
            toggle.setContentDescription(a.title() + " enabled");
            toggle.setMinHeight(dp(48));
            toggle.setMinWidth(dp(48));
            toggle.setChecked(a.enabled);
            toggle.setOnCheckedChangeListener((v, on) -> toggle(a.id, on));
            item.addView(toggle);
            box.addView(item);
        }
    }

    private void updateCountdown() {
        if (countdown == null) return;
        long seconds = Math.max(0, (nextAt - System.currentTimeMillis() + 999) / 1000);
        if (seconds == 0) countdown.setText("Due now");
        else if (seconds < 60) countdown.setText("In " + seconds + " sec");
        else if (seconds < 3600) countdown.setText("In " + seconds / 60 + " min");
        else if (seconds < 86400) countdown.setText("In " + seconds / 3600 + "h " + (seconds / 60 % 60) + "m");
        else countdown.setText("In " + seconds / 86400 + "d " + (seconds / 3600 % 24) + "h");
    }

    private void settings(String action) {
        Intent i = new Intent(action);
        if (action.equals(Settings.ACTION_APP_NOTIFICATION_SETTINGS))
            i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        else i.setData(android.net.Uri.parse("package:" + getPackageName()));
        try { startActivity(i); }
        catch (ActivityNotFoundException unavailable) { message("This setting is unavailable in LightOS."); }
    }

    private void newAlarm(boolean reminder) {
        editing = new AlarmStore.Alarm();
        editing.reminder = reminder;
        ZonedDateTime start = ZonedDateTime.now().plusMinutes(5);
        editing.schedule.date = start.toLocalDate().toString();
        editing.schedule.minute = start.getHour() * 60 + start.getMinute();
        for (int i = 0; i < 5; i++) editing.schedule.week[i] = editing.schedule.minute;
        if (reminder) {
            editing.tone = 0;
            editing.seconds = 30;
        }
        existing = false;
        editor();
    }

    private void editor() {
        stopPreview();
        screen = "editor";
        page(existing ? "Edit " + noun() : "New " + noun(), "Save", this::home, this::save);
        label(root, "Label");
        editText(root, editing.label, editing.reminder ? "e.g. Take a break" : "e.g. Work", 60, s -> editing.label = s);
        if (editing.reminder) {
            gap(root, 14);
            label(root, "Note");
            editText(root, editing.note, "Optional", 280, s -> editing.note = s);
        }
        gap(root, 16);
        label(root, "When it rings");
        line(root, "Schedule", scheduleSummary(editing), this::schedulePage);
        line(root, "Sound and vibration", Tone.NAMES[editing.tone] + " · " +
                (editing.vibration == 0 ? "No vibration" : Tone.PATTERNS[editing.vibration]), this::soundPage);
        line(root, "Follow-ups", editing.followupCount == 0 ? "Off" : editing.followupCount
                + " additional rings · " + editing.followupGap + " min apart", this::followupPage);
        if (editing.reviewFollowups) note(root, "Earlier retry and backup settings were combined. Review Follow-ups before saving.");
        Schedule.Next next = previewNext();
        if (next != null) {
            gap(root, 5);
            note(root, (editing.enabled ? "Next · " : "If enabled · ") + day(next.at()) + " at " + time(next.at()));
        }
        if (existing) {
            gap(root, 12);
            actions(root, button("Duplicate", false, () -> {
                editing.id = UUID.randomUUID().toString();
                editing.schedule.lastDay = "";
                existing = false;
                editor();
            }), button("Delete", false, this::confirmDelete));
        }
    }

    private String noun() { return editing.reminder ? "reminder" : "alarm"; }

    private void schedulePage() {
        screen = "schedule";
        int previousY = scroll == null ? 0 : scroll.getScrollY();
        page("Schedule", null, this::editor, null);
        Schedule s = editing.schedule;
        choice(root, "Repeat", RULES, s.kind, i -> {
            if (i == Schedule.WEEK && s.kind != Schedule.WEEK) s.sharedTime(s.minute);
            s.kind = i;
            schedulePage();
        });
        if (s.kind == Schedule.ONCE) line(root, "Date", dateFormat(LocalDate.parse(s.date)),
                () -> pickDate(s.date, value -> { s.date = value; schedulePage(); }));
        line(root, s.kind == Schedule.WEEK ? "Time for selected days" : "Time",
                s.kind == Schedule.WEEK && s.mixedWeekTimes() ? "Different saved times · choose one shared time"
                        : minutes(s.kind == Schedule.WEEK ? s.selectedWeekTime() : s.minute),
                () -> pickTime(s.kind == Schedule.WEEK ? s.selectedWeekTime() : s.minute,
                        value -> { s.sharedTime(value); schedulePage(); }));
        if (s.kind == Schedule.WEEK) {
            label(root, "Days");
            for (int rowStart = 0; rowStart < 7; rowStart += 4) {
                LinearLayout days = row();
                for (int d = rowStart; d < Math.min(7, rowStart + 4); d++) {
                    int idx = d;
                    TextView day = button(DAYS[d].substring(0, 3), s.week[d] >= 0, () -> {
                        s.week[idx] = s.week[idx] >= 0 ? -1 : s.selectedWeekTime();
                        schedulePage();
                    });
                    day.setContentDescription(DAYS[d] + (s.week[d] >= 0 ? " selected" : " not selected"));
                    LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, dp(48), 1);
                    if (d > rowStart) cell.leftMargin = dp(5);
                    days.addView(day, cell);
                }
                root.addView(days);
                gap(root, 6);
            }
            note(root, s.mixedWeekTimes() ? "Existing times are kept until you choose a shared time above."
                    : "Selected days all use the time above.");
            gap(root, 10);
            line(root, "Day presets", "Weekdays, weekends or every day", () -> new AlertDialog.Builder(this)
                    .setTitle("Enable days")
                    .setItems(new String[]{"Weekdays", "Weekends", "Every day"}, (dialog, index) -> {
                        int shared = s.selectedWeekTime();
                        for (int i = 0; i < 7; i++) {
                            boolean included = index == 2 || (index == 0 ? i < 5 : i >= 5);
                            s.week[i] = included ? (s.week[i] >= 0 ? s.week[i] : shared) : -1;
                        }
                        schedulePage();
                    }).show());
        }
        if (s.kind == Schedule.MONTH_DAY) {
            number(root, "Day of the month", s.monthDay, 1, 31, "day", v -> { s.monthDay = v; schedulePage(); });
            note(root, "Months without this date are skipped.");
            gap(root, 8);
        }
        if (s.kind == Schedule.MONTH_WEEK) {
            String[] ordinals = {"First", "Second", "Third", "Fourth", "Last"};
            choice(root, "Week of the month", ordinals, s.ordinal == -1 ? 4 : s.ordinal - 1, i -> {
                s.ordinal = i == 4 ? -1 : i + 1; schedulePage();
            });
            choice(root, "Day of the week", DAYS, s.weekday - 1, i -> { s.weekday = i + 1; schedulePage(); });
        }
        if (s.kind != Schedule.ONCE) {
            line(root, "Repeat through", s.end.isEmpty() ? "No end date" : dateFormat(LocalDate.parse(s.end)),
                    () -> new AlertDialog.Builder(this).setTitle("End date")
                            .setItems(new String[]{"Choose a date", "No end date"}, (dialog, index) -> {
                                if (index == 0) pickDate(s.end.isEmpty() ? LocalDate.now().toString() : s.end,
                                        v -> { s.end = v; schedulePage(); });
                                else { s.end = ""; schedulePage(); }
                            }).show());
        }
        line(root, "Time zone", s.zone.isEmpty() ? "Follow the phone" : s.zone, this::zonePicker);
        Schedule.Next next = previewNext();
        if (next != null) note(root, "Next on this phone · " + day(next.at()) + " at " + time(next.at()));
        scroll.post(() -> scroll.scrollTo(0, previousY));
    }

    private void zonePicker() {
        ArrayList<String> all = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(all);
        all.add(0, "Follow the phone");
        LinearLayout content = column();
        content.setPadding(dp(18), dp(8), dp(18), 0);
        EditText filter = new EditText(this);
        filter.setSingleLine();
        filter.setHint("Search cities or zones");
        content.addView(filter);
        ListView list = new ListView(this);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(245)));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, all);
        list.setAdapter(adapter);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Time zone").setView(content)
                .setNegativeButton("Cancel", null).create();
        filter.addTextChangedListener(watcher(s -> adapter.getFilter().filter(s.replace(' ', '_'))));
        list.setOnItemClickListener((parent, view, position, id) -> {
            String picked = adapter.getItem(position);
            editing.schedule.zone = picked.equals("Follow the phone") ? "" : picked;
            dialog.dismiss();
            schedulePage();
        });
        dialog.show();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        list.requestFocus();
    }

    private void stopPreview() {
        if (previewStop != null) handler.removeCallbacks(previewStop);
        previewStop = null;
        preview.close();
        if (previewVolume != null) previewVolume.close();
        previewVolume = null;
        previewPlaying = false;
        if (previewButton != null) previewButton.setText("Preview tone");
    }

    private void soundPage() {
        stopPreview();
        screen = "ringing";
        page("Sound and vibration", null, this::editor, null);
        choice(root, "Tone", Tone.NAMES, editing.tone, i -> { preview.close(); editing.tone = i; soundPage(); });
        previewButton = button("Preview tone", false, () -> {});
        previewButton.setOnClickListener(v -> {
            if (previewPlaying) { stopPreview(); previewButton.setText("Preview tone"); return; }
            if (editing.tone == 0) { message("This alarm is set to silent."); return; }
            if (!new AlarmStore(this).ringing.isEmpty()) { message("Finish the ringing alarm before previewing a tone."); return; }
            previewVolume = new AlarmVolume(this);
            if (!previewVolume.apply(editing.volume)) message("Phone settings may be blocking alarm volume.");
            preview.play(editing.tone, 0, 0);
            previewPlaying = true;
            previewButton.setText("Stop preview");
            previewStop = () -> { stopPreview(); previewButton.setText("Preview tone"); };
            handler.postDelayed(previewStop, 8000);
        });
        root.addView(previewButton);
        gap(root, 10);
        number(root, "Alarm volume", editing.volume, 10, 100, "%", v -> { editing.volume = v; soundPage(); });
        note(root, "Used while this alarm rings, even if the alarm volume was low. Previous volume is restored afterward. Do Not Disturb is respected.");
        if (AlarmVolume.dndBlocksAlarms(this)) note(root, "Do Not Disturb currently blocks alarm sound.");
        gap(root, 10);
        choice(root, "Vibration", Tone.PATTERNS, editing.vibration, i -> {
            editing.vibration = i;
            if (i > 0) getSystemService(VibratorManager.class).getDefaultVibrator().vibrate(Tone.pattern(i, false));
            soundPage();
        });
        number(root, "Fade in", editing.fade, 0, 180, "sec",
                v -> { editing.fade = v; soundPage(); });
        number(root, "Gentle pre-alarm", editing.pre, 0, 60, "min before",
                v -> { editing.pre = v; soundPage(); });
        note(root, "The pre-alarm plays a quiet tone for eight seconds. Dismissing it leaves the main alarm armed.");
    }

    private void followupPage() {
        screen = "followup";
        page("Follow-ups", null, this::editor, null);
        if (editing.reviewFollowups) note(root, "Your earlier retry/backup settings were combined below. Choose the count and gap you want, then save the alarm.");
        number(root, "Additional rings", editing.followupCount, 0, 15, "rings", v -> { editing.followupCount = v; followupPage(); });
        number(root, "Time between rings", editing.followupGap, 1, 60, "min", v -> { editing.followupGap = v; followupPage(); });
        Switch unanswered = new Switch(this);
        unanswered.setText("Also repeat if unanswered");
        unanswered.setChecked(editing.repeatUnanswered);
        unanswered.setMinHeight(dp(52));
        unanswered.setOnCheckedChangeListener((v, on) -> editing.repeatUnanswered = on);
        root.addView(unanswered);
        note(root, "One hold to snooze starts all remaining follow-ups. Snoozing again delays them without resetting the count. Hold to dismiss cancels them all for this occurrence.");
        gap(root, 12);
        number(root, "Ring for", editing.seconds, 10, 600, "sec", v -> { editing.seconds = v; followupPage(); });
        note(root, "Both Snooze and Dismiss need a 1.5-second hold. Release early to cancel.");
        gap(root, 8);
        Switch privacy = new Switch(this);
        privacy.setText("Hide label and note while locked");
        privacy.setChecked(editing.hide);
        privacy.setMinHeight(dp(48));
        privacy.setOnCheckedChangeListener((v, on) -> editing.hide = on);
        root.addView(privacy);
    }

    private void history() {
        screen = "history";
        page("History", "Clear", this::home, () -> new AlertDialog.Builder(this)
                .setTitle("Clear alarm history?").setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (d, w) -> {
                    AlarmStore data = new AlarmStore(this);
                    data.history.clear();
                    data.save();
                    AlarmReceiver.schedule(this, data);
                    history();
                }).show());
        AlarmStore data = new AlarmStore(this);
        data.purge(System.currentTimeMillis());
        snapshot = data.history.toString();
        label(root, "Last 24 hours");
        if (data.history.isEmpty()) note(root, "No alarm activity in the last 24 hours.");
        for (AlarmStore.Log log : data.history) {
            LinearLayout box = card(root);
            box.addView(text(log.label(), 16, true, INK));
            note(box, log.action() + " · " + day(log.at()) + ", " + time(log.at()));
        }
    }

    private void save() {
        Schedule.Next next = previewNext();
        if (next == null && editing.enabled) {
            message("Choose a future time or enable at least one valid repeat day.");
            return;
        }
        if (editing.enabled && !exact()) { message("This phone is currently blocking exact alarms."); return; }
        if (editing.enabled && editing.tone == 0 && editing.vibration == 0 && !getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            message("Enable notifications for a visual-only alarm, or choose a tone or vibration.");
            return;
        }
        if (editing.followupCount > 0 && editing.followupGap * 60 < editing.seconds) {
            message("The time between rings must be at least as long as Ring for.");
            return;
        }
        AlarmStore data = new AlarmStore(this);
        editing.reviewFollowups = false;
        editing.schedule.lastDay = "";
        long now = System.currentTimeMillis();
        data.put(editing, now);
        data.save();
        AlarmReceiver.schedule(this, data);
        AlarmReceiver.refresh(this, data);
        home();
        message(editing.enabled ? "Saved · " + day(next.at()) + " at " + time(next.at()) : "Saved · disabled");
    }

    private Schedule.Next previewNext() {
        Schedule schedule = editing.schedule;
        String old = schedule.lastDay;
        schedule.lastDay = "";
        try { return schedule.next(System.currentTimeMillis(), ZoneId.systemDefault()); }
        finally { schedule.lastDay = old; }
    }

    private void toggle(String id, boolean on) {
        AlarmStore data = new AlarmStore(this);
        AlarmStore.Alarm a = data.find(id);
        if (a == null) return;
        if (on && !exact()) { message("This phone is currently blocking exact alarms."); home(); return; }
        if (on && a.tone == 0 && a.vibration == 0 && !getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            message("Enable notifications for a visual-only alarm, or choose a tone or vibration.");
            home();
            return;
        }
        a.enabled = on;
        if (on) a.schedule.lastDay = "";
        data.cancel(id);
        data.plan(a, System.currentTimeMillis());
        data.save();
        AlarmReceiver.schedule(this, data);
        AlarmReceiver.refresh(this, data);
        if (on && !a.enabled) message("Edit the alarm to choose a future time.");
        home();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this).setTitle("Delete " + noun() + "?").setNegativeButton("Cancel", null)
                .setPositiveButton("Delete", (d, w) -> {
                    AlarmStore data = new AlarmStore(this);
                    data.cancel(editing.id);
                    data.alarms.removeIf(a -> a.id.equals(editing.id));
                    data.save();
                    AlarmReceiver.schedule(this, data);
                    AlarmReceiver.refresh(this, data);
                    home();
                    SnackbarUndo.show(this, root, editing);
                }).show();
    }

    private static final class SnackbarUndo {
        static void show(MainActivity page, LinearLayout container, AlarmStore.Alarm removed) {
            TextView undo = page.button("Deleted · Undo", false, () -> {
                AlarmStore data = new AlarmStore(page);
                data.put(removed, System.currentTimeMillis());
                data.save();
                AlarmReceiver.schedule(page, data);
                page.home();
            });
            container.addView(undo, 0);
            page.handler.postDelayed(() -> container.removeView(undo), 6500);
        }
    }

    private String scheduleSummary(AlarmStore.Alarm a) {
        Schedule s = a.schedule;
        String zone = s.zone.isEmpty() ? "" : " · " + s.zone;
        if (s.kind == Schedule.ONCE) return dateFormat(LocalDate.parse(s.date)) + " · " + minutes(s.minute) + zone;
        if (s.kind == Schedule.MONTH_DAY) return "Monthly on day " + s.monthDay + " · " + minutes(s.minute) + zone;
        if (s.kind == Schedule.MONTH_WEEK) {
            String ord = s.ordinal == -1 ? "Last" : new String[]{"First", "Second", "Third", "Fourth"}[s.ordinal - 1];
            return ord + " " + DAYS[s.weekday - 1] + " · " + minutes(s.minute) + zone;
        }
        ArrayList<String> names = new ArrayList<>();
        Set<Integer> times = new LinkedHashSet<>();
        for (int i = 0; i < 7; i++) if (s.week[i] >= 0) {
            names.add(DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault()));
            times.add(s.week[i]);
        }
        if (names.isEmpty()) return "Choose repeat days";
        return String.join(", ", names) + " · " + (times.size() == 1 ? minutes(times.iterator().next()) : "Different times") + zone;
    }

    private void number(LinearLayout parent, String title, int value, int min, int max, String unit, IntConsumer save) {
        line(parent, title, value == 0 ? "Off" : value + " " + unit, () -> {
            EditText field = new EditText(this);
            field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            field.setText(Integer.toString(value));
            field.setSelectAllOnFocus(true);
            LinearLayout box = column();
            box.setPadding(dp(22), dp(2), dp(22), 0);
            note(box, min + "–" + max + " " + unit + (min == 0 ? " · 0 turns this off" : ""));
            box.addView(field);
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(box)
                    .setNegativeButton("Cancel", null).setPositiveButton("Set", null).create();
            dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                try {
                    int selected = Integer.parseInt(field.getText().toString());
                    if (selected < min || selected > max) throw new NumberFormatException();
                    dialog.dismiss();
                    save.accept(selected);
                } catch (NumberFormatException ignored) { field.setError("Enter a number from " + min + " to " + max); }
            }));
            dialog.show();
        });
    }

    private void choice(LinearLayout parent, String label, String[] values, int current, IntConsumer onChoose) {
        line(parent, label, values[current], () -> new AlertDialog.Builder(this).setTitle(label)
                .setSingleChoiceItems(values, current, (dialog, i) -> { dialog.dismiss(); onChoose.accept(i); })
                .setNegativeButton("Cancel", null).show());
    }

    private void editText(LinearLayout parent, String value, String hint, int max, java.util.function.Consumer<String> setter) {
        EditText field = new EditText(this);
        field.setTextColor(INK);
        field.setTextSize(16);
        field.setSingleLine(max < 100);
        field.setMaxLines(3);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setText(value);
        field.setHint(hint);
        field.setContentDescription(hint);
        field.setPadding(dp(12), dp(9), dp(12), dp(9));
        field.setMinimumHeight(dp(54));
        field.setBackground(shape(WHITE, RULE, 8));
        field.addTextChangedListener(watcher(setter));
        parent.setFocusableInTouchMode(true);
        parent.addView(field, new LinearLayout.LayoutParams(-1, -2));
        parent.requestFocus();
    }

    private TextWatcher watcher(java.util.function.Consumer<String> setter) {
        return new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { setter.accept(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        };
    }

    private void pickTime(int minutes, IntConsumer chosen) {
        new TimePickerDialog(this, (picker, h, m) -> chosen.accept(h * 60 + m),
                minutes / 60, minutes % 60, android.text.format.DateFormat.is24HourFormat(this)).show();
    }

    private void pickDate(String value, java.util.function.Consumer<String> chosen) {
        LocalDate d = LocalDate.parse(value);
        new DatePickerDialog(this, (picker, y, m, day) -> chosen.accept(LocalDate.of(y, m + 1, day).toString()),
                d.getYear(), d.getMonthValue() - 1, d.getDayOfMonth()).show();
    }

    private String minutes(int time) {
        return LocalTime.of(time / 60, time % 60).format(DateTimeFormatter.ofPattern(
                android.text.format.DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a"));
    }

    private String time(long at) {
        ZonedDateTime shown = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());
        return minutes(shown.getHour() * 60 + shown.getMinute());
    }

    private String day(long at) {
        LocalDate day = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate today = LocalDate.now();
        if (day.equals(today)) return "Today";
        if (day.equals(today.plusDays(1))) return "Tomorrow";
        return dateFormat(day);
    }

    private String dateFormat(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern(d.getYear() == LocalDate.now().getYear() ? "EEE, d MMM" : "EEE, d MMM uuuu"));
    }

    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
