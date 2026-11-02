package de.pdfteiler;

import com.tom_roush.pdfbox.io.RandomAccessRead;

import java.io.*;
import java.nio.file.*;

/** Test-Startprogramm für den Desktop (gleicher Kern wie die App). */
public class Cli {
    public static void main(String[] a) throws Exception {
        final File src = new File(a[0]);
        final File out = new File(a[1]);
        out.mkdirs();
        SplitCore.Settings s = new SplitCore.Settings();
        if (a.length > 2) s.maxMb = Double.parseDouble(a[2]);
        String n = src.getName();
        s.baseName = n.toLowerCase().endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
        final File tmp = Files.createTempDirectory("pdfteiler").toFile();
        SplitCore.Io io = new SplitCore.Io() {
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
        SplitCore.Listener l = new SplitCore.Listener() {
            public void log(String m) { System.out.println(m); }
            public void progress(int d, int t) { }
            public boolean cancelled() { return false; }
        };
        if (a.length > 3) { System.out.println(new SplitCore(io, l, s).preview()); return; }
        SplitCore.Result r = new SplitCore(io, l, s).run();
        System.out.println("TEILE=" + r.parts + " FEHLER=" + r.errors + " WARN=" + r.warnings.size());
    }
}
