package de.pdfteiler.desktop;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** Oberflächen-Test unter Xvfb: Vorschau, Aufteilen, Ergebnis, Update (lokaler Server). Aufruf: PDF Ausgabeordner Bildordner */
public class DesktopTest {
    static void check(boolean ok, String what) { System.out.println((ok ? "OK    " : "FEHLER ") + what); if (!ok) System.exit(1); }

    static void waitIdle(SplittTool t, int secs) throws Exception {
        Thread.sleep(300);
        for (int i = 0; i < secs * 10 && t.worker != null && t.worker.isAlive(); i++) Thread.sleep(100);
        Thread.sleep(500);
    }

    static void shot(SplittTool t, String file) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = t.frame;
            BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
            f.paint(img.getGraphics());
            try { ImageIO.write(img, "png", new File(file)); } catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    public static void main(String[] a) throws Exception {
        Updater.API_URL = "http://127.0.0.1:8765/nope";
        Updater.RAW_URL = "http://127.0.0.1:8765/update.json";
        final SplittTool[] h = new SplittTool[1];
        SwingUtilities.invokeAndWait(() -> { h[0] = new SplittTool(); h[0].build(); });
        SplittTool t = h[0];
        Thread.sleep(2000);
        check(t.bUpdate.getText().startsWith("● Update"), "Update-Knopf rot: " + t.bUpdate.getText());
        SwingUtilities.invokeAndWait(() -> { t.outDir = new File(a[1]); t.setPdf(new File(a[0])); });
        waitIdle(t, 120);
        System.out.println("VORSCHAU " + t.previewInfo.getText());
        check(t.chapterModel.getRowCount() > 0, "Kapitel-Vorschau: " + t.chapterModel.getRowCount() + " Kapitel, " + t.partModel.getRowCount() + " Teile");
        shot(t, a[2] + "/1_vorschau.png");
        SwingUtilities.invokeAndWait(() -> t.start(false));
        waitIdle(t, 600);
        System.out.println("STATUS " + t.status.getText());
        File out = t.target();
        String[] files = out.list();
        java.util.Arrays.sort(files);
        for (String f : files) System.out.println("  DATEI " + f + " " + new File(out, f).length());
        check(t.status.getText().startsWith("Fertig:"), "Aufteilen fertig");
        check(t.log.getText().contains("bitgenau identisch"), "bitgenaue Prüfung im Protokoll");
        check(new File(out, "00_CLAUDE_PROJEKT.md").isFile(), "Quellen-Karte geschrieben");
        SwingUtilities.invokeAndWait(() -> t.frame.getContentPane().validate());
        shot(t, a[2] + "/2_fertig.png");
        // Update: lädt neue Programmdatei in app\ (splitt.home = Testordner)
        t.updater.check(false);
        File jar = null;
        for (int i = 0; i < 100 && jar == null; i++) {
            Thread.sleep(100);
            File[] js = Updater.appDir().listFiles((d, n) -> n.equals("splitt-tool-99.jar"));
            if (js != null && js.length == 1) jar = js[0];
        }
        Thread.sleep(500);
        check(jar != null && jar.length() > 100000, "Update geladen: " + jar);
        System.out.println("LETZTE MELDUNG " + SplittTool.lastInfo);
        System.exit(0);
    }
}
