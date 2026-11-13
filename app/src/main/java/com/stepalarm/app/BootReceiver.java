package com.stepalarm.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Puts the alarm back after the phone restarts or the clock changes. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Alarms.schedule(context);
    }
}
