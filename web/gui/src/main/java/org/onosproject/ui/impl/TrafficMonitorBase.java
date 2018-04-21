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

import org.onosproject.incubator.net.PortStatisticsService.MetricType;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.statistic.Load;
import org.onosproject.ui.impl.topo.util.ServicesBundle;
import org.onosproject.ui.impl.topo.util.TrafficLink;
import org.onosproject.ui.impl.topo.util.TrafficLinkMap;
import org.onosproject.ui.topo.AbstractTopoMonitor;
import org.onosproject.ui.topo.Highlights;
import org.onosproject.ui.topo.TopoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

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
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficTreatment;
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
import org.onosproject.net.statistic.StatisticService;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;


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
            timer.schedule(trafficTask, trafficPeriod, trafficPeriod);
        } else {
            log.debug("(traffic task already running)");
        }
    }

    /**
     * Cancels the background monitor task.
     */
    protected synchronized void cancelTask() {
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
            //rate : bytes/s result : b/s
            if(statisticService.vportload(connectPoint) != null) {
                vportCurSpeed = statisticService.vportload(connectPoint).rate();
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
        Port port = deviceService.getPort(connectPoint.deviceId(), connectPoint.port());
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
        return Long.min(getVportLoadCapability(srcConnectPoint), getVportLoadCapability(dstConnectPoint));
    }

    /**
     * 2018 3 6
     * @param srcConnectPoint
     * @param dstConnectPoint
     * @return
     */
    private long getIntraLinkMaxBw(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
        return Long.min(getVportMaxCapability(srcConnectPoint), getVportMaxCapability(dstConnectPoint));
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

    protected Highlights trafficSummary(TrafficLink.StatsType type){
        Highlights highlights = new Highlights();
        Set<TrafficLink> linksWithTraffic = computeLinksWithTraffic(type);
        Set<TrafficLink> aggregatedLinks = doAggregation(linksWithTraffic);

        for (TrafficLink tlink : aggregatedLinks) {
            highlights.add(tlink.highlight(type));
        }
        return highlights;
    }

    protected Highlights mytrafficSummary(TrafficLink.StatsType type) {
        Highlights highlights = new Highlights();
        TrafficLinkMap linkMap = new TrafficLinkMap();
        TrafficLinkMap linkMapForFlow = new TrafficLinkMap();
        compileLinks(linkMap);
        addEdgeLinks(linkMap);
        compileLinks(linkMapForFlow);
        addEdgeLinks(linkMapForFlow);
        double sum = 0;
        double sum_UsedRate = 0;
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

        for(TrafficLink  tlink1 : linkMapForFlow.biLinks()){
            if(type == TrafficLink.StatsType.PORT_STATS){
                //對流也要做一份處理
                attachFlowLoad(tlink1);
            }
        }

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
                linksWithTraffic.add(tlink);
                LinkHighlight linkHighlight = tlink.highlight(type);
                highlights.add(linkHighlight);
                /**
                 * 目前我准备在这开启监控
                 */
                if(type == StatsType.PORT_STATS){

                    /**
                     * LinkHighlight实际上是：
                     * highlightForStats(statsType);
                     *
                     *
                     *
                     private LinkHighlight highlightForStats(StatsType type) {
                     return new LinkHighlight(linkId(), SECONDARY_HIGHLIGHT)
                     .setLabel(generateLabel(type));
                     }


                     */


                    ConnectPoint src = tlink.key().src();
                    ConnectPoint dst = tlink.key().dst();


//                    log.info("========= monitor ========================");
//
//                    log.info("src.toString(): " + src.toString());
//                    log.info("dst.toString(): " + dst.toString());
//                    log.info("src.elementId(): " + src.elementId());
//                    log.info("dst.elementId(): " + dst.elementId());
//                    log.info("src.port(): " + src.port().toString());
//                    log.info("dst.port(): " + dst.port().toString());



                    //linkHighlight.label()就是带宽
                    //log.info("linkId: " + tlink.linkId());
                    //log.info("link的带宽"+"label: " + linkHighlight.label());
                    String bandwidth = linkHighlight.label();
                    double level = 100000;
                    String tlinkId = tlink.linkId();
                    if(bandwidth.contains("M")){
                        double temp = Double.valueOf(bandwidth.trim().substring(0, bandwidth.indexOf("M"))) * 1000;
                        log.info("=====bandwidth: " + temp + ", 帶寬利用率： " + temp/level);
                        tLinkId_BandWidth.put(tlinkId, temp);
                        tLinkId_BandWidthUsedRate.put(tlinkId, temp/level);
                        sum += temp;
                        sum_UsedRate += temp/level;

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
                        tLinkId_BandWidth.put(tlinkId, temp);
                        tLinkId_BandWidthUsedRate.put(tlinkId, temp/level1);
                        sum += temp;
                        sum_UsedRate += temp/level1;
                    }
                    //log.info("curSUm: " +  sum);

                    //sum


                }else{
                    //type == StatsType.FLOW_STATS

                }

            }else{
                double level2 = 100000;
                double temp = 0;// 带宽设为0
                String tlinkId = tlink.linkId();
                tLinkId_BandWidth.put(tlinkId, temp);
                tLinkId_BandWidthUsedRate.put(tlinkId, temp/level2);
                sum += 0;
                sum_UsedRate += 0;
            }
        }
        // TODO: consider whether a map would be better...
        //Set<TrafficLink> linksWithTraffic = computeLinksWithTraffic(type);

        //Set<TrafficLink> aggregatedLinks = doAggregation(linksWithTraffic);

        for (TrafficLink tlink : linksWithTraffic) {
            highlights.add(tlink.highlight(type));
        }


        //csv
        /**
         * 每5秒周期，计算出拓扑中所有link负载的均衡度
         * 目前在mininet上设定的最大linkcapacity是10M
         */

        int TrafficLinkSize = linkMap.biLinks().size();
        //log.info("TrafficLinkSize: " + TrafficLinkSize);

        /**
         * 每条link平均的带宽
         */
        double meanTrafficBandWidth = sum / TrafficLinkSize;
        log.info("meanTrafficBandWidth: " + meanTrafficBandWidth);
        /**
         * 每条link平均的带宽利用率
         */
        double meanTrafficBandWidthUsedRate = sum_UsedRate / TrafficLinkSize;

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
         *
         */

        double bdInterval2_Sum = 0;
        for(Map.Entry<String, Double> entry : tLinkId_BandWidth.entrySet()){
            //tLinkId
            String key = entry.getKey();
            //BandWidth
            Double value = entry.getValue();
            double bdInterval = Math.abs(value - meanTrafficBandWidth);
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
         */
        double standard_deviation = Math.pow(variance, 0.5);
        double standard_deviation_usedRate = Math.pow(variance_of_usedRate, 0.5);
        log.info("标准差(网络拓扑所有link帶寬的標準差）== " + standard_deviation);
        log.info("標準差(網絡拓撲所有link帶寬利用率的標準差) == " + standard_deviation_usedRate);

        File csvFile = new File("/home/zengxiaosen/BandWidthUsedRateStandardDeviation.csv");
        checkExist(csvFile);
        //boolean b = appendData(csvFile, standard_deviation+"");
        boolean b = appendData(csvFile, standard_deviation_usedRate+"");
        if(b == true){
            log.info("追加写成功..");
        }else{
            log.info("追加写失败..");
        }


        return highlights;
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
}
