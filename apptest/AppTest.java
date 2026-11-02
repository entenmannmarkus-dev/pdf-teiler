package de.pdfteiler;

import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenu;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = "app/AndroidManifest.xml", sdk = 27, packageName = "de.pdfteiler")
public class AppTest {

    /** Minimaler Dokumenten-Anbieter auf einem Ordner, wie „Eigene Dateien“. */
    public static class FolderProvider extends DocumentsProvider {
        static File base;
        File f(String id) { return id.equals("root") ? base : new File(base, id); }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor queryRoots(String[] p) { return new MatrixCursor(new String[]{"root_id"}); }
        @Override public Cursor queryDocument(String id, String[] p) {
            String[] cols = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS};
            MatrixCursor c = new MatrixCursor(cols);
            File x = f(id);
            c.addRow(new Object[]{id, x.getName(), x.isDirectory() ? Document.MIME_TYPE_DIR : "application/pdf", x.length(), Document.FLAG_DIR_SUPPORTS_CREATE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_WRITE});
            return c;
        }
        @Override public Cursor queryChildDocuments(String id, String[] p, String s) { return new MatrixCursor(new String[]{Document.COLUMN_DOCUMENT_ID}); }
        @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cs) throws FileNotFoundException {
            return ParcelFileDescriptor.open(f(id), ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            String id = parent.equals("root") ? name : parent + "/" + name;
            File x = f(id);
            try { if (Document.MIME_TYPE_DIR.equals(mime)) x.mkdirs(); else x.createNewFile(); } catch (Exception e) { throw new FileNotFoundException(e.toString()); }
            return id;
        }
        @Override public void deleteDocument(String id) { f(id).delete(); }
        @Override public boolean isChildDocument(String parent, String doc) { return true; }
    }

    static final String AUTH = "test.docs";

    private MainActivity start(File out) {
        FolderProvider.base = out;
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTH; info.exported = true; info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS"; info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        Robolectric.buildContentProvider(FolderProvider.class).create(info);
        return Robolectric.setupActivity(MainActivity.class);
    }

    private static void waitFor(MainActivity a, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        java.lang.reflect.Field wf = MainActivity.class.getDeclaredField("worker");
        wf.setAccessible(true);
        while (System.currentTimeMillis() < end) {
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            Thread w = (Thread) wf.get(a);
            if (w == null || !w.isAlive()) break;
            Thread.sleep(100);
        }
        Thread.sleep(200);
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
    }

    private static Object field(Object o, String n) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void set(Object o, String n, Object v) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(n);
        f.setAccessible(true);
        f.set(o, v);
    }

    private static List<Button> buttons(View v, List<Button> acc) {
        if (v instanceof Button) acc.add((Button) v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) buttons(((ViewGroup) v).getChildAt(i), acc);
        return acc;
    }

    private static Button button(MainActivity a, String text) {
        for (Button b : buttons(a.getWindow().getDecorView(), new ArrayList<Button>()))
            if (b.getText().toString().contains(text)) return b;
        return null;
    }

    @Test public void startsAndHasUpdateInMenu() {
        MainActivity a = start(new File(System.getProperty("java.io.tmpdir")));
        RoboMenu menu = new RoboMenu(a);
        a.onCreateOptionsMenu(menu);
        MenuItem mi = menu.findItem(1);
        assertNotNull(mi);
        assertEquals("Update", mi.getTitle().toString());
        assertNotNull(button(a, "PDF auswählen"));
        assertNotNull(button(a, "Vorschau"));
        assertNotNull(button(a, "Aufteilen"));
        assertNotNull(button(a, "Senden (GitHub)"));
        System.out.println("UI ok, Untertitel: " + a.getActionBar().getSubtitle());
    }

    @Test public void updateButtonChecksServer() throws Exception {
        MainActivity a = start(new File(System.getProperty("java.io.tmpdir")));
        RoboMenu menu = new RoboMenu(a);
        a.onCreateOptionsMenu(menu);
        ((TextView) field(a, "updateView")).performClick();
        for (int i = 0; i < 100; i++) {
            Thread.sleep(150);
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            AlertDialog d = ShadowAlertDialog.getLatestAlertDialog();
            if (d != null && d.isShowing() && !"Update".equals(String.valueOf(Shadows.shadowOf(d).getTitle()))) {
                String msg = Shadows.shadowOf(d).getMessage().toString();
                String title = Shadows.shadowOf(d).getTitle().toString();
                System.out.println("UPDATE-DIALOG: " + title + " | " + msg);
                assertTrue(title.length() > 0);
                return;
            }
        }
        fail("kein Dialog nach Update-Klick");
    }

    @Test public void previewAndSplitAndShare() throws Exception {
        File out = new File("/tmp/claude-0/robo/out"); deleteTree(out); out.mkdirs();
        MainActivity a = start(out);
        // PDF wie aus „Eigene Dateien“ gewählt
        File pdf = new File(out, "buch.pdf");
        java.nio.file.Files.copy(new File("/tmp/claude-0/t/verschachtelt.pdf").toPath(), pdf.toPath());
        Uri pdfUri = DocumentsContract.buildDocumentUri(AUTH, "buch.pdf");
        try {
            Cursor q = a.getContentResolver().query(pdfUri, new String[]{"_display_name", "_size"}, null, null, null);
            System.out.println("QUERY: " + q + (q != null ? " cols=" + java.util.Arrays.toString(q.getColumnNames()) + " n=" + q.getCount() : ""));
        } catch (Exception e) { System.out.println("QUERY-FEHLER: " + e); }
        java.lang.reflect.Method setPdf = MainActivity.class.getDeclaredMethod("setPdf", Uri.class);
        setPdf.setAccessible(true);
        setPdf.invoke(a, pdfUri);
        System.out.println("PDF-Anzeige: " + ((TextView) field(a, "tvPdf")).getText());

        // Vorschau läuft nach Auswahl automatisch
        waitFor(a, 120000);
        String log = ((TextView) field(a, "tvLog")).getText().toString();
        System.out.println("LOG nach Vorschau:\n" + log);
        assertTrue(log.contains("Vorschau fertig"));
        ViewGroup pb = (ViewGroup) field(a, "previewBody");
        StringBuilder sb = new StringBuilder();
        dump(pb, sb, 0);
        System.out.println("VORSCHAU-KASTEN:\n" + sb);
        assertTrue(pb.getChildCount() > 5);
        System.out.println("STATUS: " + ((TextView) field(a, "tvStatus")).getText());

        // Aufteilen
        set(a, "treeUri", DocumentsContract.buildTreeDocumentUri(AUTH, "root"));
        button(a, "Aufteilen").performClick();
        waitFor(a, 300000);
        log = ((TextView) field(a, "tvLog")).getText().toString();
        System.out.println("LOG nach Aufteilen:\n" + log);
        System.out.println("STATUS: " + ((TextView) field(a, "tvStatus")).getText());
        File dir = new File(out, "Dokument_teile");
        String[] files = dir.list();
        java.util.Arrays.sort(files);
        for (String f : files) System.out.println("  DATEI " + f + " " + new File(dir, f).length());
        assertTrue(log.contains("Fertig:"));
        assertFalse(log.contains("FEHLER"));

        // Teilen-Knopf
        Button share = button(a, "Teilen");
        assertNotNull("Teilen-Knopf fehlt", share);
        share.performClick();
        Intent started = Shadows.shadowOf(a).getNextStartedActivity();
        assertNotNull(started);
        Intent inner = started.getParcelableExtra(Intent.EXTRA_INTENT);
        System.out.println("TEILEN: " + started.getAction() + " -> " + inner.getAction() + " " + inner.getType() + " "
                + inner.getParcelableExtra(Intent.EXTRA_STREAM) + " flags=" + Integer.toHexString(inner.getFlags()));
        assertEquals(Intent.ACTION_SEND, inner.getAction());
        Button all = button(a, "Teile teilen");
        all.performClick();
        Intent st2 = Shadows.shadowOf(a).getNextStartedActivity();
        Intent in2 = st2.getParcelableExtra(Intent.EXTRA_INTENT);
        System.out.println("ALLE TEILEN: " + in2.getAction() + " anzahl=" + in2.getParcelableArrayListExtra(Intent.EXTRA_STREAM).size());

        // Verbesserungsvorschlag
        ((TextView) field(a, "etFeedback")).setText("Bitte größere Knöpfe");
        button(a, "Senden (GitHub)").performClick();
        Intent gh = Shadows.shadowOf(a).getNextStartedActivity();
        System.out.println("FEEDBACK-URL: " + gh.getData().toString().substring(0, 140));
        assertTrue(gh.getData().toString().startsWith("https://github.com/entenmannmarkus-dev/pdf-teiler/issues/new"));
        button(a, "In Claude-App").performClick();
        Intent ca = Shadows.shadowOf(a).getNextStartedActivity();
        System.out.println("CLAUDE-APP: " + ca.getAction() + " pkg=" + ca.getPackage() + " text=" + ca.getStringExtra(Intent.EXTRA_TEXT).substring(0, 60));
    }

    @Test public void updateOpensBrowserDownload() throws Exception {
        Updater.API_URL = "http://127.0.0.1:8765/nope";
        Updater.UPDATE_URL = "http://127.0.0.1:8765/update.json";
        MainActivity a = start(new File(System.getProperty("java.io.tmpdir")));
        a.onCreateOptionsMenu(new RoboMenu(a));
        ((TextView) field(a, "updateView")).performClick();
        AlertDialog d = null;
        for (int i = 0; i < 100; i++) {
            Thread.sleep(100);
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
            d = ShadowAlertDialog.getLatestAlertDialog();
            if (d != null && d.isShowing() && !"Update".equals(String.valueOf(Shadows.shadowOf(d).getTitle()))) break;
        }
        System.out.println("DIALOG1: " + Shadows.shadowOf(d).getTitle() + " | " + Shadows.shadowOf(d).getMessage());
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks();
        Intent next = Shadows.shadowOf(a).getNextStartedActivity();
        System.out.println("BROWSER: " + next.getAction() + " " + next.getData());
        assertEquals(Intent.ACTION_VIEW, next.getAction());
        assertTrue(next.getData().toString().endsWith(".apk"));
        assertTrue(a.getPackageManager().getPackageInstaller().getAllSessions().isEmpty());
    }

    @Test public void redButtonWhenUpdateAvailable() throws Exception {
        Updater.API_URL = "http://127.0.0.1:8765/nope";
        Updater.UPDATE_URL = "http://127.0.0.1:8765/update.json";
        org.robolectric.android.controller.ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class);
        FolderProvider.base = new File(System.getProperty("java.io.tmpdir"));
        MainActivity a = ctl.create().start().get();
        RoboMenu menu = new RoboMenu(a);
        a.onCreateOptionsMenu(menu);
        TextView uv = (TextView) field(a, "updateView");
        System.out.println("VORHER: " + uv.getText());
        ctl.resume();
        for (int i = 0; i < 60; i++) { Thread.sleep(100); ShadowLooper.runUiThreadTasksIncludingDelayedTasks(); if (uv.getText().toString().startsWith("●")) break; }
        System.out.println("NACHHER: " + uv.getText() + " bg=" + uv.getBackground() + " anim=" + uv.getAnimation());
        assertTrue(uv.getText().toString().startsWith("● Update 9.9"));
    }

    @Test public void liveGithubSourceReachable() throws Exception {
        org.json.JSONObject o = Updater.fetchInfo();
        System.out.println("LIVE update.json: " + o);
        assertTrue(o.getInt("versionCode") >= 7);
    }

    static void dump(View v, StringBuilder sb, int depth) {
        if (v instanceof TextView) sb.append(new String(new char[depth]).replace("\0", "  ")).append(((TextView) v).getText()).append('\n');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) dump(((ViewGroup) v).getChildAt(i), sb, depth + 1);
    }

    @Test public void noSilentInstallWhenUpdateFound() throws Exception {
        Updater.API_URL = "http://127.0.0.1:8765/nope";
        Updater.UPDATE_URL = "http://127.0.0.1:8765/update.json";
        FolderProvider.base = new File(System.getProperty("java.io.tmpdir"));
        org.robolectric.android.controller.ActivityController<MainActivity> ctl = Robolectric.buildActivity(MainActivity.class);
        MainActivity a = ctl.create().start().get();
        a.onCreateOptionsMenu(new RoboMenu(a));
        ctl.resume();
        TextView uv = (TextView) field(a, "updateView");
        for (int i = 0; i < 60 && !uv.getText().toString().startsWith("●"); i++) { Thread.sleep(100); ShadowLooper.runUiThreadTasksIncludingDelayedTasks(); }
        System.out.println("KNOPF: " + uv.getText() + " LOG: " + ((TextView) field(a, "tvLog")).getText());
        assertTrue(uv.getText().toString().startsWith("●"));
        assertTrue(a.getPackageManager().getPackageInstaller().getAllSessions().isEmpty());
        assertNull(Shadows.shadowOf(a).getNextStartedActivity());
    }

    static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        f.delete();
    }
}
