/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Title:        EdgeCloudSim - Deadline Statistics Logger
 *
 * Description:
 * DeadlineStatsLogger complements the core SimLogger for deadline_app.
 * It follows every task from submission to its final outcome and records
 *  - the tier the task was offloaded to (local edge, remote edge, cloud)
 *  - whether the task was completed within its application deadline
 *    (max_delay_requirement in applications.xml)
 *
 * Tasks submitted during the warm-up period are excluded, consistent with
 * the core SimLogger. Results are written to
 *   <outputFolder>/<filePrefix>_DEADLINE.log
 * as semicolon-separated rows (one per application plus ALL_APPS).
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 */

package edu.boun.edgecloudsim.applications.deadline_app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.utils.SimLogger;

/** Collects deadline and tier statistics for deadline_app. */
public final class DeadlineStatsLogger {

	/** Index of max_delay_requirement in the SimSettings task lookup table. */
	public static final int DEADLINE_INDEX = 13;

	/** Offloading tiers distinguished by this scenario. */
	public enum TIER { LOCAL_EDGE, REMOTE_EDGE, CLOUD }

	private static final DeadlineStatsLogger INSTANCE = new DeadlineStatsLogger();

	/** Live (unresolved) tasks keyed by task id. */
	private final Map<Integer, TaskRecord> liveTasks = new HashMap<>();

	private int numOfApps;
	private String outputFolder;
	private String filePrefix;

	// Per-application counters (index = task type)
	private int[] total;          // tasks submitted after warm-up
	private int[][] tierCount;    // [app][tier] tasks offloaded to each tier
	private int[] rejected;       // rejected before offloading (VM capacity / bandwidth)
	private int[] dropped;        // dropped by the orchestrator's admission control (ECT_FB_AC)
	private int[] failed;         // failed after offloading (bandwidth / mobility / MAN)
	private int[] completed;      // completed (on time or late)
	private int[] onTime;         // completed within deadline
	private int[][] onTimeByTier; // [app][tier] completed within deadline per tier
	private int[][] completedByTier; // [app][tier]
	private List<List<Double>> serviceTimes; // service times of completed tasks
	private double[] lateness;    // sum of (serviceTime - deadline) for late tasks
	private int[] estimated;      // completed tasks that carry an orchestrator estimate (ECT)
	private double[] estError;    // sum of (actual - estimated) service time
	private double[] estAbsError; // sum of |actual - estimated| service time

	private DeadlineStatsLogger() {
	}

	public static DeadlineStatsLogger getInstance() {
		return INSTANCE;
	}

	/** Returns the deadline (seconds) of the given application type. */
	public static double getDeadline(int taskType) {
		return SimSettings.getInstance().getTaskLookUpTable()[taskType][DEADLINE_INDEX];
	}

	/** Resets all statistics; call before each simulation run. */
	public void simStarted(String outputFolder, String filePrefix) {
		this.outputFolder = outputFolder;
		this.filePrefix = filePrefix;
		numOfApps = SimSettings.getInstance().getTaskLookUpTable().length;
		int n = numOfApps + 1; // last slot aggregates all applications

		liveTasks.clear();
		total = new int[n];
		tierCount = new int[n][TIER.values().length];
		rejected = new int[n];
		dropped = new int[n];
		failed = new int[n];
		completed = new int[n];
		onTime = new int[n];
		onTimeByTier = new int[n][TIER.values().length];
		completedByTier = new int[n][TIER.values().length];
		lateness = new double[n];
		estimated = new int[n];
		estError = new double[n];
		estAbsError = new double[n];
		serviceTimes = new ArrayList<>();
		for (int i = 0; i < n; i++)
			serviceTimes.add(new ArrayList<>());
	}

	/** A task has been created and submitted by a mobile device. */
	public void taskSubmitted(int taskId, int taskType, double time) {
		if (time < SimSettings.getInstance().getWarmUpPeriod())
			return; // warm-up tasks are ignored
		liveTasks.put(taskId, new TaskRecord(taskType, time));
		total[taskType]++;
		total[numOfApps]++;
	}

	/** The task has been offloaded (upload started) to the given tier. */
	public void taskOffloaded(int taskId, TIER tier) {
		TaskRecord r = liveTasks.get(taskId);
		if (r == null)
			return;
		r.tier = tier;
		tierCount[r.taskType][tier.ordinal()]++;
		tierCount[numOfApps][tier.ordinal()]++;
	}

	/** The orchestrator's estimated service time for the task (used by ECT). */
	public void taskEstimated(int taskId, double estimatedServiceTime) {
		TaskRecord r = liveTasks.get(taskId);
		if (r != null)
			r.estimate = estimatedServiceTime;
	}

	/** The task was rejected before offloading (no VM capacity / no bandwidth). */
	public void taskRejected(int taskId) {
		TaskRecord r = liveTasks.remove(taskId);
		if (r == null)
			return;
		rejected[r.taskType]++;
		rejected[numOfApps]++;
	}

	/**
	 * The orchestrator dropped the task on purpose because it cannot meet its
	 * deadline (admission control). The core SimLogger records it afterwards as
	 * a VM-capacity rejection.
	 */
	public void taskDropped(int taskId) {
		TaskRecord r = liveTasks.remove(taskId);
		if (r == null)
			return;
		dropped[r.taskType]++;
		dropped[numOfApps]++;
	}

	/** The task failed after offloading (bandwidth, MAN or mobility failure). */
	public void taskFailed(int taskId) {
		TaskRecord r = liveTasks.remove(taskId);
		if (r == null)
			return;
		failed[r.taskType]++;
		failed[numOfApps]++;
	}

	/** The result of the task has been delivered to the mobile device. */
	public void taskCompleted(int taskId, double time) {
		TaskRecord r = liveTasks.remove(taskId);
		if (r == null)
			return;
		double serviceTime = time - r.submitTime;
		double deadline = getDeadline(r.taskType);
		boolean met = serviceTime <= deadline;

		for (int idx : new int[] { r.taskType, numOfApps }) {
			completed[idx]++;
			serviceTimes.get(idx).add(serviceTime);
			if (r.estimate >= 0) {
				estimated[idx]++;
				estError[idx] += serviceTime - r.estimate;
				estAbsError[idx] += Math.abs(serviceTime - r.estimate);
			}
			if (r.tier != null)
				completedByTier[idx][r.tier.ordinal()]++;
			if (met) {
				onTime[idx]++;
				if (r.tier != null)
					onTimeByTier[idx][r.tier.ordinal()]++;
			} else {
				lateness[idx] += serviceTime - deadline;
			}
		}
	}

	/** Writes the statistics file and prints a short console summary. */
	public void simStopped() {
		int all = numOfApps;
		int unfinished = total[all] - rejected[all] - dropped[all] - failed[all] - completed[all];
		SimLogger.printLine(String.format(Locale.US,
				"deadline: on-time %.2f%% (miss %.2f%%) | tiers L/R/C: %d/%d/%d | rejected/dropped/failed/late/unfinished: %d/%d/%d/%d/%d",
				pct(onTime[all], total[all]), 100 - pct(onTime[all], total[all]),
				tierCount[all][0], tierCount[all][1], tierCount[all][2],
				rejected[all], dropped[all], failed[all], completed[all] - onTime[all], unfinished));

		if (!SimLogger.isFileLogEnabled() || outputFolder == null)
			return;

		File file = new File(outputFolder, filePrefix + "_DEADLINE.log");
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
			bw.write("#app;deadline_s;total;offloaded_local_edge;offloaded_remote_edge;offloaded_cloud;"
					+ "rejected;failed;completed;on_time;late;unfinished;"
					+ "on_time_pct;deadline_miss_pct;avg_service_time_s;p95_service_time_s;avg_lateness_s;"
					+ "on_time_local_edge;on_time_remote_edge;on_time_cloud;"
					+ "completed_local_edge;completed_remote_edge;completed_cloud;"
					+ "estimated;est_mean_error_s;est_mean_abs_error_s;dropped");
			bw.newLine();
			for (int i = 0; i <= numOfApps; i++) {
				String name = (i == numOfApps) ? "ALL_APPS" : SimSettings.getInstance().getTaskName(i);
				double deadline = (i == numOfApps) ? 0 : getDeadline(i);
				int late = completed[i] - onTime[i];
				int unf = total[i] - rejected[i] - dropped[i] - failed[i] - completed[i];
				List<Double> st = serviceTimes.get(i);
				bw.write(String.format(Locale.US,
						"%s;%.3f;%d;%d;%d;%d;%d;%d;%d;%d;%d;%d;%.4f;%.4f;%.6f;%.6f;%.6f;%d;%d;%d;%d;%d;%d;%d;%.6f;%.6f;%d",
						name, deadline, total[i],
						tierCount[i][0], tierCount[i][1], tierCount[i][2],
						rejected[i], failed[i], completed[i], onTime[i], late, unf,
						pct(onTime[i], total[i]), 100 - pct(onTime[i], total[i]),
						mean(st), percentile(st, 95), late == 0 ? 0 : lateness[i] / late,
						onTimeByTier[i][0], onTimeByTier[i][1], onTimeByTier[i][2],
						completedByTier[i][0], completedByTier[i][1], completedByTier[i][2],
						estimated[i],
						estimated[i] == 0 ? 0 : estError[i] / estimated[i],
						estimated[i] == 0 ? 0 : estAbsError[i] / estimated[i],
						dropped[i]));
				bw.newLine();
			}
		} catch (IOException e) {
			SimLogger.printLine("Deadline statistics cannot be written: " + e.getMessage());
		}
	}

	private static double pct(int part, int whole) {
		return whole == 0 ? 0 : 100.0 * part / whole;
	}

	private static double mean(List<Double> values) {
		if (values.isEmpty())
			return 0;
		double sum = 0;
		for (double v : values)
			sum += v;
		return sum / values.size();
	}

	private static double percentile(List<Double> values, double p) {
		if (values.isEmpty())
			return 0;
		List<Double> sorted = new ArrayList<>(values);
		Collections.sort(sorted);
		int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
		return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
	}

	/** Book-keeping for a task that has not reached a final outcome yet. */
	private static final class TaskRecord {
		final int taskType;
		final double submitTime;
		TIER tier;
		double estimate = -1; // estimated service time, -1 if none

		TaskRecord(int taskType, double submitTime) {
			this.taskType = taskType;
			this.submitTime = submitTime;
		}
	}
}
