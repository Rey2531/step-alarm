package com.stepalarm.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.format.DateFormat;

import java.util.Calendar;

/** Saved settings and scheduling of the alarm. */
final class Alarms {
    static final String KEY_HOUR = "hour";
    static final String KEY_MINUTE = "minute";
    static final String KEY_STEPS = "steps";
    static final String KEY_ENABLED = "enabled";
    static final String KEY_REPEAT = "repeat";
    static final String KEY_VIBRATE = "vibrate";
    static final String KEY_SOUND_TYPE = "sound_type";
    static final String KEY_SOUND_URI = "sound_uri";
    static final String KEY_SOUND_NAME = "sound_name";

    static final String SOUND_BUILTIN = "builtin";
    static final String SOUND_SYSTEM = "system";
    static final String SOUND_FILE = "file";

    private Alarms() {}

    static boolean vibrate(Context c) { return prefs(c).getBoolean(KEY_VIBRATE, true); }

    static Uri builtinSound(Context c) {
        return Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.bangun_rey);
    }

    /** The sound the alarm should play. */
    static Uri soundUri(Context c) {
        SharedPreferences p = prefs(c);
        String type = p.getString(KEY_SOUND_TYPE, SOUND_BUILTIN);
        String uri = p.getString(KEY_SOUND_URI, null);
        if (SOUND_BUILTIN.equals(type) || uri == null) return builtinSound(c);
        return Uri.parse(uri);
    }

    static String soundName(Context c) {
        SharedPreferences p = prefs(c);
        String type = p.getString(KEY_SOUND_TYPE, SOUND_BUILTIN);
        if (SOUND_BUILTIN.equals(type)) return "Bangun Rey tone (built-in)";
        String name = p.getString(KEY_SOUND_NAME, null);
        if (name == null || name.isEmpty()) name = SOUND_FILE.equals(type) ? "Song from your phone" : "Phone alarm sound";
        return name;
    }

    static void saveSound(Context c, String type, Uri uri, String name) {
        prefs(c).edit()
                .putString(KEY_SOUND_TYPE, type)
                .putString(KEY_SOUND_URI, uri == null ? null : uri.toString())
                .putString(KEY_SOUND_NAME, name)
                .apply();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("alarm", Context.MODE_PRIVATE);
    }

    static int hour(Context c) { return prefs(c).getInt(KEY_HOUR, 7); }
    static int minute(Context c) { return prefs(c).getInt(KEY_MINUTE, 0); }
    static int steps(Context c) { return prefs(c).getInt(KEY_STEPS, 10); }

    /** Next time (in ms) the alarm should ring, always in the future. */
    static long nextTime(Context c) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.set(Calendar.HOUR_OF_DAY, hour(c));
        t.set(Calendar.MINUTE, minute(c));
        t.set(Calendar.SECOND, 0);
        t.set(Calendar.MILLISECOND, 0);
        // Add a small margin so an alarm that just fired is moved to tomorrow.
        if (t.getTimeInMillis() <= now.getTimeInMillis() + 30_000L) {
            t.add(Calendar.DAY_OF_YEAR, 1);
        }
        return t.getTimeInMillis();
    }

    private static PendingIntent firePending(Context c) {
        Intent i = new Intent(c, AlarmReceiver.class);
        i.setAction("com.stepalarm.app.FIRE");
        return PendingIntent.getBroadcast(c, 100, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets (or cancels) the system alarm to match the saved settings. */
    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent fire = firePending(c);
        am.cancel(fire);
        if (!prefs(c).getBoolean(KEY_ENABLED, false)) return;

        long t = nextTime(c);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, fire);
                return;
            }
            Intent show = new Intent(c, MainActivity.class);
            PendingIntent showPi = PendingIntent.getActivity(c, 101, show,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, showPi), fire);
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, fire);
        }
    }

    static void startRinging(Context c) {
        c.startForegroundService(new Intent(c, AlarmService.class));
    }

    static String formatTime(Context c, int h, int m) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, h);
        cal.set(Calendar.MINUTE, m);
        return DateFormat.getTimeFormat(c).format(cal.getTime());
    }
}
