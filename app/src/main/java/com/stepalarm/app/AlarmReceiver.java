package com.stepalarm.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Called by Android when the alarm time arrives. */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences p = Alarms.prefs(context);
        if (!p.getBoolean(Alarms.KEY_REPEAT, false)) {
            p.edit().putBoolean(Alarms.KEY_ENABLED, false).apply();
        }
        Alarms.schedule(context); // sets tomorrow's alarm if repeating
        Alarms.startRinging(context);
    }
}
