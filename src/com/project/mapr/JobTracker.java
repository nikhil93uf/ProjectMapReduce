package com.project.mapr;

import com.project.MapRSession;
import com.project.ConfigurationManager;
import com.project.TaskDispatchManager;
import com.project.storage.FileSystem;
import com.project.utils.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by alok on 4/11/15
 */
public class JobTracker implements Serializable {

    private List<Task> scheduledMapTasks;
    private HashMap<Integer, Queue<Task>> backupPendingTasks;
    private HashMap<Integer, Queue<Task>> pendingReduceTasks;
    private HashMap<Integer, Queue<Task>> pendingMapTasks;
    private HashMap<Integer, Integer> redirectionIndex;
    private List<Integer> pendingResults;
    public List<Node> activeSlaves;
    private List<Integer> acknowledgedFailedSlaves;
    private Input jobInput;
    private Output jobOutput;
    private int totalMapTasks = 0;
    private int totalReduceTasks = 0;
    private AtomicInteger runningTasksCount = new AtomicInteger(0);
    private AtomicInteger completedTasksCount = new AtomicInteger(0);
    private boolean isMapPhase = true;
    private int nextSlave = 0;
    private long startTime = 0;
    private boolean outputSessionFlag = false;

    public JobTracker(Input inputFile) {
        pendingMapTasks = new HashMap<>();
        scheduledMapTasks = new ArrayList<>();
        pendingReduceTasks = new HashMap<>();
        pendingResults = new ArrayList<>();
        acknowledgedFailedSlaves = new ArrayList<>();
        redirectionIndex = new HashMap<>();
        jobInput = inputFile;
        jobOutput = new Output(new File("results.txt"));
        LogFile.writeToLog("Job Tracker initialized. Output will be written to results.txt");
    }

    public void start() {
        checkForSlaves();
        TaskDispatchManager.getInstance().connectToSlaves();
        LogFile.writeToLog("All slaves connected");
        startTime = System.currentTimeMillis();
        LogFile.writeToLog("Started partitioning map tasks");
        initializeMapTasks();
        LogFile.writeToLog("Map tasks have been created");
        assignMapTasks();
        LogFile.writeToLog("Job Scheduler has assigned map tasks");
        LogFile.writeToLog("Map phase has begun");
        beginTasks();
    }

    private void checkForSlaves() {
        List<Node> slaveNodes = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : ConfigurationManager.slaveAddresses.entrySet()) {
            slaveNodes.add(new Node(Node.Type.SLAVE, entry.getKey()));
        }
        this.activeSlaves = slaveNodes;
    }

    public void initializeMapTasks() {
        Task task;
        Input taskInput;
        int count = 1;
        scheduledMapTasks.clear();

        for (File file : jobInput.getLocalFile().listFiles()) {
            if (file.isDirectory()) {
                for (File subFile : file.listFiles()) {
                    task = new Task();
                    task.setType(Task.Type.MAP);
                    task.setStatus(Task.Status.INITIALIZED);
                    task.setTaskID(count);
                    taskInput = new Input(subFile);
                    task.setTaskInput(taskInput);
                    scheduledMapTasks.add(task);
                    count++;
                }
            } else {
                task = new Task();
                task.setType(Task.Type.MAP);
                task.setStatus(Task.Status.INITIALIZED);
                task.setTaskID(count);
                taskInput = new Input(file);
                task.setTaskInput(taskInput);
                scheduledMapTasks.add(task);
                count++;
            }
        }
    }

    public void assignMapTasks() {
        Queue<Task> taskQueue;
        Node temp;

        for (Task pendingTask : scheduledMapTasks) {
            temp = getAliveSlave();

            if (!TaskDispatchManager.getInstance().offlineSlaves.contains(temp.getNodeID())) {
                pendingTask.setCurrentExecutorID(temp.getNodeID());
                if (pendingMapTasks.containsKey(temp.getNodeID())) {
                    pendingMapTasks.get(temp.getNodeID()).add(pendingTask);
                } else {
                    taskQueue = new LinkedBlockingQueue<>();
                    taskQueue.add(pendingTask);
                    pendingMapTasks.put(temp.getNodeID(), taskQueue);
                }
            }

            totalMapTasks++;
        }
    }

    public void beginTasks() {
        final HashMap<Integer, Queue<Task>> pendingTasks = isMapPhase ? pendingMapTasks : pendingReduceTasks;

        LogFile.writeToLog("Making a copy of the assigned tasks");
        backupPendingTasks = new HashMap<>();
        for (Map.Entry<Integer, Queue<Task>> entry : pendingTasks.entrySet()) {
            backupPendingTasks.put(entry.getKey(), new LinkedBlockingQueue<>(entry.getValue()));
        }

        completedTasksCount = new AtomicInteger(0);
        runningTasksCount = new AtomicInteger(0);

        for (final Map.Entry<Integer, Queue<Task>> entry : pendingTasks.entrySet()) {
            if (entry.getValue().size() != 0) {
                runningTasksCount.incrementAndGet();
                new Thread(new Runnable() {
                    Task pendingTask;

                    @Override
                    public void run() {
                        pendingTask = entry.getValue().remove();
                        if (!isMapPhase)
                            TaskDispatchManager.dispatchTask(Task.convertToRemoteInput(pendingTask));
                        else if (MapRSession.getInstance().getMode() == MapRSession.Mode.ONLINE && isMapPhase)
                            TaskDispatchManager.dispatchTask(Task.convertToRemoteInput(pendingTask));
                        else {
                            TaskDispatchManager.dispatchTask(pendingTask);
                        }
                    }
                }).start();
            }
        }
    }

    public void rescheduleTasksFrom(Integer slaveID) {
        HashMap<Integer, Queue<Task>> pendingTasks = isMapPhase ? pendingMapTasks : pendingReduceTasks;
        Iterator<Task> deadTasks = backupPendingTasks.get(slaveID).iterator();
        Node temp;
        Task pendingTask;
        Queue<Task> taskQueue;

        runningTasksCount.incrementAndGet();
        LogFile.writeToLog("Rescheduling slave " + slaveID + "'s tasks");
        while (deadTasks.hasNext()) {
            pendingTask = deadTasks.next();
            temp = getAliveSlave();
            if (!TaskDispatchManager.getInstance().offlineSlaves.contains(temp.getNodeID())) {
                pendingTask.setCurrentExecutorID(temp.getNodeID());
                completedTasksCount.decrementAndGet();
                if (isMapPhase)
                    totalMapTasks++;
                else
                    totalReduceTasks++;

                if (pendingTasks.containsKey(temp.getNodeID())) {
                    pendingTasks.get(temp.getNodeID()).add(pendingTask);
                    pendingTasks.put(temp.getNodeID(), pendingTasks.get(temp.getNodeID()));
                } else {
                    taskQueue = new LinkedBlockingQueue<>();
                    taskQueue.add(pendingTask);
                    pendingTasks.put(temp.getNodeID(), taskQueue);
                }
            }
        }
        pendingTasks.get(slaveID).clear();
        LogFile.writeToLog("Rescheduling complete");
    }

    public void acknowledgeFailure(Integer slave) {
        runningTasksCount.decrementAndGet();
        pendingResults.remove(slave);
    }

    public void markTaskComplete(Task task) {
        HashMap<Integer, Queue<Task>> pendingTasks = isMapPhase ? pendingMapTasks : pendingReduceTasks;
        Task pendingTask;
        int deadCount = 0;

        if (task.getStatus() != Task.Status.END) {
            runningTasksCount.decrementAndGet();
            completedTasksCount.incrementAndGet();
        }

        synchronized (pendingTasks) {
            if (pendingTasks.get(task.getCurrentExecutorID()).size() > 0) {
                runningTasksCount.incrementAndGet();

                pendingTask = pendingTasks.get(task.getCurrentExecutorID()).remove();
                if (!isMapPhase)
                    TaskDispatchManager.dispatchTask(Task.convertToRemoteInput(pendingTask));
                else if (MapRSession.getInstance().getMode() == MapRSession.Mode.ONLINE && isMapPhase)
                    TaskDispatchManager.dispatchTask(Task.convertToRemoteInput(pendingTask));
                else {
                    TaskDispatchManager.dispatchTask(pendingTask);
                }
            }
        }

        if (task.getType() == Task.Type.MAP)
            LogFile.writeToLog("Map progress: " + (completedTasksCount.get() * 100) / totalMapTasks
                    + "% Pending tasks: " + (totalMapTasks - completedTasksCount.get()));
        else
            LogFile.writeToLog("Reduce progress: " + (completedTasksCount.get() * 100) / totalReduceTasks
                    + "% Pending tasks: " + (totalReduceTasks - completedTasksCount.get()));

        if (isMapPhase && (getRunningTasksCount() == 0)) {
            if (isMapPhase)
                totalMapTasks = 0;
            else
                totalReduceTasks = 0;

            if (TaskDispatchManager.getInstance().offlineSlaves.size() > 0) {
                for (Integer slave : TaskDispatchManager.getInstance().offlineSlaves) {
                    if (!acknowledgedFailedSlaves.contains(slave)) {
                        rescheduleTasksFrom(slave);
                        acknowledgedFailedSlaves.add(slave);
                        deadCount++;
                    }
                }

                if (deadCount > 0)
                    beginTasks();
                else {
                    LogFile.writeToLog("Completed Map phase. Consolidating reduce tasks");
                    collectResults(task.getType());
                }
            } else {
                LogFile.writeToLog("Completed Map phase. Consolidating reduce tasks");
                collectResults(task.getType());
            }
        } else if (!isMapPhase && getRunningTasksCount() == 0) {
            collectResults(task.getType());
        }
    }

    private void collectResults(Task.Type type) {
        for (Integer slave : pendingResults) {
            Task tempTask = new Task();
            tempTask.setType(type);
            tempTask.setStatus(Task.Status.COMPLETE);
            tempTask.setCurrentExecutorID(slave);
            TaskDispatchManager.dispatchTask(tempTask);
        }
    }

    public Task getPendingTaskFor(Node slaveNode) {
        return new Task();
    }

    public HashMap<Integer, Queue<Task>> getPendingMapTasks() {
        return pendingMapTasks;
    }

    public List<Task> getScheduledMapTasks() {
        return scheduledMapTasks;
    }

    public int getRunningTasksCount() {
        return runningTasksCount.get();
    }

    public void collectTaskOutput(final Task task) {
        Task reduceTask;
        Queue<Task> taskQueue;
        Integer slave;

        if (task.getStatus() == Task.Status.COMPLETE) {
            synchronized (pendingResults) {
                if (!TaskDispatchManager.getInstance().offlineSlaves.contains(task.getCurrentExecutorID())
                        && !pendingResults.contains(task.getCurrentExecutorID()))
                    pendingResults.add(task.getCurrentExecutorID());
            }
        } else if (task.getStatus() == Task.Status.END) {
            if (task.getType() == Task.Type.MAP) {
                for (Map.Entry<Integer, String> partitionEntry : task.getReducePartitionIDs().entrySet()) {
                    reduceTask = new Task();
                    try {
                        slave = activeSlaves.get(partitionEntry.getKey()).getNodeID();
                    } catch (ClassCastException e) {
                        continue;
                    }
                    if (acknowledgedFailedSlaves.contains(slave)) {
                        if (!redirectionIndex.containsKey(slave))
                            redirectionIndex.put(slave, getAliveSlave().getNodeID());
                        slave = redirectionIndex.get(slave);
                    }
                    reduceTask.setCurrentExecutorID(slave);
                    reduceTask.setTaskInput(new Input(partitionEntry.getValue()));
                    reduceTask.setType(Task.Type.REDUCE);
                    reduceTask.setStatus(Task.Status.INITIALIZED);
                    totalReduceTasks++;

                    synchronized (pendingReduceTasks) {
                        if (pendingReduceTasks.containsKey(slave))
                            pendingReduceTasks.get(slave).add(reduceTask);
                        else {
                            taskQueue = new LinkedBlockingQueue<>();
                            taskQueue.add(reduceTask);
                            pendingReduceTasks.put(slave, taskQueue);
                        }

                        if (pendingResults.size() == 0 && isMapPhase) {
                            isMapPhase = false;
                            beginTasks();
                        }
                    }
                    pendingResults.remove(new Integer(task.getCurrentExecutorID()));
                }
            } else {
                synchronized (pendingResults) {
                    File localFile = FileSystem.copyFromRemotePath(task.getTaskOutput().getRemoteDataPath());
                    parseKeyValuePair(localFile);
                    pendingResults.remove(new Integer(task.getCurrentExecutorID()));
                    if (pendingResults.size() == 0) {
                        LogFile.writeToLog("Job took " + (System.currentTimeMillis() - startTime)
                                + " milliseconds to complete");
                        MapRSession.exit(0);
                    }
                }
            }
        }
    }

    private void parseKeyValuePair(File intermediateFile) {
        BufferedReader bufferedReader;
        StringTokenizer stringTokenizer;
        String temp, key, value;
        PrintWriter printWriter;

        try {
            bufferedReader = new BufferedReader(new FileReader(intermediateFile));
            while ((temp = bufferedReader.readLine()) != null) {
                stringTokenizer = new StringTokenizer(temp, ":");
                key = stringTokenizer.nextToken();
                value = stringTokenizer.nextToken();
                printWriter = new PrintWriter(new FileWriter(jobOutput.getLocalFile(), outputSessionFlag));
                printWriter.println(key + " " + value);
                printWriter.flush();
                printWriter.close();

                if (!outputSessionFlag)
                    outputSessionFlag = true;
            }
            bufferedReader.close();
        } catch (java.io.IOException e) {
            e.printStackTrace();
        }
    }

    private Node getAliveSlave() {
        Node temp;

        do {
            if (nextSlave >= activeSlaves.size())
                nextSlave = nextSlave % activeSlaves.size();
            temp = activeSlaves.get(nextSlave);
            nextSlave++;
        } while (TaskDispatchManager.getInstance().offlineSlaves.contains(temp.getNodeID()));

        return temp;
    }
}