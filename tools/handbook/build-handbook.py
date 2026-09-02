#!/usr/bin/env python3
"""Render the German handbook source into a PDF and copy it to the user desktop."""
from __future__ import annotations

import argparse
import datetime as dt
import html
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/handbook/handbook.html"
CSS = ROOT / "docs/handbook/handbook.css"
COMMANDS = ROOT / "docs/handbook/commands.json"
BUILD_DIR = ROOT / "docs/handbook/build"
PDF_NAME = "MapleStory-Coop-Remix-Handbuch-DE.pdf"
COMMANDS_MARKER = "<!-- COMMANDS -->"

RANK_TITLES = {
    0: "Member — Rang 0",
    1: "Donator — Rang 1",
    2: "JrGM — Rang 2",
    3: "GM — Rang 3",
    4: "SuperGM — Rang 4",
    5: "Developer — Rang 5",
    6: "Admin — Rang 6",
}

SAFETY_LABELS = {
    "SAFE": "sicher",
    "STATE": "Zustand",
    "DANGER": "gefährlich",
    "OPS": "Betrieb",
    "VPS-NO": "nicht im Betrieb",
}

SAFETY_CLASS = {
    "SAFE": "safe",
    "STATE": "state",
    "DANGER": "danger",
    "OPS": "ops",
    "VPS-NO": "vps-no",
}


def esc(value: str) -> str:
    return html.escape(value, quote=True)


def command_rows(commands: list[dict]) -> str:
    parts: list[str] = []
    for rank in sorted(RANK_TITLES):
        group = sorted(
            [command for command in commands if command["rank"] == rank],
            key=lambda command: command["name"],
        )
        if not group:
            continue
        parts.append(f"        <h3>{RANK_TITLES[rank]} — {len(group)} Einträge</h3>")
        parts.append("        <table>")
        parts.append("            <thead>")
        parts.append("            <tr>")
        parts.append("                <th style=\"width:22%\">Befehl</th>")
        parts.append("                <th style=\"width:30%\">Syntax</th>")
        parts.append("                <th style=\"width:26%\">Wirkung</th>")
        parts.append("                <th style=\"width:11%\">Klasse</th>")
        parts.append("                <th style=\"width:11%\">Hinweis</th>")
        parts.append("            </tr>")
        parts.append("            </thead>")
        parts.append("            <tbody>")
        for command in group:
            name_cell = esc(command["prefix"] + command["name"])
            if command.get("aliasOf"):
                name_cell += f"<br><span class=\"tag\">Alias von {esc(command['prefix'] + command['aliasOf'])}</span>"
            safety = command["safety"]
            note = esc(command["notes"]) if command.get("notes") else "—"
            parts.append("            <tr>")
            parts.append(f"                <td>{name_cell}</td>")
            parts.append(f"                <td class=\"syntax\">{esc(command['syntax'])}</td>")
            parts.append(f"                <td>{esc(command['purpose'])}</td>")
            parts.append(
                f"                <td><span class=\"badge {SAFETY_CLASS[safety]}\">"
                f"{esc(SAFETY_LABELS[safety])}</span></td>"
            )
            parts.append(f"                <td class=\"small\">{note}</td>")
            parts.append("            </tr>")
        parts.append("            </tbody>")
        parts.append("        </table>")
    return "\n".join(parts)


def render(intermediate: Path) -> None:
    source = SOURCE.read_text(encoding="utf-8")
    css = CSS.read_text(encoding="utf-8")
    commands = json.loads(COMMANDS.read_text(encoding="utf-8"))["commands"]

    if COMMANDS_MARKER not in source:
        raise SystemExit("handbook source is missing the command marker")

    stamp = dt.datetime.now().strftime("%d.%m.%Y")
    source = source.replace("[DATUM]", stamp)
    source = source.replace(
        '<link rel="stylesheet" href="handbook.css">',
        f"<style>\n{css}\n</style>",
    )
    source = source.replace(COMMANDS_MARKER, command_rows(commands))
    # LibreOffice paints nested <code> content line by line with the inline-code
    # background. <pre> already supplies the required monospace semantics.
    source = source.replace("<pre><code>", "<pre>").replace("</code></pre>", "</pre>")

    intermediate.parent.mkdir(parents=True, exist_ok=True)
    intermediate.write_text(source, encoding="utf-8")


def to_pdf(intermediate: Path, output: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="handbook-lo-") as profile:
        subprocess.run(
            [
                "soffice",
                f"-env:UserInstallation=file://{profile}",
                "--headless",
                "--norestore",
                "--convert-to",
                "pdf:writer_pdf_Export",
                "--outdir",
                str(intermediate.parent),
                str(intermediate),
            ],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
    produced = intermediate.with_suffix(".pdf")
    if not produced.exists():
        raise SystemExit("LibreOffice did not produce a PDF")
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(produced), str(output))


def verify(pdf: Path) -> int:
    info = subprocess.run(["pdfinfo", str(pdf)], check=True, stdout=subprocess.PIPE).stdout.decode()
    match = re.search(r"Pages:\s+(\d+)", info)
    pages = int(match.group(1)) if match else 0
    text = subprocess.run(["pdftotext", str(pdf), "-"], check=True, stdout=subprocess.PIPE).stdout.decode()
    missing = [name for name in ("@commands", "!shutdown", "@companion", "Companion") if name not in text]
    print(f"pdf: {pdf}")
    print(f"pages: {pages}")
    print(f"size: {pdf.stat().st_size} bytes")
    if pages < 10:
        raise SystemExit("PDF is suspiciously short")
    if missing:
        raise SystemExit("Missing expected handbook content: " + ", ".join(missing))
    return pages


def copy_to_desktop(pdf: Path) -> Path | None:
    desktop = Path(os.path.expanduser("~")) / "Schreibtisch"
    if not desktop.is_dir():
        home = Path(os.path.expanduser("~"))
        for candidate in ("Desktop", "Scrivania", "Bureau"):
            if (home / candidate).is_dir():
                desktop = home / candidate
                break
        else:
            print(f"Desktop directory not found; kept PDF in {pdf}")
            return None
    target = desktop / pdf.name
    shutil.copy2(pdf, target)
    print(f"desktop copy: {target}")
    return target


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--no-pdf", action="store_true", help="render the intermediate HTML only")
    parser.add_argument("--no-desktop", action="store_true", help="do not copy the PDF to the desktop")
    args = parser.parse_args()

    if not COMMANDS.exists():
        raise SystemExit("docs/handbook/commands.json is missing; run tools/handbook/generate-commands.py")

    BUILD_DIR.mkdir(parents=True, exist_ok=True)
    intermediate = BUILD_DIR / "handbook.render.html"
    render(intermediate)
    print(f"intermediate html: {intermediate}")

    if args.no_pdf:
        return

    pdf = ROOT / "docs/handbook" / PDF_NAME
    to_pdf(intermediate, pdf)
    verify(pdf)

    if not args.no_desktop:
        copy_to_desktop(pdf)


if __name__ == "__main__":
    sys.exit(main())
