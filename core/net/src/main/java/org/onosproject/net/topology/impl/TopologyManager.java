/*
 * Copyright 2014-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.net.topology.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.net.DisjointPath;
import org.onosproject.net.provider.AbstractListenerProviderRegistry;
import org.onosproject.event.Event;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.provider.AbstractProviderService;
import org.onosproject.net.topology.*;
import org.slf4j.Logger;

import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.onosproject.net.topology.AdapterLinkWeigher.adapt;
import static org.onosproject.security.AppGuard.checkPermission;
import static org.slf4j.LoggerFactory.getLogger;
import static org.onosproject.security.AppPermission.Type.*;


/**
 * Provides basic implementation of the topology SB &amp; NB APIs.
 */
@Component(immediate = true)
@Service
public class TopologyManager
        extends AbstractListenerProviderRegistry<TopologyEvent, TopologyListener,
        TopologyProvider, TopologyProviderService>
        implements TopologyService, TopologyProviderRegistry {

    private static final String TOPOLOGY_NULL = "Topology cannot be null";
    private static final String DEVICE_ID_NULL = "Device ID cannot be null";
    private static final String CLUSTER_ID_NULL = "Cluster ID cannot be null";
    private static final String CLUSTER_NULL = "Topology cluster cannot be null";
    private static final String CONNECTION_POINT_NULL = "Connection point cannot be null";
    private static final String LINK_WEIGHT_NULL = "Link weight cannot be null";
    private static HashSet<String> hs = new HashSet<String>();

    private final Logger log = getLogger(getClass());

    private TopologyStoreDelegate delegate = new InternalStoreDelegate();

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyStore store;

    @Activate
    public void activate() {

        store.setDelegate(delegate);
        eventDispatcher.addSink(TopologyEvent.class, listenerRegistry);
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        store.unsetDelegate(delegate);
        eventDispatcher.removeSink(TopologyEvent.class);
        log.info("Stopped");
    }

    @Override
    public Topology currentTopology() {
        checkPermission(TOPOLOGY_READ);
        return store.currentTopology();
    }

    @Override
    public boolean isLatest(Topology topology) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        return store.isLatest(topology);
    }

    @Override
    public Set<TopologyCluster> getClusters(Topology topology) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        return store.getClusters(topology);
    }

    @Override
    public TopologyCluster getCluster(Topology topology, ClusterId clusterId) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(topology, CLUSTER_ID_NULL);
        return store.getCluster(topology, clusterId);
    }

    @Override
    public Set<DeviceId> getClusterDevices(Topology topology, TopologyCluster cluster) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(topology, CLUSTER_NULL);
        return store.getClusterDevices(topology, cluster);
    }

    @Override
    public Set<Link> getClusterLinks(Topology topology, TopologyCluster cluster) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(topology, CLUSTER_NULL);
        return store.getClusterLinks(topology, cluster);
    }

    @Override
    public TopologyGraph getGraph(Topology topology) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        return store.getGraph(topology);
    }


    @Override
    public synchronized Set<Path> getPaths(Topology topology, DeviceId src, DeviceId dst) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(src, DEVICE_ID_NULL);
        checkNotNull(dst, DEVICE_ID_NULL);
        log.info("hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh开始执行算法");
        Set<Path> result = store.getPaths(topology, src, dst);
        log.info("hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh算法执行结果");
        log.info("对于源目分别是：" + src.toString() + "," + dst.toString());
        log.info("hhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhhh得到了" + result.size() + "条path");
        //hs.add(src.toString().trim());
        //log.info("到目前为止hs的size: " + hs.size());
        return result;
        //return store.getPaths(topology, src, dst);
    }

    @Override
    public synchronized LinkedList<Link> getAllPaths(Topology topology){
        LinkedList<Link> links = store.getAllPaths(topology);
        return links;
    }

    @Override
    public synchronized Set<Path> getPaths1(Topology topology, DeviceId src, DeviceId dst, DeviceId hs){
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(src, DEVICE_ID_NULL);
        checkNotNull(dst, DEVICE_ID_NULL);
        log.info("开始执行算法");
        Set<Path> result = store.getPaths1(topology, src, dst, hs);
        log.info("算法执行结果");
        log.info("对于源目分别是：" + src.toString() + "," + dst.toString());
        log.info("得到了" + result.size() + "条path");
        //hs.add(src.toString().trim());
        //log.info("到目前为止hs的size: " + hs.size());
        return result;
        //return store.getPaths(topology, src, dst);
    }

    @Override
    public Set<Path> getPaths(Topology topology, DeviceId src,
                              DeviceId dst, LinkWeight weight) {
        return getPaths(topology, src, dst, adapt(weight));
    }

    @Override
    public Set<Path> getPaths(Topology topology, DeviceId src,
                              DeviceId dst, LinkWeigher weigher) {
        checkPermission(TOPOLOGY_READ);

        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(src, DEVICE_ID_NULL);
        checkNotNull(dst, DEVICE_ID_NULL);
        checkNotNull(weigher, "Link weight cannot be null");

        return store.getPaths(topology, src, dst, weigher);
    }

    @Override
    public Set<DisjointPath> getDisjointPaths(Topology topology, DeviceId src,
                                              DeviceId dst) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(src, DEVICE_ID_NULL);
        checkNotNull(dst, DEVICE_ID_NULL);
        return store.getDisjointPaths(topology, src, dst);
    }

    @Override
    public Set<DisjointPath> getDisjointPaths(Topology topology, DeviceId src,
                                              DeviceId dst,
                                              LinkWeight weight) {
        return getDisjointPaths(topology, src, dst, adapt(weight));
    }

    @Override
    public Set<DisjointPath> getDisjointPaths(Topology topology, DeviceId src,
                                              DeviceId dst,
                                              LinkWeigher weigher) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(src, DEVICE_ID_NULL);
        checkNotNull(dst, DEVICE_ID_NULL);
        checkNotNull(weigher, LINK_WEIGHT_NULL);
        return store.getDisjointPaths(topology, src, dst, weigher);
    }

    @Override
    public Set<DisjointPath> getDisjointPaths(Topology topology, DeviceId src,
                                              DeviceId dst,
                                              Map<Link, Object> riskProfile) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(src, DEVICE_ID_NULL);
        checkNotNull(dst, DEVICE_ID_NULL);
        return store.getDisjointPaths(topology, src, dst, riskProfile);
    }

    @Override
    public Set<DisjointPath> getDisjointPaths(Topology topology, DeviceId src,
                                              DeviceId dst, LinkWeight weight,
                                              Map<Link, Object> riskProfile) {
        return getDisjointPaths(topology, src, dst, adapt(weight), riskProfile);
    }

    @Override
    public Set<DisjointPath> getDisjointPaths(Topology topology, DeviceId src,
                                              DeviceId dst,
                                              LinkWeigher weigher,
                                              Map<Link, Object> riskProfile) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(src, DEVICE_ID_NULL);
        checkNotNull(dst, DEVICE_ID_NULL);
        checkNotNull(weigher, LINK_WEIGHT_NULL);
        return store.getDisjointPaths(topology, src, dst, weigher, riskProfile);
    }

    @Override
    public boolean isInfrastructure(Topology topology, ConnectPoint connectPoint) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(connectPoint, CONNECTION_POINT_NULL);
        return store.isInfrastructure(topology, connectPoint);
    }

    @Override
    public boolean isBroadcastPoint(Topology topology, ConnectPoint connectPoint) {
        checkPermission(TOPOLOGY_READ);
        checkNotNull(topology, TOPOLOGY_NULL);
        checkNotNull(connectPoint, CONNECTION_POINT_NULL);
        return store.isBroadcastPoint(topology, connectPoint);
    }

    // Personalized host provider service issued to the supplied provider.
    @Override
    protected TopologyProviderService createProviderService(TopologyProvider provider) {
        return new InternalTopologyProviderService(provider);
    }

    private class InternalTopologyProviderService
            extends AbstractProviderService<TopologyProvider>
            implements TopologyProviderService {

        InternalTopologyProviderService(TopologyProvider provider) {
            super(provider);
        }

        @Override
        public void topologyChanged(GraphDescription topoDescription,
                                    List<Event> reasons) {
            checkNotNull(topoDescription, "Topology description cannot be null");

            TopologyEvent event = store.updateTopology(provider().id(),
                                                       topoDescription, reasons);
            if (event != null) {
                log.info("Topology {} changed", event.subject());
                post(event);
            }
        }
    }

    // Store delegate to re-post events emitted from the store.
    private class InternalStoreDelegate implements TopologyStoreDelegate {
        @Override
        public void notify(TopologyEvent event) {
            post(event);
        }
    }
}
