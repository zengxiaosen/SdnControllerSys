/*
 * Copyright 2015-present Open Networking Laboratory
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
package org.onosproject.common;

import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.ImmutableSetMultimap.Builder;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.graph.*;
import org.onlab.graph.GraphPathSearch.Result;
import org.onlab.graph.TarjanGraphSearch.SccResult;
import org.onosproject.net.AbstractModel;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DefaultDisjointPath;
import org.onosproject.net.DefaultPath;
import org.onosproject.net.DeviceId;
import org.onosproject.net.DisjointPath;
import org.onosproject.net.Link;
import org.onosproject.net.Link.Type;
import org.onosproject.net.Path;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.provider.ProviderId;
import org.onosproject.net.statistic.FlowStatisticService;
import org.onosproject.net.topology.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static org.onlab.graph.GraphPathSearch.ALL_PATHS;
import static org.onlab.util.Tools.isNullOrEmpty;
import static org.onosproject.core.CoreService.CORE_PROVIDER_ID;
import static org.onosproject.net.Link.State.INACTIVE;
import static org.onosproject.net.Link.Type.INDIRECT;
import static org.onosproject.net.topology.AdapterLinkWeigher.adapt;

/**
 * Default implementation of the topology descriptor. This carries the backing
 * topology data.
 */
public class DefaultTopology extends AbstractModel implements Topology {

    private static final Logger log = LoggerFactory.getLogger(DefaultTopology.class);

    private static final DijkstraGraphSearch<TopologyVertex, TopologyEdge> DIJKSTRA =
            new DijkstraGraphSearch<>();
    private static final TarjanGraphSearch<TopologyVertex, TopologyEdge> TARJAN =
            new TarjanGraphSearch<>();
    private static final SuurballeGraphSearch<TopologyVertex, TopologyEdge> SUURBALLE =
            new SuurballeGraphSearch<>();

    private static LinkWeigher defaultLinkWeigher = null;
    private static GraphPathSearch<TopologyVertex, TopologyEdge> defaultGraphPathSearch = null;

    private final long time;
    private final long creationTime;
    private final long computeCost;
    private final TopologyGraph graph;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowStatisticService flowStatisticService;

    private final LinkWeigher hopCountWeigher;
    //private final LinkWeigher bdCountWeigher;

    private final Supplier<SccResult<TopologyVertex, TopologyEdge>> clusterResults;
    private final Supplier<ImmutableMap<ClusterId, TopologyCluster>> clusters;
    private final Supplier<ImmutableSet<ConnectPoint>> infrastructurePoints;
    private final Supplier<ImmutableSetMultimap<ClusterId, ConnectPoint>> broadcastSets;
    private final Function<ConnectPoint, Boolean> broadcastFunction;
    private final Supplier<ClusterIndexes> clusterIndexes;

    /**
     * Sets the default link-weight to be used when computing paths. If null is
     * specified, the builtin default link-weight measuring hop-counts will be
     * used.
     *
     * @param linkWeigher new default link-weight
     */
    public static void setDefaultLinkWeigher(LinkWeigher linkWeigher) {

        log.info("Setting new default link-weight function to {}", linkWeigher);
        defaultLinkWeigher = linkWeigher;
    }

    /**
     * Sets the default lpath search algorighm to be used when computing paths.
     * If null is specified, the builtin default Dijkstra will be used.
     *
     * @param graphPathSearch new default algorithm
     */
    public static void setDefaultGraphPathSearch(
            GraphPathSearch<TopologyVertex, TopologyEdge> graphPathSearch) {
        log.info("Setting new default graph path algorithm to {}", graphPathSearch);
        defaultGraphPathSearch = graphPathSearch;
    }


    /**
     * Creates a topology descriptor attributed to the specified provider.
     *
     * @param providerId        identity of the provider
     * @param description       data describing the new topology
     * @param broadcastFunction broadcast point function
     */
    public DefaultTopology(ProviderId providerId, GraphDescription description,
                           Function<ConnectPoint, Boolean> broadcastFunction) {
        super(providerId);
        this.broadcastFunction = broadcastFunction;
        this.time = description.timestamp();
        this.creationTime = description.creationTime();

        // Build the graph
        this.graph = new DefaultTopologyGraph(description.vertexes(),
                description.edges());

        this.clusterResults = Suppliers.memoize(this::searchForClusters);
        this.clusters = Suppliers.memoize(this::buildTopologyClusters);

        this.clusterIndexes = Suppliers.memoize(this::buildIndexes);

        this.hopCountWeigher = adapt(new HopCountLinkWeight(graph.getVertexes().size()));

        this.broadcastSets = Suppliers.memoize(this::buildBroadcastSets);
        this.infrastructurePoints = Suppliers.memoize(this::findInfrastructurePoints);
        this.computeCost = Math.max(0, System.nanoTime() - time);
    }

    /**
     * Creates a topology descriptor attributed to the specified provider.
     *
     * @param providerId  identity of the provider
     * @param description data describing the new topology
     */
    public DefaultTopology(ProviderId providerId, GraphDescription description) {
        this(providerId, description, null);
    }

    @Override
    public long time() {
        return time;
    }

    @Override
    public long creationTime() {
        return creationTime;
    }

    @Override
    public long computeCost() {
        return computeCost;
    }

    @Override
    public int clusterCount() {
        return clusters.get().size();
    }

    @Override
    public int deviceCount() {
        return graph.getVertexes().size();
    }

    @Override
    public int linkCount() {
        return graph.getEdges().size();
    }

    private ImmutableMap<DeviceId, TopologyCluster> clustersByDevice() {
        return clusterIndexes.get().clustersByDevice;
    }

    private ImmutableSetMultimap<TopologyCluster, DeviceId> devicesByCluster() {
        return clusterIndexes.get().devicesByCluster;
    }

    private ImmutableSetMultimap<TopologyCluster, Link> linksByCluster() {
        return clusterIndexes.get().linksByCluster;
    }

    /**
     * Returns the backing topology graph.
     *
     * @return topology graph
     */
    public TopologyGraph getGraph() {
        return graph;
    }

    /**
     * Returns the set of topology clusters.
     *
     * @return set of clusters
     */
    public Set<TopologyCluster> getClusters() {
        return ImmutableSet.copyOf(clusters.get().values());
    }

    /**
     * Returns the specified topology cluster.
     *
     * @param clusterId cluster identifier
     * @return topology cluster
     */
    public TopologyCluster getCluster(ClusterId clusterId) {
        return clusters.get().get(clusterId);
    }

    /**
     * Returns the topology cluster that contains the given device.
     *
     * @param deviceId device identifier
     * @return topology cluster
     */
    public TopologyCluster getCluster(DeviceId deviceId) {
        return clustersByDevice().get(deviceId);
    }

    /**
     * Returns the set of cluster devices.
     *
     * @param cluster topology cluster
     * @return cluster devices
     */
    public Set<DeviceId> getClusterDevices(TopologyCluster cluster) {
        return devicesByCluster().get(cluster);
    }

    /**
     * Returns the set of cluster links.
     *
     * @param cluster topology cluster
     * @return cluster links
     */
    public Set<Link> getClusterLinks(TopologyCluster cluster) {
        return linksByCluster().get(cluster);
    }

    /**
     * Indicates whether the given point is an infrastructure link end-point.
     *
     * @param connectPoint connection point
     * @return true if infrastructure
     */
    public boolean isInfrastructure(ConnectPoint connectPoint) {
        return infrastructurePoints.get().contains(connectPoint);
    }

    /**
     * Indicates whether the given point is part of a broadcast set.
     *
     * @param connectPoint connection point
     * @return true if in broadcast set
     */
    public boolean isBroadcastPoint(ConnectPoint connectPoint) {
        if (broadcastFunction != null) {
            return broadcastFunction.apply(connectPoint);
        }

        // Any non-infrastructure, i.e. edge points are assumed to be OK.
        if (!isInfrastructure(connectPoint)) {
            return true;
        }

        // Find the cluster to which the device belongs.
        TopologyCluster cluster = clustersByDevice().get(connectPoint.deviceId());
        checkArgument(cluster != null,
                "No cluster found for device %s", connectPoint.deviceId());

        // If the broadcast set is null or empty, or if the point explicitly
        // belongs to it, return true.
        Set<ConnectPoint> points = broadcastSets.get().get(cluster.id());
        return isNullOrEmpty(points) || points.contains(connectPoint);
    }

    /**
     * Returns the size of the cluster broadcast set.
     *
     * @param clusterId cluster identifier
     * @return size of the cluster broadcast set
     */
    public int broadcastSetSize(ClusterId clusterId) {
        return broadcastSets.get().get(clusterId).size();
    }

    /**
     * Returns the set of the cluster broadcast points.
     *
     * @param clusterId cluster identifier
     * @return set of cluster broadcast points
     */
    public Set<ConnectPoint> broadcastPoints(ClusterId clusterId) {
        return broadcastSets.get().get(clusterId);
    }

    /**
     * Returns the set of pre-computed shortest paths between source and
     * destination devices.
     *
     * @param src source device
     * @param dst destination device
     * @return set of shortest paths
     */
    public Set<Path> getPaths(DeviceId src, DeviceId dst) {
        //log.info("Topology端调用处1。。。。。。。。。。。。。。。。。。。。。。。。。。。。。");
        return getPaths(src, dst, linkWeight(), ALL_PATHS);
    }

    public Set<Path> getPaths1(DeviceId src, DeviceId dst, DeviceId hs){
        //log.info("Topology端调用处1。。。。。。。。。。。。。。。。。。。。。。。。。。。。。");
        return getPaths1(src, dst, linkWeightDijkstra(), ALL_PATHS);
    }

    public  LinkedList<Link> getAllPaths(){
        return RealgetAllPaths();
    }

    /**
     * Computes on-demand the set of shortest paths between source and
     * destination devices.
     *
     * @param src     source device
     * @param dst     destination device
     * @param weigher link weight function
     * @return set of shortest paths
     */
    public Set<Path> getPaths(DeviceId src, DeviceId dst, LinkWeigher weigher) {
        return getPaths(src, dst, weigher, ALL_PATHS);
    }



    /**
     * Computes on-demand the set of shortest paths between source and
     * destination devices, the set of returned paths will be no more than,
     * maxPaths in size.  The first {@code maxPaths} paths will be returned
     * maintaining any ordering guarantees provided by the underlying
     * (default or if no default is specified {@link DijkstraGraphSearch})
     * search. If returning all paths of a given length would exceed
     * {@code maxPaths} a subset of paths of that length will be returned,
     * which paths will be returned depends on the currently specified
     * {@code GraphPathSearch}. See {@link #setDefaultGraphPathSearch}.
     *
     * @param src    source device
     * @param dst    destination device
     * @param weigher link weight function
     * @param maxPaths maximum number of paths
     * @return set of shortest paths
     */
    public Set<Path> getPaths(DeviceId src, DeviceId dst, LinkWeigher weigher,
                              int maxPaths) {

        //log.info(".........................");


        DefaultTopologyVertex srcV = new DefaultTopologyVertex(src);
        DefaultTopologyVertex dstV = new DefaultTopologyVertex(dst);
        Set<TopologyVertex> vertices = graph.getVertexes();
        if (!vertices.contains(srcV) || !vertices.contains(dstV)) {
            // src or dst not part of the current graph
            return ImmutableSet.of();
        }
        //在search过程中用到了weight！
        //dijkstra: graphPathSearchDijkstra
        GraphPathSearch.Result<TopologyVertex, TopologyEdge> result =
                graphPathSearch().search(graph, srcV, dstV, weigher, maxPaths);
        ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
        //log.info("上报packetIn的交换机："+src.toString()+",目的交换机："+dst.toString()+")" + ",path的条数："+result.paths().size());

        int j=0;
        // Set<Path<V, E>> paths();
        for (org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : result.paths()) {
//            log.info("第 " + j + "条path "+"..........."+path.toString());

//            for (TopologyEdge e : path.edges()) {
//                log.info("edge src,dst:" + e.link().src().deviceId().toString() + "," + e.link().dst().deviceId().toString());
//            }
            j++;
            //builder.add(networkPath(path));
        }

        for (org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : result.paths()) {

            builder.add(networkPath(path));
        }
        //log.info(".........................");

        return builder.build();
    }

    public Set<Path> getPaths1(DeviceId src, DeviceId dst, LinkWeigher weigher, int maxPaths){
        //log.info(".........................");


        DefaultTopologyVertex srcV = new DefaultTopologyVertex(src);
        DefaultTopologyVertex dstV = new DefaultTopologyVertex(dst);
        Set<TopologyVertex> vertices = graph.getVertexes();
        if (!vertices.contains(srcV) || !vertices.contains(dstV)) {
            // src or dst not part of the current graph
            return ImmutableSet.of();
        }
        //在search过程中用到了weight！
        //dijkstra: graphPathSearchDijkstra
        GraphPathSearch.Result<TopologyVertex, TopologyEdge> result =
                graphPathSearchDijkstra().search(graph, srcV, dstV, weigher, maxPaths);
        ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
        //log.info("上报packetIn的交换机："+src.toString()+",目的交换机："+dst.toString()+")" + ",path的条数："+result.paths().size());

        int j=0;
        // Set<Path<V, E>> paths();
        for (org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : result.paths()) {
//            log.info("第 " + j + "条path "+"..........."+path.toString());

//            for (TopologyEdge e : path.edges()) {
//                log.info("edge src,dst:" + e.link().src().deviceId().toString() + "," + e.link().dst().deviceId().toString());
//            }
            j++;
            //builder.add(networkPath(path));
        }

        for (org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : result.paths()) {

            builder.add(networkPath(path));
        }
        //log.info(".........................");

        return builder.build();
    }



    /**
     * 统计路径每link信息
     * @return
     */
    public synchronized LinkedList<Link> RealgetAllPaths() {

        /**
         * result
         */
        LinkedList<Link> links = new LinkedList<Link>();

        /**
         * 得到所有的点
         */
        Set<TopologyVertex> vertices = graph.getVertexes();
        Set<TopologyEdge> edges = graph.getEdges();

        for(TopologyEdge edge : edges){
            Link link = edge.link();
            links.add(link);

        }

        return links;

    }






    private Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> myChoicedPaths(TopologyGraph graph, DefaultTopologyVertex srcV, DefaultTopologyVertex dstV, DeviceId hs, Set<org.onlab.graph.Path<TopologyVertex, TopologyEdge>> myresult1) {

        //flowStatisticService = new FlowStatisticManager();
        int i=0;
        for(org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : myresult1){
            for(int j=0; j< 10; j++){
                log.info("统计网络基本信息！！！！！");
            }
            log.info("path " + i + " : ");

            int k=0;
            for(TopologyEdge edge : path.edges()){
                log.info("edge " + k + " : ");
                ConnectPoint connectPoint_linksrc = edge.link().src();
                ConnectPoint connectPoint_linkdst = edge.link().dst();

                //List<org.onosproject.net.Port> ports = new ArrayList<>(deviceService.getPorts(connectPoint_linksrc.deviceId()));



//                for(org.onosproject.net.Port port : ports){
//                    log.info("port number: " + port.number());
//                    ConnectPoint cp = new ConnectPoint(connectPoint_linksrc.deviceId(), port.number());
//                }
                k++;
            }

            i++;
        }

        return null;
    }

    private  synchronized  Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> mysearchPaths(TopologyGraph graph, DefaultTopologyVertex srcV, DefaultTopologyVertex dstV, LinkWeigher weigher,
                                                                                 int maxPaths, DeviceId hs) {
        gouzaoFatreeLayer(graph);
        showFattreeLayer(graph);
        AddLayerDataToV(graph, srcV, dstV, hs);
        Set<TopologyEdge> e1 = graph.getEdgesFrom(srcV);



        // 粘型11
        // 如果上报packetin的交换机在第一层，和目的交换机连着同一个边缘交换机
        Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> result_j0 = new LinkedHashSet<>();
        log.info("上报交换机的层数：" + srcV.deviceId().getLayer());
        log.info("源交换机的层数：" + hs.getLayer());
        log.info("目的交换机的层数："+ dstV.deviceId().getLayer());
        log.info("源头交换机："+hs.toString().trim());
        log.info("上报交换机："+srcV.deviceId().toString());
        int biaoji = 0;
        if(srcV.deviceId().getLayer().equals("1") && srcV.deviceId().toString().trim().equals(hs.toString().trim()) && dstV.deviceId().getLayer().equals("1")){

            for(TopologyEdge edge : e1){
                if(edge.dst().deviceId().getLayer().equals("2")){
                    Set<TopologyEdge> xiaxing = graph.getEdgesFrom(edge.dst());
                        for(TopologyEdge edge1 : xiaxing){
                            if(edge1.dst().deviceId().getLayer().equals("1") && edge1.dst().deviceId().toString().trim().equals(dstV.deviceId().toString().trim())){
                                log.info("找到了！");
                                biaoji ++;
                                List<TopologyEdge> edge_merge = new ArrayList<>();
                                edge_merge.add(edge);
                                edge_merge.add(edge1);
                                org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge> thePath = new org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge>(srcV, dstV);
                                thePath.setEdges(edge_merge);
                                result_j0.add(thePath);
                            }
                        }
                    }
                }

            if(biaoji != 0){
                return result_j0;
            }
        }




        // 分离型11
        // 如果上报packetin的交换机在第一层，和目的交换机没有连在同一交换机，必须经过第三层交换机通信
        Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> result_11 = new LinkedHashSet<>();
        if(srcV.deviceId().getLayer().equals("1") && srcV.deviceId().toString().trim().equals(hs.toString().trim()) && dstV.deviceId().getLayer().equals("1") && biaoji == 0){
            for(TopologyEdge edge : e1){
                if(edge.dst().deviceId().getLayer().equals("2")){
                    log.info("源目都是一层，跑到上行第二层了");
                    Set<TopologyEdge> shangxing = graph.getEdgesFrom(edge.dst());
                    for(TopologyEdge edge1 : shangxing){
                        if(edge1.dst().deviceId().getLayer().equals("3")){
                            log.info("源目都是一层，跑到第三层了");
                            Set<TopologyEdge> xiaxing = graph.getEdgesFrom(edge1.dst());
                            for(TopologyEdge edge2 : xiaxing){
                                if(edge2.dst().deviceId().getLayer().equals("2")){
                                    log.info("源目都是一层，跑到下行第二层了");
                                    Set<TopologyEdge> xiaxing1 = graph.getEdgesFrom(edge2.dst());
                                    for(TopologyEdge edge3 : xiaxing1){
                                        if(edge3.dst().deviceId().getLayer().equals("1")){
                                            log.info("源目都是一层，跑到下行第一层了");
                                            if(edge3.dst().deviceId().toString().trim().equals(dstV.deviceId().toString().trim())){
                                                log.info("找到了！");
                                                List<TopologyEdge> edge_merge = new ArrayList<>();
                                                edge_merge.add(edge);
                                                edge_merge.add(edge1);
                                                edge_merge.add(edge2);
                                                edge_merge.add(edge3);
                                                org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge> thePath = new org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge>(srcV, dstV);
                                                thePath.setEdges(edge_merge);
                                                log.info(thePath.toString());
                                                result_11.add(thePath);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return result_11;
        }



        // 粘型21
        // 如果上报packetin的交换机底下就有目的交换机
        // 上报交换机在第二层
        // myedge_j1严格来说应该属于result21，但是不想沾，属于分离型21（2代表srcV在第二层，1代表dstV在第一层）
        List<TopologyEdge> myedge_j1 = new ArrayList<TopologyEdge>();
        //log.info("上报packetin的交换机："+srcV.deviceId().toString() + ",源交换机:" + hs.toString() + "目的交换机：" + dstV.deviceId().toString());
        for(TopologyEdge e : e1){
            //log.info("从上报交换机出发，通往的交换机节点有："+e.dst().deviceId().toString());
            //通过打印得知，e1涵盖了所有与此交换机相连的边
            if(dstV.deviceId().toString().trim().equals(e.dst().deviceId().toString().trim())){
                myedge_j1.add(e);
            }
        }
        if(!myedge_j1.isEmpty() && myedge_j1.size() == 1){
            //属于上述情况
            org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge> myPathObject = new org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge>(srcV, dstV);
            myPathObject.setEdges(myedge_j1);
            //myPathObject.setEdges(mylist);
            Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> myresult_j = new LinkedHashSet<>();
            myresult_j.add(myPathObject);
            return myresult_j;
        }
        // 以上已debug验证通过





        // 分离型21
        //如果说上报packetin在上行第二层，且下面没有目的交换机，沿着第三层遍历肯定ok（上行第二层，通过fattree结果做算法实验
        // h001 ping h005
        Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> result_21 = new LinkedHashSet<>();
        if(srcV.deviceId().getLayer().equals("2")){
            for(TopologyEdge edge : e1){
                if(edge.dst().deviceId().getLayer().equals("3")){
                    Set<TopologyEdge> xiaxing = graph.getEdgesFrom(edge.dst());
                    for(TopologyEdge edge1 : xiaxing){
                        if(edge1.dst().deviceId().getLayer().equals("2")){
                            Set<TopologyEdge> xiaxing1 = graph.getEdgesFrom(edge1.dst());
                            for(TopologyEdge edge2 : xiaxing1){
                                if(edge2.dst().deviceId().getLayer().equals("1")){
                                    if(edge2.dst().deviceId().toString().trim().equals(dstV.deviceId().toString().trim())){
                                        log.info("找到了！");
                                        List<TopologyEdge> edge_merge = new ArrayList<>();
                                        edge_merge.add(edge);
                                        edge_merge.add(edge1);
                                        edge_merge.add(edge2);
                                        org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge> thePath = new org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge>(srcV, dstV);
                                        thePath.setEdges(edge_merge);
                                        result_21.add(thePath);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return result_21;
        }






        //如果上报了packetin在第三层，那么到目的交换机就只有唯一一条路径了，就顺着走就行了
        Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> result_31 = new LinkedHashSet<>();
        if(srcV.deviceId().getLayer().equals("3")){
            for(TopologyEdge edge : e1){
                if(edge.dst().deviceId().getLayer().equals("2")){
                    Set<TopologyEdge> xiaxing = graph.getEdgesFrom(edge.dst());
                    for(TopologyEdge edge1 : xiaxing){
                        if(edge1.dst().deviceId().getLayer().equals("1")){
                            if(edge1.dst().deviceId().toString().trim().equals(dstV.deviceId().toString().trim())){
                                log.info("找到了！");
                                List<TopologyEdge> edge_merge = new ArrayList<>();
                                edge_merge.add(edge);
                                edge_merge.add(edge1);
                                org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge> thePath = new org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge>(srcV, dstV);
                                thePath.setEdges(edge_merge);
                                result_31.add(thePath);
                            }
                        }
                    }
                }
            }
            return result_31;
        }






//        TopologyVertex yuanshiNode = null;
//        for(TopologyEdge e : e1){
//            //前面已经判断了，上报交换机下面没有目的交换机，所以只能去胖树策略找
//            if(e.dst().deviceId().toString().trim().equals(hs.toString().trim())){
//                zV = 1;
//                yuanshiNode = e.dst();
//
//            }
//        }
//        if(zV == 1){
//            Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> myresult_j1 = new LinkedHashSet<>();
//            myresult_j1 = getFattreePathOrigin1(graph, srcV, dstV, yuanshiNode);
//            return myresult_j1;
//        }
//
//
        //temp  没用的，做测试
        Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> myresult_ssss = new LinkedHashSet<>();
        return myresult_ssss;










    }

    private void AddLayerDataToV(TopologyGraph graph, DefaultTopologyVertex srcV, DefaultTopologyVertex dstV, DeviceId hs) {
        for(TopologyVertex vertex : graph.getVertexes()) {
            if(vertex.deviceId().toString().trim().equals(srcV.deviceId().toString().trim())){
                srcV.deviceId().setLayer(vertex.deviceId().getLayer());
            }
            if(vertex.deviceId().toString().trim().equals(dstV.deviceId().toString().trim())){
                dstV.deviceId().setLayer(vertex.deviceId().getLayer());
            }
            if(vertex.deviceId().toString().trim().equals(hs.toString().trim())){
                hs.setLayer(vertex.deviceId().getLayer());
            }
        }
    }

    private void showFattreeLayer(TopologyGraph graph) {
        Set<TopologyVertex> temp = graph.getVertexes();
        for (TopologyVertex tv : temp){
            log.info("deviceId " + tv.deviceId().toString().trim() + " : layer " + tv.deviceId().getLayer() );
        }
    }

    private void gouzaoFatreeLayer(TopologyGraph graph) {
        Set<TopologyVertex> temp = graph.getVertexes();
        List<String> bianyuanceng = new ArrayList<>();
        List<String> huijuceng = new ArrayList<>();
        List<String> hexinceng = new ArrayList<>();

        bianyuanceng.add("of:0000000000000bbd");
        bianyuanceng.add("of:0000000000000bbe");
        bianyuanceng.add("of:0000000000000bbf");
        bianyuanceng.add("of:0000000000000bc0");
        bianyuanceng.add("of:0000000000000bbb");
        bianyuanceng.add("of:0000000000000bbc");
        bianyuanceng.add("of:0000000000000bba");
        bianyuanceng.add("of:0000000000000bb9");

        huijuceng.add("of:00000000000007d6");
        huijuceng.add("of:00000000000007d5");
        huijuceng.add("of:00000000000007d8");
        huijuceng.add("of:00000000000007d7");
        huijuceng.add("of:00000000000007d4");
        huijuceng.add("of:00000000000007d3");
        huijuceng.add("of:00000000000007d2");
        huijuceng.add("of:00000000000007d1");

        hexinceng.add("of:00000000000003eb");
        hexinceng.add("of:00000000000003ec");
        hexinceng.add("of:00000000000003ea");
        hexinceng.add("of:00000000000003e9");
        for(TopologyVertex tv : temp){
            if(bianyuanceng.contains(tv.deviceId().toString().trim())){
                tv.deviceId().setLayer("1");
            }else if(huijuceng.contains(tv.deviceId().toString().trim())){
                tv.deviceId().setLayer("2");
            }else if(hexinceng.contains(tv.deviceId().toString().trim())){
                tv.deviceId().setLayer("3");
            }
        }

    }

    private synchronized Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> getFattreePathOrigin1(TopologyGraph graph, DefaultTopologyVertex srcV, DefaultTopologyVertex dstV, TopologyVertex hs)
    {

        //此时的srcV在二层
        Set<TopologyEdge> e2 = graph.getEdgesFrom(srcV);
        List<TopologyVertex> result3Layer = new ArrayList<>();
        for(TopologyEdge e : e2){

            if (e.dst().deviceId().getLayer().equals("3")){
                result3Layer.add(e.dst());
                log.info("第三层的deviceId是:"+e.dst().deviceId().toString());
            }
        }


        Set<TopologyEdge> topologyEdgeSet = graph.getEdgesFrom(hs);

        Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> myresult = new LinkedHashSet<>();
        for(int i=0; i< result3Layer.size(); i++){
            LinkedHashMap<TopologyVertex, TopologyEdge> linkedHashMap = new LinkedHashMap<>();
            Set<TopologyEdge> e2_1 = graph.getEdgesTo(srcV);
            for(TopologyEdge eTemp : e2_1){
                if (eTemp.dst().deviceId().getLayer().equals("1") && eTemp.src().deviceId().toString().trim().equals(hs.deviceId().toString().trim())) {
                    linkedHashMap.put(srcV, eTemp);
                }
            }

            TopologyVertex tempLayer3 = result3Layer.get(i);

            Set<TopologyEdge> tempLayer3Edge = graph.getEdgesTo(tempLayer3);
            for(TopologyEdge edge : tempLayer3Edge){
                TopologyVertex ee = edge.src();
                if(ee.deviceId().toString().trim().equals(srcV.deviceId().toString().trim())){
                    linkedHashMap.put(tempLayer3, edge);
                }
            }



            //result3Layer.get(i);
            DefaultTopologyVertex mys = new DefaultTopologyVertex(hs.deviceId());
            org.onlab.graph.Path<TopologyVertex, TopologyEdge> tempPath = gouzaoPath(graph, result3Layer.get(i), srcV, mys, dstV, linkedHashMap);
            // 这条path是从源开始的，所有我们要从发出packetin的交换机开始。
            org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge> mytempPath = new org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge>(srcV, dstV);
            List<TopologyEdge> myedgeSegments = new ArrayList<>();
            int k=0;
            for(TopologyEdge edge_z : tempPath.edges()){
                if (edge_z.src().deviceId().toString().trim().equals(srcV.deviceId().toString().trim())){
                    k++;
                }
                if(k != 0){
                    myedgeSegments.add(edge_z);
                }
            }
            mytempPath.setEdges(myedgeSegments);
            myresult.add(mytempPath);
        }
        return myresult;

    }



    private Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> getFattreePathOrigin(TopologyGraph graph, DefaultTopologyVertex srcV, DefaultTopologyVertex dstV, DeviceId hs)
    {
        // 汇聚层
        // dst节点和以该节点为尾巴的边(上行)
        LinkedHashMap<TopologyVertex, TopologyEdge> linkedHashMap = new LinkedHashMap<>();
        Set<TopologyVertex> huijuceng = new LinkedHashSet<>();
        Set<TopologyEdge> topologyEdgeSet = graph.getEdgesFrom(srcV);
        //log.info("对应的汇聚层的个数："+topologyEdgeSet.size());


        Set<org.onlab.graph.Path<TopologyVertex,TopologyEdge>> myresult = new LinkedHashSet<>();
        log.info("遍历对应的汇聚层交换机edge");
        for (TopologyEdge topologyEdge : topologyEdgeSet) {
            TopologyVertex tempV = topologyEdge.dst();
            log.info("edge 源目:(" + topologyEdge.src().deviceId().toString() + ", "+ topologyEdge.dst().deviceId().toString()+")");
            log.info("link 源目:(" + topologyEdge.link().src().deviceId().toString() + ", "+topologyEdge.link().dst().deviceId().toString()+ ")");
            huijuceng.add(tempV);
            linkedHashMap.put(tempV, topologyEdge);
        }
        for (TopologyVertex topologyVertex : huijuceng) {
            // 对每个汇聚层交换机找出它的核心层交换机set
            //Set<TopologyVertex> hxc = findHxc(topologyVertex, graph);
            Set<TopologyEdge> temp1 = graph.getEdgesFrom(topologyVertex);
            for (TopologyEdge edge : temp1) {
                TopologyVertex hxcdst = edge.dst();
                linkedHashMap.put(hxcdst, edge);
            }
        }
        // 汇聚层下面没有dst目的交换机
        for (TopologyVertex topologyVertex : huijuceng) {
            // 对每个汇聚层交换机找出它的核心层交换机set
            Set<TopologyVertex> hxc = findHxc(topologyVertex, graph);
            // 找到了最高层，其实下行路径中的路已经确定了
            for (TopologyVertex topologyVertex1 : hxc) {
                // 参数：核心层，汇聚层，边缘层的交换机
                org.onlab.graph.Path<TopologyVertex, TopologyEdge> tempPath = gouzaoPath(graph, topologyVertex1, topologyVertex, srcV, dstV, linkedHashMap);
                myresult.add(tempPath);
            }
        }
        return myresult;

    }



    private Set<TopologyVertex> findHxc(TopologyVertex topologyVertex, TopologyGraph graph) {
        Set<TopologyEdge> topologyEdgeSet = graph.getEdgesFrom(topologyVertex);
        Set<TopologyVertex> result = new LinkedHashSet<>();
        for (TopologyEdge topologyEdge : topologyEdgeSet) {
            TopologyVertex temp = topologyEdge.dst();
            result.add(temp);
        }
        return result;
    }

    //org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge> myPathObject = new org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge>(srcV, dstV);

    private org.onlab.graph.Path<TopologyVertex,TopologyEdge> gouzaoPath(TopologyGraph graph, TopologyVertex topologyVertex1, TopologyVertex topologyVertex,
                                                                         DefaultTopologyVertex srcV, DefaultTopologyVertex dstV, LinkedHashMap<TopologyVertex, TopologyEdge> linkedHashMap) {
        org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge> myPathObject = new org.onlab.graph.DefaultPath<TopologyVertex,TopologyEdge>(srcV, dstV);
        List<TopologyEdge> myedge1 = new ArrayList<>();
        TopologyEdge e1 = linkedHashMap.get(topologyVertex);
        TopologyEdge e2 = linkedHashMap.get(topologyVertex1);
        myedge1.add(e1);
        myedge1.add(e2);
        // 最高层节点是topologyVertex1
        // 下行第一层
        Set<TopologyEdge> edges_1 = graph.getEdgesFrom(topologyVertex1);
        for (TopologyEdge edge_1 : edges_1) {
            TopologyVertex vertex_huiju = edge_1.dst();
            if (vertex_huiju.deviceId().getLayer().equals("2") && !vertex_huiju.deviceId().toString().trim().equals(topologyVertex.deviceId().toString().trim())){

                Set<TopologyEdge> edges_2 = graph.getEdgesFrom(vertex_huiju);
                for (TopologyEdge edge_2 : edges_2) {
                    TopologyVertex vertex_bianyuan = edge_2.dst();
                    if (vertex_bianyuan.deviceId().getLayer().equals("1") && vertex_bianyuan.deviceId().toString().trim().equals(dstV.deviceId().toString().trim())) {
//                        linkedHashMap.put(vertex_huiju, edge_1);
//                        linkedHashMap.put(vertex_bianyuan, edge_2);
                        myedge1.add(edge_1);
                        myedge1.add(edge_2);
                    }
                }
            }
        }
        myPathObject.setEdges(myedge1);
        myPathObject.setCost(null);
        return myPathObject;
    }







    /**
     * /**
     * Returns the set of pre-computed shortest disjoint path pairs between
     * source and destination devices.
     *
     * @param src source device
     * @param dst destination device
     * @return set of shortest disjoint path pairs
     */
    public Set<DisjointPath> getDisjointPaths(DeviceId src, DeviceId dst) {
        return getDisjointPaths(src, dst, linkWeight());
    }

    /**
     * Computes on-demand the set of shortest disjoint path pairs between
     * source and destination devices.
     *
     * @param src     source device
     * @param dst     destination device
     * @param weigher link weight function
     * @return set of disjoint shortest path pairs
     */
    public Set<DisjointPath> getDisjointPaths(DeviceId src, DeviceId dst,
                                              LinkWeigher weigher) {
        DefaultTopologyVertex srcV = new DefaultTopologyVertex(src);
        DefaultTopologyVertex dstV = new DefaultTopologyVertex(dst);
        Set<TopologyVertex> vertices = graph.getVertexes();
        if (!vertices.contains(srcV) || !vertices.contains(dstV)) {
            // src or dst not part of the current graph
            return ImmutableSet.of();
        }

        GraphPathSearch.Result<TopologyVertex, TopologyEdge> result =
                SUURBALLE.search(graph, srcV, dstV, weigher, ALL_PATHS);
        ImmutableSet.Builder<DisjointPath> builder = ImmutableSet.builder();
        for (org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : result.paths()) {
            DisjointPath disjointPath =
                    networkDisjointPath((DisjointPathPair<TopologyVertex, TopologyEdge>) path);
            if (disjointPath.backup() != null) {
                builder.add(disjointPath);
            }
        }
        return builder.build();
    }

    /**
     * Computes on-demand the set of shortest disjoint risk groups path pairs
     * between source and destination devices.
     *
     * @param src         source device
     * @param dst         destination device
     * @param weigher     edge weight object
     * @param riskProfile map representing risk groups for each edge
     * @return set of shortest disjoint paths
     */
    private Set<DisjointPath> disjointPaths(DeviceId src, DeviceId dst,
                                            LinkWeigher weigher,
                                            Map<TopologyEdge, Object> riskProfile) {
        DefaultTopologyVertex srcV = new DefaultTopologyVertex(src);
        DefaultTopologyVertex dstV = new DefaultTopologyVertex(dst);

        Set<TopologyVertex> vertices = graph.getVertexes();
        if (!vertices.contains(srcV) || !vertices.contains(dstV)) {
            // src or dst not part of the current graph
            return ImmutableSet.of();
        }

        SrlgGraphSearch<TopologyVertex, TopologyEdge> srlg =
                new SrlgGraphSearch<>(riskProfile);
        GraphPathSearch.Result<TopologyVertex, TopologyEdge> result =
                srlg.search(graph, srcV, dstV, weigher, ALL_PATHS);
        ImmutableSet.Builder<DisjointPath> builder = ImmutableSet.builder();
        for (org.onlab.graph.Path<TopologyVertex, TopologyEdge> path : result.paths()) {
            DisjointPath disjointPath =
                    networkDisjointPath((DisjointPathPair<TopologyVertex, TopologyEdge>) path);
            if (disjointPath.backup() != null) {
                builder.add(disjointPath);
            }
        }
        return builder.build();
    }

    /**
     * Computes on-demand the set of shortest disjoint risk groups path pairs
     * between source and destination devices.
     *
     * @param src         source device
     * @param dst         destination device
     * @param weigher     edge weight object
     * @param riskProfile map representing risk groups for each link
     * @return set of shortest disjoint paths
     */
    public Set<DisjointPath> getDisjointPaths(DeviceId src, DeviceId dst,
                                              LinkWeigher weigher,
                                              Map<Link, Object> riskProfile) {
        Map<TopologyEdge, Object> riskProfile2 = new HashMap<>();
        for (Link l : riskProfile.keySet()) {
            riskProfile2.put(new TopologyEdge() {
                Link cur = l;

                @Override
                public Link link() {
                    return cur;
                }

                @Override
                public TopologyVertex src() {
                    return () -> src;
                }

                @Override
                public TopologyVertex dst() {
                    return () -> dst;
                }
            }, riskProfile.get(l));
        }
        return disjointPaths(src, dst, weigher, riskProfile2);
    }

    /**
     * Computes on-demand the set of shortest disjoint risk groups path pairs
     * between source and destination devices.
     *
     * @param src         source device
     * @param dst         destination device
     * @param riskProfile map representing risk groups for each link
     * @return set of shortest disjoint paths
     */
    public Set<DisjointPath> getDisjointPaths(DeviceId src, DeviceId dst,
                                              Map<Link, Object> riskProfile) {
        return getDisjointPaths(src, dst, linkWeight(), riskProfile);
    }

    // Converts graph path to a network path with the same cost.
    private Path networkPath(org.onlab.graph.Path<TopologyVertex, TopologyEdge> path) {
        List<Link> links = path.edges().stream().map(TopologyEdge::link)
                .collect(Collectors.toList());
        return new DefaultPath(CORE_PROVIDER_ID, links, path.cost());
    }

    private DisjointPath networkDisjointPath(
            DisjointPathPair<TopologyVertex, TopologyEdge> path) {
        if (!path.hasBackup()) {
            // There was no secondary path available.
            return new DefaultDisjointPath(CORE_PROVIDER_ID,
                    (DefaultPath) networkPath(path.primary()),
                    null);
        }
        return new DefaultDisjointPath(CORE_PROVIDER_ID,
                (DefaultPath) networkPath(path.primary()),
                (DefaultPath) networkPath(path.secondary()));
    }

    // Searches for SCC clusters in the network topology graph using Tarjan
    // algorithm.
    private SccResult<TopologyVertex, TopologyEdge> searchForClusters() {
        return TARJAN.search(graph, new NoIndirectLinksWeigher());
    }

    // Builds the topology clusters and returns the id-cluster bindings.
    private ImmutableMap<ClusterId, TopologyCluster> buildTopologyClusters() {
        ImmutableMap.Builder<ClusterId, TopologyCluster> clusterBuilder =
                ImmutableMap.builder();
        SccResult<TopologyVertex, TopologyEdge> results = clusterResults.get();

        // Extract both vertexes and edges from the results; the lists form
        // pairs along the same index.
        List<Set<TopologyVertex>> clusterVertexes = results.clusterVertexes();
        List<Set<TopologyEdge>> clusterEdges = results.clusterEdges();

        // Scan over the lists and create a cluster from the results.
        for (int i = 0, n = results.clusterCount(); i < n; i++) {
            Set<TopologyVertex> vertexSet = clusterVertexes.get(i);
            Set<TopologyEdge> edgeSet = clusterEdges.get(i);

            ClusterId cid = ClusterId.clusterId(i);
            DefaultTopologyCluster cluster = new DefaultTopologyCluster(cid,
                    vertexSet.size(),
                    edgeSet.size(),
                    findRoot(vertexSet));
            clusterBuilder.put(cid, cluster);
        }
        return clusterBuilder.build();
    }

    // Finds the vertex whose device id is the lexicographical minimum in the
    // specified set.
    private TopologyVertex findRoot(Set<TopologyVertex> vertexSet) {
        TopologyVertex minVertex = null;
        for (TopologyVertex vertex : vertexSet) {
            if ((minVertex == null) || (vertex.deviceId()
                    .toString().compareTo(minVertex.deviceId().toString()) < 0)) {
                minVertex = vertex;
            }
        }
        return minVertex;
    }

    // Processes a map of broadcast sets for each cluster.
    private ImmutableSetMultimap<ClusterId, ConnectPoint> buildBroadcastSets() {
        Builder<ClusterId, ConnectPoint> builder = ImmutableSetMultimap.builder();
        for (TopologyCluster cluster : clusters.get().values()) {
            addClusterBroadcastSet(cluster, builder);
        }
        return builder.build();
    }

    // Finds all broadcast points for the cluster. These are those connection
    // points which lie along the shortest paths between the cluster root and
    // all other devices within the cluster.
    private void addClusterBroadcastSet(TopologyCluster cluster,
                                        Builder<ClusterId, ConnectPoint> builder) {
        // Use the graph root search results to build the broadcast set.
        Result<TopologyVertex, TopologyEdge> result =
                DIJKSTRA.search(graph, cluster.root(), null, hopCountWeigher, 1);
        for (Map.Entry<TopologyVertex, Set<TopologyEdge>> entry :
                result.parents().entrySet()) {
            TopologyVertex vertex = entry.getKey();

            // Ignore any parents that lead outside the cluster.
            if (clustersByDevice().get(vertex.deviceId()) != cluster) {
                continue;
            }

            // Ignore any back-link sets that are empty.
            Set<TopologyEdge> parents = entry.getValue();
            if (parents.isEmpty()) {
                continue;
            }

            // Use the first back-link source and destinations to add to the
            // broadcast set.
            Link link = parents.iterator().next().link();
            builder.put(cluster.id(), link.src());
            builder.put(cluster.id(), link.dst());
        }
    }

    // Collects and returns an set of all infrastructure link end-points.
    private ImmutableSet<ConnectPoint> findInfrastructurePoints() {
        ImmutableSet.Builder<ConnectPoint> builder = ImmutableSet.builder();
        for (TopologyEdge edge : graph.getEdges()) {
            if (edge.link().type() == Type.EDGE) {
                // exclude EDGE link from infrastructure link
                // - Device <-> Host
                // - Device <-> remote domain Device
                continue;
            }
            builder.add(edge.link().src());
            builder.add(edge.link().dst());
        }
        return builder.build();
    }

    // Builds cluster-devices, cluster-links and device-cluster indexes.
    private ClusterIndexes buildIndexes() {
        // Prepare the index builders
        ImmutableMap.Builder<DeviceId, TopologyCluster> clusterBuilder =
                ImmutableMap.builder();
        ImmutableSetMultimap.Builder<TopologyCluster, DeviceId> devicesBuilder =
                ImmutableSetMultimap.builder();
        ImmutableSetMultimap.Builder<TopologyCluster, Link> linksBuilder =
                ImmutableSetMultimap.builder();

        // Now scan through all the clusters
        for (TopologyCluster cluster : clusters.get().values()) {
            int i = cluster.id().index();

            // Scan through all the cluster vertexes.
            for (TopologyVertex vertex : clusterResults.get().clusterVertexes().get(i)) {
                devicesBuilder.put(cluster, vertex.deviceId());
                clusterBuilder.put(vertex.deviceId(), cluster);
            }

            // Scan through all the cluster edges.
            for (TopologyEdge edge : clusterResults.get().clusterEdges().get(i)) {
                linksBuilder.put(cluster, edge.link());
            }
        }

        // Finalize all indexes.
        return new ClusterIndexes(clusterBuilder.build(),
                devicesBuilder.build(),
                linksBuilder.build());
    }

    private GraphPathSearch<TopologyVertex, TopologyEdge> graphPathSearch() {
        return defaultGraphPathSearch != null ? defaultGraphPathSearch : DIJKSTRA;
        //return DIJKSTRA;
    }

    private GraphPathSearch<TopologyVertex, TopologyEdge> graphPathSearchDijkstra() {
        return DIJKSTRA != null ? DIJKSTRA : defaultGraphPathSearch;
        //return DIJKSTRA;
    }

    private LinkWeigher linkWeightDijkstra(){
        return hopCountWeigher;
    }

    private LinkWeigher linkWeight() {

//        for(int i=0; i<3; i++){
//            log.info("=====defaultLinkWeigher信息=====hopCountWeigher=====");
//        }
        //log.info("defaultLinkWeigher 是不是空： " + (defaultLinkWeigher == null));
        //defaultLinkWeigher基本上都是空的
        //log.info("defaultLinkWeigher: " + defaultLinkWeigher.toString());
        //足以证明dijkstra走的是hopweight,是基于条数走的
        //log.info("hopCountWeigher: " + hopCountWeigher.toString());
        //log.info(hopCountWeigher);
        //会经过这里去取哪种linkWeigher
        return defaultLinkWeigher != null ? defaultLinkWeigher : hopCountWeigher;
    }

    // Link weight for preventing traversal over indirect links.
    private static class NoIndirectLinksWeigher
            extends DefaultEdgeWeigher<TopologyVertex, TopologyEdge>
            implements LinkWeigher {
        @Override
        public Weight weight(TopologyEdge edge) {
            return (edge.link().state() == INACTIVE) ||
                    (edge.link().type() == INDIRECT) ?
                    getNonViableWeight() : new ScalarWeight(HOP_WEIGHT_VALUE);
        }
    }

    static final class ClusterIndexes {
        final ImmutableMap<DeviceId, TopologyCluster> clustersByDevice;
        final ImmutableSetMultimap<TopologyCluster, DeviceId> devicesByCluster;
        final ImmutableSetMultimap<TopologyCluster, Link> linksByCluster;

        public ClusterIndexes(ImmutableMap<DeviceId, TopologyCluster> clustersByDevice,
                              ImmutableSetMultimap<TopologyCluster, DeviceId> devicesByCluster,
                              ImmutableSetMultimap<TopologyCluster, Link> linksByCluster) {
            this.clustersByDevice = clustersByDevice;
            this.devicesByCluster = devicesByCluster;
            this.linksByCluster = linksByCluster;
        }
    }

    @Override
    public String toString() {
        return toStringHelper(this)
                .add("time", time)
                .add("creationTime", creationTime)
                .add("computeCost", computeCost)
                .add("clusters", clusterCount())
                .add("devices", deviceCount())
                .add("links", linkCount()).toString();
    }
}
