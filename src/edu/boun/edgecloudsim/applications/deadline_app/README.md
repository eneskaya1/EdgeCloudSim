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
