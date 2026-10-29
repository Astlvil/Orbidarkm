package com.orbit.tv;

import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> fileCb;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            web = new WebView(this);
            web.setBackgroundColor(0xFF0B132B);
            WebSettings s = web.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            web.setWebViewClient(new WebViewClient());
            web.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams fp) {
                    if (fileCb != null) fileCb.onReceiveValue(null);
                    fileCb = cb;
                    Intent[] opts = pickIntents();
                    for (int k = 0; k < opts.length; k++) {
                        Intent it = opts[k];
                        if (it.resolveActivity(getPackageManager()) == null) continue;
                        try {
                            startActivityForResult(k == 0 ? Intent.createChooser(it, "Choose photos") : it, 7);
                            return true;
                        } catch (Throwable t) { }
                    }
                    fileCb = null;
                    cb.onReceiveValue(null);
                    js("window.__noPicker&&window.__noPicker()");
                    return true;
                }
            });
            web.addJavascriptInterface(new Bridge(), "Android");
            setContentView(web);
            web.loadUrl("file:///android_asset/index.html");
            web.requestFocus();
        } catch (Throwable t) {
            web = null;
            TextView tv = new TextView(this);
            tv.setText("Orbit TV needs Android System WebView to run.\nInstall or enable it in the Play Store, then reopen this app.\n\n" + t);
            tv.setTextSize(26);
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackgroundColor(0xFF0B132B);
            tv.setPadding(90, 90, 90, 90);
            setContentView(tv);
        }
    }

    @Override
    protected void onActivityResult(int rc, int res, Intent d) {
        if (rc == 7 && fileCb != null) {
            Uri[] r = null;
            if (res == RESULT_OK && d != null) {
                if (d.getClipData() != null) {
                    int n = d.getClipData().getItemCount();
                    r = new Uri[n];
                    for (int i = 0; i < n; i++) r[i] = d.getClipData().getItemAt(i).getUri();
                } else if (d.getData() != null) {
                    r = new Uri[]{d.getData()};
                }
            }
            fileCb.onReceiveValue(r);
            fileCb = null;
        } else {
            super.onActivityResult(rc, res, d);
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (web != null) web.evaluateJavascript("window.__home&&window.__home()", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop the page working (photo rotation, timers) while another app is in front.
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
    }

    // Orbit Settings tiles -> the TV's own system screens. First one that exists on this TV is used.
    private static final String[][] SETTING_MAP = {
        {"network", "android.settings.WIFI_SETTINGS", "android.settings.WIRELESS_SETTINGS"},
        {"bluetooth", "android.settings.BLUETOOTH_SETTINGS"},
        {"cast", "android.settings.CAST_SETTINGS"},
        {"display", "android.settings.DISPLAY_SETTINGS"},
        {"sound", "android.settings.SOUND_SETTINGS"},
        {"dream", "android.settings.DREAM_SETTINGS"},
        {"apps", "android.settings.APPLICATION_SETTINGS", "android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS"},
        {"accounts", "android.settings.SYNC_SETTINGS"},
        {"privacy", "android.settings.PRIVACY_SETTINGS"},
        {"storage", "android.settings.INTERNAL_STORAGE_SETTINGS"},
        {"date", "android.settings.DATE_SETTINGS"},
        {"language", "android.settings.LOCALE_SETTINGS"},
        {"keyboard", "android.settings.INPUT_METHOD_SETTINGS"},
        {"accessibility", "android.settings.ACCESSIBILITY_SETTINGS"},
        {"voice", "android.settings.VOICE_INPUT_SETTINGS"},
        {"home", "android.settings.HOME_SETTINGS"},
        {"dev", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"},
        {"about", "android.settings.DEVICE_INFO_SETTINGS"},
        {"all", "android.settings.SETTINGS"}
    };

    private Intent settingIntent(String key) {
        for (String[] row : SETTING_MAP) {
            if (!row[0].equals(key)) continue;
            for (int i = 1; i < row.length; i++) {
                Intent it = new Intent(row[i]);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (it.resolveActivity(getPackageManager()) != null) return it;
            }
        }
        return null;
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (web != null) web.evaluateJavascript(code, null);
            }
        });
    }

    private Intent[] pickIntents() {
        Intent a = new Intent(Intent.ACTION_GET_CONTENT);
        a.addCategory(Intent.CATEGORY_OPENABLE);
        a.setType("image/*");
        a.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        Intent d = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        d.addCategory(Intent.CATEGORY_OPENABLE);
        d.setType("image/*");
        d.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        Intent c = new Intent(Intent.ACTION_PICK);
        c.setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        return new Intent[]{a, d, c};
    }

    @Override
    public void onBackPressed() {
        // A home screen should not close on Back; let the page close dialogs.
        if (web != null) web.evaluateJavascript("window.__back&&window.__back()", null);
    }

    private String icon(Drawable d) {
        int n = 96;
        Bitmap bm = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, n, n);
        d.draw(c);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, bo);
        return "data:image/png;base64," + Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    class Bridge {
        @JavascriptInterface
        public String getApps() {
            try {
                PackageManager pm = getPackageManager();
                Map<String, JSONObject> out = new LinkedHashMap<>();
                String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
                for (String c : cats) {
                    Intent i = new Intent(Intent.ACTION_MAIN);
                    i.addCategory(c);
                    for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                        String pkg = r.activityInfo.packageName;
                        if (pkg.equals(getPackageName()) || out.containsKey(pkg)) continue;
                        try {
                            JSONObject o = new JSONObject();
                            o.put("pkg", pkg);
                            o.put("name", r.loadLabel(pm).toString());
                            out.put(pkg, o);
                        } catch (Exception e) { }
                    }
                }
                return new JSONArray(out.values()).toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String getIcon(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                Drawable d = pm.getApplicationIcon(pkg);
                Drawable banner = null;
                try { banner = pm.getApplicationBanner(pkg); } catch (Exception e) { }
                return icon(d != null ? d : banner);
            } catch (Throwable t) {
                return "";
            }
        }

        @JavascriptInterface
        public void launch(final String pkg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        PackageManager pm = getPackageManager();
                        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                        if (i != null) {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        }
                    } catch (Throwable t) { }
                }
            });
        }

        @JavascriptInterface
        public boolean canPick() {
            try {
                PackageManager pm = getPackageManager();
                for (Intent i : pickIntents()) {
                    if (i.resolveActivity(pm) != null) return true;
                }
            } catch (Throwable t) { }
            return false;
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        if (web != null) web.evaluateJavascript("window.__toast&&window.__toast('No web browser found on this TV')", null);
                    }
                }
            });
        }

        @JavascriptInterface
        public boolean canOpen(String key) {
            try {
                return settingIntent(key) != null;
            } catch (Throwable t) {
                return false;
            }
        }

        @JavascriptInterface
        public boolean openSetting(String key) {
            try {
                Intent use = settingIntent(key);
                if (use == null) {
                    Intent g = new Intent("android.settings.SETTINGS");
                    g.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (g.resolveActivity(getPackageManager()) != null) use = g;
                }
                if (use == null) return false;
                final Intent fin = use;
                runOnUiThread(new Runnable() {
                    public void run() {
                        try {
                            startActivity(fin);
                        } catch (Throwable t) { }
                    }
                });
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @JavascriptInterface
        public String sysInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("model", String.valueOf(Build.MODEL));
                o.put("maker", String.valueOf(Build.MANUFACTURER));
                o.put("android", String.valueOf(Build.VERSION.RELEASE));
                o.put("sdk", Build.VERSION.SDK_INT);
                try {
                    o.put("orbit", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
                } catch (Throwable t) {
                    o.put("orbit", "6.0");
                }
                String net = "none";
                int level = -1;
                String ssid = "";
                try {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                    NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
                    if (ni != null && ni.isConnected()) {
                        int ty = ni.getType();
                        if (ty == ConnectivityManager.TYPE_WIFI) net = "wifi";
                        else if (ty == ConnectivityManager.TYPE_ETHERNET) net = "ethernet";
                        else net = "other";
                    }
                    if ("wifi".equals(net)) {
                        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                        WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                        if (wi != null) {
                            level = WifiManager.calculateSignalLevel(wi.getRssi(), 5);
                            String n = wi.getSSID();
                            if (n != null && !n.contains("unknown")) ssid = n.replace("\"", "");
                        }
                    }
                } catch (Throwable t) { }
                o.put("net", net);
                o.put("level", level);
                o.put("ssid", ssid);
                String ip = "";
                try {
                    for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e != null && e.hasMoreElements();) {
                        NetworkInterface ni2 = e.nextElement();
                        if (ni2.isLoopback() || !ni2.isUp()) continue;
                        for (Enumeration<InetAddress> a = ni2.getInetAddresses(); a.hasMoreElements();) {
                            InetAddress ia = a.nextElement();
                            if (!ia.isLoopbackAddress() && ia instanceof Inet4Address) ip = ia.getHostAddress();
                        }
                    }
                } catch (Throwable t) { }
                o.put("ip", ip);
                String bt = "none";
                try {
                    BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
                    if (ba != null) bt = ba.isEnabled() ? "on" : "off";
                } catch (Throwable t) { }
                o.put("bt", bt);
                try {
                    StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
                    o.put("total", sf.getTotalBytes());
                    o.put("free", sf.getAvailableBytes());
                } catch (Throwable t) { }
                try {
                    ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    o.put("ram", mi.totalMem);
                } catch (Throwable t) { }
            } catch (Throwable t) { }
            return o.toString();
        }

        @JavascriptInterface
        public String volume() {
            JSONObject o = new JSONObject();
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                o.put("v", am.getStreamVolume(AudioManager.STREAM_MUSIC));
                o.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                o.put("fixed", am.isVolumeFixed());
            } catch (Throwable t) {
                try {
                    o.put("max", 0);
                    o.put("fixed", true);
                } catch (Throwable t2) { }
            }
            return o.toString();
        }

        @JavascriptInterface
        public void setVolume(int v) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0);
            } catch (Throwable t) { }
        }

        @JavascriptInterface
        public String inputs() {
            JSONArray arr = new JSONArray();
            try {
                TvInputManager tm = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
                if (tm != null) {
                    for (TvInputInfo i : tm.getTvInputList()) {
                        try {
                            if (Build.VERSION.SDK_INT >= 24 && i.isHidden(MainActivity.this)) continue;
                            CharSequence label = null;
                            try {
                                label = i.loadCustomLabel(MainActivity.this);
                            } catch (Throwable t) { }
                            if (label == null || label.length() == 0) label = i.loadLabel(MainActivity.this);
                            JSONObject o = new JSONObject();
                            o.put("id", i.getId());
                            o.put("name", String.valueOf(label));
                            o.put("type", i.getType());
                            o.put("pass", i.isPassthroughInput());
                            arr.put(o);
                        } catch (Throwable t) { }
                    }
                }
            } catch (Throwable t) { }
            return arr.toString();
        }

        @JavascriptInterface
        public void openInput(final String id, final boolean pass) {
            runOnUiThread(new Runnable() {
                public void run() {
                    boolean ok = false;
                    try {
                        Intent it = null;
                        if (pass) {
                            it = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
                        } else {
                            Intent v = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
                            if (v.resolveActivity(getPackageManager()) != null) it = v;
                        }
                        if (it != null) {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(it);
                            ok = true;
                        }
                    } catch (Throwable t) { }
                    if (!ok && web != null) {
                        web.evaluateJavascript("window.__toast&&window.__toast('Could not open that input. Try the Input button on your TV remote.')", null);
                    }
                }
            });
        }

        @JavascriptInterface
        public void assistant() {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent[] tries = {
                        new Intent(Intent.ACTION_VOICE_COMMAND),
                        new Intent(Intent.ACTION_ASSIST),
                        getPackageManager().getLeanbackLaunchIntentForPackage("com.google.android.katniss")
                    };
                    for (Intent i : tries) {
                        if (i == null) continue;
                        try {
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                            return;
                        } catch (Throwable t) { }
                    }
                }
            });
        }

        @JavascriptInterface
        public void openSettings() {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable t) { }
                }
            });
        }
    }
}
