package com.stepalarm.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.timepicker.MaterialTimePicker;
import com.google.android.material.timepicker.TimeFormat;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/** Settings screen: time, steps, sound, and turning the alarm on. */
public class MainActivity extends AppCompatActivity {

    private View root;
    private TextView timeText, stepsText, statusText, permText, greeting, soundName, repeatSub;
    private MaterialSwitch enabledSwitch, repeatSwitch, vibrateSwitch;
    private MaterialButton fixButton, previewButton;
    private MaterialCardView setupCard;
    private View allSet;
    private SharedPreferences prefs;
    private boolean binding;

    private MediaPlayer previewPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable stopPreviewLater = this::stopPreview;

    /** One thing the phone still needs to allow. */
    private static final class Problem {
        final String label;
        final Runnable fix;
        Problem(String label, Runnable fix) { this.label = label; this.fix = fix; }
    }

    // Phone's own alarm-sound list.
    private final ActivityResultLauncher<Intent> ringtonePicker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                Uri uri = result.getData().getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                if (uri == null) return;
                Alarms.saveSound(this, Alarms.SOUND_SYSTEM, uri, ringtoneTitle(uri));
                refresh();
                toast("Alarm sound changed");
            });

    // Any song or sound file on the phone.
    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) { }
                Alarms.saveSound(this, Alarms.SOUND_FILE, uri, fileName(uri));
                refresh();
                toast("Alarm sound changed");
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Alarms.prefs(this);

        root = findViewById(R.id.root);
        timeText = findViewById(R.id.time);
        stepsText = findViewById(R.id.steps);
        statusText = findViewById(R.id.status);
        permText = findViewById(R.id.perm);
        greeting = findViewById(R.id.greeting);
        soundName = findViewById(R.id.sound_name);
        repeatSub = findViewById(R.id.repeat_sub);
        enabledSwitch = findViewById(R.id.enabled);
        repeatSwitch = findViewById(R.id.repeat);
        vibrateSwitch = findViewById(R.id.vibrate);
        fixButton = findViewById(R.id.fix);
        previewButton = findViewById(R.id.preview);
        setupCard = findViewById(R.id.setup_card);
        allSet = findViewById(R.id.all_set);

        timeText.setOnClickListener(v -> pickTime());
        findViewById(R.id.minus).setOnClickListener(v -> changeSteps(-1));
        findViewById(R.id.plus).setOnClickListener(v -> changeSteps(1));
        findViewById(R.id.row_repeat).setOnClickListener(v -> repeatSwitch.toggle());
        findViewById(R.id.row_vibrate).setOnClickListener(v -> vibrateSwitch.toggle());
        findViewById(R.id.row_sound).setOnClickListener(v -> chooseSound());
        previewButton.setOnClickListener(v -> togglePreview());

        enabledSwitch.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            prefs.edit().putBoolean(Alarms.KEY_ENABLED, on).apply();
            Alarms.schedule(this);
            refresh();
            if (on) toast("Alarm on: " + statusText.getText());
        });
        repeatSwitch.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            prefs.edit().putBoolean(Alarms.KEY_REPEAT, on).apply();
            refresh();
        });
        vibrateSwitch.setOnCheckedChangeListener((b, on) -> {
            if (binding) return;
            prefs.edit().putBoolean(Alarms.KEY_VIBRATE, on).apply();
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

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
    }

    // ---------- actions ----------

    private void pickTime() {
        MaterialTimePicker picker = new MaterialTimePicker.Builder()
                .setTimeFormat(DateFormat.is24HourFormat(this) ? TimeFormat.CLOCK_24H : TimeFormat.CLOCK_12H)
                .setHour(Alarms.hour(this))
                .setMinute(Alarms.minute(this))
                .setTitleText("Wake-up time")
                .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
                .build();
        picker.addOnPositiveButtonClickListener(v -> {
            prefs.edit()
                    .putInt(Alarms.KEY_HOUR, picker.getHour())
                    .putInt(Alarms.KEY_MINUTE, picker.getMinute())
                    .putBoolean(Alarms.KEY_ENABLED, true)
                    .apply();
            Alarms.schedule(this);
            refresh();
            toast("Alarm set. " + statusText.getText());
        });
        picker.show(getSupportFragmentManager(), "time");
    }

    private void changeSteps(int delta) {
        int s = Math.max(1, Math.min(200, Alarms.steps(this) + delta));
        prefs.edit().putInt(Alarms.KEY_STEPS, s).apply();
        refresh();
    }

    private void chooseSound() {
        stopPreview();
        String[] items = {
                "Bangun Rey tone (built-in)",
                "Phone alarm sounds",
                "Song or sound from my files"
        };
        new MaterialAlertDialogBuilder(this)
                .setTitle("Alarm sound")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Alarms.saveSound(this, Alarms.SOUND_BUILTIN, null, null);
                        refresh();
                        toast("Alarm sound changed");
                    } else if (which == 1) {
                        openRingtonePicker();
                    } else {
                        try {
                            filePicker.launch(new String[]{"audio/*"});
                        } catch (Exception e) {
                            toast("No file app found on this phone");
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openRingtonePicker() {
        Intent i = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound");
        if (Alarms.SOUND_SYSTEM.equals(prefs.getString(Alarms.KEY_SOUND_TYPE, ""))) {
            i.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Alarms.soundUri(this));
        }
        try {
            ringtonePicker.launch(i);
        } catch (Exception e) {
            toast("This phone has no alarm sound list");
        }
    }

    private void togglePreview() {
        if (previewPlayer != null) {
            stopPreview();
            return;
        }
        try {
            previewPlayer = new MediaPlayer();
            previewPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            previewPlayer.setDataSource(this, Alarms.soundUri(this));
            previewPlayer.setLooping(true);
            previewPlayer.prepare();
            previewPlayer.setVolume(0.7f, 0.7f);
            previewPlayer.start();
            previewButton.setIconResource(R.drawable.ic_stop);
            handler.postDelayed(stopPreviewLater, 10_000L);
        } catch (Exception e) {
            stopPreview();
            toast("Can't play this sound. Please choose another.");
        }
    }

    private void stopPreview() {
        handler.removeCallbacks(stopPreviewLater);
        if (previewPlayer != null) {
            try { previewPlayer.stop(); } catch (Exception ignored) { }
            previewPlayer.release();
            previewPlayer = null;
        }
        if (previewButton != null) previewButton.setIconResource(R.drawable.ic_play);
    }

    private void testNow() {
        stopPreview();
        Alarms.startRinging(this);
        startActivity(new Intent(this, RingActivity.class));
    }

    private void toast(CharSequence text) {
        Snackbar.make(root, text, Snackbar.LENGTH_SHORT).show();
    }

    // ---------- screen ----------

    private void refresh() {
        binding = true;
        boolean on = prefs.getBoolean(Alarms.KEY_ENABLED, false);
        boolean repeat = prefs.getBoolean(Alarms.KEY_REPEAT, false);
        timeText.setText(Alarms.formatTime(this, Alarms.hour(this), Alarms.minute(this)));
        timeText.setAlpha(on ? 1f : 0.45f);
        stepsText.setText(String.valueOf(Alarms.steps(this)));
        enabledSwitch.setChecked(on);
        repeatSwitch.setChecked(repeat);
        vibrateSwitch.setChecked(Alarms.vibrate(this));
        repeatSub.setText(repeat ? "Rings every day" : "Rings once");
        soundName.setText(Alarms.soundName(this));
        binding = false;

        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 11 ? "Selamat pagi" : h < 15 ? "Selamat siang" : h < 18 ? "Selamat sore" : "Selamat malam";
        greeting.setText(g + ", Rey");

        if (on) {
            long mins = Math.max(0, (Alarms.nextTime(this) - System.currentTimeMillis()) / 60_000L) + 1;
            long hh = mins / 60, mm = mins % 60;
            statusText.setText("Rings in " + (hh > 0 ? hh + " h " : "") + mm + " min");
            statusText.setTextColor(getColor(R.color.primary));
        } else {
            statusText.setText("Alarm is off");
            statusText.setTextColor(getColor(R.color.muted));
        }

        List<Problem> p = problems();
        if (p.isEmpty()) {
            setupCard.setVisibility(View.GONE);
            allSet.setVisibility(View.VISIBLE);
        } else {
            StringBuilder sb = new StringBuilder("These help the alarm ring on time:");
            for (Problem x : p) sb.append("\n•  ").append(x.label);
            permText.setText(sb.toString());
            fixButton.setText("Fix: " + p.get(0).label);
            setupCard.setVisibility(View.VISIBLE);
            allSet.setVisibility(View.GONE);
        }
    }

    private String ringtoneTitle(Uri uri) {
        try {
            Ringtone r = RingtoneManager.getRingtone(this, uri);
            if (r != null) return r.getTitle(this);
        } catch (Exception ignored) { }
        return "Phone alarm sound";
    }

    private String fileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null) {
                    int dot = n.lastIndexOf('.');
                    return dot > 0 ? n.substring(0, dot) : n;
                }
            }
        } catch (Exception ignored) { }
        return "Song from your phone";
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
            list.add(new Problem("Allow physical activity",
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
            list.add(new Problem("Run in background", () -> open(new Intent(
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
