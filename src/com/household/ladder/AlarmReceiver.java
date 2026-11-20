package com.household.ladder;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Wakes, reads the summary the page last wrote, posts a notification, and sets
 * the next alarm. It never decides anything — the counts and the role come
 * straight from the page's own computation.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String CHANNEL = "household";

    @Override
    public void onReceive(Context c, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        SharedPreferences p = Alarms.prefs(c);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
            || "android.intent.action.MY_PACKAGE_REPLACED".equals(action)) {
            Alarms.rescheduleAll(c);
            return;
        }

        String role = p.getString("role", "");
        if (Alarms.ACTION_DAILY.equals(action) && "M".equals(role)) {
            int open = p.getInt("openToday", 0);
            if (open > 0) {
                post(c, 1, "Still open today",
                     open + (open == 1 ? " task is" : " tasks are") + " not ticked off yet. "
                     + "Anything left at midnight is a miss.");
            }
        } else if (Alarms.ACTION_WEEK.equals(action) && "A".equals(role)) {
            int un = p.getInt("ungracedLastWeek", 0);
            post(c, 2, "Week audit ready",
                 un == 0 ? "Last week came in clean. Review it by 9am."
                         : un + (un == 1 ? " miss" : " misses") + " with no grace given. "
                           + "Review by 9am or the week closes as it stands.");
        } else if (Alarms.ACTION_AUDIT.equals(action) && "A".equals(role)) {
            if (!p.getBoolean("lastWeekReviewed", false)) {
                post(c, 3, "Week closed without your review",
                     "9am passed. The member's progression moved on what was recorded.");
            }
        }
        Alarms.rescheduleAll(c);
    }

    private void post(Context c, int id, String title, String body) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            Intent open = new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent tap = PendingIntent.getActivity(c, id, open, flags);

            Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                    ? new Notification.Builder(c, CHANNEL) : new Notification.Builder(c);
            b.setSmallIcon(android.R.drawable.ic_dialog_info)
             .setContentTitle(title).setContentText(body)
             .setStyle(new Notification.BigTextStyle().bigText(body))
             .setContentIntent(tap).setAutoCancel(true);
            nm.notify(id, b.build());
        } catch (Throwable ignored) {}
    }
}
