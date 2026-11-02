#!/usr/bin/env python3
"""
Claude PDF-Splitter
===================

Teilt große PDF-Dateien (bis 1000 MB und mehr) in Teile auf, die in einen
Claude-Chat hochgeladen werden können. Die Aufteilung folgt den Kapiteln des
Dokuments (Lesezeichen/Inhaltsverzeichnis der PDF, ersatzweise erkannte
Überschriften, notfalls feste Seitenblöcke). Zu jedem Teil werden die
enthaltenen Kapitel im Dateinamen, in den PDF-Metadaten und in einer
Übersichtsdatei (Markdown + JSON) beschrieben.

Speicherschonend: Die Quelldatei wird nie komplett in den Arbeitsspeicher
geladen. Für jeden Schreibvorgang wird ein frischer Leser geöffnet, damit der
Objekt-Cache von pypdf nicht über das ganze Dokument wächst.
"""

from __future__ import annotations

import argparse
import gc
import hashlib
import json
import math
import os
import re
import sys
import time
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Callable, Iterable

try:
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import (ArrayObject, DictionaryObject, Destination,
                               IndirectObject, StreamObject)
except ImportError:  # pragma: no cover
    sys.stderr.write(
        "Das Paket 'pypdf' fehlt. Bitte installieren mit:\n"
        "    pip install -r requirements.txt\n"
    )
    raise SystemExit(2)

__version__ = "1.0.0"

# --------------------------------------------------------------------------
# Grenzwerte für Claude (Stand: November 2026, siehe README)
# --------------------------------------------------------------------------
# Quelle: https://support.claude.com/en/articles/8241126-upload-files-to-claude
#  - Chat: bis 500 MB pro Datei, bis 20 Dateien pro Chat, PDFs bis 1000 Seiten.
#  - Projekte: 30 MB pro Datei.
#  - Bilder/Grafiken in PDFs werden NUR bei PDFs mit höchstens 100 Seiten
#    ausgewertet; bei 101–1000 Seiten liest Claude nur den Text.
#  - Alles muss zusätzlich ins Kontextfenster passen (Token).
# Standard hier: 30 MB / 100 Seiten -> passt in Chat UND Projekt und Claude
# sieht auch die Bilder (wichtig für bildlastige Bücher).
DEFAULT_MAX_MB = 30.0
DEFAULT_MAX_PAGES = 100
DEFAULT_SAFETY = 0.05           # 5 % Sicherheitsabstand zur MB-Grenze
DEFAULT_FILES_PER_CHAT = 20
TOKENS_PER_PAGE_IMAGE = 1600    # grobe Schätzung für das Seitenbild
CHARS_PER_TOKEN = 3.5           # grobe Schätzung für deutschen/englischen Text

ProgressFn = Callable[[str], None]


def _print_progress(msg: str) -> None:
    print(msg, flush=True)


# --------------------------------------------------------------------------
# Datenstrukturen
# --------------------------------------------------------------------------
@dataclass
class Chapter:
    title: str
    start: int          # 0-basiert, inklusiv
    end: int            # 0-basiert, inklusiv
    level: int = 0
    children: list["Chapter"] = field(default_factory=list)

    @property
    def pages(self) -> int:
        return self.end - self.start + 1


@dataclass
class Unit:
    """Kleinster Baustein eines Teils: ein Kapitel oder ein Stück davon."""
    title: str
    start: int
    end: int
    path: list[str]             # Kapitelpfad, z. B. ["Teil II", "Kapitel 5"]
    piece: tuple[int, int] | None = None   # (n, gesamt) falls Kapitel zerteilt
    size: int = 0               # geschätzte Bytes
    tokens: int = 0             # geschätzte Token

    @property
    def pages(self) -> int:
        return self.end - self.start + 1

    @property
    def label(self) -> str:
        if self.piece and self.piece[1] > 1:
            return f"{self.title} (Abschnitt {self.piece[0]}/{self.piece[1]})"
        return self.title


@dataclass
class Part:
    units: list[Unit] = field(default_factory=list)
    file: str = ""
    size: int = 0
    verified: bool | None = None

    @property
    def start(self) -> int:
        return self.units[0].start

    @property
    def end(self) -> int:
        return self.units[-1].end

    @property
    def pages(self) -> int:
        return self.end - self.start + 1

    @property
    def est_size(self) -> int:
        return sum(u.size for u in self.units)

    @property
    def tokens(self) -> int:
        return sum(u.tokens for u in self.units)


@dataclass
class Settings:
    max_mb: float = DEFAULT_MAX_MB
    max_pages: int = DEFAULT_MAX_PAGES
    max_tokens: int | None = None
    safety: float = DEFAULT_SAFETY
    chapter_level: int | None = None        # None = automatisch
    one_chapter_per_part: bool = False
    detect_headings: bool = True
    keep_bookmarks: bool = True
    password: str | None = None
    files_per_chat: int = DEFAULT_FILES_PER_CHAT
    verify: bool = True

    @property
    def max_bytes(self) -> int:
        return int(self.max_mb * 1024 * 1024 * (1 - self.safety))


# --------------------------------------------------------------------------
# Hilfsfunktionen
# --------------------------------------------------------------------------
class _CountingSink:
    """Datei-Ersatz, der nur Bytes zählt (für Größenmessungen ohne Disk/RAM)."""

    def __init__(self) -> None:
        self.n = 0

    def write(self, b: bytes) -> int:
        self.n += len(b)
        return len(b)

    def tell(self) -> int:
        return self.n

    def flush(self) -> None:
        pass


def human_size(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n} B"


_UMLAUTS = str.maketrans({"ä": "ae", "ö": "oe", "ü": "ue", "Ä": "Ae",
                          "Ö": "Oe", "Ü": "Ue", "ß": "ss"})


def safe_name(text: str, max_len: int = 60) -> str:
    text = text.translate(_UMLAUTS)
    text = re.sub(r"[^\w\-. ]+", " ", text, flags=re.ASCII)
    text = re.sub(r"\s+", "_", text.strip())
    text = text.strip("._-")
    if len(text) > max_len:
        text = text[:max_len].rstrip("._-")
    return text or "ohne_Titel"


def clean_title(t: str) -> str:
    t = re.sub(r"\s+", " ", str(t or "")).strip()
    return t or "Ohne Titel"


# --------------------------------------------------------------------------
# Der Splitter
# --------------------------------------------------------------------------
class PdfSplitter:
    def __init__(self, source: str | os.PathLike, settings: Settings | None = None,
                 progress: ProgressFn = _print_progress,
                 cancel: Callable[[], bool] | None = None) -> None:
        self.source = Path(source)
        self.s = settings or Settings()
        self.progress = progress
        self.cancel = cancel or (lambda: False)
        if not self.source.is_file():
            raise FileNotFoundError(f"Datei nicht gefunden: {self.source}")
        self.source_size = self.source.stat().st_size
        reader = self._open()
        self.num_pages = len(reader.pages)
        meta = reader.metadata or {}
        raw_title = clean_title(meta.get("/Title") or "")
        self.doc_title = raw_title if raw_title.lower() not in ("ohne titel", "untitled", "") \
            and len(raw_title) > 2 else self.source.stem
        self._reader_for_outline = reader
        self.chapter_source = ""
        self.errors: list[str] = []
        self.size_factor = 1.0
        self.page_tokens: list[int] = []
        self.warnings: list[str] = []

    # ---- Lesen ----------------------------------------------------------
    def _open(self) -> PdfReader:
        # WICHTIG: Dateiobjekt übergeben, nicht den Pfad – bei einem Pfad liest
        # pypdf die komplette Datei in den Arbeitsspeicher (bei 1 GB fatal).
        fh = open(self.source, "rb")
        try:
            reader = PdfReader(fh, strict=False)
        except Exception:
            fh.close()
            raise
        reader._splitter_fh = fh  # type: ignore[attr-defined]
        if reader.is_encrypted:
            pw = self.s.password or ""
            try:
                ok = reader.decrypt(pw)
            except Exception as exc:  # noqa: BLE001
                raise RuntimeError(f"PDF ist verschlüsselt und kann nicht geöffnet werden: {exc}")
            if not ok:
                raise RuntimeError("PDF ist passwortgeschützt. Bitte Passwort mit --passwort angeben.")
        return reader

    @staticmethod
    def _release(reader) -> None:
        """Leser schließen und Speicher sofort freigeben."""
        if reader is None:
            return
        fh = getattr(reader, "_splitter_fh", None)
        try:
            reader.resolved_objects.clear()
        except Exception:  # noqa: BLE001
            pass
        if fh:
            fh.close()
        gc.collect()

    def _check_cancel(self) -> None:
        if self.cancel():
            raise KeyboardInterrupt("Abgebrochen")

    # ---- Kapitel ermitteln ---------------------------------------------
    def find_chapters(self) -> list[Chapter]:
        chapters = self._chapters_from_outline()
        if chapters:
            self.chapter_source = "Lesezeichen (Inhaltsverzeichnis der PDF)"
            return chapters
        if self.s.detect_headings:
            self.progress("Keine Lesezeichen gefunden – suche Überschriften im Text …")
            chapters = self._chapters_from_headings()
            if chapters:
                self.chapter_source = "erkannte Überschriften im Text"
                return chapters
        self.chapter_source = "feste Seitenblöcke (keine Kapitel erkennbar)"
        return [Chapter(title=self.doc_title, start=0, end=self.num_pages - 1)]

    def _chapters_from_outline(self) -> list[Chapter]:
        reader = self._reader_for_outline
        try:
            outline = reader.outline
        except Exception:  # noqa: BLE001
            return []
        if not outline:
            return []

        def build(items: Iterable, level: int) -> list[Chapter]:
            result: list[Chapter] = []
            last: Chapter | None = None
            for item in items:
                if isinstance(item, list):
                    if last is not None:
                        last.children = build(item, level + 1)
                    else:
                        result.extend(build(item, level))
                    continue
                if not isinstance(item, Destination):
                    continue
                try:
                    page = reader.get_destination_page_number(item)
                except Exception:  # noqa: BLE001
                    page = None
                if page is None or page < 0:
                    continue
                last = Chapter(title=clean_title(item.title), start=page, end=page, level=level)
                result.append(last)
            return result

        tree = build(outline, 0)
        if not tree:
            return []

        # Ebene wählen: explizit oder automatisch (eine einzelne Wurzel wie
        # "Buchtitel" überspringen und deren Kinder als Kapitel nehmen).
        level = self.s.chapter_level
        if level is None:
            level = 1
            nodes = tree
            while len(nodes) <= 2 and any(n.children for n in nodes):
                nxt = [c for n in nodes for c in (n.children or [n])]
                if len(nxt) <= len(nodes):
                    break
                nodes = nxt
                level += 1
            self.progress(f"Kapitel-Ebene automatisch gewählt: {level}")
        tree = self._flatten_to_level(tree, level)
        return self._assign_ranges(tree, 0, self.num_pages - 1)

    def _flatten_to_level(self, nodes: list[Chapter], level: int) -> list[Chapter]:
        """Gibt die Knoten auf Ebene `level` (1 = oberste) zurück; Knoten ohne
        Kinder auf höheren Ebenen bleiben erhalten. Kinder darunter bleiben als
        Unterkapitel erhalten (für das Zerteilen zu großer Kapitel)."""
        if level <= 1:
            return nodes
        out: list[Chapter] = []
        for n in nodes:
            if n.children:
                kids = self._flatten_to_level(n.children, level - 1)
                for k in kids:
                    k.title = k.title  # Pfad wird separat geführt
                # Seiten vor dem ersten Kind gehören zum Elternkapitel
                first = min(k.start for k in kids)
                if first > n.start:
                    out.append(Chapter(title=n.title, start=n.start, end=n.start))
                for k in kids:
                    k._parent_title = n.title  # type: ignore[attr-defined]
                out.extend(kids)
            else:
                out.append(n)
        return out

    def _assign_ranges(self, nodes: list[Chapter], lo: int, hi: int) -> list[Chapter]:
        nodes = [n for n in nodes if lo <= n.start <= hi]
        nodes.sort(key=lambda n: n.start)
        # Kapitel, die auf derselben Seite beginnen, zusammenfassen
        merged: list[Chapter] = []
        for n in nodes:
            if merged and merged[-1].start == n.start:
                prev = merged[-1]
                prev.title = f"{prev.title} / {n.title}"
                prev.children.extend(n.children)
            else:
                merged.append(n)
        result: list[Chapter] = []
        if merged and merged[0].start > lo:
            result.append(Chapter(title="Vorspann (Seiten vor dem ersten Kapitel)",
                                  start=lo, end=merged[0].start - 1))
        for i, n in enumerate(merged):
            n.end = (merged[i + 1].start - 1) if i + 1 < len(merged) else hi
            if n.children:
                n.children = self._assign_ranges(n.children, n.start, n.end)
            result.append(n)
        return result

    _HEADING_PATTERNS = [
        re.compile(r"^(kapitel|chapter|teil|part|abschnitt|section|buch|book)\s+"
                   r"([0-9]+|[IVXLC]+|[a-zäöü]+)\b.{0,80}$", re.I),
        re.compile(r"^([0-9]{1,2})\.?\s{1,3}[A-ZÄÖÜ][^.!?]{2,70}$"),
        re.compile(r"^(einleitung|vorwort|einführung|anhang|literaturverzeichnis|"
                   r"glossar|register|zusammenfassung|fazit|introduction|preface|"
                   r"appendix|bibliography|index|conclusion)\s*$", re.I),
    ]

    def _chapters_from_headings(self) -> list[Chapter]:
        found: list[tuple[int, str]] = []
        reader = self._open()
        empty_pages = 0
        for i in range(self.num_pages):
            self._check_cancel()
            if i and i % 200 == 0:
                self._release(reader)
                reader = self._open()   # Cache leeren
                self.progress(f"  Überschriftensuche: Seite {i}/{self.num_pages}")
            try:
                text = reader.pages[i].extract_text() or ""
            except Exception:  # noqa: BLE001
                text = ""
            lines = [ln.strip() for ln in text.splitlines() if ln.strip()][:4]
            if not lines:
                empty_pages += 1
                # Reiner Scan ohne Text: nach 30 Seiten aufgeben
                if empty_pages >= 30 and empty_pages == i + 1:
                    self.progress("  Kein Text gefunden (gescanntes Dokument?) – nutze Seitenblöcke.")
                    self._release(reader)
                    return []
                continue
            for ln in lines:
                if any(p.match(ln) for p in self._HEADING_PATTERNS):
                    found.append((i, clean_title(ln)))
                    break
        self._release(reader)
        if len(found) < 2:
            return []
        nodes = [Chapter(title=t, start=p, end=p) for p, t in found]
        return self._assign_ranges(nodes, 0, self.num_pages - 1)

    # ---- Objektgraph einer Seite ---------------------------------------
    # Schlüssel, über die man zu ANDEREN Seiten/zum Dokument springt – diese
    # werden beim Ermitteln der seiteneigenen Objekte nicht verfolgt.
    _SKIP_KEYS = {"/Parent", "/P", "/Dest", "/D", "/A", "/Next", "/Prev", "/First",
                  "/Last", "/B", "/StructParent", "/StructParents", "/Popup", "/IRT"}

    @classmethod
    def _walk(cls, obj, visit) -> None:
        """Besucht alle indirekten Objekte, die zu einer Seite gehören.
        visit(ref, resolved) gibt False zurück, wenn schon bekannt."""
        stack = [obj]
        while stack:
            o = stack.pop()
            if isinstance(o, IndirectObject):
                key = (o.idnum, o.generation)
                try:
                    res = o.get_object()
                except Exception:  # noqa: BLE001
                    continue
                if not visit(key, res):
                    continue
                o = res
            if isinstance(o, DictionaryObject):  # inkl. StreamObject
                for k, v in o.items():
                    if k not in cls._SKIP_KEYS:
                        stack.append(v)
            elif isinstance(o, ArrayObject):
                stack.extend(o)

    @staticmethod
    def _obj_size(res) -> int:
        if isinstance(res, StreamObject):
            data = getattr(res, "_data", b"") or b""
            return len(data) + 40 * len(res) + 60
        if isinstance(res, (DictionaryObject, ArrayObject)):
            return 40 * len(res) + 40
        return 40

    def index_pages(self) -> None:
        """Ein einziger Durchlauf über das Dokument: merkt sich für jede Seite,
        welche Objekte (Bilder, Schriften, Inhalte) sie braucht und wie groß
        diese sind. Damit lässt sich die Größe jedes Seitenbereichs ohne
        Probeschreiben exakt genug berechnen – auch gemeinsam genutzte
        Schriften/Bilder werden nur einmal gezählt."""
        self.obj_size: dict[tuple[int, int], int] = {}
        self.page_objs: list[tuple[tuple[int, int], ...]] = []
        reader = self._open()
        budget = 0
        for i in range(self.num_pages):
            self._check_cancel()
            if budget > 100 * 1024 * 1024:   # Cache leeren, Speicher begrenzen
                self._release(reader)
                reader = self._open()
                budget = 0
            if i and i % 250 == 0:
                self.progress(f"  Seitenanalyse: {i}/{self.num_pages}")
            refs: list[tuple[int, int]] = []
            seen: set = set()

            def visit(key, res):
                if key in seen:
                    return False
                seen.add(key)
                refs.append(key)
                if key not in self.obj_size:
                    self.obj_size[key] = self._obj_size(res)
                return True

            page = reader.pages[i]
            for k in ("/Contents", "/Resources", "/Annots", "/Thumb", "/Group"):
                if k in page:
                    self._walk(page.get(k), visit)
            # geerbte Ressourcen
            if "/Resources" not in page:
                try:
                    self._walk(page.get_inherited("/Resources", None), visit)  # type: ignore[attr-defined]
                except Exception:  # noqa: BLE001
                    pass
            budget += sum(self.obj_size[r] for r in refs)
            self.page_objs.append(tuple(refs))
            if self.s.max_tokens:
                try:
                    chars = len(page.extract_text() or "")
                except Exception:  # noqa: BLE001
                    chars = 0
                self.page_tokens.append(int(chars / CHARS_PER_TOKEN) + TOKENS_PER_PAGE_IMAGE)
        self._release(reader)

    def measure(self, start: int, end: int) -> int:
        """Geschätzte Dateigröße des Seitenbereichs (Bytes)."""
        objs: set = set()
        for p in range(start, end + 1):
            objs.update(self.page_objs[p])
        est = 2000 + 300 * (end - start + 1) + sum(self.obj_size[o] for o in objs)
        return int(est * self.size_factor)

    # ---- Schreiben ------------------------------------------------------
    def _write_range(self, start: int, end: int, path: Path, bookmarks: list[tuple[str, int]] | None = None,
                     title: str | None = None, subject: str | None = None) -> None:
        """Kopiert die Seiten 1:1 (keine Neukomprimierung, kein Umschreiben
        von Text oder Bildern). Nur Metadaten und Lesezeichen kommen hinzu."""
        reader = self._open()  # frischer Leser = begrenzter Speicher
        writer = PdfWriter()
        for p in range(start, end + 1):
            writer.add_page(reader.pages[p])
        if bookmarks and self.s.keep_bookmarks:
            for text, page in bookmarks:
                try:
                    writer.add_outline_item(text, page)
                except Exception:  # noqa: BLE001
                    pass
        meta = {"/Producer": f"Claude PDF-Splitter {__version__}"}
        if title:
            meta["/Title"] = title
        if subject:
            meta["/Subject"] = subject[:2000]
        writer.add_metadata(meta)
        tmp = path.with_name(path.name + ".tmp")
        with open(tmp, "wb") as fh:
            writer.write(fh)
        writer.close()
        del writer
        self._release(reader)
        os.replace(tmp, path)

    # ---- Prüfung: Inhalt unverändert? ------------------------------------
    def _page_fingerprint(self, page) -> list[str]:
        """Prüfsummen aller Datenströme (Seiteninhalt, Bilder, Schriften) einer
        Seite – bitgenau über die gespeicherten (komprimierten) Rohdaten."""
        hashes: list[str] = []
        seen: set = set()

        def visit(key, res):
            if key in seen:
                return False
            seen.add(key)
            if isinstance(res, StreamObject):
                data = getattr(res, "_data", b"") or b""
                hashes.append(hashlib.sha1(data).hexdigest())
            return True

        for k in ("/Contents", "/Resources"):
            if k in page:
                self._walk(page.get(k), visit)
        # direkt eingebettete Inhalte (nicht indirekt) ebenfalls erfassen
        c = page.get("/Contents")
        if isinstance(c, StreamObject):
            hashes.append(hashlib.sha1(getattr(c, "_data", b"") or b"").hexdigest())
        return sorted(hashes)

    def verify_part(self, path: Path, start: int) -> list[str]:
        problems = []
        src = self._open()
        fh = open(path, "rb")
        part = PdfReader(fh, strict=False)
        for j, page in enumerate(part.pages):
            self._check_cancel()
            orig = src.pages[start + j]
            if self._page_fingerprint(page) != self._page_fingerprint(orig):
                problems.append(f"Seite {start + j + 1}: Inhalt weicht ab")
            if [round(float(x), 2) for x in page.mediabox] != [round(float(x), 2) for x in orig.mediabox]:
                problems.append(f"Seite {start + j + 1}: Seitenformat weicht ab")
        n = len(part.pages)
        self._release(src)
        self._release(part)
        fh.close()
        if n == 0:
            problems.append("Teil enthält keine Seiten")
        return problems

    def estimate_tokens(self, start: int, end: int) -> int:
        return sum(self.page_tokens[start:end + 1])

    # ---- Einheiten bilden ----------------------------------------------
    def build_units(self, chapters: list[Chapter]) -> list[Unit]:
        units: list[Unit] = []
        total = len(chapters)
        for idx, ch in enumerate(chapters, 1):
            self._check_cancel()
            parent = getattr(ch, "_parent_title", None)
            path = [parent, ch.title] if parent else [ch.title]
            self.progress(f"Analysiere Kapitel {idx}/{total}: {ch.title[:60]} "
                          f"(S. {ch.start + 1}–{ch.end + 1})")
            units.extend(self._split_chapter(ch, path))
        return units

    def _fits(self, u: Unit) -> bool:
        if u.pages > self.s.max_pages:
            return False
        if u.size > self.s.max_bytes:
            return False
        if self.s.max_tokens and u.tokens > self.s.max_tokens:
            return False
        return True

    def _make_unit(self, title: str, start: int, end: int, path: list[str]) -> Unit:
        u = Unit(title=title, start=start, end=end, path=path)
        if u.pages <= self.s.max_pages:
            u.size = self.measure(start, end)
            if self.s.max_tokens:
                u.tokens = self.estimate_tokens(start, end)
        return u

    def _split_chapter(self, ch: Chapter, path: list[str]) -> list[Unit]:
        # Kapitel schon zu viele Seiten? Dann gleich vorab zerteilen.
        if ch.pages <= self.s.max_pages:
            u = self._make_unit(ch.title, ch.start, ch.end, path)
            if self._fits(u):
                return [u]
        # 1) Unterkapitel verwenden, falls vorhanden
        if ch.children and len(ch.children) > 1:
            out: list[Unit] = []
            first = ch.children[0].start
            if first > ch.start:
                out += self._split_chapter(Chapter(title=ch.title, start=ch.start, end=first - 1),
                                           path)
            for c in ch.children:
                out += self._split_chapter(c, path + [c.title])
            return out
        # 2) Sonst Seitenbereiche
        pieces = self._split_pages(ch.start, ch.end)
        n = len(pieces)
        result = []
        for i, (a, b, size, tokens) in enumerate(pieces, 1):
            result.append(Unit(title=ch.title, start=a, end=b, path=path,
                               piece=(i, n), size=size, tokens=tokens))
        return result

    def _split_pages(self, start: int, end: int) -> list[tuple[int, int, int, int]]:
        """Zerlegt einen Seitenbereich in möglichst wenige, gleichmäßig große
        Stücke, die alle Grenzen einhalten."""
        def fits(a: int, b: int) -> tuple[bool, int, int]:
            size = self.measure(a, b)
            tok = self.estimate_tokens(a, b) if self.s.max_tokens else 0
            ok = (b - a + 1 <= self.s.max_pages and size <= self.s.max_bytes
                  and (not self.s.max_tokens or tok <= self.s.max_tokens))
            return ok, size, tok

        # 1) gierig: so viele Seiten wie möglich pro Stück
        greedy: list[tuple[int, int, int, int]] = []
        a = start
        while a <= end:
            self._check_cancel()
            b = a
            ok, size, tok = fits(a, a)
            if not ok:
                msg = (f"Seite {a + 1} allein ist {human_size(size)} groß und überschreitet die "
                       f"Grenze. Sie wird unverändert als eigener Teil gespeichert – ein "
                       f"Verkleinern würde den Inhalt verändern.")
                self.progress("  WARNUNG: " + msg)
                self.warnings.append(msg)
                greedy.append((a, a, size, tok))
                a += 1
                continue
            while b < end:
                ok2, s2, t2 = fits(a, b + 1)
                if not ok2:
                    break
                b, size, tok = b + 1, s2, t2
            greedy.append((a, b, size, tok))
            a = b + 1
        # 2) gleiche Stückzahl, aber gleichmäßig verteilt (schöner zum Lesen)
        n, pages = len(greedy), end - start + 1
        if n > 1 and all(g[0] != g[1] or fits(g[0], g[1])[0] for g in greedy):
            bounds = [start + round(i * pages / n) for i in range(n + 1)]
            even = []
            for i in range(n):
                x, y = bounds[i], bounds[i + 1] - 1
                ok, size, tok = fits(x, y)
                if not ok:
                    return greedy
                even.append((x, y, size, tok))
            return even
        return greedy

    # ---- Teile packen ---------------------------------------------------
    def pack(self, units: list[Unit]) -> list[Part]:
        parts: list[Part] = []
        cur = Part()
        for u in units:
            if cur.units:
                pages = u.end - cur.start + 1
                size = cur.est_size + u.size
                tokens = cur.tokens + u.tokens
                new_chapter = u.path != cur.units[-1].path or u.piece is None
                fits = (pages <= self.s.max_pages and size <= self.s.max_bytes
                        and (not self.s.max_tokens or tokens <= self.s.max_tokens))
                if not fits or (self.s.one_chapter_per_part and new_chapter):
                    parts.append(cur)
                    cur = Part()
            cur.units.append(u)
        if cur.units:
            parts.append(cur)
        return parts

    # ---- Beschreibungen -------------------------------------------------
    @staticmethod
    def chapter_lines(part: Part) -> list[str]:
        lines, seen = [], set()
        for u in part.units:
            key = (tuple(u.path), u.piece)
            if key in seen:
                continue
            seen.add(key)
            label = " › ".join(u.path)
            if u.piece and u.piece[1] > 1:
                label += f" (Abschnitt {u.piece[0]}/{u.piece[1]})"
            lines.append(f"{label} – S. {u.start + 1}–{u.end + 1}")
        return lines

    @staticmethod
    def short_label(part: Part) -> str:
        first, last = part.units[0], part.units[-1]
        a = first.label
        if len({tuple(u.path) for u in part.units}) == 1:
            return a
        return f"{a} bis {last.label}"

    # ---- Hauptablauf ----------------------------------------------------
    def run(self, out_dir: str | os.PathLike | None = None) -> dict:
        t0 = time.time()
        out = Path(out_dir) if out_dir else self.source.with_name(self.source.stem + "_teile")
        out.mkdir(parents=True, exist_ok=True)

        self.progress(f"Quelle: {self.source.name} – {human_size(self.source_size)}, "
                      f"{self.num_pages} Seiten")
        self.progress(f"Grenzen: {self.s.max_mb:g} MB (Ziel ≤ {human_size(self.s.max_bytes)}), "
                      f"{self.s.max_pages} Seiten"
                      + (f", ~{self.s.max_tokens} Token" if self.s.max_tokens else "")
                      + " pro Teil")

        chapters = self.find_chapters()
        self._release(self._reader_for_outline)
        self._reader_for_outline = None
        self.progress(f"Kapitelquelle: {self.chapter_source} – {len(chapters)} Kapitel")

        self.progress("Analysiere Seiten (Größe von Bildern, Schriften, Inhalten) …")
        self.index_pages()
        hard_limit = self.s.max_mb * 1024 * 1024
        for attempt in range(6):
            self.warnings.clear()
            units = self.build_units(chapters)
            parts = self.pack(units)
            parts, ratio = self._write_parts(out, parts, allow_retry=attempt < 5)
            if not ratio:
                break
            # Schätzung war zu knapp: Faktor erhöhen und neu aufteilen
            self.size_factor *= ratio * 1.03
            self.progress(f"Ein Teil war größer als erwartet – teile neu auf "
                          f"(Korrekturfaktor {self.size_factor:.2f}) …")
            for p in parts:
                (out / p.file).unlink(missing_ok=True)
            self.errors.clear()
        for p in parts:
            if p.size > hard_limit:
                msg = (f"{p.file} ist {human_size(p.size)} groß – über {self.s.max_mb:g} MB "
                       f"(einzelne Seite zu groß).")
                self.warnings.append(msg)
        # Vollständigkeit: jede Seite genau einmal, in richtiger Reihenfolge
        expected = 0
        for p in parts:
            if p.start != expected:
                self.errors.append(f"Seitenlücke/Überlappung vor Seite {p.start + 1}")
            expected = p.end + 1
        if expected != self.num_pages:
            self.errors.append(f"Nicht alle Seiten übernommen (bis {expected} von {self.num_pages})")
        total = len(parts)
        manifest = self._write_manifest(out, parts, chapters, time.time() - t0)
        self.progress(f"Fertig: {total} Teile in {out} ({time.time() - t0:.0f} s)")
        return manifest

    def _write_parts(self, out: Path, parts: list[Part],
                     allow_retry: bool = True) -> tuple[list[Part], float]:
        total = len(parts)
        width = max(2, len(str(total)))
        stem = safe_name(self.source.stem, 40)
        for i, part in enumerate(parts, 1):
            self._check_cancel()
            label = self.short_label(part)
            fname = (f"{stem}_Teil{i:0{width}d}von{total:0{width}d}_S{part.start + 1}-{part.end + 1}_"
                     f"{safe_name(label, 50)}.pdf")
            part.file = fname
            chap = self.chapter_lines(part)
            bookmarks, seen = [], set()
            for u in part.units:
                if (tuple(u.path), u.piece) not in seen:
                    seen.add((tuple(u.path), u.piece))
                    bookmarks.append((u.label, u.start - part.start))
            subject = (f"Teil {i} von {total} aus '{self.source.name}', Seiten "
                       f"{part.start + 1}–{part.end + 1}. Kapitel: " + "; ".join(chap))
            self.progress(f"Schreibe Teil {i}/{total}: {fname}")
            self._write_range(part.start, part.end, out / fname, bookmarks,
                              title=f"{self.doc_title} – Teil {i}/{total}: {label}",
                              subject=subject)
            part.size = (out / fname).stat().st_size
            if allow_retry and part.size > self.s.max_mb * 1024 * 1024 and part.pages > 1:
                # Abbruch dieses Durchgangs: neu aufteilen
                self.progress(f"  {human_size(part.size)} – zu groß, Aufteilung wird angepasst.")
                return parts[:i], part.size / max(1, part.est_size)
            if self.s.verify:
                problems = self.verify_part(out / fname, part.start)
                part.verified = not problems
                if problems:
                    self.progress("  FEHLER bei der Prüfung: " + "; ".join(problems[:5]))
                    self.errors.extend(f"Teil {i}: {p}" for p in problems)
                else:
                    self.progress(f"  geprüft: {part.pages} Seite(n) bitgenau identisch mit dem "
                                  f"Original ({human_size(part.size)})")
            else:
                self.progress(f"  {part.pages} Seiten, {human_size(part.size)}")
        return parts, 0.0

    def _write_manifest(self, out: Path, parts: list[Part], chapters: list[Chapter],
                        seconds: float) -> dict:
        total = len(parts)
        chats = math.ceil(total / self.s.files_per_chat)
        data = {
            "quelle": self.source.name,
            "titel": self.doc_title,
            "quellgroesse_bytes": self.source_size,
            "seiten": self.num_pages,
            "kapitelquelle": self.chapter_source,
            "anzahl_kapitel": len(chapters),
            "anzahl_teile": total,
            "grenzen": {
                "max_mb": self.s.max_mb, "sicherheitsabstand": self.s.safety,
                "max_seiten": self.s.max_pages, "max_token": self.s.max_tokens,
            },
            "dauer_sekunden": round(seconds, 1),
            "inhalt_unveraendert_geprueft": self.s.verify,
            "pruefung_ok": (not self.errors) if self.s.verify else None,
            "fehler": self.errors,
            "warnungen": self.warnings,
            "teile": [],
        }
        md = [f"# Übersicht: {self.doc_title}", "",
              f"- **Quelldatei:** {self.source.name} ({human_size(self.source_size)}, "
              f"{self.num_pages} Seiten)",
              f"- **Aufgeteilt in:** {total} Teile",
              f"- **Kapitel erkannt über:** {self.chapter_source} ({len(chapters)} Kapitel)",
              f"- **Grenzen pro Teil:** ≤ {self.s.max_mb:g} MB, ≤ {self.s.max_pages} Seiten"
              + (f", ≈ ≤ {self.s.max_tokens} Token" if self.s.max_tokens else ""),
              f"- **Hinweis:** max. {self.s.files_per_chat} Dateien pro Chat – "
              f"mindestens {chats} Chat(s) nötig. Für gute Antworten besser "
              f"1–2 Teile pro Chat hochladen.",
              "- **Inhalt:** Seiten werden 1:1 kopiert – Texte, Bilder und Schriften "
              "bleiben bitgenau unverändert, es wird nichts neu komprimiert oder ergänzt "
              "(nur Titel/Lesezeichen als Metadaten)."
              + (" Jede Seite wurde nach dem Schreiben mit dem Original verglichen: "
                 + ("**alle identisch**." if not self.errors else "**ABWEICHUNGEN gefunden!**")
                 if self.s.verify else ""), ""]
        if self.warnings or self.errors:
            md += ["## Hinweise", ""] + [f"- ⚠️ {w}" for w in self.warnings + self.errors] + [""]
        md += [
              "| Teil | Datei | Seiten | Größe | Kapitel |",
              "|---:|---|---|---:|---|"]
        for i, p in enumerate(parts, 1):
            chap = self.chapter_lines(p)
            data["teile"].append({
                "nummer": i, "datei": p.file, "seite_von": p.start + 1, "seite_bis": p.end + 1,
                "seitenanzahl": p.pages, "groesse_bytes": p.size,
                "geschaetzte_token": p.tokens or None,
                "inhalt_geprueft_identisch": p.verified,
                "kapitel": [{"pfad": u.path, "titel": u.label,
                             "seite_von": u.start + 1, "seite_bis": u.end + 1}
                            for u in p.units],
                "beschreibung": f"Teil {i}/{total}: " + "; ".join(chap),
            })
            md.append(f"| {i} | `{p.file}` | {p.start + 1}–{p.end + 1} ({p.pages}) | "
                      f"{human_size(p.size)} | " + "<br>".join(c.replace("|", "/") for c in chap) + " |")
        md += ["", "## Vorlage für den Chat", "",
               "Diesen Text beim Hochladen eines Teils in Claude einfügen:", "",
               "```",
               f"Dies ist Teil X von {total} des Dokuments \"{self.doc_title}\" "
               f"(Seiten A–B). Enthaltene Kapitel: …",
               "Bitte berücksichtige, dass es sich um einen Ausschnitt handelt.",
               "```", ""]
        for i, p in enumerate(parts, 1):
            md.append(f"**Teil {i}:** Dies ist Teil {i} von {total} des Dokuments "
                      f"\"{self.doc_title}\" (Seiten {p.start + 1}–{p.end + 1}). "
                      f"Enthaltene Kapitel: " + "; ".join(self.chapter_lines(p)) + "  ")
        (out / "00_UEBERSICHT.md").write_text("\n".join(md) + "\n", encoding="utf-8")
        (out / "00_uebersicht.json").write_text(json.dumps(data, ensure_ascii=False, indent=2),
                                                encoding="utf-8")
        return data


# --------------------------------------------------------------------------
# Kommandozeile
# --------------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="pdf_splitter",
        description="Teilt große PDFs kapitelweise in Claude-taugliche Teile auf.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter)
    p.add_argument("pdf", help="Pfad zur PDF-Datei")
    p.add_argument("-o", "--ausgabe", help="Ausgabeordner (Standard: <name>_teile neben der PDF)")
    p.add_argument("--max-mb", type=float, default=DEFAULT_MAX_MB, help="max. Dateigröße pro Teil in MB")
    p.add_argument("--max-seiten", type=int, default=DEFAULT_MAX_PAGES, help="max. Seiten pro Teil")
    p.add_argument("--max-token", type=int, default=None,
                   help="optional: max. geschätzte Token pro Teil (langsamer, liest Text aus)")
    p.add_argument("--sicherheit", type=float, default=DEFAULT_SAFETY,
                   help="Sicherheitsabstand zur MB-Grenze (0.05 = 5 %%)")
    p.add_argument("--kapitel-ebene", type=int, default=None,
                   help="Lesezeichen-Ebene für Kapitel (1 = oberste; Standard: automatisch)")
    p.add_argument("--ein-kapitel-pro-teil", action="store_true",
                   help="Kapitel nicht zusammenfassen – jedes Kapitel bekommt eigene Datei(en)")
    p.add_argument("--keine-erkennung", action="store_true",
                   help="keine Überschriftensuche im Text, wenn Lesezeichen fehlen")
    p.add_argument("--ohne-lesezeichen", action="store_true",
                   help="keine Lesezeichen in die Teil-PDFs schreiben")
    p.add_argument("--ohne-pruefung", action="store_true",
                   help="Teile nach dem Schreiben NICHT mit dem Original vergleichen (schneller)")
    p.add_argument("--passwort", help="Passwort für verschlüsselte PDFs")
    p.add_argument("--dateien-pro-chat", type=int, default=DEFAULT_FILES_PER_CHAT)
    p.add_argument("--nur-analyse", action="store_true",
                   help="nur Kapitel anzeigen, nichts schreiben")
    p.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    return p


def settings_from_args(a: argparse.Namespace) -> Settings:
    return Settings(max_mb=a.max_mb, max_pages=a.max_seiten, max_tokens=a.max_token,
                    safety=a.sicherheit, chapter_level=a.kapitel_ebene,
                    one_chapter_per_part=a.ein_kapitel_pro_teil,
                    detect_headings=not a.keine_erkennung,
                    keep_bookmarks=not a.ohne_lesezeichen, password=a.passwort,
                    files_per_chat=a.dateien_pro_chat, verify=not a.ohne_pruefung)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        splitter = PdfSplitter(args.pdf, settings_from_args(args))
        if args.nur_analyse:
            chapters = splitter.find_chapters()
            print(f"\nKapitelquelle: {splitter.chapter_source}")
            for c in chapters:
                parent = getattr(c, "_parent_title", None)
                name = f"{parent} › {c.title}" if parent else c.title
                print(f"  S. {c.start + 1:>5}–{c.end + 1:<5} ({c.pages:>4} S.)  {name}")
            return 0
        result = splitter.run(args.ausgabe)
        if result.get("fehler"):
            print("ACHTUNG: Die Prüfung hat Abweichungen gefunden – siehe 00_UEBERSICHT.md",
                  file=sys.stderr)
            return 3
        return 0
    except KeyboardInterrupt:
        print("\nAbgebrochen.", file=sys.stderr)
        return 130
    except Exception as exc:  # noqa: BLE001
        print(f"FEHLER: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
