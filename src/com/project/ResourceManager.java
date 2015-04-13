package com.project;

import com.project.utils.DataSerializer;
import com.project.utils.LogFile;
import com.project.utils.Node;
import org.I0Itec.zkclient.ZkClient;
import org.apache.zookeeper.*;

import java.util.List;

/**
 * Created by alok on 4/11/15 in ProjectMapReduce
 */
public class ResourceManager {

    private ZkClient zkClient;
    private static final int SESSION_TIMEOUT = 65000;
    private static ResourceManager resourceManagerInstance;

    public final static String APPLICATION_ROOT_PATH = "/mapr";
    public final static String SLAVES_ROOT_PATH = "/slaves";
    public final static String MASTER_ROOT_PATH = "/masters";
    public final static String TASKS_ROOT_PATH = "/tasks";

    public final static String IDLE_SLAVES_PATH = "/idle";
    public final static String BUSY_SLAVES_PATH = "/busy";
    public final static String ALL_SLAVES_PATH = "/all";

    private ResourceManager() {
        zkClient = new ZkClient(MapRSession.getInstance().getZookeeperHost(),
                SESSION_TIMEOUT, SESSION_TIMEOUT, new DataSerializer());

        zkClient.waitUntilConnected();

        LogFile.writeToLog("Connected to Zookeeper Configuration Manager");
    }

    public static void configureResourceManager(List<Integer> slaveIDs) {

        createZNode(APPLICATION_ROOT_PATH, "MapReduceRoot".getBytes(),
                CreateMode.PERSISTENT);

        createZNode(APPLICATION_ROOT_PATH + MASTER_ROOT_PATH, "MastersRoot".getBytes(),
                CreateMode.PERSISTENT);

        createZNode(APPLICATION_ROOT_PATH + TASKS_ROOT_PATH, "TasksRoot".getBytes(),
                CreateMode.PERSISTENT);

        createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH, "SlavesRoot".getBytes(),
                CreateMode.PERSISTENT);

        createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + IDLE_SLAVES_PATH,
                "IdleSlaves".getBytes(), CreateMode.PERSISTENT);

        createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + BUSY_SLAVES_PATH,
                "BusySlaves".getBytes(), CreateMode.PERSISTENT);

        createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + ALL_SLAVES_PATH,
                "AllSlaves".getBytes(), CreateMode.PERSISTENT);

        for (Integer slaveID : slaveIDs) {
            createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + ALL_SLAVES_PATH + "/" + slaveID,
                    ("Slave" + slaveID).getBytes(), CreateMode.PERSISTENT);
        }
    }

    public static List<String> getIdleSlavePaths() {
        List<String> idleSlaves = getInstance().zkClient.getChildren(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                + IDLE_SLAVES_PATH);
        return idleSlaves;
    }

    public static List<String> getBusySlavePaths() {
        List<String> busySlaves = getInstance().zkClient.getChildren(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                + BUSY_SLAVES_PATH);
        return busySlaves;
    }

    public static List<String> getAllSlavePaths() {
        List<String> allSlaves = getInstance().zkClient.getChildren(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                + ALL_SLAVES_PATH);
        return allSlaves;
    }

    public static String getMasterPath() {
        return getInstance().zkClient.getChildren(APPLICATION_ROOT_PATH + MASTER_ROOT_PATH).get(0);
    }

    public static Node getNodeFrom(String nodePath) {
        byte[] bytes = getInstance().zkClient.readData(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                + IDLE_SLAVES_PATH + "/" + nodePath);
        if(bytes != null)   {
            return Node.deserialize(bytes);
        }
        else
            return null;
    }

    public static void registerNode(Node node) {
        switch (node.getType()) {
            case MASTER:
                createZNode(APPLICATION_ROOT_PATH + MASTER_ROOT_PATH + "/" + node.getNodeID(),
                        Node.serialize(node), CreateMode.EPHEMERAL);
                break;

            case SLAVE:
                createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + IDLE_SLAVES_PATH + "/" + node.getNodeID(),
                        Node.serialize(node), CreateMode.EPHEMERAL);
                TaskHandler.subscribeTo("" + node.getNodeID(), node);
                break;
        }
    }

    public static void changeNodeState(int nodeID, Node.Status status) {
        switch (status) {
            case STARTUP:
                createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + IDLE_SLAVES_PATH + "/" + nodeID,
                        (nodeID + "").getBytes(), CreateMode.EPHEMERAL);
                break;

            case IDLE:
                getInstance().zkClient.delete(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                        + BUSY_SLAVES_PATH + "/" + nodeID);
                createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + IDLE_SLAVES_PATH + "/" + nodeID,
                        (nodeID + "").getBytes(), CreateMode.EPHEMERAL);
                break;

            case BUSY:
                getInstance().zkClient.delete(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                        + IDLE_SLAVES_PATH + "/" + nodeID);
                createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + BUSY_SLAVES_PATH + "/" + nodeID,
                        (nodeID + "").getBytes(), CreateMode.EPHEMERAL);
                break;

            case OFFLINE:
                createZNode(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH + ALL_SLAVES_PATH + "/" + nodeID,
                        (nodeID + "").getBytes(), CreateMode.PERSISTENT);

                if (getInstance().zkClient.exists(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                        + BUSY_SLAVES_PATH + "/" + nodeID))
                    getInstance().zkClient.delete(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                            + BUSY_SLAVES_PATH + "/" + nodeID);

                else if (getInstance().zkClient.exists(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                        + IDLE_SLAVES_PATH + "/" + nodeID))
                    getInstance().zkClient.delete(APPLICATION_ROOT_PATH + SLAVES_ROOT_PATH
                            + IDLE_SLAVES_PATH + "/" + nodeID);
                break;
        }
    }

    public static ResourceManager getInstance() {
        if (resourceManagerInstance == null) {
            resourceManagerInstance = new ResourceManager();
        }
        return resourceManagerInstance;
    }

    private static void createZNode(String path, Object data, CreateMode createMode) {

        if (!getInstance().zkClient.exists(path)) {
            switch (createMode) {
                case EPHEMERAL:
                    getInstance().zkClient.createEphemeral(path, data);
                    break;
                case PERSISTENT:
                    getInstance().zkClient.createPersistent(path, data);
            }
        }
    }
}
