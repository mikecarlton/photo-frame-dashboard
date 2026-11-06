package com.wesbos.homehq;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import java.util.Calendar;

/**
 * Optional nightly screen-off, set with the "sleep" extra:
 *   --es sleep 20:30         sleep like a power-button press; wake with the power button
 *   --es sleep 20:30-07:30   screen off, back on at 07:30 (stays awake overnight)
 * An empty value turns it off. Needs root (su): the screen is switched with input key events.
 * With an on time, a kernel wake lock is held while the screen is off, because frames without an
 * RTC can't wake from suspend for the morning alarm. Runs again at boot and when the clock or
 * timezone changes.
 */
public class SleepSchedule extends BroadcastReceiver {
  static final String PREF = "sleep";
  private static final String ACTION = "com.wesbos.homehq.SLEEP_SCHEDULE";
  private static final String LOCK = "homehq_sleep";
  private static final String OFF = "echo " + LOCK + " > /sys/power/wake_lock; input keyevent 223";
  private static final String ON = "input keyevent 224; echo " + LOCK + " > /sys/power/wake_unlock";

  @Override
  public void onReceive(Context context, Intent intent) {
    final PendingResult result = goAsync();
    final Context app = context.getApplicationContext();
    new Thread(() -> {
      try {
        update(app, true);
      } finally {
        result.finish();
      }
    }, "sleep-schedule").start();
  }

  /** Saves a "sleep" extra if present, then schedules the next change without touching the screen. */
  static void apply(Context c, Intent intent) {
    if (intent != null && intent.hasExtra(PREF)) {
      String v = intent.getStringExtra(PREF);
      SharedPreferences p = c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
      if (v == null || v.isEmpty()) {
        p.edit().remove(PREF).apply();
        Log.i(MainActivity.TAG, "Sleep schedule off");
        new Thread(() -> root(ON), "sleep-schedule").start();
      } else if (parse(v) == null) {
        Log.w(MainActivity.TAG, "Sleep schedule ignored, expected HH:MM-HH:MM: " + v);
      } else {
        p.edit().putString(PREF, v).apply();
        Log.i(MainActivity.TAG, "Sleep schedule " + v);
      }
    }
    update(c, false);
  }

  /** Sets the alarm for the next change. With enforce, also switches the screen to match the time. */
  static void update(Context c, boolean enforce) {
    AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
    PendingIntent pi = PendingIntent.getBroadcast(c, 0,
        new Intent(c, SleepSchedule.class).setAction(ACTION), PendingIntent.FLAG_UPDATE_CURRENT);
    int[] w = parse(c.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE).getString(PREF, null));
    Calendar now = Calendar.getInstance();
    // Without an RTC the clock starts in the past after a power cut; wait for TIME_SET.
    if (w == null || now.get(Calendar.YEAR) < 2025) {
      am.cancel(pi);
      return;
    }
    int n = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
    if (w[1] < 0) {
      // Off time only. A late alarm (frame was asleep past it) must not turn off a screen just woken.
      if (enforce && (n - w[0] + 1440) % 1440 < 10) {
        Log.i(MainActivity.TAG, "Sleep schedule: sleep");
        root("input keyevent 223");
      }
      at(am, pi, now, w[0]);
      return;
    }
    boolean off = w[0] < w[1] ? n >= w[0] && n < w[1] : n >= w[0] || n < w[1];
    if (enforce) {
      Log.i(MainActivity.TAG, "Sleep schedule: screen " + (off ? "off" : "on"));
      if (!off) syncTime(c);
      root(off ? OFF : ON);
    }
    at(am, pi, now, off ? w[1] : w[0]);
  }

  /** Sets the alarm for the next time the clock reads minute-of-day next. */
  private static void at(AlarmManager am, PendingIntent pi, Calendar now, int next) {
    Calendar t = (Calendar) now.clone();
    t.set(Calendar.HOUR_OF_DAY, next / 60);
    t.set(Calendar.MINUTE, next % 60);
    t.set(Calendar.SECOND, 0);
    t.set(Calendar.MILLISECOND, 0);
    if (!t.after(now)) t.add(Calendar.DAY_OF_MONTH, 1);
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.getTimeInMillis(), pi);
  }

  /** "HH:MM-HH:MM" (off-on) or "HH:MM" (off only, on = -1) to minutes of the day, or null. */
  static int[] parse(String v) {
    if (v != null && v.matches("\\d{1,2}:\\d{2}")) v = v + "-99:99";
    if (v == null || !v.matches("\\d{1,2}:\\d{2}-\\d{1,2}:\\d{2}")) return null;
    String[] p = v.split("[:-]");
    int h1 = Integer.parseInt(p[0]), m1 = Integer.parseInt(p[1]);
    int h2 = Integer.parseInt(p[2]), m2 = Integer.parseInt(p[3]);
    if (h1 > 23 || m1 > 59) return null;
    if (h2 == 99) return new int[] {h1 * 60 + m1, -1};
    if (h2 > 23 || m2 > 59) return null;
    int a = h1 * 60 + m1, b = h2 * 60 + m2;
    return a == b ? null : new int[] {a, b};
  }

  /**
   * Asks Android for a fresh network time by turning automatic time off and on (only when it is
   * on). Waits up to 60 s for a network first, since Wi-Fi reconnects after suspend. Blocks; call
   * off the main thread. Needs root.
   */
  static void syncTime(Context c) {
    ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
    for (int i = 0; i < 60; i++) {
      NetworkInfo ni = cm.getActiveNetworkInfo();
      if (ni != null && ni.isConnected()) break;
      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        return;
      }
    }
    Log.i(MainActivity.TAG, "Syncing network time");
    root("[ \"$(settings get global auto_time)\" = 1 ] && settings put global auto_time 0 && settings put global auto_time 1");
  }

  private static void root(String cmd) {
    try {
      Runtime.getRuntime().exec(new String[] {"su", "-c", cmd}).waitFor();
    } catch (Exception e) {
      Log.w(MainActivity.TAG, "Sleep schedule: su failed: " + e);
    }
  }
}
