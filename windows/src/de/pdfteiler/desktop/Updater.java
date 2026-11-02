package de.pdfteiler.desktop;

import org.json.JSONObject;

import javax.swing.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;

/**
 * Update über GitHub: liest update.json (Abschnitt „windows“), lädt die neue Programmdatei
 * in den eigenen Ordner (Benutzerprofil, keine Adminrechte) und startet neu.
 */
final class Updater {
    static String API_URL = "https://api.github.com/repos/" + SplittTool.REPO + "/contents/update.json?ref=main";
    static String RAW_URL = "https://raw.githubusercontent.com/" + SplittTool.REPO + "/main/update.json";

    private final SplittTool app;
    private JSONObject available;

    Updater(SplittTool app) { this.app = app; }

    static File home() {
        String h = System.getProperty("splitt.home");
        if (h != null) return new File(h);
        try {
            File jar = new File(Updater.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.getParentFile().getParentFile();
        } catch (Exception e) { return new File("."); }
    }

    static File appDir() { return new File(home(), "app"); }

    static JSONObject fetchInfo() throws Exception {
        try { return new JSONObject(new String(get(API_URL, "application/vnd.github.raw+json"), "UTF-8")); }
        catch (Exception apiFailed) {
            return new JSONObject(new String(get(RAW_URL + "?t=" + System.currentTimeMillis(), null), "UTF-8"));
        }
    }

    /** quiet = beim Start: nur Knopf rot färben. Sonst Dialog. */
    void check(boolean quiet) {
        new Thread(() -> {
            try {
                JSONObject w = fetchInfo().optJSONObject("windows");
                if (w == null) throw new IOException("Für Windows ist noch keine Version veröffentlicht.");
                boolean newer = w.getInt("versionCode") > Version.CODE;
                SwingUtilities.invokeLater(() -> {
                    if (newer) { available = w; app.styleUpdate(w.optString("versionName")); }
                    if (quiet) return;
                    if (!newer) { app.info("Kein Update", "Du hast bereits die neueste Version (" + Version.NAME + ")."); return; }
                    String notes = w.optString("notes", "");
                    if (SplittTool.testMode) { download(w); return; }
                    int ok = JOptionPane.showConfirmDialog(app.frame, (notes.isEmpty() ? "" : notes + "\n\n")
                            + "Jetzt herunterladen und installieren? Das Programm startet danach neu.",
                            "Neue Version " + w.optString("versionName"), JOptionPane.YES_NO_OPTION);
                    if (ok == JOptionPane.YES_OPTION) download(w);
                });
            } catch (Exception e) {
                if (!quiet) SwingUtilities.invokeLater(() -> app.info("Update nicht möglich",
                        "Die Update-Information konnte nicht geladen werden.\nBitte Internetverbindung prüfen.\n\n(" + e.getMessage() + ")"));
            }
        }, "update-check").start();
    }

    void download(JSONObject w) {
        app.status.setText("Lade Update …");
        new Thread(() -> {
            try {
                int code = w.getInt("versionCode");
                File dir = appDir();
                dir.mkdirs();
                File part = new File(dir, "splitt-tool-" + code + ".jar.part");
                File done = new File(dir, "splitt-tool-" + code + ".jar");
                try (InputStream in = open(w.getString("jarUrl"), null).getInputStream();
                     OutputStream out = new FileOutputStream(part)) {
                    in.transferTo(out);
                }
                try (java.util.jar.JarFile check = new java.util.jar.JarFile(part)) {
                    if (check.getEntry("de/pdfteiler/desktop/SplittTool.class") == null) throw new IOException("Download unvollständig.");
                }
                Files.move(part.toPath(), done.toPath(), StandardCopyOption.REPLACE_EXISTING);
                SwingUtilities.invokeLater(() -> {
                    app.status.setText("Update geladen");
                    restart();
                });
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> { app.status.setText(" "); app.info("Update fehlgeschlagen", String.valueOf(e.getMessage())); });
            }
        }, "update-download").start();
    }

    void restart() {
        String exe = System.getProperty("splitt.exe");
        if (exe != null && new File(exe).isFile()) {
            try {
                new ProcessBuilder(exe).start();
                System.exit(0);
            } catch (IOException ignored) { }
        }
        app.info("Update installiert", "Bitte das Splitt-Tool schließen und neu starten.");
    }

    /** Alte Programmdateien aufräumen (die laufende bleibt). */
    void cleanup() {
        File[] jars = appDir().listFiles((d, n) -> n.matches("splitt-tool-\\d+\\.jar(\\.part)?"));
        if (jars == null) return;
        for (File j : jars) {
            int c = Integer.parseInt(j.getName().replaceAll("\\D", ""));
            if (c < Version.CODE || j.getName().endsWith(".part")) j.delete();
        }
    }

    private static HttpURLConnection open(String url, String accept) throws IOException {
        for (int i = 0; i < 6; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "Splitt-Tool-Windows");
            if (accept != null) c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = new URL(new URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
            if (code != 200) { c.disconnect(); throw new IOException("Server antwortet " + code); }
            return c;
        }
        throw new IOException("Zu viele Weiterleitungen");
    }

    private static byte[] get(String url, String accept) throws IOException {
        HttpURLConnection c = open(url, accept);
        try (InputStream in = c.getInputStream()) { return in.readAllBytes(); } finally { c.disconnect(); }
    }
}
