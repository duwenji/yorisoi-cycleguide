"""
Reads the developer's export of rides (settings > 走行の記録を書き出す, format cycleguide-ride-export)
and prints what the thresholds are checked with (architecture.md A-3, SpeechBudget):

- the speeds the location fixes report, and the median the app decides with, by the state it was in
- each change of state, with how long the rider had been stopped
- the characters a second of each line read out (`said` followed by `speech_done`)
- the decisions, and the AI calls that failed

  python tools/analyze-rides.py cycleguide-rides-20261025-1530.json [--stop-mps 0.8]

Only Python's standard library.
"""

import argparse
import json
import statistics
from collections import Counter


def quantiles(values):
    if not values:
        return "-"
    values = sorted(values)
    pick = lambda q: values[min(len(values) - 1, int(q * len(values)))]
    return f"n={len(values)} min={values[0]:.2f} p10={pick(0.1):.2f} median={statistics.median(values):.2f} p90={pick(0.9):.2f} max={values[-1]:.2f}"


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
