package com.stepalarm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Settings screen: pick the time, the number of steps, and turn the alarm on. */
public class MainActivity extends Activity {

    private TextView timeText, stepsText, statusText, permText;
    private Switch enabledSwitch;
    private CheckBox repeatBox;
    private Button fixButton;
    private SharedPreferences prefs;
    private boolean binding;

    /** One thing the phone still needs to allow. */
    private static final class Problem {
        final String label;
        final Runnable fix;
        Problem(String label, Runnable fix) { this.label = label; this.fix = fix; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Alarms.prefs(this);

        timeText = findViewById(R.id.time);
        stepsText = findViewById(R.id.steps);
        statusText = findViewById(R.id.status);
        permText = findViewById(R.id.perm);
        enabledSwitch = findViewById(R.id.enabled);
        repeatBox = findViewById(R.id.repeat);
        fixButton = findViewById(R.id.fix);

        timeText.setOnClickListener(v -> pickTime());
        findViewById(R.id.minus).setOnClickListener(v -> changeSteps(-1));
        findViewById(R.id.plus).setOnClickListener(v -> changeSteps(1));
        enabledSwitch.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            prefs.edit().putBoolean(Alarms.KEY_ENABLED, on).apply();
            Alarms.schedule(this);
            refresh();
        });
        repeatBox.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            prefs.edit().putBoolean(Alarms.KEY_REPEAT, on).apply();
            refresh();
        });
        findViewById(R.id.test).setOnClickListener(v -> testNow());
        fixButton.setOnClickListener(v -> {
            List<Problem> p = problems();
            if (!p.isEmpty()) p.get(0).fix.run();
        });

        askRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Alarms.schedule(this);
        refresh();
    }

    private void pickTime() {
        new TimePickerDialog(this, (view, h, m) -> {
            prefs.edit()
                    .putInt(Alarms.KEY_HOUR, h)
                    .putInt(Alarms.KEY_MINUTE, m)
                    .putBoolean(Alarms.KEY_ENABLED, true)
                    .apply();
            Alarms.schedule(this);
            refresh();
            Toast.makeText(this, "Alarm set for " + Alarms.formatTime(this, h, m), Toast.LENGTH_SHORT).show();
        }, Alarms.hour(this), Alarms.minute(this), DateFormat.is24HourFormat(this)).show();
    }

    private void changeSteps(int delta) {
        int s = Math.max(1, Math.min(200, Alarms.steps(this) + delta));
        prefs.edit().putInt(Alarms.KEY_STEPS, s).apply();
        refresh();
    }

    private void testNow() {
        Alarms.startRinging(this);
        startActivity(new Intent(this, RingActivity.class));
    }

    private void refresh() {
        binding = true;
        boolean on = prefs.getBoolean(Alarms.KEY_ENABLED, false);
        timeText.setText(Alarms.formatTime(this, Alarms.hour(this), Alarms.minute(this)));
        stepsText.setText(String.valueOf(Alarms.steps(this)));
        enabledSwitch.setChecked(on);
        repeatBox.setChecked(prefs.getBoolean(Alarms.KEY_REPEAT, false));
        binding = false;

        if (on) {
            long mins = Math.max(0, (Alarms.nextTime(this) - System.currentTimeMillis()) / 60_000L) + 1;
            long h = mins / 60, m = mins % 60;
            statusText.setText("Rings in " + (h > 0 ? h + " h " : "") + m + " min");
        } else {
            statusText.setText("Alarm is off");
        }

        List<Problem> p = problems();
        if (p.isEmpty()) {
            permText.setText("✓ All set. Keep the app installed and don't force-stop it.");
            permText.setTextColor(getColor(R.color.accent));
            fixButton.setVisibility(View.GONE);
        } else {
            StringBuilder sb = new StringBuilder("Still needed for a reliable alarm:");
            for (Problem x : p) sb.append("\n• ").append(x.label);
            permText.setText(sb.toString());
            permText.setTextColor(getColor(R.color.warn));
            fixButton.setText("Fix: " + p.get(0).label);
            fixButton.setVisibility(View.VISIBLE);
        }
    }

    // ---------- permissions ----------

    private void askRuntimePermissions() {
        List<String> ask = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            ask.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            ask.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (!ask.isEmpty()) {
            for (String s : ask) prefs.edit().putBoolean("asked_" + s, true).apply();
            requestPermissions(ask.toArray(new String[0]), 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        refresh();
    }

    private boolean granted(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private List<Problem> problems() {
        List<Problem> list = new ArrayList<>();
        String pkg = getPackageName();

        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            list.add(new Problem("Allow notifications", () -> runtimeFix(Manifest.permission.POST_NOTIFICATIONS)));
        }
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            list.add(new Problem("Allow physical activity (step counting)",
                    () -> runtimeFix(Manifest.permission.ACTIVITY_RECOGNITION)));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = getSystemService(AlarmManager.class);
            if (!am.canScheduleExactAlarms()) {
                list.add(new Problem("Allow alarms & reminders", () -> open(new Intent(
                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg)))));
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (!nm.canUseFullScreenIntent()) {
                list.add(new Problem("Allow full-screen alarm", () -> open(new Intent(
                        Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg)))));
            }
        }
        PowerManager pm = getSystemService(PowerManager.class);
        if (!pm.isIgnoringBatteryOptimizations(pkg)) {
            list.add(new Problem("Let it run in the background (battery)", () -> open(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)))));
        }
        return list;
    }

    private void runtimeFix(String permission) {
        boolean askedBefore = prefs.getBoolean("asked_" + permission, false);
        if (!askedBefore || shouldShowRequestPermissionRationale(permission)) {
            prefs.edit().putBoolean("asked_" + permission, true).apply();
            requestPermissions(new String[]{permission}, 2);
        } else {
            openAppSettings();
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) { }
    }
}
