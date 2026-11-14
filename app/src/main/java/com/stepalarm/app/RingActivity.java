package com.stepalarm.app;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.util.Date;

/** Full-screen ringing screen. There is no stop button: only walking stops it. */
public class RingActivity extends AppCompatActivity implements AlarmService.Listener {

    private TextView title, count, goalText, hint;
    private CircularProgressIndicator bar;
    private MaterialButton emergency;
    private ObjectAnimator pulse;
    private int shown = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_ring);

        title = findViewById(R.id.title);
        count = findViewById(R.id.count);
        goalText = findViewById(R.id.goal);
        hint = findViewById(R.id.hint);
        bar = findViewById(R.id.bar);
        emergency = findViewById(R.id.emergency);

        Date now = new Date();
        ((TextView) findViewById(R.id.clock)).setText(DateFormat.getTimeFormat(this).format(now));
        ((TextView) findViewById(R.id.date)).setText(
                DateFormat.format("EEEE, d MMMM", now));

        emergency.setOnClickListener(v -> AlarmService.stopWithoutSensors(this));

        // Back button does nothing while the alarm rings.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() { }
        });

        View box = findViewById(R.id.ring_box);
        pulse = ObjectAnimator.ofPropertyValuesHolder(box,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f));
        pulse.setDuration(700);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!AlarmService.ringing) {
            finish();
            return;
        }
        AlarmService.listener = this;
        emergency.setVisibility(AlarmService.noSensors ? View.VISIBLE : View.GONE);
        onProgress(AlarmService.steps, AlarmService.goal);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (AlarmService.listener == this) AlarmService.listener = null;
    }

    @Override
    protected void onDestroy() {
        if (pulse != null) pulse.cancel();
        super.onDestroy();
    }

    @Override
    public void onProgress(int steps, int goal) {
        runOnUiThread(() -> {
            count.setText(String.valueOf(steps));
            goalText.setText(steps == 1 && goal == 1 ? "of 1 step" : "of " + goal + " steps");
            bar.setMax(goal);
            bar.setProgressCompat(steps, shown >= 0);
            if (shown >= 0 && steps > shown) {
                count.animate().scaleX(1.18f).scaleY(1.18f).setDuration(90)
                        .withEndAction(() -> count.animate().scaleX(1f).scaleY(1f).setDuration(140).start())
                        .start();
            }
            shown = steps;
        });
    }

    @Override
    public void onDone() {
        runOnUiThread(() -> {
            if (pulse != null) pulse.cancel();
            title.setText("SELAMAT PAGI!");
            count.setText("✓");
            goalText.setText("Alarm off");
            hint.setText("Have a great day, Rey.");
            emergency.setVisibility(View.GONE);
            bar.setProgressCompat(bar.getMax(), true);
            count.postDelayed(this::finish, 1800);
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return true; // volume buttons can't silence it
        }
        return super.onKeyDown(keyCode, event);
    }
}
