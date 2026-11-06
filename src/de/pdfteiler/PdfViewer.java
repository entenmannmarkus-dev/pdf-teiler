package de.pdfteiler;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.io.RandomAccessRead;
import com.tom_roush.pdfbox.io.ScratchFile;
import com.tom_roush.pdfbox.pdfparser.PDFParser;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PDF durchblättern: Seite als Bild, Knöpfe „Zurück/Weiter“ und eine Leiste rechts.
 * Die Leiste springt zu jeder Stelle des Dokuments; Striche markieren den Anfang jedes Kapitels.
 * Alle PDF-Zugriffe laufen nacheinander auf einem Hintergrund-Thread (PDFBox ist nicht parallel-sicher).
 */
final class PdfViewer extends LinearLayout {
    static final int DPI = 100;          // Lesbar auf dem Tablet, Speicher bleibt beherrschbar
    static final int CACHE = 4;          // zuletzt gezeigte Seiten im Speicher

    private final ImageView img;
    private final TextView label;
    private final NavBar bar;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Bitmap> cache = new LinkedHashMap<Integer, Bitmap>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Bitmap> e) { return size() > CACHE; }
    };

    // nur auf dem Hintergrund-Thread benutzen
    private PDDocument doc;
    private PDFRenderer renderer;
    private ScratchFile scratch;

    // nur auf dem Haupt-Thread benutzen
    private int pages = 0;
    private int current = 0;

    PdfViewer(Context c) {
        super(c);
        img = new ImageView(c);
        label = new TextView(c);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.TOP);

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(VERTICAL);
        addView(left, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));

        FrameLayout frame = new FrameLayout(c);
        frame.setBackgroundColor(Color.WHITE);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        img.setAdjustViewBounds(false);
        frame.addView(img, new FrameLayout.LayoutParams(-1, dp(460)));
        label.setText("Wähle eine PDF – sie erscheint hier zum Durchblättern.");
        label.setTextColor(MainActivity.MUTED);
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(8), dp(8), dp(8), dp(8));
        frame.addView(label, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        left.addView(frame, new LayoutParams(-1, -2));

        LinearLayout nav = new LinearLayout(c);
        nav.setOrientation(HORIZONTAL);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setPadding(0, dp(6), 0, 0);
        left.addView(nav, new LayoutParams(-1, -2));
        Button prev = button(c, "‹ Zurück");
        Button next = button(c, "Weiter ›");
        TextView pos = new TextView(c);
        pos.setGravity(Gravity.CENTER);
        pos.setTextColor(MainActivity.TEXT);
        pos.setTextSize(14);
        pos.setTag("pos");
        nav.addView(prev, new LayoutParams(0, -2, 1));
        nav.addView(pos, new LayoutParams(0, -2, 1.2f));
        nav.addView(next, new LayoutParams(0, -2, 1));
        prev.setContentDescription("Vorige Seite");
        next.setContentDescription("Nächste Seite");
        prev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { jump(current - 1); } });
        next.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { jump(current + 1); } });

        bar = new NavBar(c);
        bar.setListener(new NavBar.Listener() { public void onPage(int p) { jump(p); } });
        addView(bar, new LayoutParams(dp(48), -1));
    }

    private Button button(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        return b;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ------------------------------------------------------------------ Öffnen / Schließen
    /** Öffnet die PDF im Hintergrund. Die Seite 1 erscheint, sobald sie gerendert ist. */
    void open(final ContentResolver cr, final Uri u, final File tmpDir) {
        worker.execute(new Runnable() { public void run() {
            try {
                closeDocument();
                ParcelFileDescriptor pfd = cr.openFileDescriptor(u, "r");
                if (pfd == null) throw new java.io.IOException("Datei nicht lesbar");
                InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
                RandomAccessRead src = new ChannelRead(((FileInputStream) in).getChannel(), in);
                scratch = new ScratchFile(MemoryUsageSetting.setupMixed(48L * 1024 * 1024).setTempDir(tmpDir));
                PDFParser parser = new PDFParser(src, "", scratch);
                parser.parse();
                doc = parser.getPDDocument();
                renderer = new PDFRenderer(doc);
                final int n = doc.getNumberOfPages();
                main.post(new Runnable() { public void run() { showDocument(n); } });
            } catch (final Exception e) {
                closeDocument();
                main.post(new Runnable() { public void run() { showError(String.valueOf(e.getMessage())); } });
            }
        }});
    }

    /** Gibt die Datei frei (z. B. beim Schließen der App oder bei einer neuen PDF). */
    void close() {
        worker.execute(new Runnable() { public void run() { closeDocument(); } });
        pages = 0;
        cache.clear();
        img.setImageBitmap(null);
        bar.setPages(1);
        label.setVisibility(VISIBLE);
        label.setText("Wähle eine PDF – sie erscheint hier zum Durchblättern.");
    }

    private void closeDocument() {
        renderer = null;
        if (doc != null) {
            try { doc.close(); } catch (Exception ignored) { }
            doc = null;
        }
        if (scratch != null) {
            try { scratch.close(); } catch (Exception ignored) { }
            scratch = null;
        }
    }

    private void showDocument(int n) {
        pages = n;
        current = 0;
        cache.clear();
        bar.setPages(n);
        label.setVisibility(GONE);
        show(0);
    }

    private void showError(String msg) {
        pages = 0;
        img.setImageBitmap(null);
        label.setVisibility(VISIBLE);
        label.setText("Die Vorschau konnte nicht geladen werden.\n" + msg);
    }

    /** Striche in der Leiste an den Kapitelanfängen (Seitennummern ab 1). */
    void setChapterPages(int[] startPages) {
        int[] zero = new int[startPages.length];
        for (int i = 0; i < startPages.length; i++) zero[i] = Math.max(0, startPages[i] - 1);
        bar.setTicks(zero);
    }


    // ------------------------------------------------------------------ Blättern
    private void jump(int page) {
        if (pages <= 0) return;
        int p = Math.max(0, Math.min(pages - 1, page));
        if (p == current && img.getDrawable() != null) return;
        show(p);
    }

    private void show(final int page) {
        current = page;
        bar.setValue(page);
        updatePos();
        final int wanted = page;
        worker.execute(new Runnable() { public void run() {
            final Bitmap b;
            try {
                Bitmap cached = cache.get(wanted);
                if (cached != null) b = cached;
                else {
                    if (renderer == null) return;
                    b = renderer.renderImageWithDPI(wanted, DPI);
                    cache.put(wanted, b);
                }
            } catch (final Exception e) {
                main.post(new Runnable() { public void run() { showError("Seite " + (wanted + 1) + ": " + e.getMessage()); } });
                return;
            }
            main.post(new Runnable() { public void run() {
                if (wanted == current) img.setImageBitmap(b);   // nur die zuletzt gewählte Seite zeigen
            }});
        }});
    }

    private void updatePos() {
        View v = findViewWithTag("pos");
        if (v instanceof TextView) ((TextView) v).setText(pages > 0 ? "Seite " + (current + 1) + " / " + pages : "");
    }

    // ------------------------------------------------------------------ Leiste
    /** Senkrechte Leiste: Daumen zeigt die Stelle, Ziehen oder Tippen springt. */
    static final class NavBar extends View {
        interface Listener { void onPage(int page); }

        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private Listener listener;
        private int pages = 1, value = 0;
        private int[] ticks = new int[0];
        private int lastSent = -1;
        private final float density;

        NavBar(Context c) {
            super(c);
            density = getResources().getDisplayMetrics().density;
            track.setColor(MainActivity.BORDER);
            fill.setColor(MainActivity.BLUE2);
            tick.setColor(MainActivity.MUTED);
            thumb.setColor(MainActivity.BLUE);
            setContentDescription("Seiten-Leiste: wischen oder tippen, um zu blättern");
        }

        void setListener(Listener l) { listener = l; }

        void setPages(int n) { pages = Math.max(1, n); value = Math.min(value, pages - 1); invalidate(); }

        void setValue(int v) { value = v; invalidate(); }

        void setTicks(int[] t) { ticks = t == null ? new int[0] : t; invalidate(); }

        private float pad() { return 14 * density; }

        private float yFor(int page) {
            float top = pad(), bottom = getHeight() - pad();
            if (pages <= 1) return top;
            return top + (bottom - top) * page / (pages - 1);
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float w = 6 * density;
            rect.set(cx - w / 2, pad(), cx + w / 2, getHeight() - pad());
            canvas.drawRoundRect(rect, w, w, track);
            float yv = yFor(value);
            rect.set(cx - w / 2, pad(), cx + w / 2, yv);
            canvas.drawRoundRect(rect, w, w, fill);
            for (int t : ticks) {
                if (t < 0 || t >= pages) continue;
                float y = yFor(t);
                canvas.drawRect(cx - 12 * density, y - 0.5f * density, cx + 12 * density, y + 0.5f * density, tick);
            }
            canvas.drawCircle(cx, yv, 9 * density, thumb);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                float top = pad(), bottom = getHeight() - pad();
                float f = bottom > top ? (e.getY() - top) / (bottom - top) : 0f;
                f = Math.max(0f, Math.min(1f, f));
                int p = Math.round(f * (pages - 1));
                if (p != lastSent) {
                    lastSent = p;
                    value = p;
                    invalidate();
                    if (listener != null) listener.onPage(p);
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) lastSent = -1;
            return true;
        }
    }
}
