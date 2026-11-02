package de.pdfteiler.desktop;

import de.pdfteiler.ChannelRead;
import de.pdfteiler.SplitCore;
import org.apache.pdfbox.io.RandomAccessRead;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Splitt-Tool für Windows: gleicher Kern wie die Android-App (SplitCore), Oberfläche mit Swing.
 * Läuft aus einem Ordner im Benutzerprofil – keine Adminrechte nötig.
 */
public class SplittTool {
    static final Color BLUE = new Color(0x0D2B5E), BLUE2 = new Color(0x1E4B8F), BG = new Color(0xF2F5FA),
            BORDER = new Color(0xD3DCEC), MUTED = new Color(0x5F6B7D), RED = new Color(0xE53935);
    static final String REPO = "entenmannmarkus-dev/pdf-teiler";

    final JFrame frame = new JFrame("Splitt-Tool");
    final Properties prefs = new Properties();
    final File prefsFile = new File(appData(), "einstellungen.properties");
    File pdf, outDir;
    final JLabel inLabel = new JLabel("Noch keine PDF gewählt – Datei hierher ziehen oder „PDF wählen“.");
    final JLabel outLabel = new JLabel("Neben der PDF (Ordner „…_teile“)");
    final JTextField mb = new JTextField(5), pages = new JTextField(5);
    final JCheckBox cbOne = new JCheckBox("Jedes Kapitel in eigene Datei"),
            cbDetect = new JCheckBox("Überschriften suchen, wenn die PDF keine Lesezeichen hat"),
            cbVerify = new JCheckBox("Jeden Teil mit dem Original vergleichen (empfohlen)");
    final DefaultTableModel chapterModel = new DefaultTableModel(new String[]{"Kapitel", "Seiten", "Umfang"}, 0) {
        public boolean isCellEditable(int r, int c) { return false; }
    };
    final DefaultTableModel partModel = new DefaultTableModel(new String[]{"Teil", "Enthält"}, 0) {
        public boolean isCellEditable(int r, int c) { return false; }
    };
    final JLabel previewInfo = new JLabel(" ");
    final JProgressBar bar = new JProgressBar(0, 1000);
    final JLabel status = new JLabel(" ");
    final JTextArea log = new JTextArea(8, 60);
    final JPanel result = new JPanel();
    final JButton bPreview = button("Vorschau"), bSplit = button("Aufteilen"), bCancel = button("Abbrechen");
    final JButton bUpdate = new JButton("⟳ Update");
    final AtomicBoolean cancel = new AtomicBoolean();
    volatile Thread worker;
    final Updater updater = new Updater(this);

    public static void main(String[] a) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) { }
        SwingUtilities.invokeLater(() -> {
            SplittTool t = new SplittTool();
            t.build();
            if (a.length > 0 && new File(a[0]).isFile()) t.setPdf(new File(a[0]));
        });
    }

    static File appData() {
        String ad = System.getenv("APPDATA");
        File d = new File(ad != null ? ad : System.getProperty("user.home"), "Splitt-Tool");
        d.mkdirs();
        return d;
    }

    static String version() { return Version.NAME; }

    // ------------------------------------------------------------------ Oberfläche
    void build() {
        try (InputStream in = new FileInputStream(prefsFile)) { prefs.load(in); } catch (IOException ignored) { }
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        try { frame.setIconImage(javax.imageio.ImageIO.read(SplittTool.class.getResource("/icon.png"))); } catch (Exception ignored) { }

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BLUE);
        header.setBorder(new EmptyBorder(10, 16, 10, 12));
        JLabel title = new JLabel("Splitt-Tool  ·  Version " + version());
        title.setForeground(Color.WHITE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        header.add(title, BorderLayout.WEST);
        styleUpdate(null);
        bUpdate.addActionListener(e -> {
            if (worker != null && worker.isAlive()) { info("Bitte warten", "Erst nach dem Aufteilen aktualisieren."); return; }
            updater.check(false);
        });
        header.add(bUpdate, BorderLayout.EAST);

        JPanel root = new WidthPanel();
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(12, 12, 12, 12));

        // Eingang / Ausgang nebeneinander
        JPanel io = new JPanel(new GridLayout(1, 2, 12, 0));
        io.setOpaque(false);
        JPanel in = card("Eingang: PDF");
        in.add(inLabel);
        JButton pick = button("PDF wählen");
        pick.addActionListener(e -> choosePdf());
        in.add(pick);
        JPanel out = card("Ausgang: Zielordner");
        out.add(outLabel);
        JButton pickOut = button("Ordner wählen");
        pickOut.addActionListener(e -> chooseOut());
        out.add(pickOut);
        io.add(in);
        io.add(out);
        root.add(full(io));

        // Kapitel-Vorschau
        JPanel pv = card("Kapitel-Vorschau");
        pv.add(previewInfo);
        JTable ct = new JTable(chapterModel);
        ct.getColumnModel().getColumn(0).setPreferredWidth(420);
        JScrollPane cs = new JScrollPane(ct);
        cs.setPreferredSize(new Dimension(600, 160));
        pv.add(cs);
        JTable pt = new JTable(partModel);
        pt.getColumnModel().getColumn(0).setPreferredWidth(220);
        pt.getColumnModel().getColumn(1).setPreferredWidth(480);
        JScrollPane ps = new JScrollPane(pt);
        ps.setPreferredSize(new Dimension(600, 110));
        pv.add(ps);
        root.add(full(pv));

        // Grenzen
        JPanel set = card("⚙  Grenzen pro Teil");
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        mb.setText(prefs.getProperty("mb", "30"));
        pages.setText(prefs.getProperty("pages", "100"));
        row.add(new JLabel("max. MB")); row.add(mb);
        row.add(new JLabel("   max. Seiten")); row.add(pages);
        set.add(row);
        JLabel hint = new JLabel("<html><div style='width:640px'>Claude: Chat max. 500 MB und 1000 Seiten pro PDF, Projekt max. 30 MB. Bilder wertet "
                + "Claude nur bis 100 Seiten aus. Standard 30 MB / 100 Seiten passt überall.</div></html>");
        hint.setForeground(MUTED);
        set.add(hint);
        for (JCheckBox c : new JCheckBox[]{cbOne, cbDetect, cbVerify}) { c.setOpaque(false); set.add(c); }
        cbOne.setSelected(Boolean.parseBoolean(prefs.getProperty("one", "false")));
        cbDetect.setSelected(Boolean.parseBoolean(prefs.getProperty("detect", "true")));
        cbVerify.setSelected(Boolean.parseBoolean(prefs.getProperty("verify", "true")));
        root.add(full(set));

        // Aufteilen
        JPanel run = card("▶  Aufteilen");
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btns.setOpaque(false);
        btns.add(bPreview); btns.add(Box.createHorizontalStrut(8));
        btns.add(bSplit); btns.add(Box.createHorizontalStrut(8));
        btns.add(bCancel);
        bCancel.setEnabled(false);
        bPreview.addActionListener(e -> start(true));
        bSplit.addActionListener(e -> start(false));
        bCancel.addActionListener(e -> cancel.set(true));
        run.add(btns);
        run.add(bar);
        run.add(status);
        result.setLayout(new BoxLayout(result, BoxLayout.Y_AXIS));
        result.setOpaque(false);
        result.setAlignmentX(0f);
        run.add(result);
        root.add(full(run));

        // Protokoll
        JPanel lc = card("Protokoll");
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane ls = new JScrollPane(log);
        ls.setPreferredSize(new Dimension(600, 150));
        lc.add(ls);
        root.add(full(lc));

        // Vorschlag
        JPanel fb = card("Verbesserungsvorschlag an Claude");
        JTextArea fbText = new JTextArea(3, 60);
        fbText.setLineWrap(true);
        fbText.setWrapStyleWord(true);
        fb.add(new JScrollPane(fbText));
        JButton send = button("Senden (GitHub)");
        send.addActionListener(e -> sendFeedback(fbText.getText()));
        fb.add(send);
        root.add(full(fb));

        leftAlign(root);
        JScrollPane main = new JScrollPane(root);
        main.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        main.getVerticalScrollBar().setUnitIncrement(16);
        main.setBorder(null);
        frame.getContentPane().add(header, BorderLayout.NORTH);
        frame.getContentPane().add(main, BorderLayout.CENTER);
        frame.setSize(980, 900);
        frame.setLocationRelativeTo(null);

        new DropTarget(frame, new DropTarget() {
            @Override public synchronized void drop(DropTargetDropEvent ev) {
                try {
                    ev.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) ev.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) setPdf(files.get(0));
                } catch (Exception ignored) { }
            }
        });
        frame.setVisible(true);
        updater.cleanup();
        if (!Boolean.getBoolean("splitt.offline")) updater.check(true);
    }

    /** Panel, das sich der Fensterbreite anpasst (kein seitliches Scrollen). */
    static final class WidthPanel extends JPanel implements Scrollable {
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 16; }
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return r.height - 32; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** In BoxLayout-Spalten alles linksbündig ausrichten. */
    static void leftAlign(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof JComponent) ((JComponent) k).setAlignmentX(0f);
            if (k instanceof Container && !(k instanceof JScrollPane) && !(k instanceof JTable)) leftAlign((Container) k);
        }
    }

    static JComponent full(JComponent c) {
        c.setAlignmentX(0f);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 400));
        JPanel w = new JPanel(new BorderLayout());
        w.setOpaque(false);
        w.setBorder(new EmptyBorder(0, 0, 12, 0));
        w.add(c);
        w.setAlignmentX(0f);
        return w;
    }

    static JPanel card(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Color.WHITE);
        p.setBorder(BorderFactory.createCompoundBorder(new LineBorder(BORDER, 1, true), new EmptyBorder(12, 14, 12, 14)));
        if (title != null) {
            JLabel t = new JLabel(title);
            t.setFont(t.getFont().deriveFont(Font.BOLD, 15f));
            t.setForeground(BLUE);
            t.setBorder(new EmptyBorder(0, 0, 8, 0));
            p.add(t);
        }
        return p;
    }

    static JButton button(String text) {
        JButton b = new JButton(text);
        b.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        b.setBackground(BLUE);
        b.setForeground(Color.WHITE);
        b.setFocusPainted(false);
        b.setOpaque(true);
        b.setBorderPainted(false);
        b.setBorder(new EmptyBorder(8, 14, 8, 14));
        b.setAlignmentX(0f);
        b.addPropertyChangeListener("enabled", e -> b.setBackground(b.isEnabled() ? BLUE : new Color(0xB8C2D3)));
        return b;
    }

    void styleUpdate(String availableVersion) {
        if (!(bUpdate.getUI() instanceof javax.swing.plaf.basic.BasicButtonUI) || bUpdate.getUI().getClass() != javax.swing.plaf.basic.BasicButtonUI.class)
            bUpdate.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        bUpdate.setFocusPainted(false);
        bUpdate.setOpaque(true);
        bUpdate.setBorderPainted(false);
        bUpdate.setForeground(Color.WHITE);
        bUpdate.setBorder(new EmptyBorder(6, 14, 6, 14));
        if (availableVersion == null) { bUpdate.setText("⟳ Update"); bUpdate.setBackground(BLUE2); }
        else { bUpdate.setText("● Update " + availableVersion); bUpdate.setBackground(RED); }
    }

    // ------------------------------------------------------------------ Auswahl
    void choosePdf() {
        JFileChooser fc = new JFileChooser(prefs.getProperty("lastDir", System.getProperty("user.home")));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("PDF-Dateien", "pdf"));
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) setPdf(fc.getSelectedFile());
    }

    void chooseOut() {
        JFileChooser fc = new JFileChooser(outDir != null ? outDir : pdf != null ? pdf.getParentFile() : null);
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            outDir = fc.getSelectedFile();
            outLabel.setText(outDir.getAbsolutePath());
        }
    }

    void setPdf(File f) {
        pdf = f;
        prefs.setProperty("lastDir", f.getParent());
        savePrefs();
        inLabel.setText("<html><b>" + esc(f.getName()) + "</b><br>" + SplitCore.human(f.length()) + "</html>");
        if (outDir == null) outLabel.setText(new File(f.getParentFile(), baseName() + "_teile").getAbsolutePath());
        start(true);
    }

    String baseName() {
        String n = pdf.getName();
        return n.toLowerCase().endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
    }

    File target() { return new File(outDir != null ? outDir : pdf.getParentFile(), baseName() + "_teile"); }

    // ------------------------------------------------------------------ Arbeit
    void start(boolean preview) {
        if (pdf == null) { info("Keine PDF", "Bitte zuerst eine PDF wählen."); return; }
        if (worker != null && worker.isAlive()) return;
        final SplitCore.Settings s = new SplitCore.Settings();
        try {
            s.maxMb = Double.parseDouble(mb.getText().trim().replace(',', '.'));
            s.maxPages = Integer.parseInt(pages.getText().trim());
        } catch (NumberFormatException e) { info("Bitte prüfen", "MB und Seiten müssen Zahlen sein."); return; }
        if (s.maxMb < 1 || s.maxMb > 500 || s.maxPages < 1 || s.maxPages > 1000) {
            info("Bitte prüfen", "Erlaubt sind 1–500 MB und 1–1000 Seiten pro Teil (Grenzen von Claude)."); return;
        }
        s.onePerPart = cbOne.isSelected();
        s.detectHeadings = cbDetect.isSelected();
        s.verify = cbVerify.isSelected();
        s.baseName = baseName();
        prefs.setProperty("mb", mb.getText().trim());
        prefs.setProperty("pages", pages.getText().trim());
        prefs.setProperty("one", "" + s.onePerPart);
        prefs.setProperty("detect", "" + s.detectHeadings);
        prefs.setProperty("verify", "" + s.verify);
        savePrefs();

        log.setText("");
        result.removeAll();
        result.revalidate();
        status.setText(preview ? "Vorschau wird erstellt …" : "Läuft …");
        bar.setValue(0);
        cancel.set(false);
        busy(true);
        final File out = target();
        worker = new Thread(() -> {
            try {
                Files.createDirectories(out.toPath());
                final File tmp = new File(System.getProperty("java.io.tmpdir"),
                        "splitt-tool-" + ProcessHandle.current().pid() + "-" + System.nanoTime());
                Files.createDirectories(tmp.toPath());
                SplitCore core = new SplitCore(fileIo(pdf, out, tmp), listener(), s);
                if (preview) {
                    core.preview();
                    final SplitCore.PreviewData pd = core.lastPreview;
                    SwingUtilities.invokeLater(() -> { showPreview(pd); status.setText("Vorschau fertig"); busy(false); });
                } else {
                    final SplitCore.Result r = core.run();
                    SwingUtilities.invokeLater(() -> {
                        busy(false);
                        status.setText(r.errors.isEmpty() ? "Fertig: " + r.parts + " Teile" : "Fertig, aber mit Fehlern – siehe Protokoll");
                        showResult(r, out);
                    });
                }
                deleteTree(tmp);
                if (preview) { File[] k = out.listFiles(); if (k != null && k.length == 0) out.delete(); }
            } catch (Throwable e) {
                final boolean c = cancel.get();
                SwingUtilities.invokeLater(() -> {
                    busy(false);
                    status.setText(c ? "Abgebrochen" : "Fehler");
                    if (!c) info("Fehler", e instanceof OutOfMemoryError ? "Zu wenig Arbeitsspeicher." : String.valueOf(e.getMessage()));
                });
            }
        }, "splitter");
        worker.start();
    }

    static SplitCore.Io fileIo(File src, File out, File tmp) {
        return new SplitCore.Io() {
            public RandomAccessRead openSource() throws IOException {
                RandomAccessFile f = new RandomAccessFile(src, "r");
                return new ChannelRead(f.getChannel(), f);
            }
            public OutputStream createOutput(String name, String mime) throws IOException {
                return new BufferedOutputStream(new FileOutputStream(new File(out, name)), 1 << 16);
            }
            public RandomAccessRead openOutput(String name) throws IOException {
                RandomAccessFile f = new RandomAccessFile(new File(out, name), "r");
                return new ChannelRead(f.getChannel(), f);
            }
            public long outputSize(String name) { return new File(out, name).length(); }
            public void deleteOutput(String name) { new File(out, name).delete(); }
            public File tempDir() { return tmp; }
        };
    }

    SplitCore.Listener listener() {
        return new SplitCore.Listener() {
            public void log(String m) { SwingUtilities.invokeLater(() -> { log.append(m + "\n"); log.setCaretPosition(log.getDocument().getLength()); }); }
            public void progress(int d, int t) { SwingUtilities.invokeLater(() -> bar.setValue(t <= 0 ? 0 : (int) (1000L * d / t))); }
            public boolean cancelled() { return cancel.get(); }
        };
    }

    void busy(boolean b) { bPreview.setEnabled(!b); bSplit.setEnabled(!b); bCancel.setEnabled(b); }

    void showPreview(SplitCore.PreviewData pd) {
        chapterModel.setRowCount(0);
        partModel.setRowCount(0);
        if (pd == null) return;
        for (String[] c : pd.chapters) chapterModel.addRow(c);
        for (String[] p : pd.parts) partModel.addRow(new Object[]{p[0], p[1].replace("\n", "  ·  ")});
        String w = pd.warnings.isEmpty() ? "" : "  ⚠ " + String.join(" ", pd.warnings);
        previewInfo.setText("<html>" + pd.pages + " Seiten · Kapitel erkannt über: " + esc(pd.source) + " · "
                + pd.parts.size() + " Teile" + (pd.note != null ? "<br>" + esc(pd.note).replace("\n", "<br>") : "") + esc(w) + "</html>");
    }

    void showResult(SplitCore.Result r, File out) {
        result.removeAll();
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 6));
        row.setOpaque(false);
        JButton open = button("Ordner öffnen");
        open.addActionListener(e -> openPath(out));
        JButton claude = button("An Claude (Ordner + claude.ai öffnen)");
        claude.addActionListener(e -> { openPath(out); browse("https://claude.ai/projects"); });
        JButton copy = button("Projekt-Anweisung kopieren");
        copy.addActionListener(e -> {
            String t = r.projectGuide == null ? "" : r.projectGuide;
            int a = t.indexOf("```\n"), b = a < 0 ? -1 : t.indexOf("```", a + 4);
            if (a >= 0 && b > a) t = t.substring(a + 4, b).trim();
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(t), null);
            info("Kopiert", "Die Projekt-Anweisung ist in der Zwischenablage.\n\nIn Claude: Projekt öffnen oder anlegen → "
                    + "„Anweisungen“ → einfügen. Dann die Teile und " + SplitCore.PROJECT_FILE + " unter „Dateien“ hinzufügen.");
        });
        JButton teams = button("Teams öffnen");
        teams.addActionListener(e -> { openPath(out); browse("msteams:"); });
        for (JButton b : new JButton[]{open, claude, copy, teams}) { row.add(b); row.add(Box.createHorizontalStrut(8)); }
        row.setAlignmentX(0f);
        result.add(row);
        for (int i = 0; i < r.files.size(); i++) {
            JLabel l = new JLabel("<html>" + esc(r.labels.get(i)).replace("\n", "<br>") + "</html>");
            l.setBorder(new EmptyBorder(4, 0, 4, 0));
            l.setAlignmentX(0f);
            result.add(l);
        }
        result.revalidate();
        result.repaint();
    }

    // ------------------------------------------------------------------ Vorschlag
    void sendFeedback(String text) {
        text = text.trim();
        if (text.isEmpty()) { info("Leer", "Bitte zuerst den Vorschlag eintippen."); return; }
        String title = text.split("\n")[0];
        if (title.length() > 60) title = title.substring(0, 60) + " …";
        String body = text + "\n\n---\nWindows-Version: " + version() + "\nSystem: " + System.getProperty("os.name")
                + "\n\nProtokoll:\n```\n" + tail(log.getText(), 3000) + "\n```";
        browse("https://github.com/" + REPO + "/issues/new?labels=vorschlag&title="
                + URLEncoder.encode("Vorschlag (Windows): " + title, StandardCharsets.UTF_8)
                + "&body=" + URLEncoder.encode(body, StandardCharsets.UTF_8));
    }

    static String tail(String s, int n) { return s.length() <= n ? s : s.substring(s.length() - n); }

    // ------------------------------------------------------------------ Hilfen
    void savePrefs() {
        try (OutputStream o = new FileOutputStream(prefsFile)) { prefs.store(o, "Splitt-Tool"); } catch (IOException ignored) { }
    }

    /** Für automatische Tests: Meldungen nur protokollieren statt Fenster öffnen. */
    static volatile String lastInfo;
    static boolean testMode = Boolean.getBoolean("splitt.test");

    void info(String title, String msg) {
        lastInfo = title + ": " + msg;
        if (testMode) { System.out.println("MELDUNG " + lastInfo); return; }
        JOptionPane.showMessageDialog(frame, msg, title, JOptionPane.INFORMATION_MESSAGE);
    }

    static void openPath(File f) {
        try { Desktop.getDesktop().open(f); } catch (Exception e) {
            try { new ProcessBuilder("explorer.exe", f.getAbsolutePath()).start(); } catch (IOException ignored) { }
        }
    }

    /** Öffnet Web-Adressen bevorzugt in Google Chrome, sonst im Standard-Browser. */
    static void browse(String url) {
        if (url.startsWith("http")) {
            for (String env : new String[]{"ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA"}) {
                String base = System.getenv(env);
                if (base == null) continue;
                File chrome = new File(base, "Google\\Chrome\\Application\\chrome.exe");
                if (chrome.isFile()) {
                    try { new ProcessBuilder(chrome.getAbsolutePath(), url).start(); return; } catch (IOException ignored) { }
                }
            }
        }
        try { Desktop.getDesktop().browse(new URI(url)); }
        catch (Exception e) {
            try { new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start(); } catch (IOException ignored) { }
        }
    }

    static String esc(String s) { return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    static void deleteTree(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteTree(x);
        f.delete();
    }
}
