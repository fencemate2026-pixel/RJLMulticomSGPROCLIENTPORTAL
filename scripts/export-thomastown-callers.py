"""Export CommercialWhitelistSeed.kt numbers as validated E.164 for site kit."""
from __future__ import annotations

import csv
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SEED = (
    ROOT
    / "app/src/main/java/com/example/rjlmulticomsg_proclientportal/data/local/CommercialWhitelistSeed.kt"
)
OUT_DIRS = [
    ROOT / "firmware/sgpro_gsm_controller",
    Path(r"C:\Users\User\OneDrive\Desktop\ESP32-FLASH-THOMASTOWN"),
    Path(r"C:\Users\User\OneDrive\Desktop\sgpro_gsm_controller-DOWNLOAD"),
]


def normalize(raw: str) -> str | None:
    d = re.sub(r"[\s()-]", "", raw.strip())
    if d.startswith("+"):
        d = d[1:]
    elif d.startswith("00"):
        d = d[2:]
    if re.fullmatch(r"04\d{8}", d):
        d = "61" + d[1:]
    elif re.fullmatch(r"4\d{8}", d):
        d = "61" + d
    if re.fullmatch(r"614\d{8}", d):
        return "+" + d
    return None


def main() -> None:
    text = SEED.read_text(encoding="utf-8")
    rows = re.findall(
        r'Row\("([^"]*)",\s*"([^"]*)",\s*"([^"]*)",\s*"([^"]*)"',
        text,
    )
    by: dict[str, dict] = {}
    bad: list[tuple[str, str]] = []
    for unit, name, mobile, role in rows:
        e164 = normalize(mobile)
        if not e164:
            bad.append((name, mobile))
            continue
        if e164 not in by:
            by[e164] = {
                "e164": e164,
                "name": name,
                "unit": unit,
                "role": role,
                "mobile_local": "0" + e164[3:],
            }
        else:
            by[e164]["unit"] = by[e164]["unit"] + "|" + unit

    lines = [
        "Thomastown Settlement Rd — authorised GSM mobiles (E.164 for Multicom/ESP)",
        "Source: CommercialWhitelistSeed.kt",
        "Gate SIM people DIAL: 0414 371 302 (+61414371302) — not a whitelist entry",
        "ESP/cloud storage format: +614xxxxxxxx  (Caller ID must be visible)",
        "",
        f"SEED_ROWS={len(rows)}  UNIQUE_E164={len(by)}  BAD={len(bad)}",
        "",
        f"{'E.164':<15} {'Local':<12} {'Unit':<10} Name",
        "-" * 72,
    ]
    for e164 in sorted(by):
        r = by[e164]
        lines.append(
            f"{r['e164']:<15} {r['mobile_local']:<12} {r['unit']:<10} {r['name']}"
        )
    lines += [
        "",
        "RJL admin: +61400101132 (0400 101 132)",
        "Duplicates in seed are collapsed to one ESP entry (first name wins).",
        "",
    ]
    if bad:
        lines.append("BAD NUMBERS (must fix in seed):")
        for name, mobile in bad:
            lines.append(f"  {name}: {mobile}")

    text_out = "\n".join(lines) + "\n"
    for d in OUT_DIRS:
        d.mkdir(parents=True, exist_ok=True)
        p = d / "THOMASTOWN-CALLERS-E164.txt"
        p.write_text(text_out, encoding="utf-8")
        print("wrote", p)

    csv_path = OUT_DIRS[1] / "THOMASTOWN-CALLERS-E164.csv"
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["phoneNumberE164", "mobileLocal", "unit", "displayName", "role"])
        for e164 in sorted(by):
            r = by[e164]
            w.writerow(
                [r["e164"], r["mobile_local"], r["unit"], r["name"], r["role"]]
            )
    print("csv", csv_path, "unique", len(by), "bad", len(bad))
    if bad:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
