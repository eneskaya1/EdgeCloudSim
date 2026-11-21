# Deadline-Aware Three-Tier Task Offloading (CMPE583 Assignment)

## Engineering Problem

Mobile applications with very different latency tolerances (augmented reality,
health monitoring, infotainment) share the same edge infrastructure. Each task can
be executed on the **local edge server** (via WLAN), on a **remote edge server**
(via WLAN + MAN), or on the **cloud** (via WAN). Simple threshold-based orchestrators
(e.g. "edge busy -> go to cloud") ignore task deadlines and the actual network and
queueing conditions, so tasks miss their deadlines even when another tier could
have served them in time.

## Scenario

This application is derived from `tutorial3` (multi-tier workload orchestration)
and extends it with per-application deadlines.

### Application Deadlines

Deadlines are set with the optional `max_delay_requirement` element (seconds) in
`scripts/deadline_app/config/applications.xml`. A task meets its deadline if its
service time (submission until the result reaches the mobile device) is not larger
than the deadline. Rejected, failed and unfinished tasks count as deadline misses.

| Application | Task length | Upload / download | Deadline | Rationale |
| --- | --- | --- | --- | --- |
| `AUGMENTED_REALITY` | 15000 MI (1.5 s on an edge VM) | 1500 / 50 KB | 2.5 s | Compute-heavy; tight relative to its execution time. Cloud VMs help when the WAN is not congested. |
| `HEALTH_APP` | 3000 MI (0.3 s on an edge VM) | 50 / 1250 KB | 1.0 s | Large result; downloading it over the WAN usually breaks the deadline, so it should stay at the edge. |
| `INFOTAINMENT_APP` | 9000 MI (0.9 s on an edge VM) | 250 / 1000 KB | 3.0 s | Delay-tolerant; can be served by any tier. |

The values were calibrated with the baseline policies (10 min simulation, seed 1):
under light load (200 devices) they meet about 81-86% of deadlines; under heavy
load (2000 devices) only about 21-33%.

### Additional Statistics

`DeadlineStatsLogger` follows every task (warm-up tasks excluded) and writes
`SIMRESULT_<scenario>_<policy>_<N>DEVICES_DEADLINE.log` next to the standard
EdgeCloudSim result files. It holds one semicolon-separated row per application plus
an `ALL_APPS` row (the first line is a `#` header):

| Column group | Meaning |
| --- | --- |
| `total` | Tasks submitted after the warm-up period |
| `offloaded_local_edge / remote_edge / cloud` | Tier the task was sent to (remote edge = reached via MAN) |
| `rejected` | Rejected before offloading (no VM capacity / no bandwidth) |
| `failed` | Failed after offloading (WLAN/MAN/WAN bandwidth or mobility) |
| `completed`, `on_time`, `late`, `unfinished` | Final outcome (unfinished = still running at the end of the simulation) |
| `on_time_pct`, `deadline_miss_pct` | `on_time / total` and its complement |
| `avg_service_time_s`, `p95_service_time_s`, `avg_lateness_s` | Over completed tasks; lateness only over late tasks |
| `on_time_*`, `completed_*` | On-time and completed counts per tier |
| `estimated`, `est_mean_error_s`, `est_mean_abs_error_s` | ECT only: completed tasks with an estimate and the mean (absolute) estimation error |

A short summary line (`deadline: on-time ... | tiers L/R/C ...`) is also printed
to the console after each run.

Note: compared with the core logger, the totals differ slightly by design. The core
logger leaves out tasks still running when the simulation ends, and it counts tasks
rejected during warm-up, because rejected tasks never set the warm-up flag.

## Orchestration Policies

| Policy | Description |
| --- | --- |
| `RANDOM` | 50/50 random choice between edge and cloud (baseline). |
| `UTILIZATION_BASED` | Cloud if average edge utilization > 75%, otherwise edge. |
| `NETWORK_BASED` | Cloud if estimated WAN bandwidth > 5 Mbps, otherwise edge. |
| `ECT` *(proposed)* | Deadline-aware Estimated Completion Time; see below. |

The competitors pick a VM with the least-loaded rule (largest free CPU %) among
all edge VMs or all cloud VMs. Because there are 14 edge servers, the chosen edge
VM is usually on a *remote* server, reached via the MAN.

### Proposed Policy: Deadline-Aware Estimated Completion Time (ECT)

Implemented in `DeadlineEdgeOrchestrator`. For each new task and **every VM** that
can host it (same CPU-capacity admission rule as the competitors), ECT estimates
the end-to-end service time:

```
ECT = upload delay + execution time + download delay
  local edge : WLAN up                   + exec + WLAN down
  remote edge: WLAN up + MAN up          + exec + MAN down + WLAN down
  cloud      : WAN up                    + exec + WAN down
```

* **Network delays** come from the current state of the network model (number of
  active WLAN/WAN users per access point, M/M/1 model of the MAN). The MAN is
  probed with side-effect-free `estimateMan*Delay()` methods, so ECT's estimates
  do not change the MAN model's statistics.
* **Execution time** replays CloudSim's time-shared scheduler: with `k` running
  single-core tasks on a VM with `P` cores of `m` MIPS, each task runs at
  `m * min(1, P/k)`. ECT does this for the tasks already running on the VM (their
  remaining length), the tasks still uploading to it (tracked by ECT itself) and
  the new task, until the new task finishes.
* **Decision:** a candidate is *safely on time* if `ECT <= 0.8 * deadline`
  (`SAFETY_MARGIN`). Among those, ECT picks the **cheapest tier** (local edge, then
  remote edge, then cloud), which saves MAN and WAN bandwidth for other tasks. Within
  a tier it picks the smallest ECT. If no candidate is safely on time, it
  picks the candidate with the smallest ECT.

Assumption: like most offloading studies, ECT knows the length (MI) and data sizes
of the task when it is created (e.g. from application profiling). The competitors
do not use this information.

The estimate of every ECT task is logged, and the `estimated`, `est_mean_error_s`
and `est_mean_abs_error_s` columns of the `_DEADLINE.log` file report the estimator's
accuracy (actual minus estimated service time).

## Running

Configuration files live in `scripts/deadline_app/config`. Compile with JDK 21+ and run:

```
java -cp "bin;lib/*" edu.boun.edgecloudsim.applications.deadline_app.MainApp \
    scripts/deadline_app/config/default_config.properties \
    scripts/deadline_app/config/edge_devices.xml \
    scripts/deadline_app/config/applications.xml \
    <output_folder> <iteration>
```

(Use `:` instead of `;` as the classpath separator on Linux/macOS.)
Add `-Dedgecloudsim.seed=<n>` for reproducible runs.

### Running Experiments (all platforms)

`scripts/deadline_app/run_experiments.py` runs every (iteration, policy, number of
devices) combination in its own JVM and spreads the jobs over the CPU cores. Within an
iteration every policy gets the same seed (`1000 + iteration`), so all policies are
compared on the same workload. Finished jobs are skipped when the command is repeated.

```
python scripts/deadline_app/run_experiments.py --name final --sim-time 20 --warmup 2 \
    --devices 200 600 1000 1400 1800 --iterations 10 --parallel 7 \
    --policies ECT NETWORK_BASED UTILIZATION_BASED RANDOM

python scripts/deadline_app/python/summarize.py scripts/deadline_app/output/final --csv summary.csv
```

`--tag` and `--java-opt` let you run variants side by side, e.g.
`--tag m0.6 --java-opt=-Dect.margin=0.6`. `summarize.py` prints the mean and 95%
confidence interval over iterations of the on-time rate, failure rate, service time
and tier shares (`--app` selects a single application).

### ECT Variants (ablation)

| Policy | Difference from `ECT` |
| --- | --- |
| `ECT_GREEDY` | Ignores the tier preference: always picks the candidate with the smallest ECT. |
| `ECT_AVG` | Does not know the task's actual length / data sizes; uses the application's average values. |
| `ECT_FB` | Online feedback correction: learns the mean estimation error (actual - estimated) of completed tasks per tier (EWMA, weight 0.02) and adds it to future estimates. |
| `ECT_AC` | Admission control: if even the best estimate misses the deadline, the task is dropped immediately instead of loading the system. |
| `ECT_FB_AC` | Feedback correction + admission control. |

The safety margin (default 0.8) can be changed with `-Dect.margin=<value>`.
Tasks dropped by admission control are counted in the `dropped` column of the
`_DEADLINE.log` file, and as misses in `on_time_pct`. The core SimLogger records them
as VM-capacity rejections.

### Tuning Results

Short runs (5 min simulation, 1 min warm-up, 2 iterations, seeds 1001-1002); full
table in `scripts/deadline_app/results/tuning_summary.txt`. On-time rate, ALL_APPS:

| Policy | 400 devices | 1200 devices | 2000 devices |
| --- | --- | --- | --- |
| NETWORK_BASED | 78.1% | 68.9% | 40.9% |
| UTILIZATION_BASED | 80.3% | 44.0% | 21.7% |
| RANDOM | 83.8% | 54.7% | 33.9% |
| ECT (margin 0.6 / 0.8 / 1.0) | 97.8 / 97.9 / 98.0% | 95.2 / 95.9 / 94.2% | 53.5 / 54.5 / 51.2% |
| ECT_GREEDY | 97.4% | 92.8% | 52.9% |
| ECT_AVG | 84.3% | 72.4% | 16.8% |
| ECT_FB | 97.9% | 95.8% | 49.3% |
| ECT_AC | 98.0% | 96.4% | 88.5% (5.2% dropped) |
| ECT_FB_AC | 98.0% | 96.4% | 91.0% (5.7% dropped) |

Observations:
* The safety margin hardly matters (0.8 kept). Preferring the cheapest tier helps a
  little over pure greedy at medium load and uses much less cloud/MAN bandwidth.
* Knowing the actual task size is essential: `ECT_AVG` is worse than the competitors
  under high load.
* Under overload, plain ECT's estimates become optimistic (+1.2 s), because tasks
  arriving later slow down running ones. Admission control prevents this overload
  collapse: by dropping about 5% of hopeless tasks it keeps the estimation error at
  0.13 s and raises the on-time rate from 54% to 91%. Feedback correction alone does
  not help, but it adds a little on top of admission control.
