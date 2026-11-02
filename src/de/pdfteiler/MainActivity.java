package de.pdfteiler;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.RandomAccessRead;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int REQ_PDF = 1, REQ_DIR = 2;

    private Uri pdfUri, treeUri;
    private String pdfName = "";
    private long pdfSize = -1;
    private TextView tvPdf, tvDir, tvLog, tvStatus;
    private EditText etMb, etPages;
    private CheckBox cbOne, cbVerify, cbDetect;
    private Button bPdf, bDir, bStart, bCancel, bPreview;
    private ProgressBar bar;
    private ScrollView logScroll;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancel = new AtomicBoolean(false);
    private Thread worker;
    private SharedPreferences prefs;
    private Updater updater;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        PDFBoxResourceLoader.init(getApplicationContext());
        prefs = getSharedPreferences("einstellungen", MODE_PRIVATE);
        updater = new Updater(this);
        buildUi();
        String t = prefs.getString("tree", null);
        if (t != null) {
            Uri u = Uri.parse(t);
            if (hasPermission(u)) { treeUri = u; tvDir.setText(describeTree(u)); }
        }
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent in) {
        if (in == null) return;
        if (Updater.ACTION_STATUS.equals(in.getAction())) { updater.onInstallStatus(in); return; }
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(in.getAction())) u = in.getData();
        else if (Intent.ACTION_SEND.equals(in.getAction())) u = in.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) setPdf(u);
    }

    // ------------------------------------------------------------------ Oberfläche
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView label(LinearLayout parent, String text, int sizeSp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(10), 0, dp(4));
        parent.addView(t);
        return t;
    }

    private Button button(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        parent.addView(b);
        return b;
    }

    private EditText number(LinearLayout row, String hint, String value, boolean decimal) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(hint);
        box.addView(t);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        e.setText(value);
        e.setMinEms(4);
        box.addView(e);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(16);
        row.addView(box, lp);
        return e;
    }

    private void buildUi() {
        ScrollView outer = new ScrollView(this);
        outer.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(12), dp(20), dp(20));
        outer.addView(root);

        TextView intro = label(root, "Teilt große PDFs (bis 1 GB) kapitelweise in Teile, die du in Claude hochladen kannst. "
                + "Die Seiten werden 1:1 übernommen – nichts wird verändert oder hinzugefügt.", 15, false);
        intro.setTextColor(Color.DKGRAY);

        label(root, "1. PDF-Datei", 18, true);
        bPdf = button(root, "PDF auswählen …");
        tvPdf = label(root, "Noch keine Datei gewählt.", 14, false);

        label(root, "2. Zielordner für die Teile", 18, true);
        bDir = button(root, "Ordner auswählen …");
        tvDir = label(root, "Noch kein Ordner gewählt. Darin wird ein Unterordner „<Name>_teile“ angelegt.", 14, false);

        label(root, "3. Grenzen pro Teil", 18, true);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        etMb = number(row, "max. MB", prefs.getString("mb", "30"), true);
        etPages = number(row, "max. Seiten", prefs.getString("pages", "100"), false);
        TextView hint = label(root, "Claude-Grenzen: Chat max. 500 MB und 1000 Seiten pro PDF, Projekt max. 30 MB. Bilder wertet "
                + "Claude nur bis 100 Seiten aus. Standard 30 MB / 100 Seiten passt überall.", 13, false);
        hint.setTextColor(Color.GRAY);

        cbOne = new CheckBox(this); cbOne.setText("Jedes Kapitel in eigene Datei"); root.addView(cbOne);
        cbOne.setChecked(prefs.getBoolean("one", false));
        cbDetect = new CheckBox(this); cbDetect.setText("Überschriften suchen, wenn die PDF keine Lesezeichen hat"); root.addView(cbDetect);
        cbDetect.setChecked(prefs.getBoolean("detect", true));
        cbVerify = new CheckBox(this); cbVerify.setText("Jeden Teil mit dem Original vergleichen (empfohlen)"); root.addView(cbVerify);
        cbVerify.setChecked(prefs.getBoolean("verify", true));

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(12), 0, 0);
        root.addView(btns);
        bPreview = button(btns, "Vorschau");
        bStart = button(btns, "▶  Aufteilen");
        bCancel = button(btns, "Abbrechen");
        bCancel.setEnabled(false);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        tvStatus = label(root, "", 14, true);

        logScroll = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(12);
        tvLog.setTextIsSelectable(true);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        tvLog.setBackgroundColor(0xFFF2F2F2);
        logScroll.addView(tvLog);
        root.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320)));

        LinearLayout upd = new LinearLayout(this);
        upd.setOrientation(LinearLayout.HORIZONTAL);
        upd.setGravity(Gravity.CENTER_VERTICAL);
        upd.setPadding(0, dp(16), 0, 0);
        root.addView(upd);
        Button bUpdate = button(upd, "⟳  Nach Update suchen");
        TextView ver = new TextView(this);
        ver.setText("   Version " + Updater.currentVersionName(this));
        ver.setTextColor(Color.GRAY);
        upd.addView(ver);
        bUpdate.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (worker != null && worker.isAlive()) { alert("Bitte warten", "Erst nach dem Aufteilen aktualisieren."); return; }
            updater.check();
        }});
        setContentView(outer);

        bPdf.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/pdf");
            startActivityForResult(i, REQ_PDF);
        }});
        bDir.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i, REQ_DIR);
        }});
        bStart.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { start(false); }});
        bPreview.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { start(true); }});
        bCancel.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            cancel.set(true); log("Breche ab …");
        }});
    }

    // ------------------------------------------------------------------ Auswahl
    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_PDF) {
            try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) { }
            setPdf(u);
        } else if (req == REQ_DIR) {
            try {
                getContentResolver().takePersistableUriPermission(u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
            treeUri = u;
            prefs.edit().putString("tree", u.toString()).apply();
            tvDir.setText(describeTree(u));
        }
    }

    private boolean hasPermission(Uri u) {
        for (android.content.UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isWritePermission()) return true;
        return false;
    }

    private String describeTree(Uri u) {
        String id = DocumentsContract.getTreeDocumentId(u);
        return "Zielordner: " + id.replace("primary:", "Interner Speicher/");
    }

    private void setPdf(Uri u) {
        pdfUri = u;
        pdfName = "Dokument.pdf";
        pdfSize = -1;
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null);
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) pdfName = c.getString(0);
                if (!c.isNull(1)) pdfSize = c.getLong(1);
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        tvPdf.setText(pdfName + (pdfSize >= 0 ? "  (" + SplitCore.human(pdfSize) + ")" : ""));
    }

    // ------------------------------------------------------------------ Ablauf
    private void log(final String m) {
        main.post(new Runnable() { public void run() {
            tvLog.append(m + "\n");
            logScroll.post(new Runnable() { public void run() { logScroll.fullScroll(View.FOCUS_DOWN); } });
        }});
    }

    private void setBusy(boolean busy) {
        bStart.setEnabled(!busy); bPdf.setEnabled(!busy); bDir.setEnabled(!busy); bPreview.setEnabled(!busy);
        bCancel.setEnabled(busy);
        if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void alert(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void start(final boolean preview) {
        if (pdfUri == null) { alert("Keine PDF", "Bitte zuerst eine PDF-Datei auswählen."); return; }
        if (treeUri == null && !preview) { alert("Kein Zielordner", "Bitte einen Ordner auswählen, in dem die Teile gespeichert werden."); return; }
        final SplitCore.Settings s = new SplitCore.Settings();
        try {
            s.maxMb = Double.parseDouble(etMb.getText().toString().replace(',', '.'));
            s.maxPages = Integer.parseInt(etPages.getText().toString().trim());
            if (s.maxMb <= 0 || s.maxPages <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            alert("Eingabe prüfen", "Bitte gültige positive Zahlen für MB und Seiten eingeben."); return;
        }
        if (s.maxMb > 500 || s.maxPages > 1000) {
            alert("Über Claudes Grenze", "Claude erlaubt im Chat höchstens 500 MB und 1000 Seiten pro PDF "
                    + "(Bilder werden nur bis 100 Seiten ausgewertet). Bitte kleinere Werte eintragen.");
            return;
        }
        s.onePerPart = cbOne.isChecked();
        s.detectHeadings = cbDetect.isChecked();
        s.verify = cbVerify.isChecked();
        String base = pdfName.toLowerCase().endsWith(".pdf") ? pdfName.substring(0, pdfName.length() - 4) : pdfName;
        s.baseName = base;
        prefs.edit().putString("mb", etMb.getText().toString()).putString("pages", etPages.getText().toString())
                .putBoolean("one", s.onePerPart).putBoolean("detect", s.detectHeadings)
                .putBoolean("verify", s.verify).apply();

        long free = getCacheDir().getUsableSpace();
        if (pdfSize > 0 && free < pdfSize + 200L * 1024 * 1024)
            log("Hinweis: wenig freier Speicher (" + SplitCore.human(free) + "). Die App braucht vorübergehend "
                    + "etwa so viel freien Platz wie die PDF groß ist.");

        tvLog.setText("");
        tvStatus.setText(preview ? "Vorschau wird erstellt …" : "Läuft … (App bitte geöffnet lassen)");
        bar.setProgress(0);
        cancel.set(false);
        setBusy(true);
        worker = new Thread(new Runnable() { public void run() { if (preview) previewWork(s); else work(s); } }, "pdf-splitter");
        worker.start();
    }

    private void previewWork(SplitCore.Settings s) {
        File tmp = new File(getCacheDir(), "arbeit");
        deleteTree(tmp);
        tmp.mkdirs();
        try {
            SplitCore.Io io = new AndroidIo(getContentResolver(), pdfUri, null, tmp);
            final String text = new SplitCore(io, listener(), s).preview();
            main.post(new Runnable() { public void run() {
                setBusy(false);
                bar.setProgress(1000);
                tvStatus.setText("Vorschau fertig");
                showPreview(text);
            }});
        } catch (final Throwable e) {
            failed(e);
        } finally {
            deleteTree(tmp);
        }
    }

    private void showPreview(String text) {
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(20), dp(12), dp(20), dp(12));
        sv.addView(tv);
        new AlertDialog.Builder(this)
            .setTitle("Vorschau: " + pdfName)
            .setView(sv)
            .setPositiveButton("Jetzt aufteilen", new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) { start(false); }
            })
            .setNegativeButton("Schließen", null)
            .show();
    }

    private SplitCore.Listener listener() {
        return new SplitCore.Listener() {
            public void log(String m) { MainActivity.this.log(m); }
            public void progress(final int d, final int t) {
                main.post(new Runnable() { public void run() { bar.setProgress(t == 0 ? 0 : (int) (1000L * d / t)); } });
            }
            public boolean cancelled() { return cancel.get(); }
        };
    }

    private void failed(final Throwable e) {
        final boolean cancelled = e instanceof SplitCore.CancelledException;
        log(cancelled ? "Abgebrochen." : "FEHLER: " + e);
        main.post(new Runnable() { public void run() {
            setBusy(false);
            tvStatus.setText(cancelled ? "Abgebrochen" : "Fehler");
            if (!cancelled) alert("Fehler", e instanceof OutOfMemoryError
                    ? "Zu wenig Arbeitsspeicher. Bitte andere Apps schließen und erneut versuchen."
                    : String.valueOf(e.getMessage()));
        }});
    }

    private void work(SplitCore.Settings s) {
        final ContentResolver cr = getContentResolver();
        File tmp = new File(getCacheDir(), "arbeit");
        deleteTree(tmp);
        tmp.mkdirs();
        try {
            Uri treeDoc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            String dirName = SplitCore.safeName(s.baseName, 60) + "_teile";
            final Uri dir = DocumentsContract.createDocument(cr, treeDoc, DocumentsContract.Document.MIME_TYPE_DIR, dirName);
            if (dir == null) throw new IOException("Ordner konnte nicht angelegt werden.");
            log("Ausgabeordner: " + dirName);
            SplitCore.Io io = new AndroidIo(cr, pdfUri, dir, tmp);
            SplitCore.Listener l = new SplitCore.Listener() {
                public void log(String m) { MainActivity.this.log(m); }
                public void progress(final int d, final int t) {
                    main.post(new Runnable() { public void run() { bar.setProgress(t == 0 ? 0 : (int) (1000L * d / t)); } });
                }
                public boolean cancelled() { return cancel.get(); }
            };
            final SplitCore.Result r = new SplitCore(io, l, s).run();
            final String folder = dirName;
            main.post(new Runnable() { public void run() {
                setBusy(false);
                bar.setProgress(1000);
                String msg = r.parts + " Teile im Ordner „" + folder + "“ gespeichert.\n\n"
                        + "Übersicht mit Kapiteln: 00_UEBERSICHT.md";
                if (!r.errors.isEmpty()) {
                    tvStatus.setText("Achtung: Abweichungen gefunden!");
                    alert("Prüfung", "Achtung: Die Prüfung hat Abweichungen gefunden. Details in 00_UEBERSICHT.md.\n\n" + msg);
                } else {
                    tvStatus.setText("Fertig ✓ " + r.parts + " Teile" + (cbVerify.isChecked() ? ", alle Seiten geprüft" : ""));
                    alert("Fertig", msg + (r.warnings.isEmpty() ? "" : "\n\nHinweise: " + r.warnings.size() + " (siehe Übersicht)"));
                }
            }});
        } catch (final Throwable e) {
            final boolean cancelled = e instanceof SplitCore.CancelledException;
            log(cancelled ? "Abgebrochen." : "FEHLER: " + e);
            main.post(new Runnable() { public void run() {
                setBusy(false);
                tvStatus.setText(cancelled ? "Abgebrochen" : "Fehler");
                if (!cancelled) alert("Fehler", e instanceof OutOfMemoryError
                        ? "Zu wenig Arbeitsspeicher. Bitte andere Apps schließen und erneut versuchen."
                        : String.valueOf(e.getMessage()));
            }});
        } finally {
            deleteTree(tmp);
        }
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // ------------------------------------------------------------------ Dateizugriff (Storage Access Framework)
    static final class AndroidIo implements SplitCore.Io {
        private final ContentResolver cr;
        private final Uri src, dir;
        private final File tmp;
        private final Map<String, Uri> files = new HashMap<String, Uri>();

        AndroidIo(ContentResolver cr, Uri src, Uri dir, File tmp) {
            this.cr = cr; this.src = src; this.dir = dir; this.tmp = tmp;
        }

        private static RandomAccessRead open(ContentResolver cr, Uri u) throws IOException {
            ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "r");
            if (pfd == null) throw new IOException("Datei nicht lesbar");
            // AutoCloseInputStream besitzt den Deskriptor und schließt ihn sauber
            FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            return new ChannelRead(in.getChannel(), in);
        }

        public RandomAccessRead openSource() throws IOException { return open(cr, src); }

        public OutputStream createOutput(String name, String mime) throws IOException {
            Uri u = files.get(name);
            if (u == null) {
                u = DocumentsContract.createDocument(cr, dir, mime, name);
                if (u == null) throw new IOException("Datei konnte nicht angelegt werden: " + name);
                files.put(name, u);
            }
            OutputStream os = cr.openOutputStream(u, "wt");
            if (os == null) throw new IOException("Datei nicht beschreibbar: " + name);
            return new BufferedOutputStream(os, 1 << 16);
        }

        public RandomAccessRead openOutput(String name) throws IOException {
            Uri u = files.get(name);
            if (u == null) throw new IOException("Unbekannte Datei " + name);
            return open(cr, u);
        }

        public long outputSize(String name) throws IOException {
            Uri u = files.get(name);
            ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "r");
            try { return pfd.getStatSize(); } finally { pfd.close(); }
        }

        public void deleteOutput(String name) {
            Uri u = files.remove(name);
            if (u != null) try { DocumentsContract.deleteDocument(cr, u); } catch (Exception ignored) { }
        }

        public File tempDir() { return tmp; }
    }
}
