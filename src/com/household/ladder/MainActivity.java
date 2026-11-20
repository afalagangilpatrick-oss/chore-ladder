package com.household.ladder;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Household — offline WebView shell.
 *
 * Assets are served over https://appassets.androidplatform.net/ rather than
 * file://, because file:// is not a secure context and localStorage there can
 * have an opaque origin. Nothing touches the network; every request is answered
 * from local assets and anything else is refused.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final String CHANNEL = "household";

    private WebView web;
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        // Screenshots allowed by choice. To block them again — which also
        // blanks the recents thumbnail — restore:
        //   getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
        //                        WindowManager.LayoutParams.FLAG_SECURE);

        root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        /* targetSdk 35 forces edge-to-edge, which is what pushed the page under
         * the status bar. Pad the view by the real system-bar insets instead of
         * trusting CSS env() to be honoured. Re-applied on fold/unfold, because
         * the Fold's two screens have different cutouts. */
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = bars.left; t = bars.top; r = bars.right; b = bars.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft();
                t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight();
                b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        root.setFitsSystemWindows(false);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (u == null || !HOST.equals(u.getHost())) return empty();
                String path = u.getPath();
                if (path == null || path.equals("/") || path.equals("/index.html")) return serveIndex();
                return empty();
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                return u == null || !HOST.equals(u.getHost());   // offline app: never leave
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage m) {
                android.util.Log.i("HHConsole", m.messageLevel() + " " + m.message() + " @" + m.lineNumber());
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Native");

        setContentView(root);
        createChannel();
        askForNotifications();
        web.loadUrl(START_URL);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] granted) {
        super.onRequestPermissionsResult(code, perms, granted);
        if (web != null) web.reload();     // so the Sync picker fills in straight away
    }

    @Override public void onBackPressed() { finish(); }   // PIN-free, but the app reloads clean

    private WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }

    private WebResourceResponse serveIndex() {
        try {
            InputStream in = getAssets().open("index.html");
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            String html = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            int at = html.lastIndexOf("</body>");
            html = (at >= 0) ? html.substring(0, at) + SHIM + html.substring(at) : html + SHIM;
            return new WebResourceResponse("text/html", "utf-8",
                    new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "utf-8",
                    new ByteArrayInputStream(("Could not load: " + e.getMessage()).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** Export uses createObjectURL + <a download>, which a WebView cannot save.
     *  Catch the Blob and hand its text to the bridge instead. */
    private static final String SHIM =
        "<script>(function(){if(!window.Native)return;" +
        "var map=Object.create(null);" +
        "var oc=URL.createObjectURL.bind(URL);" +
        "URL.createObjectURL=function(o){var u=oc(o);try{map[u]=o}catch(e){}return u};" +
        "var orv=URL.revokeObjectURL.bind(URL);" +
        "URL.revokeObjectURL=function(u){try{delete map[u]}catch(e){}return orv(u)};" +
        "var ck=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){var a=this;" +
        " if(a.download&&a.href&&map[a.href]){var fr=new FileReader();" +
        "  fr.onload=function(){try{Native.saveBackup(String(fr.result),a.download)}" +
        "   catch(e){Native.toast('Export failed: '+e.message)}};" +
        "  fr.readAsText(map[a.href]);return;}" +
        " return ck.apply(a,arguments)};" +
        "})();</script>";

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel c = new NotificationChannel(CHANNEL, "Household",
                NotificationManager.IMPORTANCE_DEFAULT);
        c.setDescription("Tasks, flags, audits and rewards");
        c.setShowBadge(true);
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33
            && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
               != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private BluetoothAdapter adapter() {
        try {
            if (Build.VERSION.SDK_INT >= 18) {
                BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
                if (bm != null) return bm.getAdapter();
            }
        } catch (Throwable ignored) {}
        return BluetoothAdapter.getDefaultAdapter();
    }
    private boolean hasBtPermission() {
        if (Build.VERSION.SDK_INT < 31) return true;
        return checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
               == PackageManager.PERMISSION_GRANTED;
    }
    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
    /** Hand the outcome back to the page. The peer's log is base64'd rather than
     *  escaped into a JS string literal — it is JSON full of quotes and newlines,
     *  and hand-escaping that is a bug waiting to happen. */
    private void back(final boolean ok, final String msg, final String peer) {
        final String b64 = (peer == null) ? null
            : android.util.Base64.encodeToString(peer.getBytes(StandardCharsets.UTF_8),
                                                 android.util.Base64.NO_WRAP);
        runOnUiThread(() -> {
            String js = "window.__syncDone && window.__syncDone("
                      + ok + ",\"" + esc(msg) + "\","
                      + (b64 == null ? "null" : "\"" + b64 + "\"") + ");";
            try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
        });
    }

    /** A line of status for the page while a sync is still trying. */
    private void progress(final String msg) {
        runOnUiThread(() -> {
            try { web.evaluateJavascript(
                "window.__syncProgress && window.__syncProgress(\"" + esc(msg) + "\");", null); }
            catch (Throwable ignored) {}
        });
    }

    public class Bridge {
        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }

        /** Raised locally on this phone. Nothing is sent anywhere. */
        @JavascriptInterface
        public void notify(final String kind, final String title, final String body) {
            runOnUiThread(() -> {
                try {
                    Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                            ? new Notification.Builder(MainActivity.this, CHANNEL)
                            : new Notification.Builder(MainActivity.this);
                    b.setSmallIcon(android.R.drawable.ic_dialog_info)
                     .setContentTitle(title == null ? "Household" : title)
                     .setContentText(body == null ? "" : body)
                     .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                     .setAutoCancel(true);
                    getSystemService(NotificationManager.class)
                        .notify(Math.abs((kind + title + body).hashCode()), b.build());
                } catch (Exception e) {
                    android.util.Log.w("HHNotify", "failed: " + e.getMessage());
                }
            });
        }

        /**
         * The page mirrors a small summary here every time it saves, so the alarm
         * receiver has something to read without reimplementing any of the rules.
         */
        @JavascriptInterface
        public void setAlarmState(String json) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                SharedPreferences.Editor e = Alarms.prefs(MainActivity.this).edit();
                e.putString("role", o.optString("role", ""));
                e.putString("dailyTime", o.optString("dailyTime", "20:00"));
                e.putBoolean("notifDailyIncomplete", o.optBoolean("dailyIncomplete", true));
                e.putBoolean("notifAuditReady", o.optBoolean("auditReady", true));
                e.putInt("openToday", o.optInt("openToday", 0));
                e.putInt("ungracedLastWeek", o.optInt("ungracedLastWeek", 0));
                e.putBoolean("lastWeekReviewed", o.optBoolean("lastWeekReviewed", false));
                e.apply();
                Alarms.rescheduleAll(MainActivity.this);
            } catch (Throwable t) {
                android.util.Log.w("HHAlarm", "bad state: " + t.getMessage());
            }
        }

        /** For the Settings screen: what is actually scheduled right now. */
        @JavascriptInterface
        public String alarmInfo() {
            SharedPreferences p = Alarms.prefs(MainActivity.this);
            return "{\"role\":\"" + esc(p.getString("role","")) + "\""
                 + ",\"dailyTime\":\"" + esc(p.getString("dailyTime","20:00")) + "\""
                 + ",\"openToday\":" + p.getInt("openToday",0)
                 + ",\"ungracedLastWeek\":" + p.getInt("ungracedLastWeek",0) + "}";
        }

        /** The Settings test button, so the organizer can see one arrive. */
        @JavascriptInterface
        public void testNotification() {
            notify("test", "Chore Ladder", "Notifications are working on this phone.");
        }

        /** Does this phone still owe us the Nearby devices permission? */
        @JavascriptInterface
        public boolean needsBtPermission() { return !hasBtPermission(); }

        /** Ask for it outright from the Settings screen. */
        @JavascriptInterface
        public void askBtPermission() {
            if (Build.VERSION.SDK_INT >= 31 && !hasBtPermission()) {
                runOnUiThread(() -> requestPermissions(
                    new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 2));
            }
        }

        /** The phones this one is already paired with, for the Settings picker. */
        @JavascriptInterface
        public String listPaired() {
            try {
                if (!hasBtPermission()) return "{\"needPermission\":true}";
                BluetoothAdapter a = adapter();
                if (a == null) return "{\"error\":\"no Bluetooth on this phone\"}";
                if (!a.isEnabled()) return "{\"error\":\"Bluetooth is off\"}";
                StringBuilder sb = new StringBuilder("{\"devices\":[");
                boolean first = true;
                for (BluetoothDevice d : a.getBondedDevices()) {
                    String n = d.getName();
                    if (!first) sb.append(",");
                    sb.append("{\"mac\":\"").append(esc(d.getAddress())).append("\",")
                      .append("\"name\":\"").append(esc(n == null ? d.getAddress() : n)).append("\"}");
                    first = false;
                }
                return sb.append("]}").toString();
            } catch (Throwable t) {
                return "{\"error\":\"" + esc(String.valueOf(t.getMessage())) + "\"}";
            }
        }

        /**
         * Swap event logs with the other phone. Bluetooth first — it needs no
         * network at all and works in the same room. If that finds nobody, fall
         * back to the tailnet address, which covers being apart. Either way both
         * phones come away holding everything.
         */
        @JavascriptInterface
        public void syncNow(final String peerMac, final String peerIp, final String payload, final String role) {
            if (!hasBtPermission() && (peerIp == null || peerIp.trim().isEmpty())) {
                if (Build.VERSION.SDK_INT >= 31) {
                    runOnUiThread(() -> requestPermissions(
                        new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, 2));
                }
                back(false, "Allow the Nearby devices permission, then tap Sync again.", null);
                return;
            }
            final boolean canNet = peerIp != null && !peerIp.trim().isEmpty();
            if (!hasBtPermission()) { overNet(peerIp, payload); return; }

            progress("Looking over Bluetooth — have them tap Sync too.");
            BtSync.exchange(adapter(), peerMac, payload, role, new BtSync.Result() {
                @Override public void ok(String peer, String name) {
                    back(true, "Bluetooth · " + name, peer);
                }
                @Override public void fail(String why) {
                    if (canNet) { progress("No Bluetooth answer — trying the tailnet."); overNet(peerIp, payload); }
                    else back(false, why, null);
                }
            });
        }

        private void overNet(final String peerIp, final String payload) {
            NetSync.exchange(peerIp, payload, new NetSync.Result() {
                @Override public void ok(String peer, String name) { back(true, "Tailnet · " + name, peer); }
                @Override public void fail(String why) { back(false, why, null); }
            });
        }

        /** Kept so an older page still works. */
        @JavascriptInterface
        public void syncBluetooth(String peerMac, String payload) { syncNow(peerMac, "", payload, "A"); }

        @JavascriptInterface
        public void saveBackup(final String json, final String nameIn) {
            final String name = (nameIn == null || nameIn.isEmpty()) ? "household-backup.json" : nameIn;
            try {
                String where;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) throw new Exception("no Downloads entry");
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                    os.close();
                    where = "Downloads/" + name;
                } else {
                    File f = new File(getExternalFilesDir(null), name);
                    FileOutputStream fos = new FileOutputStream(f);
                    fos.write(json.getBytes(StandardCharsets.UTF_8));
                    fos.close();
                    where = f.getAbsolutePath();
                }
                toast("Saved to " + where + " — unencrypted, keep it somewhere safe.");
            } catch (Exception e) {
                toast("Backup failed: " + e.getMessage());
            }
        }
    }
}
