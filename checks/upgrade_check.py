"""Private helper for upgrade.sh. All UI actions and reads target the vetted, fresh emulator."""
import json
import re
import sqlite3
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "uk.co.violetscale.probe"
NOTE_WEIGHT = "FixtureAlpha"
NOTE_WAIST = "FixtureBeta"
DB = "violet-history.db"
SERIAL, WORK, MODE, *ARGS = sys.argv[1:]
WORK = Path(WORK)


def require(condition, message):
    if not condition:
        raise SystemExit("Upgrade check: " + message)


def adb(*args, check=True, timeout=20):
    result = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, timeout=timeout)
    if check:
        require(result.returncode == 0, "ADB operation failed (" + str(args[0]) + ").")
    return result


def tree():
    remote = "/data/local/tmp/scales-upgrade-ui.xml"
    # Never reuse a previous hierarchy after a failed dump.
    adb("shell", "rm", "-f", remote)
    if adb("shell", "uiautomator", "dump", remote, check=False, timeout=12).returncode:
        return []
    data = adb("exec-out", "cat", remote, check=False)
    try:
        root = ET.fromstring(data.stdout)
    except ET.ParseError:
        return []
    return [n for n in root.iter("node") if n.get("package") == PKG]


def matches(node, label):
    return any(node.get(key, "").casefold() == label.casefold() for key in ("text", "content-desc"))


def wait_for(select, description):
    end = time.monotonic() + 35
    while time.monotonic() < end:
        try:
            found = select(tree())
        except subprocess.TimeoutExpired:
            found = None
        if found is not None:
            return found
        time.sleep(0.35)
    raise SystemExit("Upgrade check: UI did not show " + description + ".")


def labelled(label):
    return wait_for(lambda nodes: next((n for n in nodes if matches(n, label)), None), label)


def tap(node):
    bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    require(bounds is not None, "UI control had no tappable bounds.")
    left, top, right, bottom = map(int, bounds.groups())
    require(right > left and bottom > top, "UI control was hidden.")
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def press(label):
    tap(labelled(label))


def fill(index, value):
    def select(nodes):
        inputs = [n for n in nodes if n.get("class") == "android.widget.EditText"]
        return inputs[index] if len(inputs) > index else None
    tap(wait_for(select, "measurement field " + str(index + 1)))
    adb("shell", "input", "text", value)
    # Confirm delivery in the focused field before interacting with another control.
    wait_for(lambda nodes: next((n for n in nodes if n.get("class") == "android.widget.EditText"
                                  and n.get("text") == value), None), "entered fixture value")


def seed():
    press("Profile and settings")
    press("Height")
    fill(0, "178")
    press("Save")
    press("Back to Today")
    press("History")
    press("Add an entry")
    fill(0, "82.35")
    fill(1, "87.5")
    fill(2, NOTE_WEIGHT)
    press("Save")
    labelled(NOTE_WEIGHT)
    press("Add an entry")
    fill(1, "86.75")
    fill(2, NOTE_WAIST)
    press("Save")
    labelled(NOTE_WAIST)
    press("Profile and settings")
    press("Display units")
    press("Pounds")
    labelled("Pounds")
    print("Created two synthetic measurements, a height and a display preference through Scales 1.2.")


def history():
    press("History")
    # Ensure both rows are present in the same freshly rendered activity.
    wait_for(lambda nodes: True if all(any(matches(n, label) for n in nodes)
                                      for label in (NOTE_WEIGHT, NOTE_WAIST)) else None, "both fixture notes")


def snapshot(tag):
    require(tag in ("1.2", "1.3", "restart"), "Unknown snapshot.")
    require(adb("shell", "pidof", PKG, check=False).returncode != 0, "Application still running during snapshot.")
    folder = WORK / tag
    folder.mkdir()
    for suffix in ("", "-wal", "-journal"):
        name = DB + suffix
        remote = "/data/user/0/" + PKG + "/databases/" + name
        if adb("shell", "test", "-f", remote, check=False).returncode and suffix:
            continue
        data = adb("exec-out", "cat", remote).stdout
        (folder / name).write_bytes(data)
    with sqlite3.connect(folder / DB) as db:
        db.row_factory = sqlite3.Row
        require(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok", "SQLite integrity check failed.")
        entries = [dict(row) for row in db.execute("SELECT * FROM entries ORDER BY id")]
        prefs = dict(db.execute("SELECT key, value FROM settings ORDER BY key"))
        version = db.execute("PRAGMA user_version").fetchone()[0]
    saved = {"entries": entries, "settings": prefs, "schema": version}
    (folder / "snapshot.json").write_text(json.dumps(saved, ensure_ascii=False), encoding="utf-8")
    return saved


def compare():
    def read(tag):
        return json.loads((WORK / tag / "snapshot.json").read_text(encoding="utf-8"))
    before = read("1.2")
    by_note = {row["note"]: row for row in before["entries"]}
    require(before["schema"] == 3 and set(by_note) == {NOTE_WEIGHT, NOTE_WAIST}, "Baseline rows/schema differ.")
    weight, waist = by_note[NOTE_WEIGHT], by_note[NOTE_WAIST]
    require(weight["id"] != waist["id"] and all(row["source"] == "manual" and row["time_ms"] > 0
                                               for row in (weight, waist)), "Baseline identity or provenance differs.")
    require(weight["weight_kg"] == 82.35 and weight["waist_cm"] == 87.5 and
            weight["original_unit"] == "kg" and weight["height_cm_at_entry"] == 178,
            "The baseline weight was not saved with the requested values.")
    require(waist["weight_kg"] is None and waist["waist_cm"] == 86.75 and waist["waist_source"] == "manual",
            "The baseline waist-only measurement was not saved correctly.")
    require(before["settings"].get("unit") == "lb" and float(before["settings"].get("heightCm", 0)) == 178,
            "Baseline preference values differ.")
    for tag in ("1.3", "restart"):
        after = read(tag)
        require(after["schema"] >= before["schema"], "The database schema version regressed.")
        require(len(after["entries"]) == len(before["entries"]), "The number of measurements changed.")
        for old, new in zip(before["entries"], after["entries"]):
            require(all(key in new and new[key] == value for key, value in old.items()),
                    "An original measurement field changed.")
        require(all(key in after["settings"] and after["settings"][key] == value
                    for key, value in before["settings"].items()), "A saved preference changed.")


if MODE == "seed":
    seed()
elif MODE == "history":
    history()
elif MODE == "snapshot":
    snapshot(ARGS[0])
elif MODE == "compare":
    compare()
else:
    raise SystemExit("Unknown upgrade check phase.")
