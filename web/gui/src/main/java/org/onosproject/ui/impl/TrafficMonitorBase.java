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

import org.apache.commons.lang.StringUtils;
import org.onlab.packet.MacAddress;
import org.onosproject.core.DefaultApplicationId;
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
import org.onosproject.ui.impl.topo.util.ServicesBundle;
import org.onosproject.ui.impl.topo.util.TrafficLink;
import org.onosproject.ui.impl.topo.util.TrafficLinkMap;
import org.onosproject.ui.topo.AbstractTopoMonitor;
import org.onosproject.ui.topo.Highlights;
import org.onosproject.ui.topo.TopoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.onosproject.incubator.net.PortStatisticsService.MetricType.BYTES;
import static org.onosproject.incubator.net.PortStatisticsService.MetricType.PACKETS;
import static org.onosproject.net.DefaultEdgeLink.createEdgeLink;
import static org.onosproject.ui.impl.TrafficMonitorBase.Mode.IDLE;

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
        return 100*1000000;
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




    // =======================================================================
    // === Methods for computing traffic on links

    /**
     * Generates a {@link Highlights} object summarizing the traffic on the
     * network, ready to be transmitted back to the client for display on
     * the topology view.
     *
     * @param type the type of statistics to be displayed
     * @return highlights, representing links to be labeled/colored
     */
    /** 2018 3 6
     * 监控的核心方法
     * @param type
     * @return
     */
    ///////////////////////////////////////////////TrafficLink.StatsType.PORT_STATS///////////////////////////////////////////////////////
    protected Highlights trafficSummaryV2(TrafficLink.StatsType type){
        Highlights highlights = new Highlights();
        Set<TrafficLink> linksWithTraffic = computeLinksWithTraffic(type);
        Set<TrafficLink> aggregatedLinks = doAggregation(linksWithTraffic);

        for (TrafficLink tlink : aggregatedLinks) {
            highlights.add(tlink.highlight(type));
        }
        return highlights;
    }

    protected Highlights trafficSummary(TrafficLink.StatsType type) {
        Highlights highlights = new Highlights();
        TrafficLinkMap linkMap = new TrafficLinkMap();
        //TrafficLinkMap linkMapForFlow = new TrafficLinkMap();
        compileLinks(linkMap);
        addEdgeLinks(linkMap);
        //compileLinks(linkMapForFlow);
        //addEdgeLinks(linkMapForFlow);
        double sum = 0;
        double sum_UsedRate = 0;
        double sum_restBw = 0;
        /**
         * key: String tlinkId
         * value: Double BandWidth
         */
        HashMap<String, Double> tLinkId_BandWidth = new HashMap<>();
        /**
         * key: String tlinkId
         * value: Double BandwidthUsedRate
         */
        HashMap<String, Double> tLinkId_BandWidthUsedRate = new HashMap<>();
        Set<TrafficLink> linksWithTraffic = new HashSet<>();

//        for(TrafficLink  tlink1 : linkMapForFlow.biLinks()){
//            if(type == TrafficLink.StatsType.PORT_STATS){
//                //對流也要做一份處理
//                attachFlowLoad(tlink1);
//            }
//        }
        int numbers = 0;
        for (TrafficLink tlink : linkMap.biLinks()) {
            if (type == TrafficLink.StatsType.FLOW_STATS) {
                attachFlowLoad(tlink);
            } else if (type == TrafficLink.StatsType.PORT_STATS) {
                //TrafficLink tlinkCopy = new TrafficLink(tlink);
                //attachFlowLoad(tlink);
                attachPortLoad(tlink, BYTES);
            } else if (type == TrafficLink.StatsType.PORT_PACKET_STATS) {
                attachPortLoad(tlink, PACKETS);
            }

            // we only want to report on links deemed to have traffic
            if (tlink.hasTraffic()) {
                numbers ++;
                linksWithTraffic.add(tlink);
                LinkHighlight linkHighlight = tlink.highlight(type);
                //LinkHighlight linkHighlight1 = new LinkHighlight(linkHighlight);
                highlights.add(linkHighlight);
                /**
                 * 目前我准备在这开启监控
                 */
                if(type == StatsType.PORT_STATS){

                    ConnectPoint src = tlink.key().src();
                    ConnectPoint dst = tlink.key().dst();


                    /**
                     * LinkHighlight实际上是：
                     * highlightForStats(statsType);
                     *
                     private LinkHighlight highlightForStats(StatsType type) {
                     return new LinkHighlight(linkId(), SECONDARY_HIGHLIGHT)
                     .setLabel(generateLabel(type));
                     }
                     */


                    //linkHighlight.label()就是带宽
                    //log.info("linkId: " + tlink.linkId());
                    //log.info("link的带宽"+"label: " + linkHighlight.label());
                    /**
                     * case ALL_PORT_TRAFFIC_BIT_PS:
                     *                 clearSelection();
                     *                 scheduleTask();
                     *                 sendAllPortTrafficBits();
                     *                 break;
                     *
                     * show that the label unit is bit
                     */
                    String bandwidth = linkHighlight.label();

                    double level = 100000;
                    String tlinkId = tlink.linkId();
                    double bwUsedRate = 0;
                    double restBw = 0.0;
                    if(bandwidth.contains("M")){
                        double temp = Double.valueOf(bandwidth.trim().substring(0, bandwidth.indexOf("M"))) * 1000;
                        bwUsedRate = temp / level;
                        tLinkId_BandWidth.put(tlinkId, temp);
                        tLinkId_BandWidthUsedRate.put(tlinkId, temp/level);
                        sum += temp;
                        sum_UsedRate += temp/level;
                        double restTemp = 0.0;
                        if(level > temp){
                            restTemp = level - temp;
                        }
                        restBw = restTemp;
                        sum_restBw += restTemp;

                        log.info("curBw: " + temp);
                        log.info("totalBw: " + level);
                        log.info("restBw: " + restTemp);

                    }else if(bandwidth.contains("K")){
                        double level1 = 100000;
                        String tempETL = bandwidth.trim().substring(0, bandwidth.indexOf("K"));
                        //处理 “1,006.67”这种脏数据
                        String tempString = "";
                        if(tempETL.contains(",")){
                            String[] tempStringArray = tempETL.split(",");
                            tempString = tempStringArray[0] + tempStringArray[1];
                        }
                        //log.info("curTemp: " + tempString);
                        double temp = 0;
                        if(tempString != null &&  tempString != "" && !tempString.equals("")){
                            temp = Double.valueOf(tempString);
                        }
                        log.info("=====bandwidth(M: " + temp  + ", 帶寬利用率： " + temp/level1);
                        bwUsedRate = temp/level1;
                        tLinkId_BandWidth.put(tlinkId, temp);
                        tLinkId_BandWidthUsedRate.put(tlinkId, temp/level1);
                        sum += temp;
                        sum_UsedRate += temp/level1;
                        double restTemp = 0.0;
                        if(level1 > temp){
                            restTemp = level1 - temp;
                        }
                        restBw = restTemp;
                        sum_restBw += restTemp;

                        log.info("curBw: " + temp);
                        log.info("totalBw: " + level1);
                        log.info("restBw: " + restTemp);

                    }



                    //log.info("curSUm: " +  sum);
                    if(bwUsedRate > 0.5){
                        log.info("-------2----------------");
                        log.info("bwUsedRate: " + bwUsedRate);
                        /**
                         * check if the link load reach 70%
                         * choose the biggest flow
                         * and replace it to the new path for load balance
                         *
                         */
                        if(src.toString().trim().split(":")[0].equals("of") &&
                                dst.toString().trim().split(":")[0].equals("of")){
                            DeviceId curDid = src.deviceId();
                            PortNumber curPort = src.port();
                            //flow and load
                            ConcurrentHashMap<String, String> flowIdRateCollection = services.flowStats().getFlowId_flowRate();
                            //choose the biggest flow
                            String maxFlowId = "";
                            double maxFlowRate = 0.0;
                            DeviceId maxFlowSrcDeviceId = null;
                            DeviceId maxFlowDstDeviceId = null;
                            FlowEntry flowEntryObject = null;
                            Map<Double, FlowEntry> flowRateFlowEntry = new TreeMap<>();
                            //sort by rate
                            for(FlowEntry r0 : services.flow().getFlowEntries(curDid)){
                                String objectFlowId = r0.id().toString();
                                String flowRateOutOfMonitor = getflowRateFromMonitorModule2(objectFlowId, flowIdRateCollection);
                                String flowSpeedEtl = flowRateOutOfMonitor.substring(0, flowRateOutOfMonitor.indexOf("b"));
                                Double resultFlowSpeed = Double.valueOf(flowSpeedEtl);
                                if(resultFlowSpeed > 0){
                                    flowRateFlowEntry.put(resultFlowSpeed, r0);
                                }

                            }

                            Map<Double, FlowEntry> sortedFlowRateFlowEntry = sortMapByKey(flowRateFlowEntry);




                            // has sorted by rate finished
                            for(Map.Entry<Double, FlowEntry> entryEntry : sortedFlowRateFlowEntry.entrySet()){
                                FlowEntry r = entryEntry.getValue();
                                String objectFlowId = r.id().toString();
                                String flowRateOutOfMonitor = getflowRateFromMonitorModule2(objectFlowId, flowIdRateCollection);
                                String flowSpeedEtl = flowRateOutOfMonitor.substring(0, flowRateOutOfMonitor.indexOf("b"));
                                Double resultFlowSpeed = Double.valueOf(flowSpeedEtl);

                                EthCriterion srcEth = (EthCriterion)r.selector().getCriterion(Criterion.Type.ETH_SRC);
                                EthCriterion dstEth = (EthCriterion)r.selector().getCriterion(Criterion.Type.ETH_DST);
//                                if(resultFlowSpeed > maxFlowRate
//                                        && r != null
//                                        && srcEth != null
//                                        && dstEth != null
//                                        ){
//                                    log.info("resultFlowSpeed: " + resultFlowSpeed); //bps
//
//                                    maxFlowRate = resultFlowSpeed;
//                                    maxFlowId = objectFlowId;
//                                    //flow src
//                                    MacAddress srcMac = srcEth.mac();
//                                    HostId srcHostId = HostId.hostId(srcMac);
//                                    Host srcHost = services.host().getHost(srcHostId);
//                                    DeviceId srcDeviceId = srcHost.location().deviceId();
//                                    //flow dst
//                                    MacAddress dstMac = dstEth.mac();
//                                    HostId dstHostId = HostId.hostId(dstMac);
//                                    Host dstHost = services.host().getHost(dstHostId);
//                                    DeviceId dstDeviceId = dstHost.location().deviceId();
//
//                                    maxFlowSrcDeviceId = srcDeviceId;
//                                    maxFlowDstDeviceId = dstDeviceId;
//                                    flowEntryObject = r;
//
//                                }
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
                                    if(r != null && srcDeviceId != null && dstDeviceId != null){
                                        Set<Path> reachablePaths = services.topology().getPaths(services.topology().currentTopology(), srcDeviceId, dstDeviceId);
                                        log.info("--------------reachablePaths.size(): " + reachablePaths.size());

                                        /**
                                         * judge each link of the reachable path
                                         * choise the min link restbw
                                         *
                                         */

                                        Set<Path> paths = PathsDecision_PLLB(resultFlowSpeed, reachablePaths);
                                        //Set<Path> paths = PathsDecision_FESM(reachablePaths);
                                        log.info("----------------filteredSize: " + paths.size());

                                        Path pathObject = null;
                                        //size == 1
                                        for(Path pathTemp : paths){
                                            pathObject = pathTemp;
                                            break;
                                        }

                                        if(paths.size() == 0 || paths == null || pathObject == null){
                                            //not install rule
                                        }else{
                                            //install rule
                                            //has problem
                                            log.info("install rule ing ..............");
                                            //flowEntryObject
                                            installRuleForPath(r, pathObject);
                                            break;
                                        }

                                        log.info("install rule finish");

                                    }else{
                                        log.info("xxxxxxxxxxxxxxxxxxxxxxxx");
                                    }







                                }


                            }

                        }



                    }

                    /////////////////////////////////////////////////////////////////////////


                }else{
                    //type == StatsType.FLOW_STATS

                }

            }else{
                double level2 = 100000;//100M
                double temp = 0;// 带宽设为0
                String tlinkId = tlink.linkId();
                tLinkId_BandWidth.put(tlinkId, temp);
                tLinkId_BandWidthUsedRate.put(tlinkId, temp/level2);
                sum += 0;
                sum_UsedRate += 0;
                sum_restBw += 0;
            }
        }
        // TODO: consider whether a map would be better...
        //Set<TrafficLink> linksWithTraffic = computeLinksWithTraffic(type);

        //Set<TrafficLink> aggregatedLinks = doAggregation(linksWithTraffic);

        //for (TrafficLink tlink : linksWithTraffic) {
        //    highlights.add(tlink.highlight(type));
        //}


        //csv
        /**
         * 每5秒周期，计算出拓扑中所有link负载的均衡度
         * 目前在mininet上设定的最大linkcapacity是10M
         */
        //int TrafficLinkSize = numbers;
        int TrafficLinkSize = linkMap.biLinks().size();
        //log.info("TrafficLinkSize: " + TrafficLinkSize);

        /**
         * 每条link平均的带宽
         */
        double meanTrafficBandWidth = sum / TrafficLinkSize;
        //log.info("meanTrafficBandWidth: " + meanTrafficBandWidth);
        /**
         * 每条link平均的带宽利用率
         */
        double meanTrafficBandWidthUsedRate = sum_UsedRate / TrafficLinkSize;
        /**
         * mean restbw of links
         */
        double meanTrafficRestBandWidth = sum_restBw / TrafficLinkSize;

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

        double bdInterval2_Sum = 0;
        for(Map.Entry<String, Double> entry : tLinkId_BandWidth.entrySet()){
            //tLinkId
            String key = entry.getKey();
            //BandWidth
            Double value = entry.getValue();
            //bit -> Byte
            double bdInterval = Math.abs(value - meanTrafficBandWidth) / 80;
            //log.info("bdInterval : " + bdInterval);
            double bdInterval2 = Math.pow(bdInterval, 2);
            //log.info("bdInterval2 : " + bdInterval2);
            bdInterval2_Sum += bdInterval2;
        }

        /**
         * 對tLinkId_BandWidth中每條link的帶寬利用率算方差 來表示負載 的均衡度
         *
         */
        double bdInterval3_Sum = 0;
        for(Map.Entry<String, Double> entry : tLinkId_BandWidthUsedRate.entrySet()){
            String key = entry.getKey();
            Double value = entry.getValue();
            double bdInterval = Math.abs(value - meanTrafficBandWidthUsedRate);
            double bdInterval3 = Math.pow(bdInterval, 2);
            bdInterval3_Sum += bdInterval3;
        }


        //log.info("bdInterval2_Sum : " + bdInterval2_Sum);
        //log.info("TrafficLinkSize : " + TrafficLinkSize);
        /**
         * 方差
         */
        double variance = bdInterval2_Sum / TrafficLinkSize;
        double variance_of_usedRate = bdInterval3_Sum / TrafficLinkSize;
        //log.info("variance(方差）: " + variance);
        /**
         * 标准差
         * bit -> Byte
         * K -> M
         */
        double standard_deviation = Math.pow(variance, 0.5) ;
        double standard_deviation_usedRate = Math.pow(variance_of_usedRate, 0.5);
        log.info("标准差(网络拓扑所有link帶寬的標準差）== " + standard_deviation);
        log.info("標準差(網絡拓撲所有link帶寬利用率的標準差) == " + standard_deviation_usedRate);
        log.info("mean bw used rate == " + meanTrafficBandWidthUsedRate);
        log.info("mean bw KBPS == " + meanTrafficBandWidth);

        File csvFile = new File("/home/lihaifeng/BandWidthUsedRateStandardDeviation.csv");
        File csvFile1 = new File("/home/lihaifeng/BandWidthStandardDeviation.csv");
        File csvFile2 = new File("/home/lihaifeng/BwMeanRest.csv");
        File csvFile3 = new File("/home/lihaifeng/BwMeanUsedRate.csv");
        File csvFile4 = new File("/home/lihaifeng/BwMeanBps.csv");
        checkExist(csvFile);
        checkExist(csvFile1);
        checkExist(csvFile2);
        checkExist(csvFile3);
        checkExist(csvFile4);
        //boolean b = appendData(csvFile, standard_deviation+"");
        boolean b = appendData(csvFile, standard_deviation_usedRate+"");
        boolean b0 = appendData(csvFile1, standard_deviation + "");
        boolean b1 = appendData(csvFile2, meanTrafficRestBandWidth+"");
        boolean b2 = appendData(csvFile3, meanTrafficBandWidthUsedRate+"");
        boolean b3 = appendData(csvFile4, meanTrafficBandWidth + "");
        if(b == true && b1 == true && b2 == true && b3 == true && b0 == true){
            log.info("追加写成功..");
        }else{
            log.info("追加写失败..");
        }


        return highlights;
    }

    private Map<Double, FlowEntry> sortMapByKey(Map<Double, FlowEntry> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }

        Map<Double, FlowEntry> sortMap = new TreeMap<Double, FlowEntry>(
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

            System.out.println("------" + path.links().get(0).src().deviceId().toString());
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


    private  Set<Path> PathsDecision_FESM(Set<Path> paths) {

        /**
         *
         * flowStatisticService 是信息统计模块，
         * 这里通过 IOC 技术注入到 负载均衡决策模块，
         *
         * Set<Path> paths 是 拓扑计算模块 根据源目ip 计算拓扑中此 (src, dst)对的所有等价路径
         * topologyService 拓扑计算模块 是通过 IOC 技术注入到 伏在均衡决策模块
         *
         *
         */

        //flowStatisticService.loadSummaryPortInternal()
        Set<Path> result = new HashSet<>();
        Map<Integer, Path> indexPath = new LinkedHashMap<>();
        //Path finalPath = paths.iterator().next();
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
        String sql = null;
        //DBHelper db1 = null;

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

                //log.info("统计信息=====对于path " + i + " 的第 " + j + "条link： ");

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
                long IntraLinkMaxBw = getIntraLinkMaxBw(link.src(), link.dst()); //bps
                long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
                double IntraLinkCapability = getIntraLinkCapability(link.src(), link.dst());

//                    log.info("link的负载(bps): " + IntraLinkLoadBw);
//                    log.info("link的最大带宽(bps): " + IntraLinkMaxBw);
//                    log.info("link的剩余带宽(bps): " + IntraLinkRestBw);
//                    log.info("link的带宽利用率(bps): " + IntraLinkCapability);



                //SummaryFlowEntryWithLoad summaryFlowEntryWithLoad = flowStatisticService.loadSummaryPortInternal(link.src());
//                    for(int i1=0; i1<30; i1++){
//                        log.info("kkkkkkkkkkkkkkkkk" + summaryFlowEntryWithLoad.getTotalLoad().rate() + " ");
//                    }
//                    long latest = statisticService.load(link.src()).latest();
//                    long epochtime = statisticService.load(link.src()).time();


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

                long packetsReceived_src = 0;
                if(link.src()!=null &&  link.src().deviceId() != null && link.src().port() !=null && flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    packetsReceived_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsReceived();
                }
                long packetsSent_src = 0;
                if(link.src()!=null && link.src().deviceId() !=null && link.src().port() != null && flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    packetsSent_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsSent();
                }
                long bytesReceived_src = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    bytesReceived_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).bytesReceived();
                }

                long bytesSent_src = 0;

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    bytesSent_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).bytesSent();
                }
                long rx_dropped_src = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    rx_dropped_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsRxDropped();
                }
                long tx_dropped_src = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsTxDropped();
                }
                long rx_tx_dropped_src = rx_dropped_src+tx_dropped_src;

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
                 */
                long packetsReceived_dst = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    packetsReceived_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsReceived();
                }
                long packetsSent_dst = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    packetsSent_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsSent();
                }
                long bytesReceived_dst = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    bytesReceived_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).bytesReceived();
                }
                long bytesSent_dst = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    bytesSent_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).bytesSent();
                }
                long rx_dropped_dst = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsRxDropped();
                }
                long tx_dropped_dst = 0;
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsTxDropped();
                }

//                    log.info("packetsReceived_src: " + packetsReceived_src);
//                    log.info("packetsSent_src: " + packetsSent_src);
//                    log.info("bytesReceived_src(bytes): " + bytesReceived_src);
//                    log.info("bytesSent_src(bytes): " + bytesSent_src);
//                    log.info("rx_dropped_dst: " + rx_dropped_dst);
//                    log.info("tx_dropped_dst: " + tx_dropped_dst);
                long rx_tx_dropped_dst = tx_dropped_dst+rx_dropped_dst;

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
        }else{
            result.add(finalPath);
        }
        return result;

    }

    private  Set<Path> PathsDecision_PLLB(Double curFlowSpeed, Set<Path> paths) {

        /**
         * sBigFlow, paths, pkt.receivedFrom().deviceId(),
         dst.location().deviceId(),
         src.location().deviceId(),
         LinksResult
         */

        //flowStatisticService.loadSummaryPortInternal()
        Set<Path> result = new HashSet<Path>();
        Map<Integer, Path> indexPath = new LinkedHashMap<>();
        //Path finalPath = paths.iterator().next();
        Path finalPath = null;

        int i=0;
        /**
         *
         * 对多条等价路径进行选路决策
         *
         */
        double maxScore = 0.0;
        //init with a small score
        double flowbw = 10.0;
        if(curFlowSpeed > 0){
            flowbw = curFlowSpeed;
        }
        /**
         * pre add the flowbw to path
         * compute the standard deviation of all link in all reachable path
         */
        HashMap<Path, Integer> path_index_ofPaths = new HashMap<Path, Integer>();

        Integer index_of_path_inPaths = 0;
        HashMap<Integer, String> pathIndex_linksrestBw_ofPaths = new HashMap<Integer, String>();
        for(Path path : paths){
            path_index_ofPaths.put(path, index_of_path_inPaths);
            StringBuffer sb = new StringBuffer();
            //compute all link rest bw of this path
            for(Link link : path.links()){
                //long IntraLinkRestBw = services.flowStats().load(link).rate();


                long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
                sb.append(IntraLinkRestBw+"|");
            }
            log.info("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz");
            log.info("path i : " + index_of_path_inPaths);
            log.info("links rest bw : " + sb.toString());
            pathIndex_linksrestBw_ofPaths.put(index_of_path_inPaths, sb.toString());
            index_of_path_inPaths ++;

        }

        for(Path path : paths){
            /**
             * in order to compute the standard deviation of all links after pre add the flowBw to curPath
             * now:
             * compute all linksRestBw of all path except cur path
             * And insert them into the otherPathLinksRestBw(ArrayList<Double>)
             */
            Integer curPathIndex = path_index_ofPaths.get(path);
            ArrayList<Double> otherPathLinksRestBw = new ArrayList<>();
            for(Map.Entry<Path, Integer> entry : path_index_ofPaths.entrySet()){
                Path thisPath = entry.getKey();
                Integer thisPathIndex = entry.getValue();
                if(thisPathIndex != curPathIndex){
                    // this is the other path needed to compute the rest Bw
                    String alllinkRestBw_OfThisPath = pathIndex_linksrestBw_ofPaths.get(thisPathIndex);
                    //ETL: link1_RestBw|link2_RestBw|link3_RestBw....
                    alllinkRestBw_OfThisPath = alllinkRestBw_OfThisPath.substring(0, alllinkRestBw_OfThisPath.length()-1);
                    String[] alllinkRestBw = StringUtils.split(alllinkRestBw_OfThisPath, "|");
                    for(String s : alllinkRestBw){
                        Double tmp = Double.valueOf(s);
                        otherPathLinksRestBw.add(tmp);
                    }
                }

            }
            int j=0;
            indexPath.put(i, path);
            int rPathLength = path.links().size();
            /**
             *
             *  PathsDecision_PLLB
             *
             *  ChokeLinkPassbytes: link bytes
             *
             */
            double allLinkOfPath_BandWidth = 0;
            double allLinkOfPath_RestBandWidth = 0;
            //if there is no traffic in this link, that means the link bandwidth is 100M
            long ChokePointRestBandWidth = 100*1000000;
            long ChokeLinkPassbytes = 0;
            ArrayList<Double> arrayList = new ArrayList<>();
            long IntraLinkMaxBw = 100 * 1000000;
            int ifPathCanChoose = 1;
            for(Link link : path.links()){

                long IntraLinkLoadBw = getIntraLinkLoadBw(link.src(), link.dst());
                //long IntraLinkLoadBw = services.flowStats().load(link).rate();
                //long IntraLinkLoadBw = services.portStats().load(link.src(), BYTES).rate();
                //long IntraLinkMaxBwTest = getIntraLinkMaxBw(link.src(), link.dst()); //bps
                long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
//                    double IntraLinkCapability = getIntraLinkCapability(link.src(), link.dst());

                arrayList.add((double)IntraLinkLoadBw);
                allLinkOfPath_BandWidth += IntraLinkLoadBw;
                //long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
                //long IntraLinkRestBw = 100*1000000 - IntraLinkLoadBw;
                log.info("check............................................");
                //bit/s
//                log.info("src: " + link.src().deviceId().toString());
//                log.info("dst: " + link.dst().deviceId().toString());
                log.info("IntraLinkLoadBw: " + IntraLinkLoadBw);
                log.info("IntraLinkRestBw: " + IntraLinkRestBw);
                log.info("flowbw: " + flowbw);
                if(flowbw > IntraLinkLoadBw){
                    log.info("flow speed too large");
                    ifPathCanChoose = 0;
                }else{
                    log.info("flow is enough to put");
                }
                // --------------------------------
                /**
                 * pre add the flowBw to curPath
                 */
                Double theAddRestBw = flowbw;
                Double thisLinkResBw = Double.valueOf(IntraLinkLoadBw);
                Double tp = thisLinkResBw - theAddRestBw;
                if(tp < 0){
                    tp = 0.0;
                }
                otherPathLinksRestBw.add(tp);
                // ---------------------------------
                allLinkOfPath_RestBandWidth += IntraLinkRestBw;
                /**
                 * link 源端口和目的端口 信息监控
                 */


                /**
                 * the choke point link means(the min restBandWidth)
                 * b denotes the byte count of the critical
                 * r denotes the forwarding rate
                 */
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
             * path中各个link的平均负载
             */
            double pathMeanLoad = allLinkOfPath_BandWidth / pathlinksSize;
            /**
             * the mean restBandWidth of all link at this path
             */
            double pathMeanRestBw = allLinkOfPath_RestBandWidth / pathlinksSize;

            // -------------------------------------

            /**
             * otherPathLinksRestBw: ArrayList<Double>
             * this data structure store the rest Bw of all links in all Path after pre add the flowBw to the cur Path
             * now compute the standart deviation of them
             */
            int sizeOf_otherPathLinksRestBw = otherPathLinksRestBw.size();
            double sumLinksRestBw = 0;
            for(int k1=0; k1<otherPathLinksRestBw.size(); k1++){
                double t = otherPathLinksRestBw.get(k1);
                sumLinksRestBw += t;
            }
            double meanLinksResBw = sumLinksRestBw / sizeOf_otherPathLinksRestBw;
            double sumpownode = 0;
            for(int k2=0; k2<otherPathLinksRestBw.size(); k2++){
                double t2 = otherPathLinksRestBw.get(k2);
                double t3 = t2-sumLinksRestBw;
                double t4 = Math.pow(t3, 2);
                sumpownode += t4;
            }
            double preAddFlowToThisPath_AllStandardDeviation = Math.sqrt(sumpownode)/sizeOf_otherPathLinksRestBw;
            //log.info("preAddFlowToThisPath_AllStandardDeviation: " + preAddFlowToThisPath_AllStandardDeviation);
            // -------------------------------------
            /**
             * 特征工程(all between 0~1 )
             * rb = 1.0/log(b+1) + 1
             * rrestBW = (double)(Math.log((double)ChokePointRestBandWidth + 1)) / (double)(Math.log((double)(IntraLinkMaxBw + 1)));
             *
             */
            //double feature_ChokeLinkPassbytes = 1.0 / (double)(Math.log((double)(ChokeLinkPassbytes + 2))) + 1;
            //double feature_ChokePointRestBandWidth = (double)(Math.log((double)ChokePointRestBandWidth + 1)) / (double)(Math.log((double)(IntraLinkMaxBw + 1)));
            double feature_ChokePointRestBandWidth = (double)(Math.log((double)ChokePointRestBandWidth + 1));
            //double feature_pathMeanRestBw = (double)(Math.log((double)pathMeanRestBw + 1)) / (double)(Math.log((double)(IntraLinkMaxBw + 1)));
            double feature_pathMeanRestBw = (double)(Math.log((double)pathMeanRestBw + 1));
            double feature_preAddFlowToThisPath_AllStandardDeviation = 1.0/(double)(Math.log((double)preAddFlowToThisPath_AllStandardDeviation + 1) + 1);
//                    log.info("feature_ChokeLinkPassbytes: " + feature_ChokeLinkPassbytes);
//                    log.info("feature_ChokePointRestBandWidth: " + feature_ChokePointRestBandWidth);
//                    log.info("feature_pathMeanRestBw: " + feature_pathMeanRestBw);
//                    log.info("feature_preAddFlowToThisPath_AllStandardDeviation: " + feature_preAddFlowToThisPath_AllStandardDeviation);

            //log.info("resultScore: " + resultScore);
            //there are some problem
            double resultScore = feature_ChokePointRestBandWidth * 5 + feature_pathMeanRestBw * 2.5 + feature_preAddFlowToThisPath_AllStandardDeviation * 2.5;

            //double resultScore = (ChokePointRestBandWidth*0.4 + pathMeanRestBw*0.2 + 2)*10/(0.4*preAddFlowToThisPath_AllStandardDeviation + 1);
            //log.info("resultScore: "+ resultScore);

            //there are some links not satisfy the flow bw
            if(ifPathCanChoose == 0){
                // not choose this path
                resultScore = 0;
            }
            if(resultScore > maxScore){
                finalPath = path;
            }

            i++;
        }

        //result.add(indexPath.get(0));
        if(finalPath == null){
            result.add(indexPath.get(0));
        }else{
            result.add(finalPath);
        }
        return result;


//            int hashvalue = (srcId.toString()+dstid.toString()).hashCode()%paths.size();
//            Set<Path> result = new HashSet<>();
//            //result.add(paths[hashvalue]);
//            int j=0;
//            for(Path path : paths){
//                if(j == hashvalue){
//                    result.add(path);
//                }
//                j++;
//            }
//            return result;
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

    public void checkExist(File file) {
        //判断文件目录的存在
        if(file.exists()){
            //file exists
        }else{
            //file not exists, create it ...
            try{
                file.createNewFile();
            }catch (IOException e){
                e.printStackTrace();
            }
        }
    }

    public boolean appendData(File csvFile, String data){
        try{
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvFile, true), "GBK"), 1024);
            bw.write(data);
            bw.write("\n");
            //bw.flush();
            bw.close();
            return true;
        }catch (Exception e){
            e.printStackTrace();
        }
        return false;
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
}
