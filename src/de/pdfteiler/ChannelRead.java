package de.pdfteiler;

import com.tom_roush.pdfbox.io.RandomAccessRead;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Wahlfreier Lesezugriff über einen FileChannel mit kleinem Puffer.
 * Liest nie die ganze Datei in den Speicher – wichtig für PDFs bis 1 GB.
 */
public final class ChannelRead implements RandomAccessRead {
    private static final int BUF = 64 * 1024;
    private final FileChannel ch;
    private final Closeable owner;
    private final long len;
    private final ByteBuffer buf = ByteBuffer.allocate(BUF);
    private long bufStart = -1;
    private int bufLen = 0;
    private long pos = 0;
    private boolean closed = false;

    public ChannelRead(FileChannel ch, Closeable owner) throws IOException {
        this.ch = ch; this.owner = owner; this.len = ch.size();
    }

    private boolean fill() throws IOException {
        if (pos >= len) return false;
        if (bufStart >= 0 && pos >= bufStart && pos < bufStart + bufLen) return true;
        buf.clear();
        long p = pos;
        int total = 0;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p + total);
            if (n <= 0) break;
            total += n;
        }
        bufStart = p; bufLen = total;
        return total > 0;
    }

    @Override public int read() throws IOException {
        if (!fill()) return -1;
        int v = buf.get((int) (pos - bufStart)) & 0xff;
        pos++;
        return v;
    }

    @Override public int read(byte[] b) throws IOException { return read(b, 0, b.length); }

    @Override public int read(byte[] b, int off, int l) throws IOException {
        if (l == 0) return 0;
        if (pos >= len) return -1;
        int done = 0;
        while (done < l && fill()) {
            int idx = (int) (pos - bufStart);
            int n = Math.min(l - done, bufLen - idx);
            for (int i = 0; i < n; i++) b[off + done + i] = buf.get(idx + i);
            done += n; pos += n;
        }
        return done == 0 ? -1 : done;
    }

    @Override public long getPosition() { return pos; }
    @Override public void seek(long p) throws IOException {
        if (p < 0) throw new IOException("Ungültige Position " + p);
        pos = p;
    }
    @Override public long length() { return len; }
    @Override public boolean isClosed() { return closed; }
    @Override public int peek() throws IOException { int v = read(); if (v != -1) pos--; return v; }
    @Override public void rewind(int n) throws IOException { seek(pos - n); }
    @Override public byte[] readFully(int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = read(b, off, n - off);
            if (r < 0) throw new EOFException("Unerwartetes Dateiende");
            off += r;
        }
        return b;
    }
    @Override public boolean isEOF() { return pos >= len; }
    @Override public int available() { return (int) Math.min(Integer.MAX_VALUE, len - pos); }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        if (owner != null) owner.close(); else ch.close();
    }
}
