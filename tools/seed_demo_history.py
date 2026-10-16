#!/usr/bin/env python3
"""Seed a RunnerUp debug install on an emulator/device with fake activities.

Generates realistic-looking runs, rides and walks around Seoul (GPS track,
heart rate, altitude, per-km laps) so the History screen (compact + feed)
and the activity detail screen can be checked without recording real runs.

Usage (app must have been launched at least once so the database exists):
    python tools/seed_demo_history.py              # replace demo data
    python tools/seed_demo_history.py --count 40   # more activities
    python tools/seed_demo_history.py --remove     # remove demo data only

Only rows created by this script are replaced/removed (tagged in the
activity "nullColumnHack" column); real activities are left alone.
"""

import argparse
import math
import os
import random
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

DEMO_TAG = "demo-seed"
DB_NAME = "runnerup.db"

SPORT_RUNNING = 0
SPORT_BIKING = 1
SPORT_WALKING = 4
SPORT_TREADMILL = 5

TYPE_START = 1
TYPE_END = 2
TYPE_GPS = 3

# (name, lat, lon, shape) - shape "loop" or "river" (out-and-back along a line)
PLACES = [
    ("Yeouido Han River Park", 37.5284, 126.9326, "river"),
    ("Banpo Han River Park", 37.5104, 126.9958, "river"),
    ("Olympic Park", 37.5206, 127.1215, "loop"),
    ("Seoul Forest", 37.5444, 127.0374, "loop"),
    ("Namsan", 37.5512, 126.9882, "loop"),
    ("Seokchon Lake", 37.5096, 127.1041, "loop"),
    ("Cheonggyecheon", 37.5690, 126.9910, "river"),
    ("Yangjaecheon", 37.4801, 127.0420, "river"),
]

RUN_NAMES = [
    "Easy recovery jog", "Tempo run", "Long Sunday run", "Interval session",
    "Progression run", "Sunrise run", "Lunch break run", "Hill repeats",
]
RIDE_NAMES = ["Han River ride", "Weekend gran fondo prep", "Commute"]
WALK_NAMES = ["Evening walk", "Walk with the dog"]
COMMENTS = [
    "Legs felt fresh, kept it conversational.",
    "Windy along the river, pushed hard on the way back. Negative split!",
    "Humid morning. Took a gel at 12 km and it helped a lot.",
    "Easy pace, focused on cadence and relaxed shoulders.",
    "Felt heavy for the first 3 km, then settled in nicely.",
    None, None, None,
]


def find_adb():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(env):
            candidate = Path(os.environ[env]) / "platform-tools" / "adb"
            for c in (candidate, candidate.with_suffix(".exe")):
                if c.exists():
                    return str(c)
    props = Path(__file__).resolve().parent.parent / "local.properties"
    if props.exists():
        for line in props.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
                candidate = Path(sdk) / "platform-tools" / "adb"
                for c in (candidate, candidate.with_suffix(".exe")):
                    if c.exists():
                        return str(c)
    found = shutil.which("adb")
    if found:
        return found
    sys.exit("adb not found: set ANDROID_HOME or sdk.dir in local.properties")


class Adb:
    def __init__(self, adb, serial):
        if not serial:
            out = subprocess.run([adb, "devices", "-l"], capture_output=True, text=True).stdout
            devices = [line for line in out.splitlines()[1:] if " device " in line + " "]
            if len(devices) > 1:
                lines = []
                for d in devices:
                    words = d.split()
                    model = " ".join(w for w in words[1:] if w.startswith("model:"))
                    lines.append(f"  {words[0]}  {model}")
                sys.exit("More than one device is connected, choose one with --serial:\n"
                         + "\n".join(lines))
        self.base = [adb] + (["-s", serial] if serial else [])

    def run(self, *args, binary=False, timeout=60):
        res = subprocess.run(self.base + list(args), capture_output=True, timeout=timeout)
        if res.returncode != 0:
            sys.exit(f"adb {' '.join(args)} failed: {res.stderr.decode(errors='replace')}")
        return res.stdout if binary else res.stdout.decode(errors="replace")


# --- track generation -------------------------------------------------------

M_PER_DEG_LAT = 111_320.0


def offset(lat, lon, north_m, east_m):
    return (lat + north_m / M_PER_DEG_LAT,
            lon + east_m / (M_PER_DEG_LAT * math.cos(math.radians(lat))))


def shape_points(rng, lat0, lon0, shape, distance_m):
    """Dense polyline (local metres) with roughly the requested length."""
    pts = []
    if shape == "loop":
        laps = max(1, round(distance_m / 4500))
        circumference = distance_m / laps
        r = circumference / (2 * math.pi)
        wobble = [(rng.uniform(0.05, 0.18), rng.randint(2, 5), rng.uniform(0, 6.28))
                  for _ in range(3)]
        n = 400 * laps
        for i in range(n + 1):
            t = 2 * math.pi * laps * i / n
            k = 1 + sum(a * math.sin(f * t + p) for a, f, p in wobble)
            pts.append((r * k * math.sin(t), 1.25 * r * k * math.cos(t) - 1.25 * r))
    else:
        half = distance_m / 2
        heading = rng.uniform(0, 2 * math.pi)
        bend = rng.uniform(0.15, 0.35)
        out = []
        for i in range(301):
            s = half * i / 300
            h = heading + bend * math.sin(s / half * math.pi * 1.5)
            if out:
                prev = out[-1]
                step = half / 300
                out.append((prev[0] + step * math.cos(h), prev[1] + step * math.sin(h)))
            else:
                out.append((0.0, 0.0))
        # come back on a slightly offset path, like the other side of a river path
        back = [(n + 25 * math.sin(heading + 1.57), e + 25 * math.cos(heading + 1.57))
                for n, e in reversed(out)]
        pts = out + back + [(0.0, 0.0)]
    # scale to match requested distance
    length = sum(math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
    scale = distance_m / length
    pts = [(n * scale, e * scale) for n, e in pts]
    return [offset(lat0, lon0, n, e) + (n, e) for n, e in pts]


def resample(track, step_m):
    """Points every step_m metres along the track: (lat, lon, cumulative distance)."""
    out = [(track[0][0], track[0][1], 0.0)]
    dist = 0.0
    next_at = step_m
    for a, b in zip(track, track[1:]):
        seg = math.dist(a[2:], b[2:])
        while seg > 0 and dist + seg >= next_at:
            f = (next_at - dist) / seg
            out.append((a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1]), next_at))
            next_at += step_m
        dist += seg
    out.append((track[-1][0], track[-1][1], dist))
    return out


# --- activity generation ----------------------------------------------------


def make_activity(rng, start_ts, sport):
    if sport == SPORT_BIKING:
        distance = rng.uniform(25_000, 70_000)
        speed = rng.uniform(6.5, 8.5)  # m/s
        name = rng.choice(RIDE_NAMES + [None])
        lap_m, hr_base = 5000, 128
    elif sport == SPORT_WALKING:
        distance = rng.uniform(2_500, 6_000)
        speed = rng.uniform(1.25, 1.55)
        name = rng.choice(WALK_NAMES + [None])
        lap_m, hr_base = 1000, 98
    else:
        distance = rng.choice([5_000, 6_000, 8_000, 10_000, 12_000, 15_000, 21_100])
        distance *= rng.uniform(0.97, 1.04)
        speed = 1000 / rng.uniform(290, 390)  # pace 4:50 - 6:30 /km
        name = rng.choice(RUN_NAMES + [None, None])
        lap_m, hr_base = 1000, 128 + (speed - 2.6) * 20
    comment = rng.choice(COMMENTS)

    if sport == SPORT_TREADMILL:
        duration = distance / speed
        return dict(start=start_ts, distance=distance, time=duration, name=name,
                    comment=comment, sport=sport, avg_hr=int(hr_base + 6),
                    max_hr=int(hr_base + 22), points=[], laps=[])

    place = rng.choice(PLACES)
    track = resample(shape_points(rng, place[1], place[2], place[3], distance), 15)

    points, laps = [], []
    t = 0.0
    phase = rng.uniform(0, 6.28)
    lap_start_t, lap_start_d, lap_hr, lap_idx = 0.0, 0.0, [], 0
    hr_all = []
    for i, (lat, lon, d) in enumerate(track):
        if i:
            seg = d - track[i - 1][2]
            v = speed * (1 + 0.06 * math.sin(d / 900 + phase) + rng.uniform(-0.03, 0.03))
            t += seg / v
        progress = d / distance
        hr = hr_base + 12 * min(1.0, t / 600) + 10 * progress + rng.uniform(-3, 3)
        hr = int(min(hr, 194))
        hr_all.append(hr)
        lap_hr.append(hr)
        alt = 25 + 12 * math.sin(d / 1400 + phase) + 4 * math.sin(d / 350)
        ptype = TYPE_START if i == 0 else (TYPE_END if i == len(track) - 1 else TYPE_GPS)
        points.append(dict(lap=lap_idx, type=ptype, time=int((start_ts + t) * 1000),
                           elapsed=int(t * 1000), distance=d, lat=lat, lon=lon,
                           alt=alt, hr=hr, speed=speed))
        if d - lap_start_d >= lap_m or i == len(track) - 1:
            laps.append(dict(lap=lap_idx, time=t - lap_start_t, distance=d - lap_start_d,
                             avg_hr=int(sum(lap_hr) / len(lap_hr)), max_hr=max(lap_hr)))
            lap_idx += 1
            lap_start_t, lap_start_d, lap_hr = t, d, []

    return dict(start=start_ts, distance=track[-1][2], time=t, name=name, comment=comment,
                sport=sport, avg_hr=int(sum(hr_all) / len(hr_all)), max_hr=max(hr_all),
                points=points, laps=laps)


def remove_demo(db):
    ids = [r[0] for r in db.execute(
        "select _id from activity where nullColumnHack = ?", (DEMO_TAG,))]
    for table in ("location", "lap"):
        db.executemany(f"delete from {table} where activity_id = ?", [(i,) for i in ids])
    db.execute("delete from activity where nullColumnHack = ?", (DEMO_TAG,))
    return len(ids)


def insert(db, a):
    # Note: in RunnerUp the column names for avg/max HR are swapped (see Constants.java)
    cur = db.execute(
        "insert into activity (start_time, distance, time, name, comment, type,"
        " max_hr, avg_hr, deleted, nullColumnHack) values (?,?,?,?,?,?,?,?,0,?)",
        (int(a["start"]), a["distance"], int(round(a["time"])), a["name"], a["comment"],
         a["sport"], a["avg_hr"], a["max_hr"], DEMO_TAG))
    aid = cur.lastrowid
    db.executemany(
        "insert into location (activity_id, lap, type, time, elapsed, distance, latitude,"
        " longitude, altitude, hr, speed, accurancy) values (?,?,?,?,?,?,?,?,?,?,?,5)",
        [(aid, p["lap"], p["type"], p["time"], p["elapsed"], p["distance"], p["lat"],
          p["lon"], p["alt"], p["hr"], p["speed"]) for p in a["points"]])
    db.executemany(
        "insert into lap (activity_id, lap, type, time, distance, avg_hr, max_hr)"
        " values (?,?,0,?,?,?,?)",
        [(aid, l["lap"], int(round(l["time"])), l["distance"], l["avg_hr"], l["max_hr"])
         for l in a["laps"]])


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--package", default="org.runnerup.debug")
    ap.add_argument("--serial", help="adb device serial (when several are connected)")
    ap.add_argument("--count", type=int, default=24, help="number of activities")
    ap.add_argument("--days", type=int, default=90, help="spread activities over N days")
    ap.add_argument("--seed", type=int, default=7, help="random seed")
    ap.add_argument("--remove", action="store_true", help="only remove demo activities")
    args = ap.parse_args()

    adb = Adb(find_adb(), args.serial)
    pkg = args.package
    files = adb.run("shell", "run-as", pkg, "ls", "databases")
    if DB_NAME not in files:
        sys.exit(f"{DB_NAME} not found for {pkg}: launch the app once, then rerun")

    adb.run("shell", "am", "force-stop", pkg)
    with tempfile.TemporaryDirectory() as tmp:
        local = os.path.join(tmp, DB_NAME)
        with open(local, "wb") as f:
            f.write(adb.run("exec-out", "run-as", pkg, "cat", f"databases/{DB_NAME}",
                            binary=True))
        db = sqlite3.connect(local)
        removed = remove_demo(db)
        added = 0
        if not args.remove:
            rng = random.Random(args.seed)
            now = time.time()
            sports = ([SPORT_RUNNING] * 7 + [SPORT_BIKING] * 2 + [SPORT_WALKING]
                      + [SPORT_TREADMILL])
            for i in range(args.count):
                day = args.days * i / max(1, args.count) + rng.uniform(0, 1.5)
                start = now - day * 86400
                # snap to a plausible hour: morning, lunch or evening
                lt = time.localtime(start)
                hour = rng.choice([6, 7, 12, 18, 19, 20])
                start = time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday, hour,
                                     rng.randint(0, 59), 0, 0, 0, -1))
                if start > now:
                    start -= 86400
                insert(db, make_activity(rng, start, rng.choice(sports)))
                added += 1
        db.commit()
        db.execute("pragma journal_mode=delete")
        db.close()

        remote_tmp = f"/data/local/tmp/{DB_NAME}"
        adb.run("push", local, remote_tmp)
        adb.run("shell", "run-as", pkg, "cp", remote_tmp, f"databases/{DB_NAME}")
        adb.run("shell", "run-as", pkg, "rm", "-f", f"databases/{DB_NAME}-journal",
                f"databases/{DB_NAME}-wal", f"databases/{DB_NAME}-shm")
        adb.run("shell", "rm", "-f", remote_tmp)

    print(f"Removed {removed} old demo activities, added {added}. Restart the app to see them.")


if __name__ == "__main__":
    main()
