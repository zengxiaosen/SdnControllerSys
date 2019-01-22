/*
 * Copyright 2017-present Open Networking Foundation
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
 *
 */

package org.onosproject.ui.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.onlab.packet.MacAddress;
import org.onosproject.core.DefaultApplicationId;
import org.onosproject.core.PathChoiceItf;
import org.onosproject.incubator.net.PortStatisticsService.MetricType;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.flow.*;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.criteria.PortCriterion;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.statistic.*;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.ui.impl.constCollection.constCollect;
import org.onosproject.ui.impl.topo.util.ServicesBundle;
import org.onosproject.ui.impl.topo.util.TrafficLink;
import org.onosproject.ui.impl.topo.util.TrafficLinkMap;
import org.onosproject.ui.topo.AbstractTopoMonitor;
import org.onosproject.ui.topo.Highlights;
import org.onosproject.ui.topo.TopoUtils;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.onosproject.incubator.net.PortStatisticsService.MetricType.BYTES;
import static org.onosproject.incubator.net.PortStatisticsService.MetricType.PACKETS;
import static org.onosproject.net.DefaultEdgeLink.createEdgeLink;
import static org.onosproject.ui.impl.TrafficMonitorBase.Mode.IDLE;
import static org.onosproject.ui.impl.Utils.PersistenceUtil.persistenceLog;
import static org.onosproject.ui.impl.constCollection.constCollect.*;

//////////////////////////////
import com.google.common.collect.ImmutableList;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.eclipse.jetty.util.StringUtil;
import org.onosproject.net.*;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions.OutputInstruction;
import org.onosproject.net.intent.FlowObjectiveIntent;
import org.onosproject.net.intent.FlowRuleIntent;
import org.onosproject.net.intent.HostToHostIntent;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.LinkCollectionIntent;
import org.onosproject.net.intent.OpticalConnectivityIntent;
import org.onosproject.net.intent.OpticalPathIntent;
import org.onosproject.net.intent.PathIntent;
import org.onosproject.net.statistic.Load;
import org.onosproject.ui.impl.topo.util.IntentSelection;
import org.onosproject.ui.impl.topo.util.ServicesBundle;
import org.onosproject.ui.impl.topo.util.TopoIntentFilter;
import org.onosproject.ui.impl.topo.util.TrafficLink;
import org.onosproject.ui.impl.topo.util.TrafficLink.StatsType;
import org.onosproject.ui.impl.topo.util.TrafficLinkMap;
import org.onosproject.ui.topo.*;
import org.onosproject.ui.topo.Highlights.Amount;
import org.onosproject.ui.topo.LinkHighlight.Flavor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Base superclass for traffic monitor (both 'classic' and 'topo2' versions).
 */
public abstract class TrafficMonitorBase extends AbstractTopoMonitor {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StatisticService statisticService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowStatisticService flowStatisticService;




    // 4 Kilo Bytes as threshold
    protected static final double BPS_THRESHOLD = 4 * TopoUtils.N_KILO;


    /**
     * Designates the different modes of operation.
     */
    public enum Mode {
        IDLE,
        ALL_FLOW_TRAFFIC_BYTES,
        ALL_PORT_TRAFFIC_BIT_PS,
        ALL_PORT_TRAFFIC_PKT_PS,
        DEV_LINK_FLOWS,
        RELATED_INTENTS,
        SELECTED_INTENT
    }

    /**
     * Number of milliseconds between invocations of sending traffic data.
     */
    protected final long trafficPeriod;

    /**
     * Holds references to services.
     */
    protected final ServicesBundle services;

    /**
     * Current operating mode.
     */
    protected Mode mode = Mode.IDLE;

    private final Timer timer;
    private TimerTask trafficTask = null;
    private TimerTask myPortStatusTrafficUpdateTask = null;
    private final Timer timer1;
    /**
     * Constructs the monitor, initializing the task period and
     * services bundle reference.
     *
     * @param trafficPeriod  traffic task period in ms
     * @param servicesBundle bundle of services
     */
    protected TrafficMonitorBase(long trafficPeriod,
                                 ServicesBundle servicesBundle) {
        this.trafficPeriod = trafficPeriod;
        this.services = servicesBundle;
        timer = new Timer("uiTopo-" + getClass().getSimpleName());
        timer1 = new Timer("statisticTopo-" + getClass().getSimpleName());
    }

    /**
     * Initiates monitoring of traffic for a given mode.
     * This causes a background traffic task to be
     * scheduled to repeatedly compute and transmit the appropriate traffic
     * data to the client.
     * <p>
     * The monitoring mode is expected to be one of:
     * <ul>
     * <li>ALL_FLOW_TRAFFIC_BYTES</li>
     * <li>ALL_PORT_TRAFFIC_BIT_PS</li>
     * <li>ALL_PORT_TRAFFIC_PKT_PS</li>
     * <li>SELECTED_INTENT</li>
     * </ul>
     *
     * @param mode the monitoring mode
     */
    public synchronized void monitor(Mode mode) {
        this.mode = mode;

        switch (mode) {

            case ALL_FLOW_TRAFFIC_BYTES:
                clearSelection();
                scheduleTask();
                sendAllFlowTraffic();
                break;

            case ALL_PORT_TRAFFIC_BIT_PS:
                clearSelection();
                scheduleTask();
                sendAllPortTrafficBits();
                break;

            case ALL_PORT_TRAFFIC_PKT_PS:
                clearSelection();
                scheduleTask();
                sendAllPortTrafficPackets();
                break;

            case SELECTED_INTENT:
                sendSelectedIntentTraffic();
                scheduleTask();
                break;

            default:
                log.warn("Unexpected call to monitor({})", mode);
                clearAll();
                break;
        }
    }

    /**
     * Subclass should compile and send appropriate highlights data showing
     * flow traffic (bytes on links).
     */
    protected abstract void sendAllFlowTraffic();

    /**
     * Subclass should compile and send appropriate highlights data showing
     * bits per second, as computed using port stats.
     */
    protected abstract void sendAllPortTrafficBits();

    /**
     * Subclass should compile and send appropriate highlights data showing
     * packets per second, as computed using port stats.
     */
    protected abstract void sendAllPortTrafficPackets();

    /**
     * Subclass should compile and send appropriate highlights data showing
     * number of flows traversing links for the "selected" device(s).
     */
    protected abstract void sendDeviceLinkFlows();

    /**
     * Subclass should compile and send appropriate highlights data showing
     * traffic traversing links for the "selected" intent.
     */
    protected abstract void sendSelectedIntentTraffic();

    /**
     * Subclass should send a "clear highlights" event.
     */
    protected abstract void sendClearHighlights();

    /**
     * Subclasses should clear any selection state.
     */
    protected abstract void clearSelection();

    /**
     * Sets the mode to IDLE, clears the selection, cancels the background
     * task, and sends a clear highlights event to the client.
     */
    protected void clearAll() {
        this.mode = Mode.IDLE;
        clearSelection();
        cancelTask();
        sendClearHighlights();
    }

    /**
     * Schedules the background monitor task to run.
     */
    protected synchronized void scheduleTask() {
        if (trafficTask == null) {
            log.debug("Starting up background traffic task...");
            trafficTask = new TrafficUpdateTask();
            //new MyPortStatusTrafficUpdateTask();
            //myPortStatusTrafficUpdateTask = new MyPortStatusTrafficUpdateTask();
            timer.schedule(trafficTask, trafficPeriod, trafficPeriod);
            //timer1.schedule(myPortStatusTrafficUpdateTask, trafficPeriod, trafficPeriod);

        } else {
            log.debug("(traffic task already running)");
        }
    }

    /**
     * Cancels the background monitor task.
     */
    protected synchronized void cancelTask() {
        if(myPortStatusTrafficUpdateTask != null){
            myPortStatusTrafficUpdateTask.cancel();
            myPortStatusTrafficUpdateTask = null;
        }
        if (trafficTask != null) {
            trafficTask.cancel();
            trafficTask = null;
        }
    }

    /**
     * Stops monitoring. (Invokes {@link #clearAll}, if not idle).
     */
    public synchronized void stopMonitoring() {
        log.debug("STOP monitoring");
        if (mode != IDLE) {
            clearAll();
        }
    }

    /**2018 3 6
     * 自研统计模块
     * 对端口带宽的统计信息
     * @param connectPoint
     * @return
     */


    private long getVportLoadCapability(ConnectPoint connectPoint) {
        long vportCurSpeed = 0;
        if(connectPoint != null){
            //rate : bytes/s result : B/s
            //services.flowStats().vportload(connectPoint)
            //services.portStats().load(connectPoint, BYTES)
            if(services.portStats().load(connectPoint, BYTES) != null) {
                vportCurSpeed = services.portStats().load(connectPoint, BYTES).rate() * 8;
            }

        }
        return vportCurSpeed;
    }
    /**
     * 2018 3 6
     * @param connectPoint
     * @return
     */

    private long getVportMaxCapability(ConnectPoint connectPoint) {

        Port port = services.device().getPort(connectPoint.deviceId(), connectPoint.port());
        long vportMaxSpeed = 0;
        if(connectPoint != null){
            vportMaxSpeed = port.portSpeed() * 1000000;  //portSpeed Mbps result : bps
        }

        return vportMaxSpeed;
    }

    /**
     * 2018 3 6
     * @param srcConnectPoint
     * @param dstConnectPoint
     * @return
     */
    private long getIntraLinkLoadBw(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
        return Long.max(getVportLoadCapability(srcConnectPoint), getVportLoadCapability(dstConnectPoint));
    }

    /**
     * 2018 3 6
     * @param srcConnectPoint
     * @param dstConnectPoint
     * @return
     */
    private long getIntraLinkMaxBw(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
        //return Long.min(getVportMaxCapability(srcConnectPoint), getVportMaxCapability(dstConnectPoint));
        //100Mbps b:bit
        return LINK_MAX_BW;
    }

    /**
     * 2018 3 6
     * @param srcConnectPoint
     * @param dstConnectPoint
     * @return
     */
    private long getIntraLinkRestBw(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {

        return getIntraLinkMaxBw(srcConnectPoint, dstConnectPoint) - getIntraLinkLoadBw(srcConnectPoint, dstConnectPoint);
    }

    /**
     * 2018 3 6
     * @param srcConnectPoint
     * @param dstConnectPoint
     * @return
     */

    private Double getIntraLinkCapability(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
        return (Double.valueOf(getIntraLinkLoadBw(srcConnectPoint, dstConnectPoint)) / Double.valueOf(getIntraLinkMaxBw(srcConnectPoint, dstConnectPoint)) * 100);
    }


    /**
     * Generates a {@link Highlights} object summarizing the traffic on the
     * network, ready to be transmitted back to the client for display on
     * the topology view.
     *
     * @param type the type of statistics to be displayed
     * @return highlights, representing links to be labeled/colored
     */


    protected Highlights trafficSummary(TrafficLink.StatsType type) {

        Highlights highlights = new Highlights();
        TrafficLinkMap linkMap = new TrafficLinkMap();

        compileLinks(linkMap);
        addEdgeLinks(linkMap);

        double sumUsedBw = 0;
        double sumUsedRate = 0;

        Map<String, Double> tLinkIdBandWidth = Maps.newHashMap();
        Map<String, Double> tLinkIdBandWidthUsedRate = Maps.newHashMap();
        Set<TrafficLink> linksWithTraffic = Sets.newHashSet();
        Collection<TrafficLink> attachLoadTrafficLinks = Lists.newArrayList();
        for(TrafficLink tlink : linkMap.biLinks()) {
            preAttachLoad(tlink, type);
            attachLoadTrafficLinks.add(tlink);
        }
        List<TrafficLink> sortedTlinkIdByBw = getSortedTlinkIdByBw(attachLoadTrafficLinks, type);
        boolean reScheduledFlag = false;
        for (TrafficLink tlink : sortedTlinkIdByBw) {

            if (tlink.hasTraffic()) {
                linksWithTraffic.add(tlink);
                LinkHighlight linkHighlight = tlink.highlight(type);
                highlights.add(linkHighlight);

                if(type == StatsType.PORT_STATS){
                    ConnectPoint src = tlink.key().src();
                    ConnectPoint dst = tlink.key().dst();

                    String bandwidth = linkHighlight.label();
                    String tlinkId = tlink.linkId();
                    double bwUsedRate = 0;
                    double restBw = 0.0;
                    if(bandwidth.contains("M")){
                        double usedBw = Double.valueOf(bandwidth.trim().substring(0, bandwidth.indexOf("M"))) * 1000;
                        bwUsedRate = usedBw / BW_LEVEL;

                        if(bwUsedRate > 1){
                            bwUsedRate = 1;
                        }
                        tLinkIdBandWidth.put(tlinkId, usedBw);
                        tLinkIdBandWidthUsedRate.put(tlinkId, bwUsedRate);
                        sumUsedBw += usedBw;
                        sumUsedRate += bwUsedRate;
                        double restTemp = 0.0;
                        if(BW_LEVEL > usedBw){
                            restTemp = BW_LEVEL - usedBw;
                        }
                        restBw = restTemp;
                        logAspect(usedBw, BW_LEVEL, restTemp);


                    }else if(bandwidth.contains("K")){

                        double usedBw = getKUsedBw(bandwidth);
                        bwUsedRate = usedBw/BW_LEVEL;
                        log.info("bw(M): " + usedBw  + ", bwUsedRate： " + bwUsedRate);
                        if(bwUsedRate >= 1){
                            bwUsedRate = 1;
                        }

                        tLinkIdBandWidth.put(tlinkId, usedBw);
                        tLinkIdBandWidthUsedRate.put(tlinkId, bwUsedRate);
                        sumUsedBw += usedBw;
                        sumUsedRate += bwUsedRate;
                        double restTemp = 0.0;
                        if(BW_LEVEL > usedBw){
                            restTemp = BW_LEVEL - usedBw;
                        }
                        restBw = restTemp;
                        logAspect(usedBw, BW_LEVEL, restTemp);

                    }

                    AlgorithmService algorithmService = new AlgorithmService();
                    if(!reScheduledFlag){
                        //flow dimension
                        if(src.toString().trim().split(":")[0].equals("of") &&
                                dst.toString().trim().split(":")[0].equals("of")){
                            DeviceId curDid = src.deviceId();
                            PortNumber curPort = src.port();
                            //flow and load
                            ConcurrentHashMap<String, String> flowIdRateCollection = services.flowStats().getFlowIdFlowRate();
                            //choose the biggest flow
                            String maxFlowId = "";
                            double maxFlowRate = 0.0;
                            DeviceId maxFlowSrcDeviceId = null;
                            DeviceId maxFlowDstDeviceId = null;
                            FlowEntry flowEntryObject = null;
                            Map<Double, FlowEntry> flowRateFlowEntry = Maps.newTreeMap();
                            //sort by rate
                            for(FlowEntry fentry : services.flow().getFlowEntries(curDid)){
                                String objectFlowId = fentry.id().toString();
                                String flowRateOutOfMonitor = getflowRateFromMonitorModule2(objectFlowId, flowIdRateCollection);
                                String flowSpeedEtl = flowRateOutOfMonitor.substring(0, flowRateOutOfMonitor.indexOf("b"));
                                Double resultFlowSpeed = Double.valueOf(flowSpeedEtl);
                                if(resultFlowSpeed > 0){
                                    flowRateFlowEntry.put(resultFlowSpeed, fentry);
                                }

                            }

                            Map<Double, FlowEntry> sortedFlowRateFlowEntry = sortMapByKey(flowRateFlowEntry);
                            log.info("sortedFlowRateFlowEntry.size : " + sortedFlowRateFlowEntry.size());

                            // has sorted by rate finished
                            for(Map.Entry<Double, FlowEntry> entryEntry : sortedFlowRateFlowEntry.entrySet()){
                                FlowEntry r = entryEntry.getValue();
                                String objectFlowId = r.id().toString();
                                String flowRateOutOfMonitor = getflowRateFromMonitorModule2(objectFlowId, flowIdRateCollection);
                                String flowSpeedEtl = flowRateOutOfMonitor.substring(0, flowRateOutOfMonitor.indexOf("b"));
                                Double resultFlowSpeed = Double.valueOf(flowSpeedEtl);

                                EthCriterion srcEth = (EthCriterion)r.selector().getCriterion(Criterion.Type.ETH_SRC);
                                EthCriterion dstEth = (EthCriterion)r.selector().getCriterion(Criterion.Type.ETH_DST);

                                if(srcEth != null && dstEth != null){
                                    //flow src
                                    MacAddress srcMac = srcEth.mac();
                                    HostId srcHostId = HostId.hostId(srcMac);
                                    Host srcHost = services.host().getHost(srcHostId);
                                    DeviceId srcDeviceId = srcHost.location().deviceId();
                                    //flow dst
                                    MacAddress dstMac = dstEth.mac();
                                    HostId dstHostId = HostId.hostId(dstMac);
                                    Host dstHost = services.host().getHost(dstHostId);
                                    DeviceId dstDeviceId = dstHost.location().deviceId();
                                    if(r != null && srcDeviceId != null && dstDeviceId != null && !reScheduledFlag){
                                        Set<Path> reachablePaths = services.topology().getPaths(services.topology().currentTopology(), srcDeviceId, dstDeviceId);
                                        log.info("reachablePaths.size(): " + reachablePaths.size());

                                        Boolean enou2PutFlow = true;
                                        //Set<Path> paths = algorithmService.PathsDecisionMyDefined(resultFlowSpeed, reachablePaths, enou2PutFlow);

                                        Set<Path> paths = PathsDecisionFsem(reachablePaths, enou2PutFlow);
                                        if(enou2PutFlow) {
                                            reScheduledFlag = true;
                                        }
                                        Path pathObject = null;
                                        //size == 1
                                        for(Path pathTemp : paths){
                                            pathObject = pathTemp;
                                            break;
                                        }

                                        if(paths != null && pathObject != null && paths.size() != 0){
                                            log.info("install rule ");
                                            installRuleForPath(r, pathObject);
                                            break;
                                        }

                                    }
                                }
                            }
                        }
                    }

                }else{
                    //type == StatsType.FLOW_STATS
                }

            }else{
                double temp = 0;// 带宽设为0
                String tlinkId = tlink.linkId();
                tLinkIdBandWidth.put(tlinkId, temp);
                tLinkIdBandWidthUsedRate.put(tlinkId, 0.0);
                sumUsedBw += 0;
                sumUsedRate += 0;
            }
        }


        /**
         * 每5秒周期，计算出拓扑中所有link负载的均衡度
         */
        int TrafficLinkSize = linkMap.biLinks().size();

        /**
         * 每条link平均的带宽
         */
        double linkMeanTrafficBandWidth = sumUsedBw / TrafficLinkSize;
        /**
         * 每条link平均的带宽利用率
         */
        double meanTrafficBandWidthUsedRate = sumUsedRate / TrafficLinkSize;


        /**
         * Kbps
         */
        double linkBwUsedRateStandardDeviation = getLinkBwUsedRateStandardDeviation(tLinkIdBandWidthUsedRate, meanTrafficBandWidthUsedRate, TrafficLinkSize);
        double linkBwStandardDeviation = getLinkBwStandardDeviation(tLinkIdBandWidth, meanTrafficBandWidthUsedRate, TrafficLinkSize);

        log.info("linkBwStandardDeviation: " + linkBwStandardDeviation);
        log.info("linkBwUsedRateStandardDeviation: " + linkBwUsedRateStandardDeviation);
        log.info("meanBw(kbps): " + linkMeanTrafficBandWidth);

        try {
            persistenceLog(linkBwUsedRateStandardDeviation, linkBwStandardDeviation, meanTrafficBandWidthUsedRate, linkMeanTrafficBandWidth);
        } catch (Exception e) {
            log.error("err message : " + e.getMessage());
        }
        return highlights;
    }

    private double getLinkBwStandardDeviation(Map<String, Double> tLinkIdBandWidth, double linkMeanTrafficBandWidth, int TrafficLinkSize) {

        /**
         * 对tLinkId_BandWidth中每条link算负载的均衡度(用帶寬的均衡度判斷）
         *
         * 标准差：
         * T= pow(bdInterval2_Sum, 1/2)
         * bdInterval2_Sum = bdInterval2的累加/N
         * bdInterval2 = pow(bdInterval, 2)
         * bdInterval = Math.abs(value - meanTrafficBandWidth)
         * value: 遍历每条link，对应的负载（kbps）
         * meanTrafficBandWidth： 所有link的平均负载（kbps）
         */

        double bdIntervalSum2 = 0;
        for(Map.Entry<String, Double> entry : tLinkIdBandWidth.entrySet()){
            //tLinkId
            String key = entry.getKey();
            //BandWidth
            Double value = entry.getValue();
            //log.info("value: " + value + ", linkMeanTrafficBandWidth: " + linkMeanTrafficBandWidth);
            double bdInterval = Math.abs(value - linkMeanTrafficBandWidth) ;
            double bdInterval2 = Math.pow(bdInterval, 2);
            bdIntervalSum2 += bdInterval2;
        }

        /**
         * 方差
         */
        double linkBwVariance = bdIntervalSum2 / TrafficLinkSize;
        return linkBwVariance;

    }

    private double getLinkBwUsedRateStandardDeviation(Map<String, Double> tLinkIdBandWidthUsedRate, double meanTrafficBandWidthUsedRate, int TrafficLinkSize) {


        /**
         * 帶寬利用率算方差
         *
         */
        double bdIntervalSum3 = 0;
        for(Map.Entry<String, Double> entry : tLinkIdBandWidthUsedRate.entrySet()){
            String key = entry.getKey();
            Double value = entry.getValue();
            double bdInterval = Math.abs(value - meanTrafficBandWidthUsedRate);
            double bdInterval3 = Math.pow(bdInterval, 2);
            bdIntervalSum3 += bdInterval3;
        }


        double linkBwUsedRateVariance = bdIntervalSum3 / TrafficLinkSize;
        double linkBwUsedRateStandardDeviation = Math.pow(linkBwUsedRateVariance, 0.5);

        return linkBwUsedRateStandardDeviation;
    }


    private void logAspect(double usedBw, double bwLevel, double restTemp) {
        log.info("curBw: " + usedBw);
        log.info("totalBw: " + BW_LEVEL);
        log.info("restBw: " + restTemp);
    }

    private double getKUsedBw(String bandwidth) {
        String bwKEtl = bandwidth.trim().substring(0, bandwidth.indexOf("K"));
        //处理 “1,006.67”这种脏数据
        String bwKStr = "";
        if(bwKEtl.contains(",")){
            String[] bwKArray = bwKEtl.split(",");
            bwKStr = bwKArray[0] + bwKArray[1];
        }
        double usedBw = 0;
        if(bwKStr != null && !bwKStr.equals("")){
            usedBw = Double.valueOf(bwKStr);
        }
        return usedBw;
    }

    private List<TrafficLink> getSortedTlinkIdByBw(Collection<TrafficLink> trafficLinks, StatsType type) {
        //sort tlinkBwUsed (bw descending sort)
        Map<String, Double> unsortedTlinkBwUsed = Maps.newHashMap();
        Map<String, TrafficLink> tIdLinks = Maps.newHashMap();
        for (TrafficLink tlink : trafficLinks) {
            LinkHighlight linkHighlight = tlink.highlight(type);
            String bandwidth = linkHighlight.label();
            double usedBw = 0;

            if(tlink.hasTraffic()){

                if(bandwidth.contains("M")) {
                    usedBw = Double.valueOf(bandwidth.trim().substring(0, bandwidth.indexOf("M"))) * 1000;
                }else if(bandwidth.contains("K")) {

                    String bwKEtl = bandwidth.trim().substring(0, bandwidth.indexOf("K"));
                    //处理 “1,006.67”这种脏数据
                    String bwKStr = "";
                    if (bwKEtl.contains(",")) {
                        String[] bwKArray = bwKEtl.split(",");
                        bwKStr = bwKArray[0] + bwKArray[1];
                    }
                    if (bwKStr != null && !bwKStr.equals("")) {
                        usedBw = Double.valueOf(bwKStr);
                    }
                }
            }
            unsortedTlinkBwUsed.put(tlink.linkId(), usedBw);
            tIdLinks.put(tlink.linkId(), tlink);

        }


        //value descending sort
        Map<String, Double> sortedTlinkBwUsed = new TreeMap<>(new MapValueComparator<Double>(unsortedTlinkBwUsed));
        sortedTlinkBwUsed.putAll(unsortedTlinkBwUsed);


        List<String> sortedLinksByBwlst = Lists.newLinkedList();
        List<TrafficLink> sortedTLink = Lists.newLinkedList();
        //log.info("sortedTlinkBwUsed.size : " + sortedTlinkBwUsed.size());
        for(Map.Entry<String, Double> entry : sortedTlinkBwUsed.entrySet()){
            String tid = entry.getKey();
            TrafficLink curTLink = tIdLinks.get(tid);
            sortedLinksByBwlst.add(entry.getKey());
            sortedTLink.add(curTLink);
        }
        return sortedTLink;

    }

    private void preAttachLoad(TrafficLink tlink, StatsType type) {
        if (type == TrafficLink.StatsType.FLOW_STATS) {
            attachFlowLoad(tlink);
        } else if (type == TrafficLink.StatsType.PORT_STATS) {
            attachPortLoad(tlink, BYTES);
        } else if (type == TrafficLink.StatsType.PORT_PACKET_STATS) {
            attachPortLoad(tlink, PACKETS);
        }
    }


    public class MapValueComparator<T extends Comparable<T>> implements Comparator<String> {
        private Map<String, T> map = null;

        public MapValueComparator(Map<String, T> map) {
            this.map = map;
        }


        @Override
        public int compare(String o1, String o2) {
            int r = map.get(o2).compareTo(map.get(o1));
            if(r == 0){
                return 1;
            }
            return r;
        }
    }




    private Map<Double, FlowEntry> sortMapByKey(Map<Double, FlowEntry> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }

        Map<Double, FlowEntry> sortMap = Maps.newTreeMap(
                new MapKeyComparator());

        sortMap.putAll(map);

        return sortMap;
    }

    private Set<Path> getChoisedPaths(Set<Path> reachablePaths) {

        return reachablePaths;
    }

    private static int curPriority = 10;

    private void installRuleForPath(FlowEntry flowEntry, Path path){

        for(int j=0; j < path.links().size(); j++){

            if(j == 0){
                PortCriterion inPortCriterion = (PortCriterion)flowEntry.selector().getCriterion(Criterion.Type.IN_PORT);
                PortNumber inPort = inPortCriterion.port();
                PortNumber outPort = path.links().get(0).src().port();
                DeviceId curDeviceId = path.links().get(0).src().deviceId();

                TrafficSelector trafficSelector = DefaultTrafficSelector.builder(flowEntry.selector()).matchInPort(inPort).build();
                TrafficTreatment trafficTreatment = DefaultTrafficTreatment.builder().add(Instructions.createOutput(outPort)).build();
                //50s
                curPriority += 1;
                log.info("curPriority: " + curPriority);
                FlowRule flowRule = new DefaultFlowRule(curDeviceId, trafficSelector, trafficTreatment, curPriority, new DefaultApplicationId(flowEntry.appId(),
                        "new flow entry for load balance"), 70000, false, flowEntry.payLoad());
                services.flow().applyFlowRules(flowRule);
            }else{
                PortNumber inPort = path.links().get(j-1).dst().port();
                PortNumber outPort = path.links().get(j).src().port();
                DeviceId curDeviceId = path.links().get(j).src().deviceId();

                TrafficSelector trafficSelector = DefaultTrafficSelector.builder(flowEntry.selector()).matchInPort(inPort).build();
                TrafficTreatment trafficTreatment = DefaultTrafficTreatment.builder().add(Instructions.createOutput(outPort)).build();

                curPriority += 1;
                log.info("curPriority: " + curPriority);
                FlowRule flowRule = new DefaultFlowRule(curDeviceId, trafficSelector, trafficTreatment, curPriority, new DefaultApplicationId(flowEntry.appId(),
                        "new flow entry for load balance"), 70000, false, flowEntry.payLoad());
                services.flow().applyFlowRules(flowRule);
                //flowRuleService.applyFlowRules(flowRule);
            }
        }
    }


    private  Set<Path> PathsDecisionFsem(Set<Path> paths, Boolean enou2PutFlow) {

        /**
         *
         * flowStatisticService 是信息统计模块
         * 这里通过 IOC 技术注入到 负载均衡决策模块
         * Set<Path> paths 是 拓扑计算模块 根据源目ip 计算拓扑中此 (src, dst)对的所有等价路径
         * topologyService 拓扑计算模块 是通过 IOC 技术注入到 伏在均衡决策模块
         *
         *
         */

        Set<Path> result = Sets.newHashSet();
        Map<Integer, Path> indexPath = Maps.newLinkedHashMap();
        Path finalPath = null;
        /**
         *
         *  数据库的IO：
         *
         *  实时监控数据
         *  选路决策数据
         *  历史流的数据
         *  效果数据
         *
         *
         */
        int i=0;

        /**
         *
         * 对多条等价路径进行选路决策
         *
         */
        double maxScore = 0.0;
        for(Path path : paths){

            int j=0;
            indexPath.put(i, path);
            int rPathLength = path.links().size();


            /**
             *
             *  FESM - TrustCom
             *
             *  since a path is composed by many switches and links,
             *  the average or the total traffic can't not reflect the real status of
             *  the path, so the critical switch and link will be selected to represent the status of the path
             *
             *
             *  the traffic of switch will be messured by packet count and byte count forward by switch.
             *  the traffic of link will be messured by the conrresponding port forwarding rate
             *
             *
             *  U = (h, p, b, r)
             *
             */
            long pObject = 0;
            long bObject = 0;
            long rObject = 0;
            for(Link link : path.links()){


                /**
                 * 链路link 信息监控
                 *
                 * "link的负载(bps): " + IntraLinkLoadBw
                 * "link的最大带宽(bps): " + IntraLinkMaxBw
                 * "link的剩余带宽(bps): " + IntraLinkRestBw
                 * "link的带宽利用率(bps): " + IntraLinkCapability
                 *
                 *
                 */

                long IntraLinkLoadBw = getIntraLinkLoadBw(link.src(), link.dst());

                //bps
                long IntraLinkMaxBw = getIntraLinkMaxBw(link.src(), link.dst());
                long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
                double IntraLinkCapability = getIntraLinkCapability(link.src(), link.dst());




                /**
                 * link 源端口和目的端口 信息监控
                 */

                /**
                 * src
                 *
                 * some statistic of the src of this link:
                 *
                 * packetsReceived_src
                 * packetsSent_src
                 * bytesReceived_src
                 * bytesSent_src
                 * rx_dropped_src
                 * tx_dropped_src
                 * rx_tx_dropped_src
                 */


                long packetsSent_src = 0;
                //flowStatisticService
                if(link.src()!=null && link.src().deviceId() !=null && link.src().port() != null && services.flowStatistic().getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    packetsSent_src = services.flowStatistic().getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsSent();
                }
                long bytesReceived_src = 0;
                if(services.flowStatistic().getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    bytesReceived_src = services.flowStatistic().getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).bytesReceived();
                }

                long bytesSent_src = 0;

                if(services.flowStatistic().getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    bytesSent_src = services.flowStatistic().getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).bytesSent();
                }


                /**
                 * dst
                 *
                 * some statistic of the dst of this link:
                 *
                 * packetsReceived_dst
                 * packetsSent_dst
                 * bytesReceived_dst
                 * bytesSent_dst
                 * rx_dropped_dst
                 * tx_dropped_dst
                 * rx_tx_dropped_dst
                 *
                 *
                 */
                long packetsReceived_dst = 0;
                if(services.flowStatistic().getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    packetsReceived_dst = services.flowStatistic().getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsReceived();
                }

                long bytesReceived_dst = 0;
                if(services.flowStatistic().getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    bytesReceived_dst = services.flowStatistic().getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).bytesReceived();
                }


                /**
                 * U = (h, p, b, r)
                 * h denotes the hop count
                 * p denotes the transmitting packet count
                 * b denotes the byte count of the critical
                 * r denotes the forwarding rate of the critical port
                 */
                if(IntraLinkLoadBw > rObject){
                    pObject = Math.max(packetsSent_src, packetsReceived_dst);
                    bObject = Math.max(bytesSent_src, bytesReceived_dst);
                    rObject = IntraLinkLoadBw;
                }

                j++;
            }

            /**
             * U = (h, p, b, r) ： 一条路径
             * h: hObject
             * p: pObject
             * b: bObject
             * r: rObject
             */
            long hObject = (long)rPathLength;

            /**
             * 特征工程
             * rh = 1.0/(e^h)
             * rp = 1.0/log(p+0.1)
             * rb = 1.0/log(b+0.1)
             * rr = 1.0/(1+e^(-r/50.0))
             *
             * so:
             *
             * the matrix R can be presented as the column vector for one path:
             * R = (rh, rp, rb, rr)
             *
             *
             */


            double rh = 1.0 / (double)(Math.exp((double)hObject));
            double rp = 1.0 / (double)(Math.log((double)(pObject + 0.1)));
            double rb = 1.0 / (double)(Math.log((double)(bObject + 0.1)));
            double rr = 1.0 / (double)(1 + Math.exp((double)((0-rObject) / 50.0)));

            /**
             *
             *
             * B = (b1, b2, ..., bm)
             * B = AOR
             *
             * A = (a1, a2, ..., an)
             *
             * R = (rh, rp, rb, rr)
             *   = (0.4, 0.15, 0.15, 0.3)
             *
             */

            double a1 = 0.4;
            double a2 = 0.15;
            double a3 = 0.15;
            double a4 = 0.3;

            double b1 = a1 * rh;
            double b2 = a2 * rp;
            double b3 = a3 * rb;
            double b4 = a4 * rr;

            double resultScore = b1 + b2 + b3 + b4;
            if(resultScore > maxScore){
                finalPath = path;
            }



            i++;
        }

        //result.add(indexPath.get(0));
        if(finalPath == null){
            result.add(indexPath.get(0));
            enou2PutFlow = false;
        }else{
            result.add(finalPath);
        }
        return result;

    }





    private Map<Integer,String> getPathIndexLinksRestBwOfPaths(Set<Path> paths, Map<Path, Integer> pathIndexOfPaths) {
        Integer indexOfPathInPaths = 0;
        Map<Integer, String> pathIndexLinksRestBwOfPaths = Maps.newHashMap();

        for(Path path : paths){
            pathIndexOfPaths.put(path, indexOfPathInPaths);
            StringBuffer sb = new StringBuffer();
            //compute all link rest bw of this path
            for(Link link : path.links()){
                //long IntraLinkRestBw = services.flowStats().load(link).rate();


                long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
                sb.append(IntraLinkRestBw+"|");
            }
            log.info("path i : " + indexOfPathInPaths);
            log.info("links rest bw : " + sb.toString());
            pathIndexLinksRestBwOfPaths.put(indexOfPathInPaths, sb.toString());
            indexOfPathInPaths ++;

        }
        return pathIndexLinksRestBwOfPaths;
    }




    public String getflowRateFromMonitorModule2(String ObjectFlowId, ConcurrentHashMap<String, String> curSwitch_deviceId){
        //如果是沒有這個key就append，有這個key就更改
        String resultFLowRate = "10b/s";
        for(Map.Entry<String, String> entry : curSwitch_deviceId.entrySet()){
            String entrykey = entry.getKey();
            String entryValue = entry.getValue();
//                for(int i=0; i< 3; i++){
//                    log.info("map.size: " + curSwitch_deviceId.size());
//                    log.info(entrykey);
//                    log.info(entryValue);
//                }
            if(entrykey.contains(ObjectFlowId)){
                for(int i=0; i< 3; i++){
                    log.info("match...");
                }
                resultFLowRate = entryValue;
            }
        }


        return resultFLowRate;
    }


    /**
     * Generates a set of "traffic links" encapsulating information about the
     * traffic on each link (that is deemed to have traffic).
     *
     * @param type the type of statistics to be displayed
     * @return the set of links with traffic
     */
    /////////////////////////////////////////////////////TrafficLink.StatsType.PORT_STATS////////////////////////////////////////////////////////
    protected Set<TrafficLink> computeLinksWithTraffic(TrafficLink.StatsType type) {
        TrafficLinkMap linkMap = new TrafficLinkMap();
        compileLinks(linkMap);
        addEdgeLinks(linkMap);
//
        Set<TrafficLink> linksWithTraffic = new HashSet<>();

        for (TrafficLink tlink : linkMap.biLinks()) {
            if (type == TrafficLink.StatsType.FLOW_STATS) {
                attachFlowLoad(tlink);
            } else if (type == TrafficLink.StatsType.PORT_STATS) {
                attachPortLoad(tlink, BYTES);
            } else if (type == TrafficLink.StatsType.PORT_PACKET_STATS) {
                attachPortLoad(tlink, PACKETS);
            }

            // we only want to report on links deemed to have traffic
            if (tlink.hasTraffic()) {
                linksWithTraffic.add(tlink);
            }
        }
        return linksWithTraffic;
    }

    /**
     * Iterates across the set of links in the topology and generates the
     * appropriate set of traffic links.
     *
     * @param linkMap link map to augment with traffic links
     */
    protected void compileLinks(TrafficLinkMap linkMap) {
        services.link().getLinks().forEach(linkMap::add);
    }

    /**
     * Iterates across the set of hosts in the topology and generates the
     * appropriate set of traffic links for the edge links.
     *
     * @param linkMap link map to augment with traffic links
     */
    protected void addEdgeLinks(TrafficLinkMap linkMap) {
        services.host().getHosts().forEach(host -> {
            linkMap.add(createEdgeLink(host, true));
            linkMap.add(createEdgeLink(host, false));
        });
    }

    /**
     * Processes the given traffic link to attach the "flow load" attributed
     * to the underlying topology links.
     *
     * @param link the traffic link to process
     */
    protected void attachFlowLoad(TrafficLink link) {
        link.addLoad(getLinkFlowLoad(link.one()));
        link.addLoad(getLinkFlowLoad(link.two()));
    }

    /**
     * Returns the load for the given link, as determined by the statistics
     * service. May return null.
     *
     * @param link the link on which to look up the stats
     * @return the corresponding load (or null)
     */
    protected Load getLinkFlowLoad(Link link) {
        if (link != null && link.src().elementId() instanceof DeviceId) {
            return services.flowStats().load(link);
        }
        return null;
    }

    /**
     * Processes the given traffic link to attach the "port load" attributed
     * to the underlying topology links, for the specified metric type (either
     * bytes/sec or packets/sec).
     *
     * @param link       the traffic link to process
     * @param metricType the metric type (bytes or packets)
     */
    protected void attachPortLoad(TrafficLink link, MetricType metricType) {
        // For bi-directional traffic links, use
        // the max link rate of either direction
        // (we choose 'one' since we know that is never null)

        Link one = link.one();
        Load egressSrc = services.portStats().load(one.src(), metricType);
        Load egressDst = services.portStats().load(one.dst(), metricType);
        link.addLoad(maxLoad(egressSrc, egressDst), metricType == BYTES ? BPS_THRESHOLD : 0);

        Load flowLoad_oneLink = getLinkFlowLoad(link.one());
        Load flowload_twoLink = getLinkFlowLoad(link.two());

//        log.info("flowLoad_oneLink: " + flowLoad_oneLink);
//        log.info("flowload_twoLink: " + flowload_twoLink);

    }

    /**
     * Returns the load with the greatest rate.
     *
     * @param a load a
     * @param b load b
     * @return the larger of the two
     */
    protected Load maxLoad(Load a, Load b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.rate() > b.rate() ? a : b;
    }


    /**
     * Subclasses (well, Traffic2Monitor really) can override this method and
     * process the traffic links before generating the highlights object.
     * In particular, links that roll up into "synthetic links" between
     * regions should show aggregated data from the constituent links.
     * <p>
     * This default implementation does nothing.
     *
     * @param linksWithTraffic link data for all links
     * @return transformed link data appropriate to the region display
     */
    protected Set<TrafficLink> doAggregation(Set<TrafficLink> linksWithTraffic) {
        return linksWithTraffic;
    }


    private class MyPortStatusTrafficUpdateTask extends TimerTask{
        @Override
        public void run(){
            try{
//                for(int i=0; i< 50; i++){
//                    log.info("zengxiaosen");
//                }
                sendAllPortTrafficBits();
            }catch (Exception e){
                log.warn("Unable to process MyPortStatusTrafficUpdateTask  due to {}", e.getMessage());
                log.warn("Boom!", e);
            }
        }
    }

    // =======================================================================
    // === Background Task

    // Provides periodic update of traffic information to the client
    private class TrafficUpdateTask extends TimerTask {
        @Override
        public void run() {
            try {
                switch (mode) {
                    case ALL_FLOW_TRAFFIC_BYTES:
                        sendAllFlowTraffic();
                        break;
                    case ALL_PORT_TRAFFIC_BIT_PS:
                        sendAllPortTrafficBits();
                        break;
                    case ALL_PORT_TRAFFIC_PKT_PS:
                        sendAllPortTrafficPackets();
                        break;
                    case DEV_LINK_FLOWS:
                        sendDeviceLinkFlows();
                        break;
                    case SELECTED_INTENT:
                        sendSelectedIntentTraffic();
                        break;

                    default:
                        // RELATED_INTENTS and IDLE modes should never invoke
                        // the background task, but if they do, they have
                        // nothing to do
                        break;
                }

            } catch (Exception e) {
                log.warn("Unable to process traffic task due to {}", e.getMessage());
                log.warn("Boom!", e);
            }
        }
    }

    private class MapKeyComparator implements Comparator<Double>{

        @Override
        public int compare(Double o1, Double o2) {
            return o2.compareTo(o1);
        }
    }

    public class AlgorithmService {


        public Set<Path> PathsDecisionMyDefined(Double curFlowSpeed, Set<Path> paths, Boolean enou2PutFlow) {


            Set<Path> result = Sets.newHashSet();
            Map<Integer, Path> indexPath = Maps.newLinkedHashMap();
            Path finalPath = null;

            int i=0;
            double maxScore = -100000000;
            double flowbw = WebTrafficComputeBuilder.build().getFlowBw(curFlowSpeed);

            /**
             * pre add the flowbw to path
             * compute the standard deviation of all link in all reachable path
             */
            Map<Path, Integer> pathIndexOfPaths = Maps.newHashMap();
            Map<Integer, String> pathIndexLinksRestBwOfPaths = getPathIndexLinksRestBwOfPaths(paths, pathIndexOfPaths);

            for(Path path : paths){

                Integer curPathIndex = pathIndexOfPaths.get(path);
                List<Double> otherPathLinksRestBw = WebTrafficComputeBuilder.build().getOtherPathLinksRestBw(pathIndexOfPaths, curPathIndex, pathIndexLinksRestBwOfPaths);
                List<Double> allPathLinksRestBwAfterAddFlow = Lists.newArrayList(otherPathLinksRestBw);
                log.info("allPathLinksRestBwAfterAddFlow.size : " + allPathLinksRestBwAfterAddFlow.size());
                indexPath.put(i, path);
                /**
                 *
                 *  ChokeLinkPassbytes: link bytes
                 *
                 */
                double allLinkOfPathRestBandWidth = 0;
                double allBwRestAfterAddFlow = 0;
                long ChokeLinkPassbytes = 0;
                long IntraLinkMaxBw = 100 * 1000000;
                boolean pathCanChooseFlag = true;
                int j=0;
                long ChokePointRestBandWidth = MAX_REST_BW;
                for(Link link : path.links()){

                    long IntraLinkLoadBw = getIntraLinkLoadBw(link.src(), link.dst());
                    long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
                    //bit/s
                    //log
                    log.info("IntraLinkLoadBw: " + IntraLinkLoadBw);
                    log.info("IntraLinkRestBw: " + IntraLinkRestBw);
                    log.info("flowbw: " + flowbw);
                    pathCanChooseFlag = WebTrafficComputeBuilder.build().getPathCanChooseFlag(flowbw, IntraLinkLoadBw);

                    //pre add the flowBw to curPath
                    Double theAddRestBw = flowbw;
                    Double thisLinkResBw = Double.valueOf(IntraLinkLoadBw);
                    Double thisLinkResBwUpdate = thisLinkResBw - theAddRestBw;
                    if(thisLinkResBwUpdate < 0){
                        thisLinkResBwUpdate = 0.0;
                    }
                    allPathLinksRestBwAfterAddFlow.add(thisLinkResBwUpdate);
                    allLinkOfPathRestBandWidth += IntraLinkRestBw;
                    allBwRestAfterAddFlow += allBwRestAfterAddFlow;


                    if(IntraLinkRestBw < ChokePointRestBandWidth){
                        //choise the choke point
                        //ChokePointRestBandWidth
                        ChokePointRestBandWidth = IntraLinkRestBw;
                    }

                    j++;
                }
                /**
                 * 从检测路径模块得到的path中包含的link的数量
                 */
                double pathlinksSize = path.links().size();

                /**
                 * the mean restBandWidth of all link at this path
                 */
                double pathMeanRestBw = allLinkOfPathRestBandWidth / pathlinksSize;

                int allPathLinkSize = allPathLinksRestBwAfterAddFlow.size();
                double sumLinksRestBwAfterAddFlow = WebTrafficComputeBuilder.build().getSumLinksRestBwAfterAddFlow(allPathLinksRestBwAfterAddFlow);

                double meanLinksResBwAfterAdd = sumLinksRestBwAfterAddFlow / allPathLinkSize;
                double sum = WebTrafficComputeBuilder.build().getSdRaw(allPathLinksRestBwAfterAddFlow, meanLinksResBwAfterAdd);
                double AllRestBWSdAfterPreAdd = Math.sqrt(sum)/allPathLinkSize;

                double fChokeLinkRestBw = (double)(Math.log((double)ChokePointRestBandWidth + 1));
                double fPathMeanRestBw = (double)(Math.log((double)pathMeanRestBw + 1));
                //double fAllRestBwSdAfterPreAdd = 1.0/(double)(Math.log((double)AllRestBWSdAfterPreAdd + 1) + 0.1);
                double fAllRestBwSdAfterPreAdd = (double)(Math.log((double)AllRestBWSdAfterPreAdd + 1));
                double resultScore = fChokeLinkRestBw * 5 + fPathMeanRestBw * 5 - fAllRestBwSdAfterPreAdd * 0;
                //log
                log.info("ChokePointRestBandWidth: " + ChokePointRestBandWidth);
                log.info("pathMeanRestBw: " + pathMeanRestBw);
                log.info("preAddFlowToThisPath_AllStandardDeviation: " + AllRestBWSdAfterPreAdd);
                log.info("feature_ChokePointRestBandWidth: " + fChokeLinkRestBw);
                log.info("feature_pathMeanRestBw: " + fPathMeanRestBw);
                log.info("feature_preAddFlowToThisPath_AllStandardDeviation: " + fAllRestBwSdAfterPreAdd);


                //there are some links not satisfy the flow bw
                if(!pathCanChooseFlag){
                    // not choose this path
                    resultScore = 0;
                }
                if(resultScore > maxScore){
                    finalPath = path;
                }
                i++;
            }

            if(finalPath == null){
                enou2PutFlow = false;
                result.add(indexPath.get(0));
            }else{
                result.add(finalPath);
            }
            return result;

        }

    }
}
