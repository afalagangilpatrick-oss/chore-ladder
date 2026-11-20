package com.household.ladder;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * The page owns all the logic, but a BroadcastReceiver cannot read the WebView's
 * localStorage. So the page mirrors a small summary into SharedPreferences every
 * time it saves, and the alarms only read that. No rule is reimplemented in Java.
 *
 * Alarms are inexact (setAndAllowWhileIdle) so no exact-alarm permission prompt is
 * needed; they land within roughly a quarter hour. That is fine because nothing
 * here *enforces* anything — the audit deadline is computed from the record, not
 * from whether a notification fired.
 */
public class Alarms {

    public static final String PREFS = "choreladder";
    public static final String KEY_STATE = "alarmState";

    public static final String ACTION_DAILY = "com.household.ladder.DAILY";
    public static final String ACTION_WEEK  = "com.household.ladder.WEEK";
    public static final String ACTION_AUDIT = "com.household.ladder.AUDIT";

    private static final int RQ_DAILY = 101, RQ_WEEK = 102, RQ_AUDIT = 103;

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Called whenever the page hands us a fresh summary, and on boot. */
    public static void rescheduleAll(Context c) {
        String role = prefs(c).getString("role", "");
        String dailyTime = prefs(c).getString("dailyTime", "20:00");
        boolean wantDaily = prefs(c).getBoolean("notifDailyIncomplete", true);
        boolean wantAudit = prefs(c).getBoolean("notifAuditReady", true);

        cancel(c, ACTION_DAILY, RQ_DAILY);
        cancel(c, ACTION_WEEK, RQ_WEEK);
        cancel(c, ACTION_AUDIT, RQ_AUDIT);

        if ("M".equals(role) && wantDaily) {
            int hh = 20, mm = 0;
            try {
                String[] p = dailyTime.split(":");
                hh = Integer.parseInt(p[0]); mm = Integer.parseInt(p[1]);
            } catch (Throwable ignored) {}
            set(c, ACTION_DAILY, RQ_DAILY, nextDaily(hh, mm));
        }
        if ("A".equals(role)) {
            // Monday just after midnight: the week is closed and ready to audit.
            set(c, ACTION_WEEK, RQ_WEEK, nextWeekly(Calendar.MONDAY, 0, 5));
            // Monday 9am: the bell. After this the week stands as recorded.
            if (wantAudit) set(c, ACTION_AUDIT, RQ_AUDIT, nextWeekly(Calendar.MONDAY, 9, 0));
        }
    }

    private static long nextDaily(int hh, int mm) {
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, hh);
        k.set(Calendar.MINUTE, mm);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        if (k.getTimeInMillis() <= System.currentTimeMillis()) k.add(Calendar.DAY_OF_YEAR, 1);
        return k.getTimeInMillis();
    }

    private static long nextWeekly(int dayOfWeek, int hh, int mm) {
        Calendar k = Calendar.getInstance();
        k.set(Calendar.HOUR_OF_DAY, hh);
        k.set(Calendar.MINUTE, mm);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        while (k.get(Calendar.DAY_OF_WEEK) != dayOfWeek
               || k.getTimeInMillis() <= System.currentTimeMillis()) {
            k.add(Calendar.DAY_OF_YEAR, 1);
        }
        return k.getTimeInMillis();
    }

    private static PendingIntent pi(Context c, String action, int rq) {
        Intent i = new Intent(c, AlarmReceiver.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(c, rq, i, flags);
    }

    private static void set(Context c, String action, int rq, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent p = pi(c, action, rq);
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
            else am.set(AlarmManager.RTC_WAKEUP, at, p);
        } catch (Throwable ignored) {}
    }

    private static void cancel(Context c, String action, int rq) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) try { am.cancel(pi(c, action, rq)); } catch (Throwable ignored) {}
    }
}
