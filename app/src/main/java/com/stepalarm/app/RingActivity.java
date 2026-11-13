package com.stepalarm.app;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Date;

/** Full-screen ringing screen. There is no stop button: only walking stops it. */
public class RingActivity extends Activity implements AlarmService.Listener {

    private TextView title;
    private TextView count;
    private TextView goalText;
    private TextView hint;
    private ProgressBar bar;
    private Button emergency;

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
        TextView clock = findViewById(R.id.clock);
        clock.setText(DateFormat.getTimeFormat(this).format(new Date()));

        emergency.setOnClickListener(v -> AlarmService.stopWithoutSensors(this));
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
    public void onProgress(int steps, int goal) {
        runOnUiThread(() -> {
            count.setText(String.valueOf(steps));
            goalText.setText("of " + goal + " steps");
            bar.setMax(goal);
            bar.setProgress(steps);
        });
    }

    @Override
    public void onDone() {
        runOnUiThread(() -> {
            title.setText("Good morning!");
            count.setText("✓");
            goalText.setText("Alarm off");
            hint.setText("");
            emergency.setVisibility(View.GONE);
            bar.setProgress(bar.getMax());
            count.postDelayed(this::finish, 1500);
        });
    }

    @Override
    public void onBackPressed() {
        // Back button does nothing while the alarm rings.
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
