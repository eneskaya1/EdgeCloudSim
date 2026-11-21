#!/usr/bin/env python3
"""Cross-platform experiment runner for deadline_app (works on Windows, Linux, macOS).

Every (iteration, policy, number of devices) combination runs in its own JVM, so
the jobs can be spread over all CPU cores. All policies of the same iteration use
the same seed (common random numbers), so they are compared on the same workload.

Example (short tuning run):
    python scripts/deadline_app/run_experiments.py --name tuning --sim-time 5 --warmup 1 \
        --devices 400 1200 2000 --policies ECT RANDOM --iterations 2 --parallel 8

Results: scripts/deadline_app/output/<name>/<tag>/ite<i>/<policy>_<devices>/...
Completed jobs are skipped when the same command is run again (resume).
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
CONFIG_DIR = os.path.join(SCRIPT_DIR, "config")
MAIN_CLASS = "edu.boun.edgecloudsim.applications.deadline_app.MainApp"
SEED_BASE = 1000


def find_java(explicit):
    """Return a Java 21+ executable: --java, JAVA_HOME, a Temurin install, or 'java'."""
    exe = "java.exe" if os.name == "nt" else "java"
    candidates = []
    if explicit:
        candidates.append(explicit)
    if os.environ.get("JAVA_HOME"):
        candidates.append(os.path.join(os.environ["JAVA_HOME"], "bin", exe))
    candidates += sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\jdk-2*\bin\java.exe"), reverse=True)
    for c in candidates:
        if os.path.isfile(c):
            return c
    return shutil.which("java") or "java"


def write_config(path, args, devices, policy):
    """Copy default_config.properties, overriding the settings of one job."""
    overrides = {
        "simulation_time": str(args.sim_time),
        "warm_up_period": str(args.warmup),
        "min_number_of_mobile_devices": str(devices),
        "max_number_of_mobile_devices": str(devices),
        "mobile_device_counter_size": "1",
        "orchestrator_policies": policy,
    }
    lines = []
    with open(os.path.join(CONFIG_DIR, args.config), encoding="utf-8") as f:
        for line in f:
            key = line.split("=", 1)[0].strip()
            if key in overrides:
                line = f"{key}={overrides.pop(key)}\n"
            lines.append(line)
    for key, value in overrides.items():
        lines.append(f"{key}={value}\n")
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.writelines(lines)


def is_done(job_dir, log_file):
    if not os.path.isfile(log_file) or not glob.glob(os.path.join(job_dir, "*_DEADLINE.log")):
        return False
    with open(log_file, encoding="utf-8", errors="replace") as f:
        return "Simulation finished" in f.read()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--name", required=True, help="experiment name (output sub-folder)")
    p.add_argument("--tag", default="default", help="label of this configuration, e.g. margin08")
    p.add_argument("--policies", nargs="+", required=True)
    p.add_argument("--devices", nargs="+", type=int, required=True)
    p.add_argument("--iterations", type=int, default=1)
    p.add_argument("--first-iteration", type=int, default=1)
    p.add_argument("--sim-time", type=float, default=20, help="simulation time in minutes")
    p.add_argument("--warmup", type=float, default=2, help="warm-up period in minutes")
    p.add_argument("--parallel", type=int, default=max(1, (os.cpu_count() or 2) - 1))
    p.add_argument("--java-opt", action="append", default=[], help="extra JVM option, e.g. -Dect.margin=0.6")
    p.add_argument("--java", help="path to the java executable (JDK 21+)")
    p.add_argument("--config", default="default_config.properties")
    p.add_argument("--edge-devices", default="edge_devices.xml")
    p.add_argument("--applications", default="applications.xml")
    args = p.parse_args()

    java = find_java(args.java)
    bin_dir = os.path.join(PROJECT_DIR, "bin")
    if not os.path.isdir(os.path.join(bin_dir, *MAIN_CLASS.split(".")[:-1])):
        sys.exit(f"Compiled classes not found in {bin_dir}; compile the project first.")
    classpath = os.pathsep.join([bin_dir, os.path.join(PROJECT_DIR, "lib", "*")])

    out_root = os.path.join(SCRIPT_DIR, "output", args.name, args.tag)
    cfg_dir = os.path.join(out_root, "configs")
    os.makedirs(cfg_dir, exist_ok=True)

    # Largest device counts first: they take longest, which balances the workers.
    jobs = []
    for devices in sorted(args.devices, reverse=True):
        for it in range(args.first_iteration, args.first_iteration + args.iterations):
            for policy in args.policies:
                job_dir = os.path.join(out_root, f"ite{it}", f"{policy}_{devices}")
                log_file = job_dir + ".log"
                if is_done(job_dir, log_file):
                    continue
                cfg = os.path.join(cfg_dir, f"{policy}_{devices}.properties")
                write_config(cfg, args, devices, policy)
                cmd = [java, f"-Dedgecloudsim.seed={SEED_BASE + it}", *args.java_opt, "-cp", classpath, MAIN_CLASS,
                       cfg, os.path.join(CONFIG_DIR, args.edge_devices), os.path.join(CONFIG_DIR, args.applications),
                       job_dir, str(it)]
                jobs.append((f"ite{it} {policy} {devices}dev", cmd, job_dir, log_file))

    print(f"java: {java}\noutput: {out_root}\njobs to run: {len(jobs)} (parallel: {args.parallel})", flush=True)
    lock = threading.Lock()
    finished = [0]
    start = time.time()

    def run(job):
        label, cmd, job_dir, log_file = job
        os.makedirs(job_dir, exist_ok=True)
        t0 = time.time()
        with open(log_file, "w", encoding="utf-8") as log:
            log.write("# " + " ".join(cmd) + "\n")
            log.flush()
            code = subprocess.call(cmd, stdout=log, stderr=subprocess.STDOUT, cwd=PROJECT_DIR)
        with lock:
            finished[0] += 1
            status = "OK" if code == 0 and is_done(job_dir, log_file) else f"FAILED (exit {code})"
            print(f"[{finished[0]}/{len(jobs)}] {label}: {status} in {time.time() - t0:.0f}s "
                  f"(elapsed {(time.time() - start) / 60:.1f} min)", flush=True)
        return code

    failures = 0
    with ThreadPoolExecutor(max_workers=args.parallel) as pool:
        for future in as_completed([pool.submit(run, j) for j in jobs]):
            failures += future.result() != 0
    print(f"done: {len(jobs) - failures} ok, {failures} failed, {(time.time() - start) / 60:.1f} min", flush=True)
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
