#!/usr/bin/env python3
"""Summarize deadline_app results produced by run_experiments.py.

Reads every *_DEADLINE.log below an experiment folder and prints, per
(tag, policy, number of devices), the mean and 95% confidence interval over
iterations of the main metrics. Optionally writes all aggregated values to CSV.

Usage:
    python scripts/deadline_app/python/summarize.py scripts/deadline_app/output/<name> [--app ALL_APPS] [--csv out.csv]
"""

import argparse
import csv
import glob
import math
import os
import re
import statistics
from collections import defaultdict

# Two-sided 95% Student-t critical values for df = 1..30
T95 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
       2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
       2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042]

PATH_RE = re.compile(r"[\\/](?P<tag>[^\\/]+)[\\/]ite(?P<it>\d+)[\\/](?P<policy>[A-Z_]+)_(?P<dev>\d+)[\\/]")


def mean_ci(values):
    m = statistics.mean(values)
    if len(values) < 2:
        return m, 0.0
    t = T95[len(values) - 2] if len(values) - 1 <= len(T95) else 1.96
    return m, t * statistics.stdev(values) / math.sqrt(len(values))


def load(folder):
    """Return {(tag, policy, devices, app): [row dict per iteration]}."""
    data = defaultdict(list)
    for path in glob.glob(os.path.join(folder, "**", "*_DEADLINE.log"), recursive=True):
        m = PATH_RE.search(path)
        if not m:
            continue
        with open(path, encoding="utf-8") as f:
            header = f.readline().lstrip("#").strip().split(";")
            for line in f:
                values = line.strip().split(";")
                if len(values) != len(header):
                    continue
                row = dict(zip(header, values))
                for k in header[1:]:
                    row[k] = float(row[k])
                total = row["total"] or 1
                offloaded = row["offloaded_local_edge"] + row["offloaded_remote_edge"] + row["offloaded_cloud"] or 1
                row.setdefault("dropped", 0.0)  # column added later; absent in older logs
                row["failed_pct"] = 100 * (row["rejected"] + row["failed"] + row["dropped"]) / total
                row["dropped_pct"] = 100 * row["dropped"] / total
                row["local_pct"] = 100 * row["offloaded_local_edge"] / offloaded
                row["remote_pct"] = 100 * row["offloaded_remote_edge"] / offloaded
                row["cloud_pct"] = 100 * row["offloaded_cloud"] / offloaded
                data[(m["tag"], m["policy"], int(m["dev"]), row["app"])].append(row)
    return data


METRICS = [("on_time_pct", "on-time%"), ("failed_pct", "fail%"), ("dropped_pct", "drop%"), ("avg_service_time_s", "avgST"),
           ("p95_service_time_s", "p95ST"), ("local_pct", "local%"), ("remote_pct", "remote%"),
           ("cloud_pct", "cloud%"), ("est_mean_abs_error_s", "estAbsErr")]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("folder")
    p.add_argument("--app", default="ALL_APPS", help="application row to print (default ALL_APPS)")
    p.add_argument("--csv", help="write mean/CI of every metric, app and configuration to this CSV file")
    args = p.parse_args()

    data = load(args.folder)
    if not data:
        raise SystemExit(f"No *_DEADLINE.log files found below {args.folder}")

    keys = sorted({k[:3] for k in data}, key=lambda k: (k[2], k[0], k[1]))
    print(f"app: {args.app}   (mean +/- 95% CI over iterations)")
    print(f"{'devices':>7} {'tag':<12} {'policy':<18} {'n':>2} " + " ".join(f"{label:>14}" for _, label in METRICS))
    for tag, policy, dev in keys:
        rows = data.get((tag, policy, dev, args.app))
        if not rows:
            continue
        cells = []
        for metric, _ in METRICS:
            m, ci = mean_ci([r[metric] for r in rows])
            cells.append(f"{m:8.2f}+/-{ci:<5.2f}")
        print(f"{dev:>7} {tag:<12} {policy:<18} {len(rows):>2} " + " ".join(cells))

    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            metrics = [k for k in next(iter(data.values()))[0] if k != "app"]
            w.writerow(["tag", "policy", "devices", "app", "n"] + [f"{m}_{s}" for m in metrics for s in ("mean", "ci95")])
            for (tag, policy, dev, app), rows in sorted(data.items()):
                vals = []
                for m in metrics:
                    vals += list(mean_ci([r[m] for r in rows]))
                w.writerow([tag, policy, dev, app, len(rows)] + [f"{v:.6f}" for v in vals])
        print(f"CSV written to {args.csv}")


if __name__ == "__main__":
    main()
