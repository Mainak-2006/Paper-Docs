"""Summarize REA's retained evidence and SDK observations, without executing the APK.

Usage: python research/summarize_apk.py <base.apk> <aapt2>
The REA inventory is kept verbatim in base-artifact.json. SDK observations are
explicitly separate; they are not REA evidence or runtime observations.
"""
import json
import pathlib
import subprocess
import sys
import zipfile

root = pathlib.Path(__file__).resolve().parent
evidence = json.loads((root / "base-artifact.json").read_text())
result = evidence["normalized_result"]
summary = {
    "evidence_id": evidence["evidence_id"],
    "subject": evidence["subject"],
    "operation": evidence["operation"],
    "result_keys": list(result),
    "substeps": [
        {key: step.get(key) for key in ("operation", "status", "evidence_id")}
        for step in result.get("substeps", [])
    ],
    "limitations": evidence.get("limitations"),
    "unknowns": evidence.get("unknowns"),
}
apk, aapt = sys.argv[1:3]
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    summary["supplemental_zip_inventory"] = {
        "member_count": len(names),
        "dex_files": [name for name in names if name.endswith(".dex")],
        "asset_paths_sample": [name for name in names if name.startswith("assets/")][:60],
    }

for name, arguments in (
    ("sdk-badging.txt", ["dump", "badging", apk]),
    ("sdk-manifest.txt", ["dump", "xmltree", apk, "--file", "AndroidManifest.xml"]),
    ("sdk-resources.txt", ["dump", "resources", apk]),
):
    run = subprocess.run([aapt, *arguments], capture_output=True, text=True, check=True)
    (root / name).write_text(run.stdout)

(root / "artifact-summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
