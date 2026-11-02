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
    private android.view.MenuItem miUpdate;
    private ProgressBar bar;
    private ScrollView logScroll;
    private LinearLayout resultBox;
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
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(in.getAction())) u = in.getData();
        else if (Intent.ACTION_SEND.equals(in.getAction())) u = in.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) setPdf(u);
    }

    // ------------------------------------------------------------------ Oberfläche
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // Farben: Dunkelblau
    static final int BLUE = 0xFF0D2B5E, BLUE2 = 0xFF1E4B8F, BLUE_LIGHT = 0xFFE8EEF8, BG = 0xFFF2F5FA,
            BORDER = 0xFFD3DCEC, TEXT = 0xFF1A2333, MUTED = 0xFF5F6B7D;

    private TextView label(LinearLayout parent, String text, int sizeSp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sizeSp);
        t.setTextColor(bold ? BLUE : TEXT);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(4));
        parent.addView(t);
        return t;
    }

    private android.graphics.drawable.GradientDrawable round(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1), stroke);
        return g;
    }

    /** Kasten (Karte) mit Überschrift. */
    private LinearLayout card(LinearLayout parent, String title, float weight) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(Color.WHITE, BORDER, 14));
        c.setPadding(dp(16), dp(12), dp(16), dp(14));
        c.setElevation(dp(2));
        LinearLayout.LayoutParams lp = weight > 0
                ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), dp(6), dp(6), dp(6));
        parent.addView(c, lp);
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextSize(17);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setTextColor(BLUE);
            t.setPadding(0, 0, 0, dp(6));
            c.addView(t);
        }
        return c;
    }

    private Button button(LinearLayout parent, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(15);
        android.graphics.drawable.StateListDrawable sl = new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{-android.R.attr.state_enabled}, round(0xFFB8C2D3, 0, 10));
        sl.addState(new int[]{android.R.attr.state_pressed}, round(BLUE2, 0, 10));
        sl.addState(new int[]{}, round(BLUE, 0, 10));
        b.setBackground(sl);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), dp(8), dp(4));
        parent.addView(b, lp);
        return b;
    }

    private EditText number(LinearLayout row, String hint, String value, boolean decimal) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(hint);
        t.setTextColor(MUTED);
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

    private LinearLayout previewCard, previewBody;
    private ScrollView outerScroll;

    private void buildUi() {
        ScrollView outer = new ScrollView(this);
        outerScroll = outer;
        outer.setFillViewport(true);
        outer.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), dp(8), dp(10), dp(20));
        outer.addView(root);

        TextView intro = new TextView(this);
        intro.setText("Teilt große PDFs (bis 1 GB) kapitelweise in Teile für Claude. Die Seiten werden 1:1 übernommen – "
                + "nichts wird verändert oder hinzugefügt.");
        intro.setTextColor(MUTED);
        intro.setTextSize(14);
        intro.setPadding(dp(8), dp(4), dp(8), dp(4));
        root.addView(intro);

        // Eingang und Ausgang nebeneinander
        LinearLayout io = new LinearLayout(this);
        io.setOrientation(LinearLayout.HORIZONTAL);
        io.setBaselineAligned(false);
        root.addView(io);
        LinearLayout in = card(io, "📄  Eingang: PDF", 1);
        bPdf = button(in, "PDF auswählen …");
        tvPdf = label(in, "Noch keine Datei gewählt.", 14, false);
        LinearLayout outC = card(io, "📁  Ausgang: Zielordner", 1);
        bDir = button(outC, "Ordner auswählen …");
        tvDir = label(outC, "Noch kein Ordner gewählt. Darin entsteht ein Unterordner „<Name>_teile“.", 14, false);

        // Kapitel-Vorschau
        previewCard = card(root, "📑  Kapitel-Vorschau", 0);
        previewBody = new LinearLayout(this);
        previewBody.setOrientation(LinearLayout.VERTICAL);
        previewCard.addView(previewBody);
        TextView ph = label(previewBody, "Wähle eine PDF – die Kapitel und die geplanten Teile erscheinen dann automatisch hier.", 14, false);
        ph.setTextColor(MUTED);

        // Einstellungen
        LinearLayout set = card(root, "⚙  Grenzen pro Teil", 0);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        set.addView(row);
        etMb = number(row, "max. MB", prefs.getString("mb", "30"), true);
        etPages = number(row, "max. Seiten", prefs.getString("pages", "100"), false);
        TextView hint = label(set, "Claude: Chat max. 500 MB und 1000 Seiten pro PDF, Projekt max. 30 MB. Bilder wertet "
                + "Claude nur bis 100 Seiten aus. Standard 30 MB / 100 Seiten passt überall.", 12, false);
        hint.setTextColor(MUTED);
        cbOne = new CheckBox(this); cbOne.setText("Jedes Kapitel in eigene Datei"); set.addView(cbOne);
        cbOne.setChecked(prefs.getBoolean("one", false));
        cbDetect = new CheckBox(this); cbDetect.setText("Überschriften suchen, wenn die PDF keine Lesezeichen hat"); set.addView(cbDetect);
        cbDetect.setChecked(prefs.getBoolean("detect", true));
        cbVerify = new CheckBox(this); cbVerify.setText("Jeden Teil mit dem Original vergleichen (empfohlen)"); set.addView(cbVerify);
        cbVerify.setChecked(prefs.getBoolean("verify", true));

        // Start
        LinearLayout run = card(root, "▶  Aufteilen", 0);
        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        run.addView(btns);
        bPreview = button(btns, "Vorschau aktualisieren");
        bStart = button(btns, "▶  Aufteilen");
        bCancel = button(btns, "Abbrechen");
        bCancel.setEnabled(false);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(BLUE));
        run.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(24)));
        tvStatus = label(run, "", 14, true);
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        run.addView(resultBox);

        // Protokoll
        LinearLayout logC = card(root, "Protokoll", 0);
        logScroll = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setTypeface(Typeface.MONOSPACE);
        tvLog.setTextSize(12);
        tvLog.setTextColor(TEXT);
        tvLog.setTextIsSelectable(true);
        tvLog.setPadding(dp(8), dp(8), dp(8), dp(8));
        tvLog.setBackground(round(BLUE_LIGHT, 0, 8));
        logScroll.addView(tvLog);
        logC.addView(logScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));

        buildFeedback(card(root, null, 0));
        setContentView(outer);
        if (getActionBar() != null) {
            getActionBar().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(BLUE));
            getActionBar().setSubtitle("Version " + Updater.currentVersionName(this));
        }
        getWindow().setStatusBarColor(0xFF081D40);

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

    // ------------------------------------------------------------------ Verbesserungsvorschläge
    private EditText etFeedback;
    private CheckBox cbLog;

    private void buildFeedback(LinearLayout root) {
        label(root, "💬  Verbesserungsvorschlag an Claude", 17, true);
        etFeedback = new EditText(this);
        etFeedback.setHint("Was soll besser werden? Was hat nicht funktioniert?");
        etFeedback.setMinLines(3);
        etFeedback.setGravity(Gravity.TOP | Gravity.START);
        etFeedback.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(etFeedback);
        cbLog = new CheckBox(this);
        cbLog.setText("Protokoll der letzten Aktion anhängen (hilft bei Fehlern)");
        cbLog.setChecked(true);
        root.addView(cbLog);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        Button gh = button(row, "Senden (GitHub)");
        Button app = button(row, "In Claude-App öffnen");
        TextView hint = label(root, "„Senden“ legt den Vorschlag im Projekt auf GitHub ab, dort kann Claude ihn lesen "
                + "und umsetzen (einmal bei GitHub anmelden). „In Claude-App öffnen“ übergibt den Text an die Claude-App.", 12, false);
        hint.setTextColor(Color.GRAY);
        gh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String body = feedbackText();
            if (body == null) return;
            String title = etFeedback.getText().toString().trim().split("\\n")[0];
            if (title.length() > 70) title = title.substring(0, 70) + " …";
            try {
                String url = "https://github.com/" + Updater.REPO + "/issues/new?labels=vorschlag&title="
                        + java.net.URLEncoder.encode("Vorschlag: " + title, "UTF-8")
                        + "&body=" + java.net.URLEncoder.encode(body, "UTF-8");
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) { alert("Nicht möglich", String.valueOf(e.getMessage())); }
        }});
        app.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            String body = feedbackText();
            if (body == null) return;
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, "Verbesserungsvorschlag für meine App „Splitt-Tool“ "
                    + "(Quellcode: github.com/" + Updater.REPO + "):\n\n" + body);
            i.setPackage("com.anthropic.claude");
            try { startActivity(i); }
            catch (Exception e) {
                i.setPackage(null);
                startActivity(Intent.createChooser(i, "Vorschlag senden an …"));
            }
        }});
    }

    private String feedbackText() {
        String t = etFeedback.getText().toString().trim();
        if (t.isEmpty()) { alert("Kein Text", "Bitte zuerst den Vorschlag eintippen."); return null; }
        StringBuilder b = new StringBuilder(t);
        b.append("\n\n---\nApp-Version: ").append(Updater.currentVersionName(this))
         .append("\nGerät: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
         .append(", Android ").append(android.os.Build.VERSION.RELEASE);
        if (cbLog.isChecked() && tvLog.length() > 0) {
            String log = tvLog.getText().toString();
            if (log.length() > 3000) log = "…" + log.substring(log.length() - 3000);
            b.append("\n\nProtokoll:\n```\n").append(log).append("\n```");
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ Update-Knopf oben rechts
    private TextView updateView;
    private String availableVersion = null;
    private long lastCheck = 0;

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        miUpdate = menu.add(0, 1, 0, "Update");
        miUpdate.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        updateView = new TextView(this);
        updateView.setTextSize(15);
        updateView.setTypeface(Typeface.DEFAULT_BOLD);
        updateView.setPadding(dp(14), dp(6), dp(14), dp(6));
        updateView.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (worker != null && worker.isAlive()) { alert("Bitte warten", "Erst nach dem Aufteilen aktualisieren."); return; }
            updater.check();
        }});
        LinearLayout wrap = new LinearLayout(this);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setPadding(0, 0, dp(10), 0);
        wrap.addView(updateView);
        miUpdate.setActionView(wrap);
        styleUpdateButton();
        return true;
    }

    /** Normal: dezenter Knopf. Update verfügbar: rot leuchtend (pulsierend). */
    private void styleUpdateButton() {
        if (updateView == null) return;
        updateView.clearAnimation();
        if (availableVersion == null) {
            updateView.setText("⟳ Update");
            updateView.setTextColor(Color.WHITE);
            updateView.setBackgroundColor(Color.TRANSPARENT);
        } else {
            updateView.setText("● Update " + availableVersion);
            updateView.setTextColor(Color.WHITE);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFFE53935);
            bg.setCornerRadius(dp(16));
            updateView.setBackground(bg);
            android.view.animation.AlphaAnimation pulse = new android.view.animation.AlphaAnimation(1f, 0.45f);
            pulse.setDuration(700);
            pulse.setRepeatMode(android.view.animation.Animation.REVERSE);
            pulse.setRepeatCount(android.view.animation.Animation.INFINITE);
            updateView.startAnimation(pulse);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        long now = System.currentTimeMillis();
        if (now - lastCheck < 10 * 60 * 1000L) return;   // höchstens alle 10 Minuten
        lastCheck = now;
        updater.checkSilently(new Updater.Callback() {
            public void available(String versionName, String notes, String apkUrl) {
                availableVersion = versionName;
                styleUpdateButton();
                log("Neue Version " + versionName + " verfügbar – oben rechts auf das rote „Update“ tippen.");
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == 1) {
            if (worker != null && worker.isAlive()) { alert("Bitte warten", "Erst nach dem Aufteilen aktualisieren."); return true; }
            updater.check();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
                int iN = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), iS = c.getColumnIndex(OpenableColumns.SIZE);
                if (iN >= 0 && !c.isNull(iN)) pdfName = c.getString(iN);
                if (iS >= 0 && !c.isNull(iS)) pdfSize = c.getLong(iS);
            }
        } catch (Exception ignored) {
        } finally { if (c != null) c.close(); }
        tvPdf.setText(pdfName + (pdfSize >= 0 ? "  (" + SplitCore.human(pdfSize) + ")" : ""));
        if (worker == null || !worker.isAlive()) start(true);   // Kapitel-Vorschau automatisch
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
        if (miUpdate != null) miUpdate.setEnabled(!busy);
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
        resultBox.removeAllViews();
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
            SplitCore core = new SplitCore(io, listener(), s);
            core.preview();
            final SplitCore.PreviewData pd = core.lastPreview;
            main.post(new Runnable() { public void run() {
                setBusy(false);
                bar.setProgress(1000);
                tvStatus.setText("Vorschau fertig – " + pd.parts.size() + " Teile geplant");
                renderPreview(pd);
            }});
        } catch (final Throwable e) {
            failed(e);
        } finally {
            deleteTree(tmp);
        }
    }

    /** Zeigt Kapitel und geplante Teile als Kästen im Hauptfenster. */
    private void renderPreview(SplitCore.PreviewData pd) {
        previewBody.removeAllViews();
        TextView info = label(previewBody, pdfName + " · " + pd.pages + " Seiten · " + pd.chapters.size()
                + " Kapitel\nErkannt über: " + pd.source, 14, false);
        info.setTextColor(MUTED);
        if (pd.note != null) {
            TextView rec = label(previewBody, "Empfehlung: " + pd.note.replace("\n", " · "), 12, false);
            rec.setTextColor(MUTED);
        }
        // Kapitel
        TextView kh = label(previewBody, "Kapitel", 15, true);
        kh.setPadding(0, dp(8), 0, dp(2));
        int shown = 0;
        for (String[] c : pd.chapters) {
            if (shown++ >= 200) { label(previewBody, "… und " + (pd.chapters.size() - 200) + " weitere", 13, false); break; }
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setBackground(round(BLUE_LIGHT, 0, 8));
            r.setPadding(dp(10), dp(6), dp(10), dp(6));
            TextView t = new TextView(this);
            t.setText(c[0]); t.setTextColor(TEXT); t.setTextSize(14);
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            TextView pg = new TextView(this);
            pg.setText(c[1] + "  (" + c[2] + ")"); pg.setTextColor(MUTED); pg.setTextSize(13);
            r.addView(pg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            previewBody.addView(r, lp);
        }
        // Geplante Teile
        TextView th = label(previewBody, "Geplante Teile (" + pd.parts.size() + ")", 15, true);
        th.setPadding(0, dp(12), 0, dp(2));
        for (String[] p : pd.parts) {
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setBackground(round(Color.WHITE, BLUE2, 10));
            box.setPadding(dp(12), dp(8), dp(12), dp(8));
            TextView h = new TextView(this);
            h.setText(p[0]); h.setTypeface(Typeface.DEFAULT_BOLD); h.setTextColor(BLUE); h.setTextSize(14);
            box.addView(h);
            TextView b = new TextView(this);
            b.setText(p[1]); b.setTextColor(TEXT); b.setTextSize(13);
            box.addView(b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(3), 0, dp(3));
            previewBody.addView(box, lp);
        }
        for (String w : pd.warnings) {
            TextView t = label(previewBody, "⚠ " + w, 13, false);
            t.setTextColor(0xFFB00020);
        }
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

    // ------------------------------------------------------------------ Ergebnis teilen
    private void showResults(SplitCore.Result r, AndroidIo io) {
        resultBox.removeAllViews();
        TextView h = label(resultBox, "Teile verschicken (z. B. an die Claude-App)", 18, true);
        h.setPadding(0, dp(12), 0, dp(4));
        final java.util.ArrayList<Uri> all = new java.util.ArrayList<Uri>();
        for (String f : r.files) { Uri u = io.uri(f); if (u != null) all.add(u); }
        Button bAll = button(resultBox, "Alle " + all.size() + " Teile teilen");
        bAll.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            if (all.size() > 20) alert("Hinweis", "Claude nimmt höchstens 20 Dateien pro Chat an. Bitte die Teile einzeln oder in Gruppen schicken.");
            else shareMany(all);
        }});
        // Export an Claude: alle Teile + Quellen-Karte in einem Rutsch, Projekt-Anweisung zum Einfügen
        final Uri guide = r.projectGuideFile == null ? null : io.uri(r.projectGuideFile);
        final String guideText = r.projectGuide;
        Button bClaude = button(resultBox, "An Claude senden (Teile + Quellen-Karte)");
        bClaude.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            java.util.ArrayList<Uri> list = new java.util.ArrayList<Uri>(all);
            if (guide != null) list.add(guide);
            if (list.size() > 20) { alert("Hinweis", "Claude nimmt höchstens 20 Dateien pro Chat an. Bitte die Teile einzeln oder in Gruppen schicken."); return; }
            sendToClaude(list);
        }});
        if (guideText != null) {
            Button bCopy = button(resultBox, "Projekt-Anweisung kopieren");
            bCopy.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                String t = guideText;
                int a = t.indexOf("```\n"), b = a < 0 ? -1 : t.indexOf("```", a + 4);
                if (a >= 0 && b > a) t = t.substring(a + 4, b).trim();
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Projekt-Anweisung", t));
                alert("Kopiert", "Die Projekt-Anweisung ist in der Zwischenablage.\n\nIn Claude: Projekt öffnen oder anlegen → "
                        + "„Anweisungen“ → einfügen. Dann die Teile und die Datei " + SplitCore.PROJECT_FILE
                        + " unter „Dateien“ hinzufügen. Danach steht alles auch am Rechner im selben Projekt bereit.");
            }});
        }
        for (int i = 0; i < r.files.size(); i++) {
            final Uri u = io.uri(r.files.get(i));
            if (u == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            TextView t = new TextView(this);
            t.setText(r.labels.get(i));
            t.setTextSize(13);
            row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button b = new Button(this);
            b.setText("Teilen");
            b.setAllCaps(false);
            row.addView(b);
            final String name = r.files.get(i);
            b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { shareOne(u, name); }});
            resultBox.addView(row);
        }
    }

    private void shareOne(Uri u, String name) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.putExtra(Intent.EXTRA_TITLE, name);
        i.setClipData(android.content.ClipData.newRawUri(name, u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startShare(i);
    }

    private void shareMany(java.util.ArrayList<Uri> uris) {
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("application/pdf");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        android.content.ClipData cd = android.content.ClipData.newRawUri("Teil", uris.get(0));
        for (int k = 1; k < uris.size(); k++) cd.addItem(new android.content.ClipData.Item(uris.get(k)));
        i.setClipData(cd);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startShare(i);
    }

    /** Teile direkt an die Claude-App; ist sie nicht installiert, normales Teilen-Menü. */
    private void sendToClaude(java.util.ArrayList<Uri> uris) {
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("*/*");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        android.content.ClipData cd = android.content.ClipData.newRawUri("Teil", uris.get(0));
        for (int k = 1; k < uris.size(); k++) cd.addItem(new android.content.ClipData.Item(uris.get(k)));
        i.setClipData(cd);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setPackage("com.anthropic.claude");
        try { startActivity(i); }
        catch (Exception notInstalled) { i.setPackage(null); startShare(i); }
    }

    private void startShare(Intent i) {
        try {
            Intent c = Intent.createChooser(i, "Teil senden an …");
            c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(c);
        } catch (Exception e) {
            alert("Teilen nicht möglich", String.valueOf(e.getMessage()));
        }
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
            final AndroidIo io = new AndroidIo(cr, pdfUri, dir, tmp);
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
                showResults(r, io);
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

        Uri uri(String name) { return files.get(name); }
    }
}
