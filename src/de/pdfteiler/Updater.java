package de.pdfteiler;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Selbst-Aktualisierung: liest update.json aus dem öffentlichen GitHub-Repository,
 * und meldet eine neuere Version. Nur nach Tipp auf den Update-Knopf: Download per Android-Download-Manager,
 * danach öffnet sich der normale Android-Installer mit Rückfrage. Nie still im Hintergrund.
 */
final class Updater {
    static final String REPO = "entenmannmarkus-dev/pdf-teiler";
    static String UPDATE_URL = "https://raw.githubusercontent.com/" + REPO + "/main/update.json";
    /** Frische Quelle ohne CDN-Verzögerung (GitHub-API, 60 Abfragen/Stunde ohne Anmeldung). */
    static String API_URL = "https://api.github.com/repos/" + REPO + "/contents/update.json?ref=main";

    interface Callback { void available(String versionName, String notes, String apkUrl); }

    /** Liest update.json – zuerst über die API (sofort aktuell), sonst über raw.githubusercontent.com. */
    static JSONObject fetchInfo() throws Exception {
        try {
            return new JSONObject(new String(get(API_URL, "application/vnd.github.raw+json"), "UTF-8"));
        } catch (Exception apiFailed) {
            return new JSONObject(new String(get(UPDATE_URL + "?t=" + System.currentTimeMillis(), null), "UTF-8"));
        }
    }

    /** Stille Prüfung im Hintergrund (beim Start der App): meldet nur, wenn es eine neuere Version gibt. */
    void checkSilently(final Callback cb) {
        new Thread(new Runnable() { public void run() {
            try {
                JSONObject o = fetchInfo();
                final int code = o.getInt("versionCode");
                final String name = o.optString("versionName", String.valueOf(code));
                final String notes = o.optString("notes", "");
                final String apk = o.getString("apkUrl");
                if (code > currentVersionCode(act))
                    main.post(new Runnable() { public void run() { cb.available(name, notes, apk); } });
            } catch (Exception ignored) { /* offline – beim nächsten Start wieder */ }
        }}, "update-check").start();
    }

    private final Activity act;
    private final Handler main = new Handler(Looper.getMainLooper());

    Updater(Activity act) { this.act = act; }

    static int currentVersionCode(Activity a) {
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), 0);
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) { return 0; }
    }

    static String currentVersionName(Activity a) {
        try {
            return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) { return "?"; }
    }

    private void dialog(final String title, final String msg) {
        main.post(new Runnable() { public void run() {
            new AlertDialog.Builder(act).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
        }});
    }

    /** Vom „Nach Update suchen“-Knopf aufgerufen. */
    void check() {
        final ProgressDialog pd = ProgressDialog.show(act, "Update", "Suche nach neuer Version …", true, true);
        new Thread(new Runnable() { public void run() {
            try {
                final JSONObject o = fetchInfo();
                final int code = o.getInt("versionCode");
                final String name = o.optString("versionName", String.valueOf(code));
                final String apk = o.getString("apkUrl");
                final String notes = o.optString("notes", "");
                main.post(new Runnable() { public void run() {
                    pd.dismiss();
                    int mine = currentVersionCode(act);
                    if (code <= mine) {
                        dialog("Kein Update", "Du hast bereits die neueste Version (" + currentVersionName(act) + ").");
                        return;
                    }
                    new AlertDialog.Builder(act)
                        .setTitle("Neue Version " + name)
                        .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "Jetzt herunterladen und installieren? Android fragt vor dem Aktualisieren noch einmal nach.")
                        .setPositiveButton("Aktualisieren", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { download(apk, false); }
                        })
                        .setNegativeButton("Später", null)
                        .show();
                }});
            } catch (final Exception e) {
                main.post(new Runnable() { public void run() {
                    pd.dismiss();
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("404")) dialog("Update nicht möglich", "Die Update-Quelle auf GitHub ("
                            + REPO + ") ist noch nicht eingerichtet.");
                    else dialog("Update nicht möglich", "Die Update-Information konnte nicht geladen werden.\n"
                            + "Bitte Internetverbindung prüfen.\n\n(" + m + ")");
                }});
            }
        }}).start();
    }

    /**
     * Lädt die neue APK mit dem Android-Download-Manager in den Ordner „Download“. Die App installiert
     * nichts selbst und braucht keine Installations-Berechtigung: Ein Tipp auf die Download-Meldung
     * öffnet den normalen Android-Installer, der vor dem Aktualisieren nachfragt.
     * Geht das nicht (alte Android-Version), wird die Adresse im Browser geöffnet.
     */
    void download(final String url, final boolean quiet) {
        if (!canInstall()) { askInstallPermission(); return; }
        try {
            final android.app.DownloadManager dm =
                    (android.app.DownloadManager) act.getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            String name = url.substring(url.lastIndexOf('/') + 1);
            if (!name.endsWith(".apk")) name = "Splitt-Tool-Update.apk";
            android.app.DownloadManager.Request r = new android.app.DownloadManager.Request(Uri.parse(url));
            r.setTitle("Splitt-Tool Update");
            r.setDescription("Fertig? Antippen und „Aktualisieren“ wählen.");
            r.setMimeType("application/vnd.android.package-archive");
            r.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            r.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, name);
            final long id = dm.enqueue(r);
            lastDownloadId = id;
            watchDownload(id);
            dialog("Update wird geladen", "Die neue Version wird jetzt geladen. Danach öffnet sich die Android-"
                    + "Installation – dort „Aktualisieren“ wählen.");
        } catch (Exception e) {
            try {
                act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e2) {
                dialog("Update", "Bitte diese Adresse im Browser öffnen:\n" + url);
            }
        }
    }

    static long lastDownloadId = -1;

    /** Darf die App den Android-Installer öffnen? (Einstellung „Unbekannte Apps installieren“) */
    boolean canInstall() {
        if (android.os.Build.VERSION.SDK_INT < 26) return true;
        try {
            return (Boolean) PackageManager.class.getMethod("canRequestPackageInstalls").invoke(act.getPackageManager());
        } catch (Exception e) { return true; }
    }

    /**
     * Öffnet den normalen Android-Installer für die geladene APK. Android zeigt „Aktualisieren?“ –
     * installiert wird erst nach deinem Tipp. Die App installiert nie still im Hintergrund.
     */
    void installDownloaded(long id) {
        android.app.DownloadManager dm =
                (android.app.DownloadManager) act.getSystemService(android.content.Context.DOWNLOAD_SERVICE);
        Uri apk = dm.getUriForDownloadedFile(id);
        if (apk == null) { dialog("Update fehlgeschlagen", "Die neue Version konnte nicht geladen werden. Bitte später erneut versuchen."); return; }
        if (!canInstall()) { askInstallPermission(); return; }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(apk, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try { act.startActivity(i); }
        catch (Exception e) { dialog("Update", "Bitte die Benachrichtigung „Splitt-Tool Update“ antippen."); }
    }

    private void askInstallPermission() {
            new AlertDialog.Builder(act)
                .setTitle("Einmal erlauben")
                .setMessage("Damit der Update-Knopf die neue Version installieren kann, muss Android dem Splitt-Tool "
                        + "einmalig erlauben, Apps zu installieren.\n\nIm nächsten Fenster „Zulassen“ einschalten, "
                        + "zurückkehren und erneut auf den roten Update-Knopf tippen.")
                .setPositiveButton("Zu den Einstellungen", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        act.startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",
                                Uri.parse("package:" + act.getPackageName())));
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }


    /** Sobald der Download fertig ist, öffnet sich der Android-Installer. */
    private void watchDownload(final long id) {
        android.content.BroadcastReceiver rec = new android.content.BroadcastReceiver() {
            public void onReceive(android.content.Context c, Intent in) {
                if (in.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                try { act.getApplicationContext().unregisterReceiver(this); } catch (Exception ignored) { }
                if (act.isFinishing()) return;
                installDownloaded(id);
            }
        };
        android.content.IntentFilter f = new android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        android.content.Context app = act.getApplicationContext();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                // registerReceiver(..., RECEIVER_EXPORTED) – Pflicht ab Android 14; die Meldung kommt vom Download-Dienst
                app.getClass().getMethod("registerReceiver", android.content.BroadcastReceiver.class,
                        android.content.IntentFilter.class, int.class).invoke(app, rec, f, 0x2);
            } else {
                app.registerReceiver(rec, f);
            }
        } catch (Exception ignored) { /* ohne Rückmeldung – die Benachrichtigung reicht */ }
    }

    private static HttpURLConnection open(String url) throws IOException { return open(url, null); }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Splitt-Tool-Android");
            if (accept != null) c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) { c.disconnect(); throw new IOException("Server antwortet " + code); }
            return c;
        }
        throw new IOException("Zu viele Weiterleitungen");
    }

    private static byte[] get(String url, String accept) throws IOException {
        HttpURLConnection c = open(url, accept);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally { c.disconnect(); }
    }
}
