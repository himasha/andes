/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.andes.server.cluster;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.andes.configuration.AndesConfigurationManager;
import org.wso2.andes.configuration.enums.AndesConfiguration;
import org.wso2.andes.kernel.Andes;
import org.wso2.andes.kernel.AndesContext;
import org.wso2.andes.kernel.AndesContextStore;
import org.wso2.andes.kernel.AndesException;
import org.wso2.andes.kernel.AndesKernelBoot;
import org.wso2.andes.server.ClusterResourceHolder;
import org.wso2.andes.server.cluster.coordination.CoordinationConstants;
import org.wso2.andes.store.FailureObservingStoreManager;
import org.wso2.andes.store.HealthAwareStore;
import org.wso2.andes.store.StoreHealthListener;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Cluster Manager is responsible for Handling the Broker Cluster Management Tasks like
 * Queue Worker distribution. Fail over handling for cluster nodes. etc.
 */
public class ClusterManager implements StoreHealthListener{

    /**
     * Class logger
     */
    private Log log = LogFactory.getLog(ClusterManager.class);

    /**
     * Id of the local node
     */
    private String nodeId;

    /**
     * AndesContextStore instance
     */
    private AndesContextStore andesContextStore;

    /**
     * Cluster agent for managing cluster communication
     */
    private ClusterAgent clusterAgent;

    /**
     * This is the cached value of the operational status of the store
     */
    private boolean storeOperational;

    /**
     * Create a ClusterManager instance
     */
    public ClusterManager() {
        this.andesContextStore = AndesContext.getInstance().getAndesContextStore();
    }

    /**
     * Initialize the Cluster manager.
     *
     * @throws AndesException
     */
    public void init() throws AndesException{

        if (AndesContext.getInstance().isClusteringEnabled()) {
            initClusterMode();
        } else {
            initStandaloneMode();
        }
        // set storeOperational to true since it can be assumed that the store is operational at startup
        // if it is non-operational, the value will be updated immediately
        storeOperational = true;
        // Register this instance to listen to the store health
        FailureObservingStoreManager.registerStoreHealthListener(this);

    }

    /**
     * Handles changes needs to be done in current node when a node joins to the cluster.
     *
     * @param addedNodeId An ID for the newly added node. This is does not refer to the correct node ID. I.E its not a
     *                    reference to the member's node ID attribute.
     */
    public void memberAdded(String addedNodeId) {
        log.info("Handling cluster gossip: Node " + addedNodeId + "  Joined the Cluster");
    }

    /**
     * Handles changes needs to be done in current node when a node leaves the cluster
     * @param deletedNodeId deleted node id
     */
    public void memberRemoved(String deletedNodeId) throws AndesException {
        log.info("Handling cluster gossip: Node " + deletedNodeId + "  left the Cluster");

        if(clusterAgent.isCoordinator()) {
            ClusterResourceHolder.getInstance().getSubscriptionManager()
                    .removeAllSubscriptionsOfNodeFromMemoryAndStore(deletedNodeId);
        } else {
            ClusterResourceHolder.getInstance().getSubscriptionManager()
                    .removeAllSubscriptionsOfNodeFromMemory(deletedNodeId);
        }
    }

    /**
     * Get whether clustering is enabled
     *
     * @return true if clustering is enabled, false otherwise.
     */
    public boolean isClusteringEnabled() {
        return AndesContext.getInstance().isClusteringEnabled();
    }

    /**
     * Get the node ID of the current node
     *
     * @return current node's ID
     */
    public String getMyNodeID() {
        return nodeId;
    }

    /**
     * Perform cleanup tasks before shutdown
     */
    public void prepareLocalNodeForShutDown() throws AndesException {
        //clear stored node IDS and mark subscriptions of node as closed
        clearAllPersistedStatesOfLocalNode();
    }

    /**
     * Gets the unique ID for the local node
     *
     * @return unique ID
     */
    public int getUniqueIdForLocalNode() {
        if (AndesContext.getInstance().isClusteringEnabled()) {
            return clusterAgent.getUniqueIdForLocalNode();
        }
        return 0;
    }

    /**
     * Initialize the node in stand alone mode without hazelcast.
     *
     * @throws AndesException, UnknownHostException
     */
    private void initStandaloneMode() throws AndesException{

        try {
            // Get Node ID configured by user in broker.xml (if not "default" we must use it as the ID)
            this.nodeId = AndesConfigurationManager.readValue(AndesConfiguration.COORDINATION_NODE_ID);

            if (AndesConfiguration.COORDINATION_NODE_ID.get().getDefaultValue().equals(this.nodeId)) {
                this.nodeId = CoordinationConstants.NODE_NAME_PREFIX + InetAddress.getLocalHost().toString();
            }

            //update node information in durable store
            List<String> nodeList = new ArrayList<>(andesContextStore.getAllStoredNodeData().keySet());

            for (String node : nodeList) {
                andesContextStore.removeNodeData(node);
            }

            log.info("Initializing Standalone Mode. Current Node ID:" + this.nodeId + " "
                     + InetAddress.getLocalHost().getHostAddress());

            andesContextStore.storeNodeDetails(nodeId, InetAddress.getLocalHost().getHostAddress());
        } catch (UnknownHostException e) {
            throw new AndesException("Unable to get the localhost address.", e);
        }
    }

    /**
     * Initializes cluster mode
     *
     * @throws AndesException
     */
    private void initClusterMode() throws AndesException {

        this.clusterAgent = new CoordinationConfigurableClusterAgent();
        AndesContext.getInstance().setClusterAgent(clusterAgent);

        nodeId = clusterAgent.start(this);
    }

    /**
     * Clears all persisted states of a disappeared node
     *
     * @param nodeID node ID
     * @throws AndesException
     */
    private void clearAllPersistedStatesOfDisappearedNode(String nodeID) throws AndesException {

        log.info("Clearing the Persisted State of Node with ID " + nodeID);
        andesContextStore.removeNodeData(nodeID);

        ClusterResourceHolder.getInstance().
                getSubscriptionManager().removeAllSubscriptionsOfNodeFromMemory(nodeID);

    }

    /**
     * Clears all persisted states of the local node.
     *
     * @throws AndesException if an error is occured when closing the connection or removing the subscription.
     */
    private void clearAllPersistedStatesOfLocalNode() throws AndesException {

        log.info("Clearing the Persisted State of Node with ID " + this.nodeId);
        andesContextStore.removeNodeData(nodeId);
        ClusterResourceHolder.getInstance().getSubscriptionManager().closeAllActiveLocalSubscriptions();
    }


    /**
     * Perform coordinator initialization tasks, when this node is elected as the new coordinator
     */
    public void localNodeElectedAsCoordinator() {
        Andes.getInstance().makeActive();
    }

    /**
     * Gets the message store's health status
     *
     * @return true if healthy, else false.
     */
    public boolean getStoreHealth() {
       return storeOperational;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void storeNonOperational(HealthAwareStore store, Exception ex) {
        log.warn("Store became non-operational.");
        storeOperational = false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void storeOperational(HealthAwareStore store) {
        storeOperational = true;
        log.info("Store became operational.");
    }

    /**
     * This method contains the tasks to be done when node become passive
     */
    public void coordinatorStateLost() {
        log.info("Start closing all connections since the active state is lost");
        Andes.getInstance().makePassive();
        AndesKernelBoot.closeAllConnections();
        log.info("Finished closing all client connections");
    }
}
