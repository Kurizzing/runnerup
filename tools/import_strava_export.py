#!/usr/bin/env python3
"""Import runs from a Strava data export into a RunnerUp debug install.

Strava: Settings > My Account > Download or Delete Your Account > Request your archive.
Unzip it and point this script at the folder (it contains activities.csv and activities/).

Usage (the app must have been launched once so the database exists):
    python tools/import_strava_export.py PATH_TO_EXPORT            # import runs
    python tools/import_strava_export.py PATH_TO_EXPORT --dry-run  # only list them
    python tools/import_strava_export.py PATH_TO_EXPORT --serial SERIAL   # pick a device
    python tools/import_strava_export.py --remove                  # remove imported runs

Each imported activity is tagged with its Strava ID, so running the import again only adds
runs that are not imported yet. Other activities are left alone.
Supported track files: .gpx and .gpx.gz (Strava exports .fit/.tcx files are skipped).
"""

import argparse
import csv
import gzip
import math
import os
import sqlite3
import sys
import tempfile
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path

sys.dont_write_bytecode = True  # no __pycache__ in the repository
sys.path.insert(0, str(Path(__file__).resolve().parent))
from seed_demo_history import DB_NAME, Adb, find_adb  # noqa: E402

TAG_PREFIX = "strava:"
SPORT_RUNNING = 0
TYPE_START, TYPE_END, TYPE_GPS = 1, 2, 3
LAP_METERS = 1000
GPX_NS = {"g": "http://www.topografix.com/GPX/1/1"}


def read_runs(export_dir):
    """Runs from activities.csv as dicts (first column of each name, Strava repeats some)."""
    with open(os.path.join(export_dir, "activities.csv"), encoding="utf-8") as f:
        rows = list(csv.reader(f))
    header, data = rows[0], rows[1:]
    col = {}
    for i, name in enumerate(header):
        col.setdefault(name, i)
    runs = []
    for r in data:
        if r[col["Activity Type"]] not in ("Run", "Trail Run", "Virtual Run"):
            continue

        def get(name):
            i = col.get(name)
            return r[i].strip() if i is not None and i < len(r) else ""

        runs.append(
            dict(
                id=get("Activity ID"),
                name=get("Activity Name"),
                description=get("Activity Description"),
                filename=get("Filename"),
                elapsed=float(get("Elapsed Time") or 0),
                moving=float(get("Moving Time") or 0),
                distance_km=float(get("Distance") or 0),
                avg_hr=get("Average Heart Rate"),
                max_hr=get("Max Heart Rate"),
                date=get("Activity Date"),
            )
        )
    return runs


def parse_time(text):
    return datetime.strptime(text.strip(), "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def read_gpx(path):
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as f:
        root = ET.parse(f).getroot()
    points = []
    for pt in root.iterfind(".//g:trkpt", GPX_NS):
        t = pt.find("g:time", GPX_NS)
        if t is None:
            continue
        ele = pt.find("g:ele", GPX_NS)
        hr = None
        for el in pt.iter():
            if el.tag.endswith("}hr"):
                hr = int(float(el.text))
        points.append(
            dict(
                lat=float(pt.get("lat")),
                lon=float(pt.get("lon")),
                alt=float(ele.text) if ele is not None else None,
                time=parse_time(t.text),
                hr=hr,
            )
        )
    return points


def haversine(a, b):
    r = 6371000.0
    p1, p2 = math.radians(a["lat"]), math.radians(b["lat"])
    dp, dl = p2 - p1, math.radians(b["lon"] - a["lon"])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def build_activity(run, points):
    start = points[0]["time"]
    t0 = start.timestamp()
    rows, laps = [], []
    dist = 0.0
    lap, lap_start_t, lap_start_d, lap_hr = 0, 0.0, 0.0, []
    for i, p in enumerate(points):
        if i:
            dist += haversine(points[i - 1], p)
        elapsed = p["time"].timestamp() - t0
        ptype = TYPE_START if i == 0 else (TYPE_END if i == len(points) - 1 else TYPE_GPS)
        rows.append((lap, ptype, int(p["time"].timestamp() * 1000), int(elapsed * 1000), dist,
                     p["lat"], p["lon"], p["alt"], p["hr"]))
        if p["hr"]:
            lap_hr.append(p["hr"])
        if dist - lap_start_d >= LAP_METERS or i == len(points) - 1:
            laps.append((lap, elapsed - lap_start_t, dist - lap_start_d,
                         int(sum(lap_hr) / len(lap_hr)) if lap_hr else None,
                         max(lap_hr) if lap_hr else None))
            lap += 1
            lap_start_t, lap_start_d, lap_hr = elapsed, dist, []

    # Totals as shown by Strava (moving time, its distance); fall back to the track
    distance = run["distance_km"] * 1000 if run["distance_km"] > 0 else dist
    duration = run["moving"] or run["elapsed"] or (points[-1]["time"] - start).total_seconds()
    hrs = [p["hr"] for p in points if p["hr"]]
    avg_hr = int(float(run["avg_hr"])) if run["avg_hr"] else (int(sum(hrs) / len(hrs)) if hrs else None)
    max_hr = int(float(run["max_hr"])) if run["max_hr"] else (max(hrs) if hrs else None)
    return dict(start=int(t0), distance=distance, time=int(round(duration)), name=run["name"],
                comment=run["description"] or None, avg_hr=avg_hr, max_hr=max_hr,
                tag=TAG_PREFIX + run["id"], points=rows, laps=laps)


def insert(db, a):
    # Note: in RunnerUp the column names for avg/max HR are swapped (see Constants.java)
    cur = db.execute(
        "insert into activity (start_time, distance, time, name, comment, type,"
        " max_hr, avg_hr, deleted, nullColumnHack) values (?,?,?,?,?,?,?,?,0,?)",
        (a["start"], a["distance"], a["time"], a["name"], a["comment"], SPORT_RUNNING,
         a["avg_hr"], a["max_hr"], a["tag"]))
    aid = cur.lastrowid
    db.executemany(
        "insert into location (activity_id, lap, type, time, elapsed, distance, latitude,"
        " longitude, altitude, hr) values (?,?,?,?,?,?,?,?,?,?)",
        [(aid,) + p for p in a["points"]])
    db.executemany(
        "insert into lap (activity_id, lap, type, time, distance, avg_hr, max_hr)"
        " values (?,?,0,?,?,?,?)",
        [(aid, n, int(round(t)), d, ah, mh) for n, t, d, ah, mh in a["laps"]])


def remove_imported(db):
    ids = [r[0] for r in db.execute(
        "select _id from activity where nullColumnHack like ?", (TAG_PREFIX + "%",))]
    for table in ("location", "lap"):
        db.executemany(f"delete from {table} where activity_id = ?", [(i,) for i in ids])
    db.execute("delete from activity where nullColumnHack like ?", (TAG_PREFIX + "%",))
    return len(ids)


def import_runs(db, export_dir, dry_run):
    done = {r[0] for r in db.execute(
        "select nullColumnHack from activity where nullColumnHack like ?", (TAG_PREFIX + "%",))}
    added = skipped = 0
    for run in read_runs(export_dir):
        tag = TAG_PREFIX + run["id"]
        fname = run["filename"]
        if tag in done:
            skipped += 1
            continue
        if not fname or not (fname.endswith(".gpx") or fname.endswith(".gpx.gz")):
            print(f"  skip {run['date']} {run['name']!r}: no GPX track ({fname or 'none'})")
            skipped += 1
            continue
        points = read_gpx(os.path.join(export_dir, fname))
        if len(points) < 2:
            print(f"  skip {run['date']} {run['name']!r}: empty track")
            skipped += 1
            continue
        a = build_activity(run, points)
        print(f"  {'would add' if dry_run else 'add'} {run['date']:>26}  {run['name'][:30]:30}"
              f"  {a['distance'] / 1000:6.2f} km  {len(points):5} points")
        if not dry_run:
            insert(db, a)
        added += 1
    return added, skipped


def main():
    # Activity names can contain emoji that a Windows console cannot print
    sys.stdout.reconfigure(errors="replace")
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("export_dir", nargs="?", help="unzipped Strava export folder")
    ap.add_argument("--package", default="org.runnerup.debug")
    ap.add_argument("--serial", help="adb device serial (when several are connected)")
    ap.add_argument("--db-file", help="work on a local runnerup.db instead of a device")
    ap.add_argument("--dry-run", action="store_true", help="only list what would be imported")
    ap.add_argument("--remove", action="store_true", help="remove runs imported from Strava")
    args = ap.parse_args()
    if not args.remove and not args.export_dir:
        ap.error("export_dir is required (or use --remove)")

    if args.db_file:
        db = sqlite3.connect(args.db_file)
        run_on_db(db, args)
        return

    adb = Adb(find_adb(), args.serial)
    pkg = args.package
    if DB_NAME not in adb.run("shell", "run-as", pkg, "ls", "databases"):
        sys.exit(f"{DB_NAME} not found for {pkg}: launch the app once, then rerun")
    if not args.dry_run:
        adb.run("shell", "am", "force-stop", pkg)
    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, DB_NAME)
        with open(local, "wb") as f:
            f.write(adb.run("exec-out", "run-as", pkg, "cat", f"databases/{DB_NAME}",
                            binary=True))
        db = sqlite3.connect(local)
        changed = run_on_db(db, args)
        db.close()
        if changed and not args.dry_run:
            remote_tmp = f"/data/local/tmp/{DB_NAME}"
            adb.run("push", local, remote_tmp)
            adb.run("shell", "run-as", pkg, "cp", remote_tmp, f"databases/{DB_NAME}")
            adb.run("shell", "run-as", pkg, "rm", "-f", f"databases/{DB_NAME}-journal",
                    f"databases/{DB_NAME}-wal", f"databases/{DB_NAME}-shm")
            adb.run("shell", "rm", "-f", remote_tmp)
            print("Done. Open the app to see the imported runs.")


def run_on_db(db, args):
    """Returns True if the database was changed."""
    if args.remove:
        n = remove_imported(db)
        db.commit()
        print(f"Removed {n} activities imported from Strava.")
        return n > 0
    added, skipped = import_runs(db, args.export_dir, args.dry_run)
    if not args.dry_run:
        db.commit()
        db.execute("pragma journal_mode=delete")
    print(f"{'Would add' if args.dry_run else 'Added'} {added} runs, skipped {skipped}.")
    return added > 0 and not args.dry_run


if __name__ == "__main__":
    main()
