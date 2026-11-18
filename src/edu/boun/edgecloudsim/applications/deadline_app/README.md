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
| `ECT` *(proposed, to be implemented)* | Estimates end-to-end completion time on the local edge, best remote edge and cloud; picks the fastest candidate that meets the task deadline. |

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
