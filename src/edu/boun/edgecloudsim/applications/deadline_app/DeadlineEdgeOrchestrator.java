/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Title:        EdgeCloudSim - Edge Orchestrator
 *
 * Description:
 * DeadlineEdgeOrchestrator offloads tasks to proper server
 * based on the applied scenario. In addition to the three tutorial3
 * policies (competitors) it implements the proposed deadline-aware
 * Estimated Completion Time (ECT) policy.
 *
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 * Copyright (c) 2022, Bogazici University, Istanbul, Turkey
 */

package edu.boun.edgecloudsim.applications.deadline_app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.ResCloudlet;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEvent;

import edu.boun.edgecloudsim.cloud_server.CloudVM;
import edu.boun.edgecloudsim.core.SimManager;
import edu.boun.edgecloudsim.core.SimSettings;
import edu.boun.edgecloudsim.edge_orchestrator.EdgeOrchestrator;
import edu.boun.edgecloudsim.edge_server.EdgeHost;
import edu.boun.edgecloudsim.edge_server.EdgeVM;
import edu.boun.edgecloudsim.edge_client.CpuUtilizationModel_Custom;
import edu.boun.edgecloudsim.edge_client.Task;
import edu.boun.edgecloudsim.network.NetworkModel;
import edu.boun.edgecloudsim.utils.SimLogger;
import edu.boun.edgecloudsim.utils.SimUtils;

/**
 * Responsibilities:
 * - Decide offloading target: cloud vs generic edge datacenter
 * - Competitor policies (from tutorial3):
 *     NETWORK_BASED      : prefer cloud if estimated uplink WAN BW > 5 Mbps
 *     UTILIZATION_BASED  : prefer cloud if overall edge utilization > 75%
 *     RANDOM             : 50/50 coin flip
 *   VM selection for competitors: Least Loaded (pick VM with largest residual
 *   CPU capacity that fits).
 * - Proposed policy:
 *     ECT                : deadline-aware Estimated Completion Time
 *   For every VM that can host the task (local edge, remote edge via MAN,
 *   cloud) the end-to-end completion time is estimated as
 *     upload delay + execution time (processor sharing) + download delay.
 *   Among the candidates that meet the deadline with a safety margin
 *   (ECT <= SAFETY_MARGIN * deadline) the cheapest tier is selected
 *   (local edge < remote edge < cloud, i.e. least network resources);
 *   within a tier the VM with the smallest ECT wins. If no candidate is
 *   safely on time, the candidate with the smallest ECT is selected.
 * Capacity definitions:
 *     requiredCapacity = predicted CPU % for task on VM type
 *     targetVmCapacity = 100 - current utilized CPU %
 * Returns null if no VM can host task (caller handles rejection).
 */
public class DeadlineEdgeOrchestrator extends EdgeOrchestrator {
	/** A candidate is "safely on time" if ECT <= SAFETY_MARGIN * deadline. */
	public static final double SAFETY_MARGIN = 0.8;

	// Tier ranks used by ECT (lower = cheaper in network resources)
	private static final int RANK_LOCAL_EDGE = 0;
	private static final int RANK_REMOTE_EDGE = 1;
	private static final int RANK_CLOUD = 2;

	// numberOfHost cached to avoid repeated settings lookups
	private int numberOfHost; //used by load balancer

	/** VM chosen by ECT in getDeviceToOffload, consumed by getVmToOffload. */
	private final Map<Integer, Vm> ectDecisions = new HashMap<>();

	/**
	 * Tasks that ECT has sent to a VM but that have not reached it yet
	 * (still uploading). Key: VM id, value: list of {arrivalTime, lengthMI}.
	 * They are not visible in the VM's scheduler yet, so ECT accounts for them
	 * itself; otherwise many tasks would be sent to the same "empty" VM.
	 */
	private final Map<Integer, List<double[]>> pendingTasks = new HashMap<>();

	/**
	 * Creates a deadline_app orchestrator.
	 *
	 * @param _policy orchestration-policy identifier
	 * @param _simScenario simulation-scenario identifier
	 */
	public DeadlineEdgeOrchestrator(String _policy, String _simScenario) {
		super(_policy, _simScenario);
	}

	@Override
	public void initialize() {
		// Cache total edge host count for iteration in getVmToOffload
		numberOfHost=SimSettings.getInstance().getNumOfEdgeHosts();
		ectDecisions.clear();
		pendingTasks.clear();
	}

	/** Selects edge or cloud execution according to the configured policy. */
	@Override
	public int getDeviceToOffload(Task task) {
		if(policy.equals("ECT"))
			return ectGetDeviceToOffload(task);

		int result = 0;

		// Create a tiny dummy task (128 KB up/down ~ 1 Mbit total each way) to probe WAN delay
		// Used to estimate available WAN bandwidth = transferred_bits / delay
		// (Simplistic instantaneous probe; ignores contention dynamics)
		Task dummyTask = new Task(0, 0, 0, 0, 128, 128, new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());

		double wanDelay = SimManager.getInstance().getNetworkModel().getUploadDelay(task.getMobileDeviceId(),
				SimSettings.CLOUD_DATACENTER_ID, dummyTask /* 1 Mbit */);
		double wanBW = (wanDelay == 0) ? 0 : (1 / wanDelay); /* Mbps (since dummy payload ~1 Mbit) */

		// Aggregate edge utilization across all edge VMs (percentage basis)
		double edgeUtilization = SimManager.getInstance().getEdgeServerManager().getAvgUtilization();


		if(policy.equals("NETWORK_BASED")){
			// Threshold heuristic: if WAN > 5 Mbps offload to cloud else stay at edge
			if(wanBW > 5)
				result = SimSettings.CLOUD_DATACENTER_ID;
			else
				result = SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else if(policy.equals("UTILIZATION_BASED")){
			// If edge utilization exceeds 75%, divert load to cloud to prevent saturation
			double utilization = edgeUtilization;
			if(utilization > 75)
				result = SimSettings.CLOUD_DATACENTER_ID;
			else
				result = SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else if(policy.equals("RANDOM")){
			// Fair random split between edge and cloud resources
			double randomNumber = SimUtils.getRandomDoubleNumber(0, 1);
			if(randomNumber < 0.5)
				result = SimSettings.CLOUD_DATACENTER_ID;
			else
				result = SimSettings.GENERIC_EDGE_DEVICE_ID;
		}
		else {
			// Unknown policy => configuration error
			SimLogger.printLine("Unknown edge orchestrator policy! Terminating simulation...");
			edu.boun.edgecloudsim.utils.SimUtils.terminateSimulation();
		}

		return result;
	}

	@Override
	public Vm getVmToOffload(Task task, int deviceId) {
		if(policy.equals("ECT"))
			return ectDecisions.remove(task.getCloudletId());

		// Select VM with maximum residual capacity that can host predicted load (Least Loaded / WORST FIT)
		Vm selectedVM = null;

		if(deviceId == SimSettings.CLOUD_DATACENTER_ID){
			// Iterate all cloud hosts and VMs
			// Select a cloud VM with the least-loaded policy.
			double selectedVmCapacity = 0; //start with min value
			List<Host> list = SimManager.getInstance().getCloudServerManager().getDatacenter().getHostList();
			for (int hostIndex=0; hostIndex < list.size(); hostIndex++) {
				List<CloudVM> vmArray = SimManager.getInstance().getCloudServerManager().getVmList(hostIndex);
				for(int vmIndex=0; vmIndex<vmArray.size(); vmIndex++){
					// requiredCapacity: predicted CPU% for this VM type
					// targetVmCapacity: current free CPU% on the VM
					// Accept and update best if residual capacity larger
					double requiredCapacity = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(vmArray.get(vmIndex).getVmType());
					double targetVmCapacity = (double)100 - vmArray.get(vmIndex).getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
					if(requiredCapacity <= targetVmCapacity && targetVmCapacity > selectedVmCapacity){
						selectedVM = vmArray.get(vmIndex);
						selectedVmCapacity = targetVmCapacity;
					}
	            }
			}
		}
		else if(deviceId == SimSettings.GENERIC_EDGE_DEVICE_ID){
			// Iterate all edge hosts and their VMs similarly
			// Select an edge VM with the least-loaded policy.
			double selectedVmCapacity = 0; //start with min value
			for(int hostIndex=0; hostIndex<numberOfHost; hostIndex++){
				List<EdgeVM> vmArray = SimManager.getInstance().getEdgeServerManager().getVmList(hostIndex);
				for(int vmIndex=0; vmIndex<vmArray.size(); vmIndex++){
					double requiredCapacity = ((CpuUtilizationModel_Custom)task.getUtilizationModelCpu()).predictUtilization(vmArray.get(vmIndex).getVmType());
					double targetVmCapacity = (double)100 - vmArray.get(vmIndex).getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
					if(requiredCapacity <= targetVmCapacity && targetVmCapacity > selectedVmCapacity){
						selectedVM = vmArray.get(vmIndex);
						selectedVmCapacity = targetVmCapacity;
					}
				}
			}
		}
		else{
			// Defensive: unexpected device id
			SimLogger.printLine("Unknown device id! The simulation has been terminated.");
			edu.boun.edgecloudsim.utils.SimUtils.terminateSimulation();
		}

		return selectedVM;
	}

	// ------------------------------------------------------------------
	// Proposed policy: deadline-aware Estimated Completion Time (ECT)
	// ------------------------------------------------------------------

	/**
	 * Evaluates every VM that can host the task and remembers the selected one.
	 * Returns GENERIC_EDGE_DEVICE_ID with no remembered VM when no VM can host
	 * the task; the device manager then rejects it (VM capacity / bandwidth).
	 */
	private int ectGetDeviceToOffload(Task task) {
		NetworkModel networkModel = SimManager.getInstance().getNetworkModel();
		DeadlineNetworkModel deadlineNetwork = (DeadlineNetworkModel) networkModel;
		int mobileId = task.getMobileDeviceId();
		int deviceWlanId = task.getSubmittedLocation().getServingWlanId();
		double now = CloudSim.clock();
		double deadline = DeadlineStatsLogger.getDeadline(task.getTaskType());
		CpuUtilizationModel_Custom cpuModel = (CpuUtilizationModel_Custom) task.getUtilizationModelCpu();

		// Network legs (0 means the link cannot carry the task right now)
		double wlanUp = networkModel.getUploadDelay(mobileId, SimSettings.GENERIC_EDGE_DEVICE_ID, task);
		double wlanDown = networkModel.getDownloadDelay(SimSettings.GENERIC_EDGE_DEVICE_ID, mobileId, task);
		double manUp = deadlineNetwork.estimateManUploadDelay();
		double manDown = deadlineNetwork.estimateManDownloadDelay();
		double wanUp = networkModel.getUploadDelay(mobileId, SimSettings.CLOUD_DATACENTER_ID, task);
		double wanDown = networkModel.getDownloadDelay(SimSettings.CLOUD_DATACENTER_ID, mobileId, task);

		Vm bestVm = null;           // overall smallest ECT
		double bestEct = Double.MAX_VALUE;
		double bestArrival = 0;
		Vm safeVm = null;           // cheapest tier among safely-on-time candidates
		int safeRank = Integer.MAX_VALUE;
		double safeEct = Double.MAX_VALUE;
		double safeArrival = 0;

		// Edge candidates (local and remote)
		if(wlanUp > 0 && wlanDown > 0){
			for(int hostIndex=0; hostIndex<numberOfHost; hostIndex++){
				List<EdgeVM> vmArray = SimManager.getInstance().getEdgeServerManager().getVmList(hostIndex);
				for(EdgeVM vm : vmArray){
					if(!fits(vm, cpuModel.predictUtilization(vm.getVmType())))
						continue;
					EdgeHost host = (EdgeHost) vm.getHost();
					boolean local = host.getLocation().getServingWlanId() == deviceWlanId;
					double upload = wlanUp;
					double download = wlanDown;
					if(!local){
						if(manUp <= 0 || manDown <= 0)
							continue; // MAN saturated -> remote edge not reachable
						upload += manUp;
						download += manDown;
					}
					double arrival = now + upload;
					double ect = upload + estimateExecutionTime(vm, task.getCloudletLength(), now) + download;
					int rank = local ? RANK_LOCAL_EDGE : RANK_REMOTE_EDGE;

					if(ect < bestEct){
						bestEct = ect; bestVm = vm; bestArrival = arrival;
					}
					if(ect <= SAFETY_MARGIN * deadline && (rank < safeRank || (rank == safeRank && ect < safeEct))){
						safeRank = rank; safeEct = ect; safeVm = vm; safeArrival = arrival;
					}
				}
			}
		}

		// Cloud candidates
		if(wanUp > 0 && wanDown > 0){
			List<Host> hostList = SimManager.getInstance().getCloudServerManager().getDatacenter().getHostList();
			for(int hostIndex=0; hostIndex<hostList.size(); hostIndex++){
				List<CloudVM> vmArray = SimManager.getInstance().getCloudServerManager().getVmList(hostIndex);
				for(CloudVM vm : vmArray){
					if(!fits(vm, cpuModel.predictUtilization(vm.getVmType())))
						continue;
					double arrival = now + wanUp;
					double ect = wanUp + estimateExecutionTime(vm, task.getCloudletLength(), now) + wanDown;

					if(ect < bestEct){
						bestEct = ect; bestVm = vm; bestArrival = arrival;
					}
					if(ect <= SAFETY_MARGIN * deadline && (RANK_CLOUD < safeRank || (RANK_CLOUD == safeRank && ect < safeEct))){
						safeRank = RANK_CLOUD; safeEct = ect; safeVm = vm; safeArrival = arrival;
					}
				}
			}
		}

		Vm selected = (safeVm != null) ? safeVm : bestVm;
		if(selected == null)
			return SimSettings.GENERIC_EDGE_DEVICE_ID; // no VM available -> rejection downstream

		double selectedEct = (safeVm != null) ? safeEct : bestEct;
		double arrival = (safeVm != null) ? safeArrival : bestArrival;

		ectDecisions.put(task.getCloudletId(), selected);
		pendingTasks.computeIfAbsent(selected.getId(), k -> new ArrayList<>())
			.add(new double[] { arrival, task.getCloudletLength() });
		DeadlineStatsLogger.getInstance().taskEstimated(task.getCloudletId(), selectedEct);

		return (selected instanceof CloudVM) ? SimSettings.CLOUD_DATACENTER_ID : SimSettings.GENERIC_EDGE_DEVICE_ID;
	}

	/** Same admission rule as the competitors: predicted CPU % must fit into the free CPU %. */
	private static boolean fits(Vm vm, double requiredCapacity) {
		double freeCapacity = 100 - vm.getCloudletScheduler().getTotalUtilizationOfCpu(CloudSim.clock());
		return requiredCapacity <= freeCapacity;
	}

	/**
	 * Estimates how long the new task would run on the VM.
	 *
	 * The VMs use CloudSim's time-shared scheduler: with k single-core tasks on
	 * a VM with P cores of m MIPS each, every task progresses at m * min(1, P/k).
	 * The tasks already running (remaining length, corrected for the time since
	 * the scheduler's last update), the tasks still uploading to this VM and the
	 * new task are replayed under processor sharing until the new task finishes.
	 * Future arrivals are ignored, so this is an optimistic but load-aware estimate.
	 */
	private double estimateExecutionTime(Vm vm, double taskLength, double now) {
		CloudletScheduler scheduler = vm.getCloudletScheduler();
		int pes = vm.getNumberOfPes();
		double mipsPerPe = vm.getMips();

		List<ResCloudlet> execList = scheduler.getCloudletExecList();
		int running = execList.size();
		double currentRate = mipsPerPe * Math.min(1.0, pes / (double) Math.max(1, running));
		double elapsed = Math.max(0, now - scheduler.getPreviousTime());

		List<Double> others = new ArrayList<>();
		for(ResCloudlet rcl : execList){
			double remaining = rcl.getRemainingCloudletLength() - currentRate * elapsed;
			if(remaining > 0)
				others.add(remaining);
		}

		List<double[]> pending = pendingTasks.get(vm.getId());
		if(pending != null){
			for(Iterator<double[]> it = pending.iterator(); it.hasNext();){
				double[] p = it.next();
				if(p[0] <= now)
					it.remove(); // already arrived -> visible in the scheduler
				else
					others.add(p[1]);
			}
		}

		return processorSharingCompletion(others, taskLength, pes, mipsPerPe);
	}

	/** Completion time of a new task of length {@code taskLength} under processor sharing. */
	static double processorSharingCompletion(List<Double> others, double taskLength, int pes, double mipsPerPe) {
		List<Double> lengths = new ArrayList<>(others);
		Collections.sort(lengths);
		int active = lengths.size() + 1;
		double time = 0;
		double done = 0; // work (MI) completed by every still-active task
		for(double length : lengths){
			if(length >= taskLength)
				break;
			double rate = mipsPerPe * Math.min(1.0, pes / (double) active);
			time += (length - done) / rate;
			done = length;
			active--;
		}
		double rate = mipsPerPe * Math.min(1.0, pes / (double) active);
		return time + (taskLength - done) / rate;
	}

	@Override
	public void processEvent(SimEvent arg0) {
		// No asynchronous internal events required (stateless orchestrator)
		// No action is required for this event.
	}

	@Override
	public void shutdownEntity() {
		// No resources to release
		// No action is required for this event.
	}

	@Override
	public void startEntity() {
		// No startup scheduling needed
		// No action is required for this event.
	}

}
