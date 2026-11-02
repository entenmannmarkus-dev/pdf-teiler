package de.pdfteiler;

import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSObject;
import com.tom_roush.pdfbox.cos.COSStream;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.io.RandomAccessRead;
import com.tom_roush.pdfbox.io.ScratchFile;
import com.tom_roush.pdfbox.pdfparser.PDFParser;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Kern des PDF-Splitters (ohne Android-Abhängigkeiten).
 *
 * Teilt eine PDF kapitelweise in Teile, die Claudes Upload-Grenzen einhalten.
 * Seiten werden 1:1 übernommen (keine Neukomprimierung, kein Umschreiben);
 * jeder geschriebene Teil wird anschließend bitgenau mit dem Original verglichen.
 */
public final class SplitCore {

    // ------------------------------------------------------------------ Schnittstellen
    public interface Io {
        /** Liefert einen frischen Lesezugriff auf die Quell-PDF. */
        RandomAccessRead openSource() throws IOException;
        OutputStream createOutput(String name, String mime) throws IOException;
        RandomAccessRead openOutput(String name) throws IOException;
        long outputSize(String name) throws IOException;
        void deleteOutput(String name) throws IOException;
        File tempDir();
    }

    public interface Listener {
        void log(String msg);
        void progress(int done, int total);
        boolean cancelled();
    }

    public static final class Settings {
        public double maxMb = 30;
        public int maxPages = 100;
        public double safety = 0.05;
        public int chapterLevel = 0;          // 0 = automatisch
        public boolean onePerPart = false;
        public boolean detectHeadings = true;
        public boolean verify = true;
        public String password = "";
        public String baseName = "Dokument";

        long maxBytes() { return (long) (maxMb * 1024 * 1024 * (1 - safety)); }
        long hardBytes() { return (long) (maxMb * 1024 * 1024); }
    }

    public static final class CancelledException extends IOException {
        CancelledException() { super("Abgebrochen"); }
    }

    // ------------------------------------------------------------------ Datenklassen
    static final class Chapter {
        String title; int start, end; String parent;
        List<Chapter> children = new ArrayList<Chapter>();
        Chapter(String t, int s) { title = t; start = s; end = s; }
        int pages() { return end - start + 1; }
    }

    static final class Unit {
        String title; int start, end; List<String> path; int piece, pieces; long size;
        Unit(String t, int s, int e, List<String> p) { title = t; start = s; end = e; path = p; }
        int pages() { return end - start + 1; }
        String label() { return pieces > 1 ? title + " (Abschnitt " + piece + "/" + pieces + ")" : title; }
        String fullLabel() {
            StringBuilder b = new StringBuilder();
            for (String s : path) { if (b.length() > 0) b.append(" › "); b.append(s); }
            if (pieces > 1) b.append(" (Abschnitt ").append(piece).append('/').append(pieces).append(')');
            return b.toString();
        }
    }

    static final class Part {
        List<Unit> units = new ArrayList<Unit>();
        String file; long size; Boolean verified;
        int start() { return units.get(0).start; }
        int end() { return units.get(units.size() - 1).end; }
        int pages() { return end() - start() + 1; }
    }

    public static final class Result {
        public int parts, pages, chapters;
        public List<String> warnings = new ArrayList<String>();
        public List<String> errors = new ArrayList<String>();
        public String overview;
        public List<String> files = new ArrayList<String>();
        public List<String> labels = new ArrayList<String>();
    }

    // ------------------------------------------------------------------ Felder
    private final Io io;
    private final Listener ui;
    private final Settings s;
    private PDDocument doc;
    private ScratchFile scratch;
    private final List<COSDictionary> pages = new ArrayList<COSDictionary>();
    private final Map<COSDictionary, Integer> pageIndex = new IdentityHashMap<COSDictionary, Integer>();
    private final Map<Object, Long> objSize = new HashMap<Object, Long>();
    private final List<Object[]> pageObjs = new ArrayList<Object[]>();
    private double sizeFactor = 1.0;
    private String chapterSource = "";
    private String planNote = null;
    private String docTitle;
    private final Result result = new Result();

    public SplitCore(Io io, Listener ui, Settings s) {
        this.io = io; this.ui = ui; this.s = s;
    }

    // ================================================================== Ablauf
    /** Vorschau: ermittelt Kapitel und geplante Teile, schreibt aber nichts. */
    public String preview() throws IOException {
        try {
            open();
            int n = pages.size();
            ui.log("Vorschau: " + n + " Seiten werden analysiert …");
            indexPages();
            List<Chapter> chapters = findChapters();
            List<Unit> units = buildUnits(chapters);
            List<Part> parts = pack(units);
            StringBuilder b = new StringBuilder();
            b.append(n).append(" Seiten · ").append(chapters.size()).append(" Kapitel\n")
             .append("Kapitel erkannt über: ").append(chapterSource).append("\n");
            if (planNote != null) b.append("\nEMPFEHLUNG DER AUFTEILUNG\n").append(planNote).append('\n');
            b.append('\n');
            b.append("KAPITEL\n");
            for (Chapter c : chapters) {
                b.append("• ");
                if (c.parent != null) b.append(c.parent).append(" › ");
                b.append(c.title).append("  (S. ").append(c.start + 1).append('–').append(c.end + 1)
                 .append(", ").append(c.pages()).append(" S.)\n");
            }
            b.append("\nGEPLANTE TEILE (").append(parts.size()).append(")\n");
            for (int i = 0; i < parts.size(); i++) {
                Part p = parts.get(i);
                b.append("\nTeil ").append(i + 1).append(": S. ").append(p.start() + 1).append('–').append(p.end() + 1)
                 .append(" (").append(p.pages()).append(" S., ca. ").append(human(measure(p.start(), p.end()))).append(")\n");
                for (String l : chapterLines(p)) b.append("   ").append(l).append('\n');
            }
            if (!result.warnings.isEmpty()) {
                b.append("\nHINWEISE\n");
                for (String w : result.warnings) b.append("⚠ ").append(w).append('\n');
            }
            ui.log("Vorschau fertig: " + parts.size() + " Teile geplant.");
            return b.toString();
        } finally {
            close();
        }
    }

    public Result run() throws IOException {
        long t0 = System.currentTimeMillis();
        try {
            open();
            int n = pages.size();
            result.pages = n;
            ui.log("Quelle: " + n + " Seiten");
            ui.log(String.format(Locale.GERMANY, "Grenzen pro Teil: %.0f MB (Ziel ≤ %s), %d Seiten",
                    s.maxMb, human(s.maxBytes()), s.maxPages));

            ui.log("Analysiere Seiten (Bilder, Schriften, Inhalte) …");
            indexPages();
            List<Chapter> chapters = findChapters();
            result.chapters = chapters.size();
            ui.log("Kapitel erkannt über: " + chapterSource + " – " + chapters.size() + " Kapitel");

            List<Part> parts = null;
            for (int attempt = 0; attempt < 6; attempt++) {
                result.warnings.clear();
                result.errors.clear();
                List<Unit> units = buildUnits(chapters);
                parts = pack(units);
                double ratio = writeParts(parts, attempt < 5);
                if (ratio == 0) break;
                sizeFactor *= ratio * 1.03;
                ui.log(String.format(Locale.GERMANY, "Ein Teil war größer als berechnet – teile neu auf (Faktor %.2f) …", sizeFactor));
                for (Part p : parts) if (p.file != null) io.deleteOutput(p.file);
            }
            // Vollständigkeit
            int expected = 0;
            for (Part p : parts) {
                if (p.start() != expected) result.errors.add("Seitenlücke/Überlappung vor Seite " + (p.start() + 1));
                expected = p.end() + 1;
                if (p.size > s.hardBytes())
                    result.warnings.add(p.file + " ist " + human(p.size) + " groß (einzelne Seite größer als die Grenze).");
            }
            if (expected != n) result.errors.add("Nicht alle Seiten übernommen (" + expected + " von " + n + ")");
            result.parts = parts.size();
            for (int i = 0; i < parts.size(); i++) {
                Part p = parts.get(i);
                result.files.add(p.file);
                result.labels.add("Teil " + (i + 1) + " · S. " + (p.start() + 1) + "–" + (p.end() + 1) + " · " + human(p.size)
                        + "\n" + shortLabel(p));
            }
            writeOverview(parts, chapters, (System.currentTimeMillis() - t0) / 1000);
            ui.log("Fertig: " + parts.size() + " Teile (" + (System.currentTimeMillis() - t0) / 1000 + " s)");
            return result;
        } finally {
            close();
        }
    }

    // ================================================================== Öffnen
    private void open() throws IOException {
        File tmp = io.tempDir();
        scratch = new ScratchFile(MemoryUsageSetting.setupMixed(48L * 1024 * 1024).setTempDir(tmp));
        PDFParser parser = new PDFParser(io.openSource(), s.password == null ? "" : s.password, scratch);
        try {
            parser.parse();
        } catch (IOException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (m.contains("password") || m.contains("encrypt"))
                throw new IOException("Die PDF ist verschlüsselt/passwortgeschützt. Bitte Passwort eingeben.");
            throw e;
        }
        doc = parser.getPDDocument();
        for (PDPage p : doc.getPages()) {
            COSDictionary d = p.getCOSObject();
            pageIndex.put(d, pages.size());
            pages.add(d);
        }
        PDDocumentInformation info = doc.getDocumentInformation();
        String t = info == null ? null : clean(info.getTitle());
        docTitle = (t == null || t.length() < 3 || t.equalsIgnoreCase("untitled")) ? s.baseName : t;
    }

    private void close() {
        try { if (doc != null) doc.close(); } catch (IOException ignored) { }
        try { if (scratch != null) scratch.close(); } catch (IOException ignored) { }
        doc = null;
    }

    private void checkCancel() throws CancelledException {
        if (ui.cancelled()) throw new CancelledException();
    }

    // ================================================================== Kapitel
    private List<Chapter> findChapters() throws IOException {
        List<Chapter> ch = chaptersFromOutline();
        if (!ch.isEmpty()) { chapterSource = "Lesezeichen (Inhaltsverzeichnis der PDF)"; return ch; }
        if (s.detectHeadings) {
            ui.log("Keine Lesezeichen – suche Überschriften im Text …");
            ch = chaptersFromHeadings();
            if (!ch.isEmpty()) { chapterSource = "erkannte Überschriften im Text"; return ch; }
        }
        chapterSource = "gleich große Seitenblöcke (keine Kapitel erkennbar)";
        Chapter all = new Chapter(docTitle, 0);
        all.end = pages.size() - 1;
        return new ArrayList<Chapter>(Collections.singletonList(all));
    }

    private List<Chapter> chaptersFromOutline() throws IOException {
        try {
            PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
            if (outline == null) return Collections.emptyList();
            List<Chapter> tree = buildTree(outline.children(), 0);
            if (tree.isEmpty()) return tree;
            int level = s.chapterLevel;
            if (level <= 0) level = bestLevel(outline, depth(tree));
            List<Chapter> flat = flatten(tree, level);
            return assignRanges(flat, 0, pages.size() - 1);
        } catch (CancelledException e) {
            throw e;
        } catch (Exception e) {
            ui.log("Lesezeichen nicht lesbar: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static int depth(List<Chapter> t) {
        int d = 0;
        for (Chapter c : t) d = Math.max(d, 1 + depth(c.children));
        return d;
    }

    /** Probiert die Lesezeichen-Ebenen durch und wählt die beste Aufteilung:
     *  möglichst wenige mitten im Kapitel geteilte Kapitel, dann möglichst wenige Teile. */
    private int bestLevel(PDDocumentOutline outline, int maxDepth) throws IOException {
        int best = 1;
        long bestScore = Long.MAX_VALUE;
        int tries = Math.min(4, Math.max(1, maxDepth));
        StringBuilder note = new StringBuilder();
        for (int lvl = 1; lvl <= tries; lvl++) {
            List<Chapter> t = buildTree(outline.children(), 0);
            List<Chapter> ch = assignRanges(flatten(t, lvl), 0, pages.size() - 1);
            List<String> saved = new ArrayList<String>(result.warnings);
            List<Part> parts = pack(buildUnits(ch));
            result.warnings.clear();
            result.warnings.addAll(saved);
            Set<List<String>> cut = new HashSet<List<String>>();
            for (Part p : parts) for (Unit u : p.units) if (u.pieces > 1) cut.add(u.path);
            long score = cut.size() * 1000L + parts.size() + (ch.size() <= 2 && lvl < tries ? 500 : 0);
            note.append("Ebene ").append(lvl).append(": ").append(ch.size()).append(" Kapitel → ")
                .append(parts.size()).append(" Teile, ").append(cut.size()).append(" Kapitel geteilt\n");
            if (score < bestScore) { bestScore = score; best = lvl; }
        }
        planNote = note.toString() + "→ gewählt: Ebene " + best;
        ui.log("Automatische Kapitelwahl:\n" + planNote);
        return best;
    }

    private List<Chapter> buildTree(Iterable<PDOutlineItem> items, int depth) {
        List<Chapter> out = new ArrayList<Chapter>();
        if (depth > 12) return out;
        for (PDOutlineItem it : items) {
            int page = -1;
            try {
                PDPage p = it.findDestinationPage(doc);
                if (p != null) { Integer i = pageIndex.get(p.getCOSObject()); if (i != null) page = i; }
            } catch (Exception ignored) { }
            List<Chapter> kids = buildTree(it.children(), depth + 1);
            if (page < 0) { out.addAll(kids); continue; }
            Chapter c = new Chapter(clean(it.getTitle()), page);
            c.children = kids;
            out.add(c);
        }
        return out;
    }

    private List<Chapter> flatten(List<Chapter> nodes, int level) {
        if (level <= 1) return nodes;
        List<Chapter> out = new ArrayList<Chapter>();
        for (Chapter n : nodes) {
            if (!n.children.isEmpty()) {
                List<Chapter> kids = flatten(n.children, level - 1);
                int first = Integer.MAX_VALUE;
                for (Chapter k : kids) first = Math.min(first, k.start);
                if (first > n.start) out.add(new Chapter(n.title, n.start));
                for (Chapter k : kids) { if (k.parent == null) k.parent = n.title; }
                out.addAll(kids);
            } else out.add(n);
        }
        return out;
    }

    private List<Chapter> assignRanges(List<Chapter> nodes, int lo, int hi) {
        List<Chapter> in = new ArrayList<Chapter>();
        for (Chapter c : nodes) if (c.start >= lo && c.start <= hi) in.add(c);
        Collections.sort(in, new java.util.Comparator<Chapter>() {
            public int compare(Chapter a, Chapter b) { return a.start < b.start ? -1 : (a.start == b.start ? 0 : 1); }
        });
        List<Chapter> merged = new ArrayList<Chapter>();
        for (Chapter c : in) {
            if (!merged.isEmpty() && merged.get(merged.size() - 1).start == c.start) {
                Chapter p = merged.get(merged.size() - 1);
                p.title = p.title + " / " + c.title;
                p.children.addAll(c.children);
            } else merged.add(c);
        }
        List<Chapter> res = new ArrayList<Chapter>();
        if (!merged.isEmpty() && merged.get(0).start > lo) {
            Chapter v = new Chapter("Vorspann (Seiten vor dem ersten Kapitel)", lo);
            v.end = merged.get(0).start - 1;
            res.add(v);
        }
        for (int i = 0; i < merged.size(); i++) {
            Chapter c = merged.get(i);
            c.end = i + 1 < merged.size() ? merged.get(i + 1).start - 1 : hi;
            if (!c.children.isEmpty()) c.children = assignRanges(c.children, c.start, c.end);
            res.add(c);
        }
        return res;
    }

    private static final Pattern[] HEADINGS = {
        Pattern.compile("^(kapitel|chapter|teil|part|abschnitt|section|buch|book)\\s+([0-9]+|[IVXLC]+|[a-zäöü]+)\\b.{0,80}$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
        Pattern.compile("^([0-9]{1,2})\\.?\\s{1,3}[A-ZÄÖÜ][^.!?]{2,70}$"),
        Pattern.compile("^(einleitung|vorwort|einführung|anhang|literaturverzeichnis|glossar|register|zusammenfassung|fazit|introduction|preface|appendix|bibliography|index|conclusion)\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
    };

    /** Sammelt pro Seite die ersten Zeilen mit ihrer Schriftgröße. */
    static final class LineStripper extends PDFTextStripper {
        final List<String> texts = new ArrayList<String>();
        final List<Float> sizes = new ArrayList<Float>();
        final List<Float> allSizes = new ArrayList<Float>();
        private final StringBuilder cur = new StringBuilder();
        private float curMax = 0;
        LineStripper() throws IOException { super(); setSortByPosition(true); }
        void reset() { texts.clear(); sizes.clear(); cur.setLength(0); curMax = 0; }
        @Override protected void writeString(String text, List<com.tom_roush.pdfbox.text.TextPosition> tps) throws IOException {
            if (cur.length() > 0) cur.append(' ');
            cur.append(text);
            for (com.tom_roush.pdfbox.text.TextPosition tp : tps) {
                float f = tp.getFontSizeInPt();
                if (f <= 1.01f) f = tp.getHeightDir();
                if (f > curMax) curMax = f;
                if (!Character.isWhitespace(tp.getUnicode().isEmpty() ? ' ' : tp.getUnicode().charAt(0))) allSizes.add(f);
            }
        }
        private void flush() {
            String t = cur.toString().replaceAll("\\s+", " ").trim();
            if (!t.isEmpty() && texts.size() < 6) { texts.add(t); sizes.add(curMax); }
            cur.setLength(0); curMax = 0;
        }
        @Override protected void writeLineSeparator() { flush(); }
        @Override protected void writeParagraphEnd() { flush(); }
        @Override protected void writePageEnd() { flush(); }
        @Override protected void writeWordSeparator() { }
        @Override protected void writeString(String text) { }
    }

    private static float median(List<Float> v) {
        if (v.isEmpty()) return 0;
        List<Float> c = new ArrayList<Float>(v);
        Collections.sort(c);
        return c.get(c.size() / 2);
    }

    /**
     * Automatische Kapitelerkennung ohne Lesezeichen:
     * 1. typische Überschriften („Kapitel 3 …“, „2 Methoden“, „Einleitung“ …)
     * 2. deutlich größere Schrift oben auf der Seite als der Fließtext
     * Wiederkehrende Kopfzeilen werden ausgefiltert. Titel stammen immer wörtlich aus der PDF.
     */
    private List<Chapter> chaptersFromHeadings() throws IOException {
        int n = pages.size();
        LineStripper st = new LineStripper();
        List<List<String>> pageTexts = new ArrayList<List<String>>();
        List<List<Float>> pageSizes = new ArrayList<List<Float>>();
        List<Float> sample = new ArrayList<Float>();
        int empty = 0;
        for (int i = 0; i < n; i++) {
            checkCancel();
            if (i % 25 == 0) ui.progress(i, n);
            st.reset();
            st.allSizes.clear();
            try {
                st.setStartPage(i + 1); st.setEndPage(i + 1);
                st.getText(doc);
            } catch (Exception e) { /* Seite ohne lesbaren Text */ }
            pageTexts.add(new ArrayList<String>(st.texts));
            pageSizes.add(new ArrayList<Float>(st.sizes));
            if (sample.size() < 200000) sample.addAll(st.allSizes);
            if (st.texts.isEmpty()) {
                empty++;
                if (empty >= 30 && empty == i + 1) {
                    ui.log("  Kein Text gefunden (gescanntes Buch?) – nutze gleich große Seitenblöcke.");
                    return Collections.emptyList();
                }
            }
        }
        float body = median(sample);
        // Kopf-/Fußzeilen: Texte (ohne Ziffern), die auf vielen Seiten vorkommen
        Map<String, Integer> freq = new HashMap<String, Integer>();
        for (List<String> lt : pageTexts) {
            Set<String> seenOnPage = new HashSet<String>();
            for (String t : lt) seenOnPage.add(norm(t));
            for (String k : seenOnPage) { Integer c = freq.get(k); freq.put(k, c == null ? 1 : c + 1); }
        }
        int repeatLimit = Math.max(4, n / 20);
        float[] factors = {1.25f, 1.5f, 1.8f};
        List<Chapter> best = Collections.emptyList();
        for (float factor : factors) {
            List<Chapter> found = new ArrayList<Chapter>();
            for (int i = 0; i < n; i++) {
                List<String> lt = pageTexts.get(i);
                List<Float> ls = pageSizes.get(i);
                for (int j = 0; j < lt.size() && j < 5; j++) {
                    String t = lt.get(j);
                    Integer fq = freq.get(norm(t));
                    boolean numbered = HEADINGS[0].matcher(t).matches();  // „Kapitel 7 …“ ist nie Kopfzeile
                    if (!numbered && fq != null && fq > repeatLimit) continue;   // Kopf-/Fußzeile
                    if (t.length() < 2 || t.length() > 90 || t.matches("[0-9 .,:–-]+")) continue;
                    boolean pattern = false;
                    for (Pattern p : HEADINGS) if (p.matcher(t).matches()) { pattern = true; break; }
                    boolean big = body > 0 && ls.get(j) >= body * factor;
                    if (pattern || big) {
                        String title = t;
                        // „Kapitel 3“ + nächste große Zeile „Die Reise“ zusammenfassen
                        if (j + 1 < lt.size() && t.length() < 20 && body > 0 && ls.get(j + 1) >= body * factor
                                && lt.get(j + 1).length() <= 80)
                            title = t + ": " + lt.get(j + 1);
                        found.add(new Chapter(clean(title), i));
                        break;
                    }
                }
            }
            // plausibel: mind. 2 Kapitel und nicht auf fast jeder Seite eine "Überschrift"
            if (found.size() >= 2 && found.size() <= Math.max(3, n * 0.35)) { best = found; break; }
            if (found.size() >= 2 && best.isEmpty()) best = found.size() <= n * 0.6 ? found : best;
        }
        if (best.size() < 2) return Collections.emptyList();
        ui.log("  " + best.size() + " Überschriften erkannt (Fließtext ≈ " + Math.round(body) + " pt).");
        return assignRanges(best, 0, n - 1);
    }

    private static String norm(String t) {
        return t.toLowerCase(Locale.ROOT).replaceAll("\\b[0-9]+\\b", "#").replaceAll("\\s+", " ").trim();
    }

    // ================================================================== Objektgraph
    private static final Set<COSName> SKIP = new HashSet<COSName>(Arrays.asList(
        COSName.PARENT, COSName.P, COSName.DEST, COSName.D, COSName.A, COSName.NEXT, COSName.PREV,
        COSName.FIRST, COSName.LAST, COSName.B, COSName.getPDFName("StructParent"),
        COSName.STRUCT_PARENTS, COSName.getPDFName("Popup"), COSName.getPDFName("IRT")));

    interface Visit { boolean visit(Object key, COSBase obj) throws IOException; }

    private static Object keyOf(COSObject o, COSBase resolved) {
        if (o.getObjectNumber() > 0) return o.getObjectNumber() * 65536L + o.getGenerationNumber();
        return resolved; // Fallback: Identität
    }

    static void walk(COSBase start, Visit v) throws IOException {
        ArrayList<COSBase> stack = new ArrayList<COSBase>();
        Set<COSBase> seenDirect = Collections.newSetFromMap(new IdentityHashMap<COSBase, Boolean>());
        stack.add(start);
        while (!stack.isEmpty()) {
            COSBase o = stack.remove(stack.size() - 1);
            if (o == null) continue;
            if (o instanceof COSObject) {
                COSObject ref = (COSObject) o;
                COSBase res = ref.getObject();
                if (res == null) continue;
                if (!v.visit(keyOf(ref, res), res)) continue;
                o = res;
            } else if (o instanceof COSDictionary || o instanceof COSArray) {
                if (!seenDirect.add(o)) continue;
                if (o instanceof COSStream && !v.visit(o, o)) continue; // direkter Stream
            }
            if (o instanceof COSDictionary) {
                for (Map.Entry<COSName, COSBase> e : ((COSDictionary) o).entrySet())
                    if (!SKIP.contains(e.getKey())) stack.add(e.getValue());
            } else if (o instanceof COSArray) {
                COSArray a = (COSArray) o;
                for (int i = 0; i < a.size(); i++) stack.add(a.get(i));
            }
        }
    }

    private static long sizeOf(COSBase b) {
        if (b instanceof COSStream) return ((COSStream) b).getLength() + 40L * ((COSStream) b).size() + 60;
        if (b instanceof COSDictionary) return 40L * ((COSDictionary) b).size() + 40;
        if (b instanceof COSArray) return 12L * ((COSArray) b).size() + 40;
        return 40;
    }

    private static final COSName[] PAGE_KEYS = { COSName.CONTENTS, COSName.RESOURCES, COSName.ANNOTS,
            COSName.getPDFName("Thumb"), COSName.GROUP };

    private void indexPages() throws IOException {
        int n = pages.size();
        for (int i = 0; i < n; i++) {
            checkCancel();
            if (i % 25 == 0) ui.progress(i, n);
            final List<Object> refs = new ArrayList<Object>();
            final Set<Object> seen = new HashSet<Object>();
            Visit v = new Visit() {
                public boolean visit(Object key, COSBase obj) {
                    if (!seen.add(key)) return false;
                    refs.add(key);
                    if (!objSize.containsKey(key)) objSize.put(key, sizeOf(obj));
                    return true;
                }
            };
            COSDictionary page = pages.get(i);
            for (COSName k : PAGE_KEYS) {
                COSBase item = page.getItem(k);
                if (item == null && k == COSName.RESOURCES) item = inherited(page, k);
                if (item != null) walk(item, v);
            }
            pageObjs.add(refs.toArray());
        }
        ui.progress(n, n);
    }

    private static COSBase inherited(COSDictionary page, COSName key) {
        COSDictionary p = page;
        for (int depth = 0; p != null && depth < 64; depth++) {
            COSBase v = p.getItem(key);
            if (v != null) return v;
            COSBase par = p.getDictionaryObject(COSName.PARENT);
            p = par instanceof COSDictionary ? (COSDictionary) par : null;
        }
        return null;
    }

    long measure(int a, int b) {
        Set<Object> objs = new HashSet<Object>();
        for (int p = a; p <= b; p++) objs.addAll(Arrays.asList(pageObjs.get(p)));
        long sum = 2000 + 300L * (b - a + 1);
        for (Object o : objs) sum += objSize.get(o);
        return (long) (sum * sizeFactor);
    }

    // ================================================================== Einheiten + Packen
    private List<Unit> buildUnits(List<Chapter> chapters) throws IOException {
        List<Unit> units = new ArrayList<Unit>();
        for (Chapter c : chapters) {
            List<String> path = new ArrayList<String>();
            if (c.parent != null) path.add(c.parent);
            path.add(c.title);
            units.addAll(splitChapter(c, path));
        }
        return units;
    }

    private boolean fits(int a, int b) {
        return b - a + 1 <= s.maxPages && measure(a, b) <= s.maxBytes();
    }

    private List<Unit> splitChapter(Chapter c, List<String> path) throws IOException {
        checkCancel();
        if (fits(c.start, c.end)) {
            Unit u = new Unit(c.title, c.start, c.end, path);
            u.size = measure(c.start, c.end);
            return Collections.singletonList(u);
        }
        if (c.children.size() > 1) {
            List<Unit> out = new ArrayList<Unit>();
            int first = c.children.get(0).start;
            if (first > c.start) {
                Chapter head = new Chapter(c.title, c.start); head.end = first - 1;
                out.addAll(splitChapter(head, path));
            }
            for (Chapter k : c.children) {
                List<String> p2 = new ArrayList<String>(path); p2.add(k.title);
                out.addAll(splitChapter(k, p2));
            }
            return out;
        }
        List<int[]> pieces = splitPages(c.start, c.end);
        List<Unit> out = new ArrayList<Unit>();
        for (int i = 0; i < pieces.size(); i++) {
            int[] pc = pieces.get(i);
            Unit u = new Unit(c.title, pc[0], pc[1], path);
            u.piece = i + 1; u.pieces = pieces.size(); u.size = measure(pc[0], pc[1]);
            out.add(u);
        }
        return out;
    }

    private List<int[]> splitPages(int start, int end) {
        List<int[]> greedy = new ArrayList<int[]>();
        int a = start;
        while (a <= end) {
            if (!fits(a, a)) {
                String msg = "Seite " + (a + 1) + " allein ist " + human(measure(a, a)) + " groß und überschreitet die Grenze. "
                        + "Sie wird unverändert als eigener Teil gespeichert – verkleinern würde den Inhalt verändern.";
                ui.log("  WARNUNG: " + msg);
                result.warnings.add(msg);
                greedy.add(new int[]{a, a}); a++; continue;
            }
            int b = a;
            while (b < end && fits(a, b + 1)) b++;
            greedy.add(new int[]{a, b});
            a = b + 1;
        }
        int n = greedy.size(), pagesN = end - start + 1;
        if (n > 1) {
            boolean allMulti = true;
            for (int[] g : greedy) if (g[0] == g[1] && !fits(g[0], g[1])) allMulti = false;
            if (allMulti) {
                List<int[]> even = new ArrayList<int[]>();
                for (int i = 0; i < n; i++) {
                    int x = start + (int) Math.round((double) i * pagesN / n);
                    int y = start + (int) Math.round((double) (i + 1) * pagesN / n) - 1;
                    if (!fits(x, y)) return greedy;
                    even.add(new int[]{x, y});
                }
                return even;
            }
        }
        return greedy;
    }

    private List<Part> pack(List<Unit> units) {
        List<Part> parts = new ArrayList<Part>();
        Part cur = new Part();
        for (Unit u : units) {
            if (!cur.units.isEmpty()) {
                Unit last = cur.units.get(cur.units.size() - 1);
                boolean newChapter = !u.path.equals(last.path) || u.pieces <= 1;
                boolean ok = u.end - cur.start() + 1 <= s.maxPages && measure(cur.start(), u.end) <= s.maxBytes();
                if (!ok || (s.onePerPart && newChapter)) { parts.add(cur); cur = new Part(); }
            }
            cur.units.add(u);
        }
        if (!cur.units.isEmpty()) parts.add(cur);
        return parts;
    }

    // ================================================================== Schreiben
    private static List<String> chapterLines(Part p) {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Unit u : p.units) {
            String l = u.fullLabel();
            if (seen.add(l)) out.add(l + " – S. " + (u.start + 1) + "–" + (u.end + 1));
        }
        return out;
    }

    private static String shortLabel(Part p) {
        Unit f = p.units.get(0), l = p.units.get(p.units.size() - 1);
        Set<List<String>> paths = new HashSet<List<String>>();
        for (Unit u : p.units) paths.add(u.path);
        return paths.size() == 1 ? f.label() : f.label() + " bis " + l.label();
    }

    /** @return 0 wenn alles passt, sonst Verhältnis tatsächliche/berechnete Größe (Neuversuch nötig). */
    private double writeParts(List<Part> parts, boolean allowRetry) throws IOException {
        int total = parts.size();
        int width = Math.max(2, String.valueOf(total).length());
        String stem = safeName(s.baseName, 40);
        for (int i = 0; i < total; i++) {
            checkCancel();
            ui.progress(i, total);
            Part part = parts.get(i);
            String label = shortLabel(part);
            String nr = String.format(Locale.ROOT, "%0" + width + "d", i + 1);
            String tot = String.format(Locale.ROOT, "%0" + width + "d", total);
            part.file = stem + "_Teil" + nr + "von" + tot + "_S" + (part.start() + 1) + "-" + (part.end() + 1) + "_" + safeName(label, 50) + ".pdf";
            List<String> chap = chapterLines(part);
            ui.log("Schreibe Teil " + (i + 1) + "/" + total + ": " + part.file);
            String subject = "Teil " + (i + 1) + " von " + total + " aus '" + s.baseName + "', Seiten "
                    + (part.start() + 1) + "–" + (part.end() + 1) + ". Kapitel: " + join(chap, "; ");
            writeRange(part, docTitle + " – Teil " + (i + 1) + "/" + total + ": " + label, subject);
            part.size = io.outputSize(part.file);
            if (allowRetry && part.size > s.hardBytes() && part.pages() > 1) {
                ui.log("  " + human(part.size) + " – zu groß, Aufteilung wird angepasst.");
                long est = 0;
                for (Unit u : part.units) est += u.size;
                return Math.max(1.05, (double) part.size / Math.max(1, measure(part.start(), part.end())));
            }
            if (s.verify) {
                List<String> problems = verifyPart(part);
                part.verified = problems.isEmpty();
                if (!problems.isEmpty()) {
                    ui.log("  FEHLER bei der Prüfung: " + join(problems.subList(0, Math.min(5, problems.size())), "; "));
                    for (String p : problems) result.errors.add("Teil " + (i + 1) + ": " + p);
                } else ui.log("  geprüft: " + part.pages() + " Seite(n) bitgenau identisch mit dem Original (" + human(part.size) + ")");
            } else ui.log("  " + part.pages() + " Seite(n), " + human(part.size));
        }
        ui.progress(total, total);
        return 0;
    }

    private static final COSName[] INHERITABLE = { COSName.RESOURCES, COSName.MEDIA_BOX, COSName.CROP_BOX, COSName.ROTATE };

    /** Kopiert die Seiten 1:1 in ein neues Dokument. Nur Verweise auf Seiten
     *  AUSSERHALB des Teils (Links, Artikel-Threads) werden gelöst, damit nicht
     *  das ganze Buch mitgeschrieben wird. Inhalte, Bilder, Schriften bleiben
     *  bitgenau erhalten. */
    private void writeRange(Part part, String title, String subject) throws IOException {
        PDDocument out = new PDDocument(MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(io.tempDir()));
        try {
            if (doc.getVersion() > out.getVersion()) out.setVersion(doc.getVersion());
            Map<COSDictionary, COSDictionary> map = new IdentityHashMap<COSDictionary, COSDictionary>();
            List<COSDictionary> copies = new ArrayList<COSDictionary>();
            for (int p = part.start(); p <= part.end(); p++) {
                COSDictionary src = pages.get(p);
                COSDictionary c = new COSDictionary(src);
                for (COSName k : INHERITABLE) if (c.getItem(k) == null) {
                    COSBase v = inherited(src, k);
                    if (v != null) c.setItem(k, v);
                }
                c.removeItem(COSName.PARENT);
                c.removeItem(COSName.B);
                map.put(src, c);
                copies.add(c);
            }
            for (COSDictionary c : copies) {
                COSBase annots = c.getDictionaryObject(COSName.ANNOTS);
                if (annots instanceof COSArray) c.setItem(COSName.ANNOTS, fixAnnots((COSArray) annots, map));
                out.addPage(new PDPage(c));
            }
            // Lesezeichen
            PDDocumentOutline ol = new PDDocumentOutline();
            Set<String> seen = new HashSet<String>();
            for (Unit u : part.units) {
                if (!seen.add(u.fullLabel())) continue;
                PDOutlineItem item = new PDOutlineItem();
                item.setTitle(u.label());
                PDPageFitDestination d = new PDPageFitDestination();
                d.setPage(out.getPage(u.start - part.start()));
                item.setDestination(d);
                ol.addLast(item);
            }
            out.getDocumentCatalog().setDocumentOutline(ol);
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(title);
            info.setSubject(subject.length() > 2000 ? subject.substring(0, 2000) : subject);
            info.setProducer("Splitt-Tool (Android)");
            out.setDocumentInformation(info);
            OutputStream os = io.createOutput(part.file, "application/pdf");
            try { out.save(os); } finally { os.close(); }
        } finally {
            out.close();
        }
    }

    private COSArray fixAnnots(COSArray annots, Map<COSDictionary, COSDictionary> map) {
        COSArray res = new COSArray();
        for (int i = 0; i < annots.size(); i++) {
            COSBase b = annots.getObject(i);
            if (!(b instanceof COSDictionary)) continue;
            COSDictionary a = new COSDictionary((COSDictionary) b);
            a.removeItem(COSName.P);
            a.removeItem(COSName.PARENT);
            a.removeItem(COSName.getPDFName("Popup"));
            a.removeItem(COSName.getPDFName("IRT"));
            COSBase dest = a.getDictionaryObject(COSName.DEST);
            if (dest != null) {
                COSArray nd = remapDest(dest, map);
                if (nd == null) a.removeItem(COSName.DEST); else a.setItem(COSName.DEST, nd);
            }
            COSBase act = a.getDictionaryObject(COSName.A);
            if (act instanceof COSDictionary) {
                COSDictionary ad = (COSDictionary) act;
                if (COSName.getPDFName("GoTo").equals(ad.getCOSName(COSName.S))) {
                    COSArray nd = remapDest(ad.getDictionaryObject(COSName.D), map);
                    if (nd == null) a.removeItem(COSName.A);
                    else { COSDictionary na = new COSDictionary(ad); na.setItem(COSName.D, nd); na.removeItem(COSName.NEXT); a.setItem(COSName.A, na); }
                } else if (ad.containsKey(COSName.NEXT)) {
                    COSDictionary na = new COSDictionary(ad); na.removeItem(COSName.NEXT); a.setItem(COSName.A, na);
                }
            }
            res.add(a);
        }
        return res;
    }

    private static COSArray remapDest(COSBase dest, Map<COSDictionary, COSDictionary> map) {
        if (!(dest instanceof COSArray) || ((COSArray) dest).size() == 0) return null;
        COSArray d = (COSArray) dest;
        COSBase target = d.getObject(0);
        if (!(target instanceof COSDictionary)) return null;
        COSDictionary copy = map.get(target);
        if (copy == null) return null;
        COSArray nd = new COSArray();
        nd.add(copy);
        for (int i = 1; i < d.size(); i++) nd.add(d.get(i));
        return nd;
    }

    // ================================================================== Prüfung
    private static List<String> fingerprint(COSDictionary page) throws IOException {
        final List<String> hashes = new ArrayList<String>();
        final Set<Object> seen = new HashSet<Object>();
        Visit v = new Visit() {
            public boolean visit(Object key, COSBase obj) throws IOException {
                if (!seen.add(key)) return false;
                if (obj instanceof COSStream) hashes.add(sha1((COSStream) obj));
                return true;
            }
        };
        COSBase c = page.getItem(COSName.CONTENTS);
        if (c != null) walk(c, v);
        COSBase r = page.getItem(COSName.RESOURCES);
        if (r == null) r = inherited(page, COSName.RESOURCES);
        if (r != null) walk(r, v);
        Collections.sort(hashes);
        return hashes;
    }

    private static String sha1(COSStream st) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            InputStream in = st.createRawInputStream();
            try {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            } finally { in.close(); }
            StringBuilder b = new StringBuilder();
            for (byte x : md.digest()) b.append(String.format(Locale.ROOT, "%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    private static String box(COSDictionary page, COSName key) {
        COSBase b = inherited(page, key);
        if (b instanceof COSObject) b = ((COSObject) b).getObject();
        if (!(b instanceof COSArray)) return "";
        StringBuilder sb = new StringBuilder();
        COSArray a = (COSArray) b;
        for (int i = 0; i < a.size(); i++) {
            COSBase x = a.getObject(i);
            if (x instanceof com.tom_roush.pdfbox.cos.COSNumber)
                sb.append(Math.round(((com.tom_roush.pdfbox.cos.COSNumber) x).floatValue() * 100)).append(',');
        }
        return sb.toString();
    }

    private List<String> verifyPart(Part part) throws IOException {
        List<String> problems = new ArrayList<String>();
        ScratchFile sf = new ScratchFile(MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(io.tempDir()));
        PDDocument check = null;
        try {
            PDFParser parser = new PDFParser(io.openOutput(part.file), "", sf);
            parser.parse();
            check = parser.getPDDocument();
            int n = check.getNumberOfPages();
            if (n != part.pages()) problems.add("Seitenzahl " + n + " statt " + part.pages());
            for (int j = 0; j < Math.min(n, part.pages()); j++) {
                checkCancel();
                COSDictionary got = check.getPage(j).getCOSObject();
                COSDictionary orig = pages.get(part.start() + j);
                if (!fingerprint(got).equals(fingerprint(orig)))
                    problems.add("Seite " + (part.start() + j + 1) + ": Inhalt weicht ab");
                if (!box(got, COSName.MEDIA_BOX).equals(box(orig, COSName.MEDIA_BOX)))
                    problems.add("Seite " + (part.start() + j + 1) + ": Seitenformat weicht ab");
            }
        } finally {
            if (check != null) check.close();
            sf.close();
        }
        return problems;
    }

    // ================================================================== Übersicht
    private void writeOverview(List<Part> parts, List<Chapter> chapters, long secs) throws IOException {
        int total = parts.size();
        StringBuilder md = new StringBuilder();
        md.append("# Übersicht: ").append(docTitle).append("\n\n");
        md.append("- **Quelldatei:** ").append(s.baseName).append(" (").append(pages.size()).append(" Seiten)\n");
        md.append("- **Aufgeteilt in:** ").append(total).append(" Teile\n");
        md.append("- **Kapitel erkannt über:** ").append(chapterSource).append(" (").append(chapters.size()).append(" Kapitel)\n");
        md.append(String.format(Locale.GERMANY, "- **Grenzen pro Teil:** ≤ %.0f MB, ≤ %d Seiten\n", s.maxMb, s.maxPages));
        md.append("- **Hinweis:** max. 20 Dateien pro Chat. Für gute Antworten besser 1–2 Teile pro Chat hochladen.\n");
        md.append("- **Inhalt:** Seiten wurden 1:1 kopiert – Texte, Bilder und Schriften bleiben bitgenau unverändert, nichts wurde neu komprimiert oder ergänzt (nur Titel/Lesezeichen als Metadaten).");
        if (s.verify) md.append(" Jede Seite wurde nach dem Schreiben mit dem Original verglichen: ")
                .append(result.errors.isEmpty() ? "**alle identisch**." : "**ABWEICHUNGEN gefunden!**");
        md.append("\n\n");
        if (!result.warnings.isEmpty() || !result.errors.isEmpty()) {
            md.append("## Hinweise\n\n");
            for (String w : result.warnings) md.append("- ⚠️ ").append(w).append('\n');
            for (String w : result.errors) md.append("- ❌ ").append(w).append('\n');
            md.append('\n');
        }
        md.append("| Teil | Datei | Seiten | Größe | Kapitel |\n|---:|---|---|---:|---|\n");
        for (int i = 0; i < total; i++) {
            Part p = parts.get(i);
            md.append("| ").append(i + 1).append(" | `").append(p.file).append("` | ")
              .append(p.start() + 1).append('–').append(p.end() + 1).append(" (").append(p.pages()).append(") | ")
              .append(human(p.size)).append(" | ").append(join(chapterLines(p), "<br>").replace("|", "/")).append(" |\n");
        }
        md.append("\n## Begleittext für den Chat\n\n");
        for (int i = 0; i < total; i++) {
            Part p = parts.get(i);
            md.append("**Teil ").append(i + 1).append(":** Dies ist Teil ").append(i + 1).append(" von ").append(total)
              .append(" des Dokuments \"").append(docTitle).append("\" (Seiten ").append(p.start() + 1).append('–')
              .append(p.end() + 1).append("). Enthaltene Kapitel: ").append(join(chapterLines(p), "; ")).append("  \n");
        }
        result.overview = md.toString();
        OutputStream os = io.createOutput("00_UEBERSICHT.md", "application/octet-stream");
        try { os.write(md.toString().getBytes("UTF-8")); } finally { os.close(); }
    }

    // ================================================================== Hilfen
    static String clean(String t) {
        if (t == null) return null;
        t = t.replaceAll("\\s+", " ").trim();
        return t.isEmpty() ? "Ohne Titel" : t;
    }

    static String safeName(String t, int max) {
        t = t.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("Ä", "Ae")
             .replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss");
        t = t.replaceAll("[^A-Za-z0-9_\\-. ]+", " ").trim().replaceAll("\\s+", "_");
        t = t.replaceAll("^[._-]+|[._-]+$", "");
        if (t.length() > max) t = t.substring(0, max).replaceAll("[._-]+$", "");
        return t.isEmpty() ? "ohne_Titel" : t;
    }

    static String human(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024L * 1024) return String.format(Locale.GERMANY, "%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format(Locale.GERMANY, "%.1f MB", n / 1048576.0);
        return String.format(Locale.GERMANY, "%.2f GB", n / 1073741824.0);
    }

    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String x : l) { if (b.length() > 0) b.append(sep); b.append(x); }
        return b.toString();
    }
}
