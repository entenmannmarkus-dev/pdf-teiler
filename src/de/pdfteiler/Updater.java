package de.pdfteiler;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Selbst-Aktualisierung: liest update.json aus dem öffentlichen GitHub-Repository,
 * lädt bei neuerer Version die APK und übergibt sie dem Android-Paketinstaller.
 * Android fragt vor der Installation immer selbst nach – nichts passiert ungefragt.
 */
final class Updater {
    static final String REPO = "entenmannmarkus-dev/pdf-teiler";
    static String UPDATE_URL = "https://raw.githubusercontent.com/" + REPO + "/main/update.json";
    /** Frische Quelle ohne CDN-Verzögerung (GitHub-API, 60 Abfragen/Stunde ohne Anmeldung). */
    static String API_URL = "https://api.github.com/repos/" + REPO + "/contents/update.json?ref=main";

    interface Callback { void available(String versionName, String notes); }

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
                if (code > currentVersionCode(act))
                    main.post(new Runnable() { public void run() { cb.available(name, notes); } });
            } catch (Exception ignored) { /* offline – beim nächsten Start wieder */ }
        }}, "update-check").start();
    }
    static final String ACTION_STATUS = "de.pdfteiler.INSTALL_STATUS";
    private static final int FLAG_MUTABLE = 0x02000000; // PendingIntent.FLAG_MUTABLE (API 31)

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
                        .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "Jetzt herunterladen und installieren?")
                        .setPositiveButton("Aktualisieren", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { download(apk); }
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

    private boolean canInstall() {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return (Boolean) PackageManager.class.getMethod("canRequestPackageInstalls").invoke(act.getPackageManager());
        } catch (Exception e) { return true; }
    }

    private void download(final String url) {
        if (!canInstall()) {
            new AlertDialog.Builder(act)
                .setTitle("Erlaubnis nötig")
                .setMessage("Damit die App sich selbst aktualisieren kann, muss Android ihr einmalig erlauben, "
                        + "Apps zu installieren. Bitte im nächsten Fenster „Zulassen“ einschalten, zurückkehren "
                        + "und erneut auf „Nach Update suchen“ tippen.")
                .setPositiveButton("Zu den Einstellungen", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        act.startActivity(new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES",
                                Uri.parse("package:" + act.getPackageName())));
                    }
                })
                .setNegativeButton("Abbrechen", null)
                .show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(act);
        pd.setTitle("Update");
        pd.setMessage("Lade neue Version …");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        new Thread(new Runnable() { public void run() {
            HttpURLConnection c = null;
            try {
                c = open(url);
                long total = c.getContentLength();
                PackageInstaller pi = act.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(act.getPackageName());
                int id = pi.createSession(sp);
                PackageInstaller.Session s = pi.openSession(id);
                InputStream in = c.getInputStream();
                OutputStream out = s.openWrite("update.apk", 0, total > 0 ? total : -1);
                byte[] buf = new byte[65536];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        final int pct = (int) (100 * done / total);
                        main.post(new Runnable() { public void run() { pd.setProgress(pct); } });
                    }
                }
                s.fsync(out);
                out.close();
                in.close();
                Intent cb = new Intent(act, MainActivity.class).setAction(ACTION_STATUS);
                PendingIntent pend = PendingIntent.getActivity(act, id, cb,
                        PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? FLAG_MUTABLE : 0));
                s.commit(pend.getIntentSender());
                s.close();
                main.post(new Runnable() { public void run() { pd.dismiss(); } });
            } catch (final Exception e) {
                main.post(new Runnable() { public void run() {
                    pd.dismiss();
                    dialog("Update fehlgeschlagen", String.valueOf(e.getMessage()));
                }});
            } finally {
                if (c != null) c.disconnect();
            }
        }}).start();
    }

    /** Rückmeldung des Paketinstallers (über MainActivity.onNewIntent). */
    void onInstallStatus(Intent in) {
        int status = in.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = in.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(confirm);
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String m = in.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) m = "Installation abgebrochen.";
            else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT)
                m = "Die neue Version ist anders signiert als die installierte. (" + m + ")";
            dialog("Update nicht installiert", String.valueOf(m));
        }
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
