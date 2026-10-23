package com.tatbiqi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Store notifications without Firebase.
 *
 * Android's JobScheduler runs this about every 15 minutes while there is a
 * network (it may wait longer when the phone is asleep). It reads the store's
 * sent notifications from <configUrl>/notifications and shows the ones this
 * phone hasn't shown yet. Tapping one opens its link inside the app.
 *
 * On the first check after install only the newest notification is shown, so a
 * new customer isn't greeted by a pile of old ones.
 */
public class NotificationJob extends JobService {

    static final int JOB_ID = 7301;
    static final String EXTRA_URL = "tatbiqi.openUrl";
    private static final String CHANNEL_ID = "store";
    private static final String PREFS = "tatbiqi";
    private static final int MAX_SEEN = 50;
    private static final int MAX_PER_RUN = 3;

    /** Schedules the periodic check once; it survives reboots. */
    static void schedule(Context ctx, String configUrl) {
        if (configUrl == null || !configUrl.startsWith("https://")) {
            return;
        }
        try {
            JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            for (JobInfo pending : js.getAllPendingJobs()) {
                if (pending.getId() == JOB_ID) {
                    return;
                }
            }
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(ctx, NotificationJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    check();
                } catch (Exception ignored) {
                }
                jobFinished(params, false);
            }
        }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // the next periodic run picks up where this one stopped
    }

    private void check() throws Exception {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return; // nothing would show; keep them for when the customer allows notifications
        }
        JSONObject cfg = readAssetConfig();
        String configUrl = cfg.optString("configUrl", "");
        if (!configUrl.startsWith("https://")) {
            return;
        }
        JSONArray list = new JSONObject(fetch(configUrl + "/notifications")).optJSONArray("notifications");
        if (list == null) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean firstRun = !prefs.getBoolean("notifInit", false);
        List<String> seen = new ArrayList<String>();
        String saved = prefs.getString("notifSeen", "");
        if (saved.length() > 0) {
            seen.addAll(Arrays.asList(saved.split(",")));
        }

        // The list is newest first.
        List<JSONObject> fresh = new ArrayList<JSONObject>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject n = list.optJSONObject(i);
            if (n == null) {
                continue;
            }
            String id = n.optString("id", "");
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || seen.contains(id)) {
                continue;
            }
            fresh.add(n);
        }
        // Show the newest few, oldest of them first so they stack in order.
        int shown = Math.min(fresh.size(), firstRun ? 1 : MAX_PER_RUN);
        for (int i = shown - 1; i >= 0; i--) {
            show(fresh.get(i), cfg.optString("primaryColor", "#0F766E"));
        }
        for (JSONObject n : fresh) {
            seen.add(n.optString("id"));
        }

        while (seen.size() > MAX_SEEN) {
            seen.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (String id : seen) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        prefs.edit().putString("notifSeen", sb.toString()).putBoolean("notifInit", true).apply();
    }

    @SuppressWarnings("deprecation")
    private void show(JSONObject n, String color) {
        String title = n.optString("title", "");
        String body = n.optString("body", "");
        if (title.length() == 0 && body.length() == 0) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "العروض والإشعارات",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String link = n.optString("link", "");
        if (link.startsWith("https://")) {
            open.putExtra(EXTRA_URL, link);
        }
        int code = n.optString("id").hashCode();
        PendingIntent pi = PendingIntent.getActivity(this, code, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        b.setSmallIcon(smallIcon())
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            b.setColor(Color.parseColor(color));
        } catch (Exception ignored) {
        }
        nm.notify(code, b.build());
    }

    /** The bell drawable build_apk.py adds; the system chat icon if it can't be found. */
    private int smallIcon() {
        int id = getResources().getIdentifier("ic_notification", "drawable", "com.tatbiqi.app");
        if (id == 0) {
            id = getResources().getIdentifier("ic_notification", "drawable", getPackageName());
        }
        return id != 0 ? id : android.R.drawable.stat_notify_chat;
    }

    private JSONObject readAssetConfig() throws Exception {
        InputStream in = getAssets().open("tatbiqi.json");
        try {
            return new JSONObject(readAll(in, 64 * 1024));
        } finally {
            in.close();
        }
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) {
                throw new Exception("HTTP " + c.getResponseCode());
            }
            InputStream in = c.getInputStream();
            try {
                return readAll(in, 256 * 1024);
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < max) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
