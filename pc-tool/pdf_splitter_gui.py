#!/usr/bin/env python3
"""Einfache Oberfläche für den Claude PDF-Splitter (tkinter, ohne Zusatzpakete)."""

from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

import pdf_splitter as core


class App(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title(f"Claude PDF-Splitter {core.__version__}")
        self.geometry("820x600")
        self.minsize(640, 480)
        self.msgs: queue.Queue = queue.Queue()
        self.worker: threading.Thread | None = None
        self.cancel_flag = threading.Event()
        self.last_out: Path | None = None

        self.v_pdf = tk.StringVar()
        self.v_out = tk.StringVar()
        self.v_mb = tk.StringVar(value=str(core.DEFAULT_MAX_MB).rstrip("0").rstrip("."))
        self.v_pages = tk.StringVar(value=str(core.DEFAULT_MAX_PAGES))
        self.v_tokens = tk.StringVar(value="")
        self.v_level = tk.StringVar(value="automatisch")
        self.v_one = tk.BooleanVar(value=False)
        self.v_verify = tk.BooleanVar(value=True)
        self.v_detect = tk.BooleanVar(value=True)
        self.v_pw = tk.StringVar()
        self._build()
        self.after(100, self._poll)

    # ------------------------------------------------------------------
    def _build(self) -> None:
        pad = {"padx": 6, "pady": 4}
        frm = ttk.Frame(self, padding=10)
        frm.pack(fill="both", expand=True)
        frm.columnconfigure(1, weight=1)

        ttk.Label(frm, text="PDF-Datei:").grid(row=0, column=0, sticky="w", **pad)
        ttk.Entry(frm, textvariable=self.v_pdf).grid(row=0, column=1, sticky="ew", **pad)
        ttk.Button(frm, text="Auswählen …", command=self._pick_pdf).grid(row=0, column=2, **pad)

        ttk.Label(frm, text="Ausgabeordner:").grid(row=1, column=0, sticky="w", **pad)
        ttk.Entry(frm, textvariable=self.v_out).grid(row=1, column=1, sticky="ew", **pad)
        ttk.Button(frm, text="Auswählen …", command=self._pick_out).grid(row=1, column=2, **pad)

        box = ttk.LabelFrame(frm, text="Grenzen pro Teil (Claude)", padding=8)
        box.grid(row=2, column=0, columnspan=3, sticky="ew", **pad)
        ttk.Label(box, text="max. MB:").grid(row=0, column=0, sticky="w")
        ttk.Entry(box, textvariable=self.v_mb, width=8).grid(row=0, column=1, sticky="w", padx=(4, 16))
        ttk.Label(box, text="max. Seiten:").grid(row=0, column=2, sticky="w")
        ttk.Entry(box, textvariable=self.v_pages, width=8).grid(row=0, column=3, sticky="w", padx=(4, 16))
        ttk.Label(box, text="max. Token (optional):").grid(row=0, column=4, sticky="w")
        ttk.Entry(box, textvariable=self.v_tokens, width=10).grid(row=0, column=5, sticky="w", padx=4)

        opt = ttk.LabelFrame(frm, text="Optionen", padding=8)
        opt.grid(row=3, column=0, columnspan=3, sticky="ew", **pad)
        ttk.Label(opt, text="Kapitel-Ebene:").grid(row=0, column=0, sticky="w")
        ttk.Combobox(opt, textvariable=self.v_level, width=12, state="readonly",
                     values=["automatisch", "1", "2", "3", "4"]).grid(row=0, column=1, sticky="w",
                                                                    padx=(4, 16))
        ttk.Checkbutton(opt, text="Jedes Kapitel in eigene Datei",
                        variable=self.v_one).grid(row=0, column=2, sticky="w", padx=8)
        ttk.Checkbutton(opt, text="Überschriften suchen, wenn keine Lesezeichen",
                        variable=self.v_detect).grid(row=1, column=0, columnspan=2, sticky="w")
        ttk.Checkbutton(opt, text="Teile mit Original vergleichen (empfohlen)",
                        variable=self.v_verify).grid(row=1, column=2, sticky="w", padx=8)
        ttk.Label(opt, text="Passwort (falls nötig):").grid(row=2, column=0, sticky="w", pady=(6, 0))
        ttk.Entry(opt, textvariable=self.v_pw, show="•", width=20).grid(row=2, column=1, sticky="w",
                                                                       padx=4, pady=(6, 0))

        btns = ttk.Frame(frm)
        btns.grid(row=4, column=0, columnspan=3, sticky="ew", **pad)
        self.b_start = ttk.Button(btns, text="▶ Aufteilen", command=self._start)
        self.b_start.pack(side="left")
        self.b_analyse = ttk.Button(btns, text="Nur Kapitel anzeigen", command=self._analyse)
        self.b_analyse.pack(side="left", padx=6)
        self.b_cancel = ttk.Button(btns, text="Abbrechen", command=self._cancel, state="disabled")
        self.b_cancel.pack(side="left", padx=6)
        self.b_open = ttk.Button(btns, text="Ordner öffnen", command=self._open_out, state="disabled")
        self.b_open.pack(side="right")

        self.bar = ttk.Progressbar(frm, mode="indeterminate")
        self.bar.grid(row=5, column=0, columnspan=3, sticky="ew", **pad)

        logf = ttk.Frame(frm)
        logf.grid(row=6, column=0, columnspan=3, sticky="nsew", **pad)
        frm.rowconfigure(6, weight=1)
        self.log = tk.Text(logf, wrap="word", height=12, state="disabled")
        sb = ttk.Scrollbar(logf, command=self.log.yview)
        self.log.configure(yscrollcommand=sb.set)
        self.log.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")

    # ------------------------------------------------------------------
    def _pick_pdf(self) -> None:
        f = filedialog.askopenfilename(filetypes=[("PDF", "*.pdf"), ("Alle Dateien", "*.*")])
        if f:
            self.v_pdf.set(f)
            if not self.v_out.get():
                p = Path(f)
                self.v_out.set(str(p.with_name(p.stem + "_teile")))

    def _pick_out(self) -> None:
        d = filedialog.askdirectory()
        if d:
            self.v_out.set(d)

    def _write(self, text: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", text + "\n")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _settings(self) -> core.Settings | None:
        try:
            mb = float(self.v_mb.get().replace(",", "."))
            pages = int(self.v_pages.get())
            tok = int(self.v_tokens.get()) if self.v_tokens.get().strip() else None
            if mb <= 0 or pages <= 0 or (tok is not None and tok <= 0):
                raise ValueError
        except ValueError:
            messagebox.showerror("Eingabe prüfen", "Bitte gültige positive Zahlen für die Grenzen eingeben.")
            return None
        lvl = self.v_level.get()
        return core.Settings(max_mb=mb, max_pages=pages, max_tokens=tok,
                             chapter_level=None if lvl == "automatisch" else int(lvl),
                             one_chapter_per_part=self.v_one.get(),
                             detect_headings=self.v_detect.get(), verify=self.v_verify.get(),
                             password=self.v_pw.get() or None)

    def _busy(self, on: bool) -> None:
        self.b_start.configure(state="disabled" if on else "normal")
        self.b_analyse.configure(state="disabled" if on else "normal")
        self.b_cancel.configure(state="normal" if on else "disabled")
        if on:
            self.bar.start(12)
        else:
            self.bar.stop()

    def _launch(self, analyse_only: bool) -> None:
        pdf = self.v_pdf.get().strip()
        if not pdf or not Path(pdf).is_file():
            messagebox.showerror("Keine Datei", "Bitte zuerst eine PDF-Datei auswählen.")
            return
        s = self._settings()
        if s is None:
            return
        out = self.v_out.get().strip() or None
        self.cancel_flag.clear()
        self._busy(True)
        self.worker = threading.Thread(target=self._work, args=(pdf, out, s, analyse_only), daemon=True)
        self.worker.start()

    def _start(self) -> None:
        self._launch(False)

    def _analyse(self) -> None:
        self._launch(True)

    def _work(self, pdf: str, out: str | None, s: core.Settings, analyse_only: bool) -> None:
        put = lambda m: self.msgs.put(("log", m))  # noqa: E731
        try:
            sp = core.PdfSplitter(pdf, s, progress=put, cancel=self.cancel_flag.is_set)
            if analyse_only:
                chapters = sp.find_chapters()
                put(f"Kapitelquelle: {sp.chapter_source} – {len(chapters)} Kapitel")
                for c in chapters:
                    parent = getattr(c, "_parent_title", None)
                    name = f"{parent} › {c.title}" if parent else c.title
                    put(f"  S. {c.start + 1}–{c.end + 1} ({c.pages} S.)  {name}")
                self.msgs.put(("done", None))
                return
            result = sp.run(out)
            self.msgs.put(("finished", (result, out or str(Path(pdf).with_name(Path(pdf).stem + "_teile")))))
        except KeyboardInterrupt:
            self.msgs.put(("log", "Abgebrochen."))
            self.msgs.put(("done", None))
        except Exception as exc:  # noqa: BLE001
            self.msgs.put(("error", str(exc)))

    def _cancel(self) -> None:
        self.cancel_flag.set()
        self._write("Breche ab …")

    def _poll(self) -> None:
        try:
            while True:
                kind, data = self.msgs.get_nowait()
                if kind == "log":
                    self._write(data)
                elif kind == "error":
                    self._busy(False)
                    self._write("FEHLER: " + data)
                    messagebox.showerror("Fehler", data)
                elif kind == "done":
                    self._busy(False)
                elif kind == "finished":
                    self._busy(False)
                    result, out = data
                    self.last_out = Path(out)
                    self.b_open.configure(state="normal")
                    if result.get("fehler"):
                        messagebox.showwarning("Prüfung", "Achtung: Abweichungen gefunden – siehe "
                                               "00_UEBERSICHT.md im Ausgabeordner.")
                    else:
                        messagebox.showinfo("Fertig", f"{result['anzahl_teile']} Teile erstellt.\n"
                                            f"Übersicht: 00_UEBERSICHT.md")
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _open_out(self) -> None:
        if not self.last_out:
            return
        p = str(self.last_out)
        if sys.platform.startswith("win"):
            os.startfile(p)  # type: ignore[attr-defined]
        elif sys.platform == "darwin":
            subprocess.Popen(["open", p])
        else:
            subprocess.Popen(["xdg-open", p])


if __name__ == "__main__":
    App().mainloop()
