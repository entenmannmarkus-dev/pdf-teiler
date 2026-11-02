"""Selbsttest: python -m unittest discover -s tests"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from pypdf import PdfReader, PdfWriter  # noqa: E402
from pypdf.generic import DecodedStreamObject, NameObject  # noqa: E402

import pdf_splitter as ps  # noqa: E402


def make_pdf(path: Path, pages: int, chapters: dict[int, str] | None, payload: int = 0) -> None:
    w = PdfWriter()
    for i in range(pages):
        page = w.add_blank_page(595, 842)
        s = DecodedStreamObject()
        # einzigartiger Inhalt + optional "Bild"-Ballast (nicht komprimiert)
        s.set_data(f"BT /F1 12 Tf 72 700 Td (Seite {i + 1}) Tj ET\n% ".encode() + bytes(payload))
        page[NameObject("/Contents")] = w._add_object(s)
    for p, title in (chapters or {}).items():
        w.add_outline_item(title, p)
    with open(path, "wb") as fh:
        w.write(fh)


class SplitterTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def run_split(self, pdf, **kw):
        s = ps.Settings(**kw)
        out = self.tmp / "out"
        res = ps.PdfSplitter(pdf, s, progress=lambda m: None).run(out)
        return res, out

    def test_chapters_pages_complete_and_verified(self):
        pdf = self.tmp / "buch.pdf"
        make_pdf(pdf, 250, {0: "Vorwort", 10: "Kapitel 1", 130: "Kapitel 2", 240: "Anhang"})
        res, out = self.run_split(pdf)
        self.assertTrue(res["pruefung_ok"])
        self.assertEqual(sum(t["seitenanzahl"] for t in res["teile"]), 250)
        for t in res["teile"]:
            self.assertLessEqual(t["seitenanzahl"], 100)
            self.assertTrue((out / t["datei"]).is_file())
            self.assertEqual(len(PdfReader(str(out / t["datei"])).pages), t["seitenanzahl"])
        titles = [k["titel"] for t in res["teile"] for k in t["kapitel"]]
        self.assertTrue(any("Kapitel 2" in x for x in titles))
        self.assertTrue((out / "00_UEBERSICHT.md").is_file())
        json.loads((out / "00_uebersicht.json").read_text(encoding="utf-8"))

    def test_size_limit(self):
        pdf = self.tmp / "gross.pdf"
        make_pdf(pdf, 60, {0: "A", 30: "B"}, payload=300_000)   # ~18 MB
        res, out = self.run_split(pdf, max_mb=3)
        for t in res["teile"]:
            self.assertLessEqual((out / t["datei"]).stat().st_size, 3 * 1024 * 1024)
        self.assertTrue(res["pruefung_ok"])

    def test_no_outline_falls_back_to_blocks(self):
        pdf = self.tmp / "ohne.pdf"
        make_pdf(pdf, 230, None)
        res, _ = self.run_split(pdf)
        self.assertEqual(len(res["teile"]), 3)
        self.assertEqual([t["seitenanzahl"] for t in res["teile"]], [77, 76, 77])


if __name__ == "__main__":
    unittest.main()
