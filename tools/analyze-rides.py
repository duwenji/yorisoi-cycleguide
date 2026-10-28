"""
Reads the developer's export of rides (settings > 走行の記録を書き出す, format cycleguide-ride-export)
and prints what the thresholds are checked with (architecture.md A-3, SpeechBudget):

- the speeds the location fixes report, and the median the app decides with, by the state it was in
- each change of state, with how long the rider had been stopped
- the characters a second of each line read out (`said` followed by `speech_done`)
- the decisions, and the AI calls that failed
- the spot searches: where the spots came from, how each Overpass server failed, and the stretches
  ridden with no answer searched there (the rider then hears of no spot ahead)

  python tools/analyze-rides.py cycleguide-rides-20261025-1530.json [--stop-mps 0.8]

Only Python's standard library.
"""

import argparse
import json
import math
import re
import statistics
from collections import Counter

# A searched answer covers 600m around its centre (kit-geo OverpassClient); past that, nothing ahead.
COVER_M = 600


def quantiles(values):
    if not values:
        return "-"
    values = sorted(values)
    pick = lambda q: values[min(len(values) - 1, int(q * len(values)))]
    return f"n={len(values)} min={values[0]:.2f} p10={pick(0.1):.2f} median={statistics.median(values):.2f} p90={pick(0.9):.2f} max={values[-1]:.2f}"


def distance_m(a, b):
    lat = math.radians((a[0] + b[0]) / 2)
    return 6371000 * math.hypot(math.radians(b[0] - a[0]), math.radians(b[1] - a[1]) * math.cos(lat))


def spot_searches(events, minimum_km):
    sources = Counter(e.get("source") for e in events if e["type"] == "spots")
    failed = [e for e in events if e["type"] == "spot_search_failed"]
    print(f"  spot answers shown: {dict(sources)}, searches failed {len(failed)}")
    servers = Counter()
    for e in failed:
        # "host HTTP 504 1234ms" or "host SocketTimeoutException 30000ms", one for each server.
        for host, how in re.findall(r"([\w.-]+\.\w+) (HTTP \d+|\w+Exception)", e.get("error", "")):
            servers[f"{host} {how}"] += 1
    for k, n in servers.most_common():
        print(f"    {k}: {n}")

    # Where answers were searched or kept: the search's centre, or where the rider was (older exports).
    covered, here, start, stretches = None, None, None, []
    t0 = events[0]["t"] if events else 0
    for e in events:
        if e["type"] == "location":
            here = (e["lat"], e["lon"], e["t"], e.get("km", 0))
            gap = covered is None or distance_m(covered, here) > COVER_M
            if gap and start is None:
                start = here
            elif not gap and start is not None:
                stretches.append((start, here))
                start = None
        elif e["type"] == "spots" and here:
            covered = (e["centerLat"], e["centerLon"]) if e.get("centerLat") is not None else here[:2]
    if start is not None and here:
        stretches.append((start, here))
    long = [(a, b) for a, b in stretches if b[3] - a[3] >= minimum_km]
    print(f"  ridden with no answer within {COVER_M}m: {sum(b[3] - a[3] for a, b in stretches):.1f} km in all, {minimum_km} km or more:")
    for a, b in long:
        print(f"    {(a[2] - t0) / 60000:.0f}-{(b[2] - t0) / 60000:.0f} min, {a[3]:.1f}-{b[3]:.1f} km")


def analyze(ride, stop_mps):
    events = ride["events"]
    start = events[0] if events else {}
    print(f"\n== ride {ride['id']}  style={start.get('style')}  channel={start.get('channel')}  ai={start.get('ai')}  events={len(events)}")

    state = start.get("state", "SIGNAL_STOP")
    speeds = {}
    medians = {}
    for e in events:
        if e["type"] == "state":
            print(f"  state {e.get('from')} -> {e.get('to')} after {e.get('stoppedMs', 0) / 1000:.0f}s stopped")
            state = e.get("to")
        elif e["type"] == "location":
            if e.get("speed") is not None:
                speeds.setdefault(state, []).append(e["speed"])
            if e.get("medianSpeed") is not None:
                medians.setdefault(state, []).append(e["medianSpeed"])
    print(f"  speed of the fixes (m/s), by state:")
    for s, v in speeds.items():
        print(f"    {s:12} {quantiles(v)}")
    print(f"  median speed the app decided with (m/s), by state:")
    for s, v in medians.items():
        print(f"    {s:12} {quantiles(v)}")
    moving_slow = [m for m in medians.get("MOVING", []) if m < stop_mps]
    stopped_fast = [m for s in ("SIGNAL_STOP", "REST") for m in medians.get(s, []) if m >= stop_mps]
    print(f"  against {stop_mps} m/s: {len(moving_slow)} fixes slower while MOVING, {len(stopped_fast)} faster while stopped")

    said = None
    rates = []
    for e in events:
        if e["type"] == "said":
            said = e
        elif e["type"] == "speech_done" and said and e.get("chars") and e.get("ms"):
            rates.append(e["chars"] / (e["ms"] / 1000))
            said = None
    print(f"  characters a second read out: {quantiles(rates)}")

    decisions = Counter(e.get("decision") for e in events if e["type"] == "decision")
    print(f"  decisions: {dict(decisions)}")
    failed = [e for e in events if e["type"] == "ai" and e.get("error")]
    print(f"  AI calls: {sum(1 for e in events if e['type'] == 'ai')}, failed {len(failed)}")
    for e in failed[:3]:
        print(f"    {e.get('error')}")
    spot_searches(events, 0.5)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("export")
    p.add_argument("--stop-mps", type=float, default=0.8, help="Motion.STOP_MPS")
    a = p.parse_args()
    with open(a.export, encoding="utf-8") as f:
        export = json.load(f)
    assert export.get("format") == "cycleguide-ride-export", "not a CycleGuide ride export"
    print(f"{export['format']} {export['formatVersion']}, app {export['app']}, {len(export['rides'])} rides")
    for ride in reversed(export["rides"]):
        analyze(ride, a.stop_mps)


if __name__ == "__main__":
    main()
