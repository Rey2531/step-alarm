package com.stepalarm.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * Rings the alarm and counts steps. It only stops when the step goal is reached.
 */
public class AlarmService extends Service implements SensorEventListener {

    interface Listener {
        void onProgress(int steps, int goal);
        void onDone();
    }

    static volatile Listener listener;
    static volatile boolean ringing;
    static volatile boolean noSensors;
    static volatile int steps;
    static volatile int goal = 10;

    private static final String CHANNEL = "alarm_ring";
    private static final int NOTIFICATION_ID = 42;
    /** Safety: stop after 60 minutes so the battery is not drained if nobody is home. */
    private static final long MAX_RING_MS = 60L * 60L * 1000L;
    /** Ignore the first moments, when you grab the phone. */
    private static final long WARMUP_MS = 1500L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private ToneGenerator tone;
    private Runnable toneLoop;
    private Vibrator vibrator;
    private SensorManager sensorManager;
    private PowerManager.WakeLock wakeLock;

    private int detectorSteps;
    private int accelSteps;
    private float base;
    private float smooth;
    private boolean above;
    private long lastAccelStep;
    private long startedAt;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (ringing) {
            showRingScreen();
            return START_STICKY;
        }
        ringing = true;
        steps = 0;
        detectorSteps = 0;
        accelSteps = 0;
        base = 0f;
        smooth = 0f;
        above = false;
        lastAccelStep = 0L;
        startedAt = SystemClock.elapsedRealtime();
        goal = Math.max(1, Alarms.steps(this));

        createChannel();
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            // Could not run in the foreground; still try to ring.
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
        }

        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BangunRey:ring");
        wakeLock.acquire(MAX_RING_MS + 60_000L);

        startSound();
        startVibration();
        startSensors();
        showRingScreen();
        handler.postDelayed(this::finishAlarm, MAX_RING_MS);
        return START_STICKY;
    }

    // ---------- notification ----------

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Alarm", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Shows the alarm while it rings");
        ch.setSound(null, null);
        ch.enableVibration(false);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        ch.setBypassDnd(true);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent ring = new Intent(this, RingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent pi = PendingIntent.getActivity(this, 200, ring,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_walk)
                .setContentTitle("Bangun, Rey!")
                .setContentText(steps + " of " + goal + " steps. Keep walking to stop the alarm.")
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(goal, steps, false)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private void showRingScreen() {
        try {
            Intent i = new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
            // The full-screen notification opens it instead.
        }
    }

    // ---------- sound and vibration ----------

    private void startSound() {
        AudioManager am = getSystemService(AudioManager.class);
        try {
            int max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int min = (int) Math.ceil(max * 0.6);
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) < min) {
                am.setStreamVolume(AudioManager.STREAM_ALARM, min, 0);
            }
        } catch (Exception ignored) { }

        // Chosen sound first, then the built-in tone, then the phone's alarm, then a beep.
        Uri[] choices = {
                Alarms.soundUri(this),
                Alarms.builtinSound(this),
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        };
        for (Uri uri : choices) {
            if (uri != null && tryPlay(uri)) {
                startVolumeRamp();
                return;
            }
        }
        startTone();
    }

    private boolean tryPlay(Uri uri) {
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            player.setDataSource(this, uri);
            player.setLooping(true);
            player.prepare();
            player.start();
            return true;
        } catch (Exception e) {
            releasePlayer();
            return false;
        }
    }

    /** Starts at 35% loudness and rises to full over about 15 seconds. */
    private void startVolumeRamp() {
        final float[] vol = {0.35f};
        if (player != null) player.setVolume(vol[0], vol[0]);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                vol[0] = Math.min(1f, vol[0] + 0.022f);
                try { player.setVolume(vol[0], vol[0]); } catch (Exception ignored) { }
                if (vol[0] < 1f) handler.postDelayed(this, 500);
            }
        }, 500);
    }

    private void startTone() {
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
        } catch (Exception e) {
            return;
        }
        toneLoop = new Runnable() {
            @Override
            public void run() {
                if (tone == null) return;
                tone.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1000);
                handler.postDelayed(this, 1500);
            }
        };
        handler.post(toneLoop);
    }

    private void startVibration() {
        if (!Alarms.vibrate(this)) return;
        vibrator = getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator()) return;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{0, 700, 500}, 0);
        vibrator.vibrate(effect, new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build());
    }

    // ---------- step counting ----------

    private void startSensors() {
        sensorManager = getSystemService(SensorManager.class);
        boolean canUseDetector = Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;
        Sensor detector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        boolean any = false;
        if (detector != null && canUseDetector) {
            any = sensorManager.registerListener(this, detector, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (accel != null) {
            any = sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME) || any;
        }
        noSensors = !any;
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (!ringing) return;
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_STEP_DETECTOR) {
            detectorSteps++;
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float g = (float) Math.sqrt(x * x + y * y + z * z);
            if (base == 0f) {
                base = g;
                smooth = g;
            }
            base += (g - base) * 0.02f;     // slow average = gravity
            smooth += (g - smooth) * 0.3f;  // lightly smoothed signal
            float d = smooth - base;
            long now = SystemClock.elapsedRealtime();
            if (!above && d > 1.4f) {
                above = true;
                if (now - startedAt > WARMUP_MS && now - lastAccelStep > 300L) {
                    accelSteps++;
                    lastAccelStep = now;
                }
            } else if (above && d < 0.3f) {
                above = false;
            }
        }

        int s = Math.min(goal, Math.max(detectorSteps, accelSteps));
        if (s != steps) {
            steps = s;
            Listener l = listener;
            if (l != null) l.onProgress(steps, goal);
            if (steps >= goal) {
                finishAlarm();
            } else {
                updateNotification();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /** Called from the ring screen only when the phone has no motion sensor at all. */
    static void stopWithoutSensors(android.content.Context c) {
        if (noSensors) c.stopService(new Intent(c, AlarmService.class));
    }

    // ---------- stopping ----------

    private void finishAlarm() {
        if (!ringing) return;
        ringing = false;
        stopEverything();
        Listener l = listener;
        if (l != null) l.onDone();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void stopEverything() {
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        if (tone != null) {
            tone.release();
            tone = null;
        }
        if (vibrator != null) vibrator.cancel();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            player.release();
            player = null;
        }
    }

    @Override
    public void onDestroy() {
        if (ringing) {
            ringing = false;
            Listener l = listener;
            if (l != null) l.onDone();
        }
        stopEverything();
        super.onDestroy();
    }
}
