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
 *
 */

package org.onosproject.ui.impl;

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

import static org.onosproject.net.DefaultEdgeLink.createEdgeLink;
import static org.onosproject.ui.impl.TrafficMonitor.Mode.IDLE;
import static org.onosproject.ui.impl.TrafficMonitor.Mode.RELATED_INTENTS;
import static org.onosproject.ui.impl.TrafficMonitor.Mode.SELECTED_INTENT;

/**
 * Encapsulates the behavior of monitoring specific traffic patterns.
 */
public class TrafficMonitor extends AbstractTopoMonitor {

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StatisticService statisticService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    // 4 Kilo Bytes as threshold
    private static final double BPS_THRESHOLD = 4 * TopoUtils.KILO;

    private static final Logger log =
            LoggerFactory.getLogger(TrafficMonitor.class);

    /**
     * Designates the different modes of operation.
     */
    public enum Mode {
        IDLE,
        ALL_FLOW_TRAFFIC,
        ALL_PORT_TRAFFIC,
        DEV_LINK_FLOWS,
        RELATED_INTENTS,
        SELECTED_INTENT
    }

    private final long trafficPeriod;
    private final ServicesBundle servicesBundle;
    private final TopologyViewMessageHandler msgHandler;
    private final TopoIntentFilter intentFilter;

    private final Timer timer = new Timer("topo-traffic");

    private TimerTask trafficTask = null;
    private Mode mode = IDLE;
    private NodeSelection selectedNodes = null;
    private IntentSelection selectedIntents = null;


    /**
     * Constructs a traffic monitor.
     *
     * @param trafficPeriod  traffic task period in ms
     * @param servicesBundle bundle of services
     * @param msgHandler     our message handler
     */
    public TrafficMonitor(long trafficPeriod, ServicesBundle servicesBundle,
                          TopologyViewMessageHandler msgHandler) {
        this.trafficPeriod = trafficPeriod;
        this.servicesBundle = servicesBundle;
        this.msgHandler = msgHandler;

        intentFilter = new TopoIntentFilter(servicesBundle);
    }

    // =======================================================================
    // === API ===

    /**
     * Monitor for traffic data to be sent back to the web client, under
     * the given mode. This causes a background traffic task to be
     * scheduled to repeatedly compute and transmit the appropriate traffic
     * data to the client.
     * <p>
     * The monitoring mode is expected to be one of:
     * <ul>
     * <li>ALL_FLOW_TRAFFIC</li>
     * <li>ALL_PORT_TRAFFIC</li>
     * <li>SELECTED_INTENT</li>
     * </ul>
     *
     * @param mode monitoring mode
     */
    public synchronized void monitor(Mode mode) {
        log.debug("monitor: {}", mode);
        this.mode = mode;

        switch (mode) {
            case ALL_FLOW_TRAFFIC:
                clearSelection();
                scheduleTask();
                sendAllFlowTraffic();
                break;

            /**
             * 在这里监控all_port_traffic_statistic
             */
            case ALL_PORT_TRAFFIC:
                clearSelection();
                scheduleTask();
                sendAllPortTraffic();
                break;

            case SELECTED_INTENT:
                scheduleTask();
                sendSelectedIntentTraffic();
                break;

            default:
                log.debug("Unexpected call to monitor({})", mode);
                clearAll();
                break;
        }
    }

    /**
     * Monitor for traffic data to be sent back to the web client, under
     * the given mode, using the given selection of devices and hosts.
     * In the case of "device link flows", this causes a background traffic
     * task to be scheduled to repeatedly compute and transmit the appropriate
     * traffic data to the client. In the case of "related intents", no
     * repeating task is scheduled.
     * <p>
     * The monitoring mode is expected to be one of:
     * <ul>
     * <li>DEV_LINK_FLOWS</li>
     * <li>RELATED_INTENTS</li>
     * </ul>
     *
     * @param mode          monitoring mode
     * @param nodeSelection how to select a node
     */
    public synchronized void monitor(Mode mode, NodeSelection nodeSelection) {
        log.debug("monitor: {} -- {}", mode, nodeSelection);
        this.mode = mode;
        this.selectedNodes = nodeSelection;

        switch (mode) {
            case DEV_LINK_FLOWS:
                // only care about devices (not hosts)
                if (selectedNodes.devicesWithHover().isEmpty()) {
                    sendClearAll();
                } else {
                    scheduleTask();
                    sendDeviceLinkFlows();
                }
                break;

            case RELATED_INTENTS:
                if (selectedNodes.none()) {
                    sendClearAll();
                } else {
                    selectedIntents = new IntentSelection(selectedNodes, intentFilter);
                    if (selectedIntents.none()) {
                        sendClearAll();
                    } else {
                        sendSelectedIntents();
                    }
                }
                break;

            default:
                log.debug("Unexpected call to monitor({}, {})", mode, nodeSelection);
                clearAll();
                break;
        }
    }

    // TODO: move this out to the "h2h/multi-intent app"

    /**
     * Monitor for traffic data to be sent back to the web client, for the
     * given intent.
     *
     * @param intent the intent to monitor
     */
    public synchronized void monitor(Intent intent) {
        log.debug("monitor intent: {}", intent.id());
        selectedNodes = null;
        selectedIntents = new IntentSelection(intent);
        mode = SELECTED_INTENT;
        scheduleTask();
        sendSelectedIntentTraffic();
    }

    /**
     * Selects the next intent in the select group (if there is one),
     * and sends highlighting data back to the web client to display
     * which path is selected.
     */
    public synchronized void selectNextIntent() {
        if (selectedIntents != null) {
            selectedIntents.next();
            sendSelectedIntents();
            if (mode == SELECTED_INTENT) {
                mode = RELATED_INTENTS;
            }
        }
    }

    /**
     * Selects the previous intent in the select group (if there is one),
     * and sends highlighting data back to the web client to display
     * which path is selected.
     */
    public synchronized void selectPreviousIntent() {
        if (selectedIntents != null) {
            selectedIntents.prev();
            sendSelectedIntents();
            if (mode == SELECTED_INTENT) {
                mode = RELATED_INTENTS;
            }
        }
    }

    /**
     * Resends selected intent traffic data. This is called, for example,
     * when the system detects an intent update happened.
     */
    public synchronized void pokeIntent() {
        if (mode == SELECTED_INTENT) {
            sendSelectedIntentTraffic();
        }
    }

    /**
     * Stop all traffic monitoring.
     */
    public synchronized void stopMonitoring() {
        log.debug("STOP monitoring");
        if (mode != IDLE) {
            sendClearAll();
        }
    }


    // =======================================================================
    // === Helper methods ===

    private void sendClearAll() {
        clearAll();
        sendClearHighlights();
    }

    private void clearAll() {
        this.mode = IDLE;
        clearSelection();
        cancelTask();
    }

    private void clearSelection() {
        selectedNodes = null;
        selectedIntents = null;
    }

    private synchronized void scheduleTask() {
        if (trafficTask == null) {
            log.debug("Starting up background traffic task...");
            trafficTask = new TrafficUpdateTask();
            timer.schedule(trafficTask, trafficPeriod, trafficPeriod);
        } else {
            log.debug("(traffic task already running)");
        }
    }

    private synchronized void cancelTask() {
        if (trafficTask != null) {
            trafficTask.cancel();
            trafficTask = null;
        }
    }

    private void sendAllFlowTraffic() {
        log.debug("sendAllFlowTraffic");
        msgHandler.sendHighlights(trafficSummary(StatsType.FLOW_STATS));
    }

    private void sendAllPortTraffic() {
        log.debug("sendAllPortTraffic");
        msgHandler.sendHighlights(trafficSummary(StatsType.PORT_STATS));
    }

    private void sendDeviceLinkFlows() {
        log.debug("sendDeviceLinkFlows: {}", selectedNodes);
        msgHandler.sendHighlights(deviceLinkFlows());
    }

    private void sendSelectedIntents() {
        log.debug("sendSelectedIntents: {}", selectedIntents);
        msgHandler.sendHighlights(intentGroup());
    }

    private void sendSelectedIntentTraffic() {
        log.debug("sendSelectedIntentTraffic: {}", selectedIntents);
        msgHandler.sendHighlights(intentTraffic());
    }

    private void sendClearHighlights() {
        log.debug("sendClearHighlights");
        msgHandler.sendHighlights(new Highlights());
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


    //////////////////////////

    // =======================================================================
    // === Generate messages in JSON object node format



    /** 2018 3 6
     * 监控的核心方法
     * @param type
     * @return
     */
    private synchronized Highlights trafficSummary(StatsType type) {
        Highlights highlights = new Highlights();

        TrafficLinkMap linkMap = new TrafficLinkMap();
        compileLinks(linkMap);
        addEdgeLinks(linkMap);
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

        for (TrafficLink tlink : linkMap.biLinks()) {
            if (type == StatsType.FLOW_STATS) {
                attachFlowLoad(tlink);
            } else if (type == StatsType.PORT_STATS) {
                //test two function
                attachFlowLoad(tlink);
                attachPortLoad(tlink);
            }

            // we only want to report on links deemed to have traffic
            // 这里高亮显示port的带宽信息
            if (tlink.hasTraffic()) {
                //highlights.add(tlink.highlight(type));
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

//                  以下两个方法不可用，可能是这里的ConnectionPoint成员信息不完整导致

//                    log.info("getVportLoadCapability(src): "+getVportLoadCapability(src));
//                    log.info("getVportMaxCapability(src): " + getVportMaxCapability(src));

//                  以下两个方法不可用，可能是这里的ConnectionPoint成员信息不完整导致
//                    log.info("src: " + src.deviceId().toString() + " , " + src.port().toString());
//                    log.info("dst: " + dst.deviceId().toString() + " , " + dst.port().toString());
//                  不可用
//                    long linkrestBandwidth = getIntraLinkRestBw(src, dst);
//                    long linkmaxBandwidth = getIntraLinkMaxBw(src, dst);

                    //linkHighlight.label()就是带宽
                    //log.info("linkId: " + tlink.linkId());
                    //log.info("link的带宽"+"label: " + linkHighlight.label());
                    String bandwidth = linkHighlight.label();
                    double level = 10000000;
                    String tlinkId = tlink.linkId();
                    if(bandwidth.contains("M")){
                        double temp = Double.valueOf(bandwidth.trim().substring(0, bandwidth.indexOf("M"))) * 1000;
                        log.info("=====bandwidth: " + temp + ", 帶寬利用率： " + temp/level);
                        tLinkId_BandWidth.put(tlinkId, temp);
                        tLinkId_BandWidthUsedRate.put(tlinkId, temp/level);
                        sum += temp;
                        sum_UsedRate += temp/level;

                    }else if(bandwidth.contains("K")){
                        double level1 = 10000000;
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
                double level2 = 10000000;
                double temp = 0;// 带宽设为0
                String tlinkId = tlink.linkId();
                tLinkId_BandWidth.put(tlinkId, temp);
                tLinkId_BandWidthUsedRate.put(tlinkId, temp/level2);
                sum += 0;
                sum_UsedRate += 0;
            }
        }

//        for(TrafficLink trafficLink : linkMap.biLinks()){
//
//
//            String linkId = trafficLink.linkId();
//            //log.info("linkId: " + linkId);
//            ConnectPoint linkOneSrc = trafficLink.one().src();
//            //log.info("linkOneSrc: " + linkOneSrc.toString());
//
//            /**
//             StatisticManager.java
//             对流的统计
//             connectPoint暂定为流的src交换机
//             private HashMap<FlowEntry, Long> flow_rate_interval(ConnectPoint connectPoint)
//             */
//
//
////            HashMap<FlowEntry, Long> flow_rate_Of_LinkSrc = statisticService.flow_rate_interval(linkOneSrc);
//
////            if(flow_rate_Of_LinkSrc == null){
////                log.info("flow_rate_Of_LinkSrc 为空！");
////            }
////             else{
////                for(Map.Entry<FlowEntry, Long> entryLongEntry : flow_rate_Of_LinkSrc.entrySet()){
////                    if(entryLongEntry.getKey() != null && entryLongEntry.getValue() != null){
////                        String flowId = entryLongEntry.getKey().id().toString();
////                        String flowRate = entryLongEntry.getValue().toString();
////                        log.info("flowId: " + flowId);
////                        log.info("flowRate: " + flowRate);
////                    }
////
////                }
////            }
//
//        }

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

    // create highlights for links, showing flows for selected devices.
    private Highlights deviceLinkFlows() {
        Highlights highlights = new Highlights();

        if (selectedNodes != null && !selectedNodes.devicesWithHover().isEmpty()) {
            // capture flow counts on bilinks
            TrafficLinkMap linkMap = new TrafficLinkMap();

            for (Device device : selectedNodes.devicesWithHover()) {
                Map<Link, Integer> counts = getLinkFlowCounts(device.id());
                for (Link link : counts.keySet()) {
                    TrafficLink tlink = linkMap.add(link);
                    tlink.addFlows(counts.get(link));
                }
            }

            // now report on our collated links
            for (TrafficLink tlink : linkMap.biLinks()) {
                highlights.add(tlink.highlight(StatsType.FLOW_COUNT));
            }

        }
        return highlights;
    }

    private Highlights intentGroup() {
        Highlights highlights = new Highlights();

        if (selectedIntents != null && !selectedIntents.none()) {
            // If 'all' intents are selected, they will all have primary
            // highlighting; otherwise, the specifically selected intent will
            // have primary highlighting, and the remainder will have secondary
            // highlighting.
            Set<Intent> primary;
            Set<Intent> secondary;
            int count = selectedIntents.size();

            Set<Intent> allBut = new HashSet<>(selectedIntents.intents());
            Intent current;

            if (selectedIntents.all()) {
                primary = allBut;
                secondary = Collections.emptySet();
                log.debug("Highlight all intents ({})", count);
            } else {
                current = selectedIntents.current();
                primary = new HashSet<>();
                primary.add(current);
                allBut.remove(current);
                secondary = allBut;
                log.debug("Highlight intent: {} ([{}] of {})",
                        current.id(), selectedIntents.index(), count);
            }

            highlightIntentLinks(highlights, primary, secondary);
        }
        return highlights;
    }

    private Highlights intentTraffic() {
        Highlights highlights = new Highlights();

        if (selectedIntents != null && selectedIntents.single()) {
            Intent current = selectedIntents.current();
            Set<Intent> primary = new HashSet<>();
            primary.add(current);
            log.debug("Highlight traffic for intent: {} ([{}] of {})",
                    current.id(), selectedIntents.index(), selectedIntents.size());

            highlightIntentLinksWithTraffic(highlights, primary);
            highlights.subdueAllElse(Amount.MINIMALLY);
        }
        return highlights;
    }

    // - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - - -

    private void compileLinks(TrafficLinkMap linkMap) {
        servicesBundle.linkService().getLinks().forEach(linkMap::add);
    }

    private void addEdgeLinks(TrafficLinkMap linkMap) {
        servicesBundle.hostService().getHosts().forEach(host -> {
            linkMap.add(createEdgeLink(host, true));
            linkMap.add(createEdgeLink(host, false));
        });
    }

    /**
     * 把值传给页面
     * @param link
     * @return
     */
    private Load getLinkFlowLoad(Link link) {


        if (link != null && link.src().elementId() instanceof DeviceId) {
            return servicesBundle.flowStatsService().load(link);
        }
        return null;
    }

    private void attachFlowLoad(TrafficLink link) {

        link.addLoad(getLinkFlowLoad(link.one()));
        link.addLoad(getLinkFlowLoad(link.two()));
    }

    /**
     * 点击页面开启监控port的按钮
     * 每5秒监控一次网络拓扑，如果负载增大了，就更新数据，看最后一行。
     * 采集各链路link的信息，评价总体link负载的均衡度，丢包率等
     * @param link
     */
    private void attachPortLoad(TrafficLink link) {
        // For bi-directional traffic links, use
        // the max link rate of either direction
        // (we choose 'one' since we know that is never null)
        Link one = link.one();


        Load egressSrc = servicesBundle.portStatsService().load(one.src());
        Load egressDst = servicesBundle.portStatsService().load(one.dst());


        /**
         * /root/onos/web/gui/src/main/java/org/onosproject/ui/impl/TrafficMonitor.java
         * test statistic
         */
//
//
//        if(egressDst != null && egressSrc != null){
//            log.info("======");
//            log.info("one.src().deviceId() : " + one.src().deviceId().toString());
//            log.info("one.dst().deviceId() : " + one.dst().deviceId().toString());
//            log.info("one.src().port() : " + one.src().port().toString());
//            log.info("one.dst().port() : " + one.dst().port().toString());
//            log.info("egressSrc.rate() : " + egressSrc.rate());
//            log.info("egressDst.rate() : " + egressDst.rate());
//            log.info("======");
//        }else{
//            /**
//             * load信息为空，此时设置为0
//             */
//            log.info("======");
//            log.info("one.src().deviceId() : " + one.src().deviceId().toString());
//            log.info("one.dst().deviceId() : " + one.dst().deviceId().toString());
//            log.info("one.src().port() : " + one.src().port().toString());
//            log.info("one.dst().port() : " + one.dst().port().toString());
//            log.info("egressSrc.rate() : " + 0);
//            log.info("egressDst.rate() : " + 0);
//            log.info("======");
//        }





        link.addLoad(maxLoad(egressSrc, egressDst), BPS_THRESHOLD);
//        link.addLoad(maxLoad(egressSrc, egressDst), 10);    // DEBUG ONLY!!
    }

    private Load maxLoad(Load a, Load b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.rate() > b.rate() ? a : b;
    }

    // Counts all flow entries that egress on the links of the given device.
    private Map<Link, Integer> getLinkFlowCounts(DeviceId deviceId) {
        // get the flows for the device
        List<FlowEntry> entries = new ArrayList<>();
        for (FlowEntry flowEntry : servicesBundle.flowService().getFlowEntries(deviceId)) {
            entries.add(flowEntry);
        }

        // get egress links from device, and include edge links
        Set<Link> links = new HashSet<>(servicesBundle.linkService()
                .getDeviceEgressLinks(deviceId));
        Set<Host> hosts = servicesBundle.hostService().getConnectedHosts(deviceId);
        if (hosts != null) {
            for (Host host : hosts) {
                links.add(createEdgeLink(host, false));
            }
        }

        // compile flow counts per link
        Map<Link, Integer> counts = new HashMap<>();
        for (Link link : links) {
            counts.put(link, getEgressFlows(link, entries));
        }
        return counts;
    }

    // Counts all entries that egress on the link source port.
    private int getEgressFlows(Link link, List<FlowEntry> entries) {
        int count = 0;
        PortNumber out = link.src().port();
        for (FlowEntry entry : entries) {
            TrafficTreatment treatment = entry.treatment();
            for (Instruction instruction : treatment.allInstructions()) {
                if (instruction.type() == Instruction.Type.OUTPUT &&
                        ((OutputInstruction) instruction).port().equals(out)) {
                    count++;
                }
            }
        }
        return count;
    }

    private void highlightIntentLinks(Highlights highlights,
                                      Set<Intent> primary, Set<Intent> secondary) {
        TrafficLinkMap linkMap = new TrafficLinkMap();
        // NOTE: highlight secondary first, then primary, so that links shared
        //       by intents are colored correctly ("last man wins")
        createTrafficLinks(highlights, linkMap, secondary, Flavor.SECONDARY_HIGHLIGHT, false);
        createTrafficLinks(highlights, linkMap, primary, Flavor.PRIMARY_HIGHLIGHT, false);
        colorLinks(highlights, linkMap);
    }

    private void highlightIntentLinksWithTraffic(Highlights highlights,
                                                 Set<Intent> primary) {
        TrafficLinkMap linkMap = new TrafficLinkMap();
        createTrafficLinks(highlights, linkMap, primary, Flavor.PRIMARY_HIGHLIGHT, true);
        colorLinks(highlights, linkMap);
    }

    private void createTrafficLinks(Highlights highlights,
                                    TrafficLinkMap linkMap, Set<Intent> intents,
                                    Flavor flavor, boolean showTraffic) {
        for (Intent intent : intents) {
            List<Intent> installables = servicesBundle.intentService()
                    .getInstallableIntents(intent.key());
            Iterable<Link> links = null;
            if (installables != null) {
                for (Intent installable : installables) {

                    if (installable instanceof PathIntent) {
                        links = ((PathIntent) installable).path().links();
                    } else if (installable instanceof FlowRuleIntent) {
                        links = addEdgeLinksIfNeeded(intent, linkResources(installable));
                    } else if (installable instanceof FlowObjectiveIntent) {
                        links = addEdgeLinksIfNeeded(intent, linkResources(installable));
                    } else if (installable instanceof LinkCollectionIntent) {
                        links = ((LinkCollectionIntent) installable).links();
                    } else if (installable instanceof OpticalPathIntent) {
                        links = ((OpticalPathIntent) installable).path().links();
                    }

                    boolean isOptical = intent instanceof OpticalConnectivityIntent;
                    processLinks(linkMap, links, flavor, isOptical, showTraffic);
                    updateHighlights(highlights, links);
                }
            }
        }
    }

    private Iterable<Link> addEdgeLinksIfNeeded(Intent parentIntent,
                                                Collection<Link> links) {
        if (parentIntent instanceof HostToHostIntent) {
            links = new HashSet<>(links);
            HostToHostIntent h2h = (HostToHostIntent) parentIntent;
            Host h1 = servicesBundle.hostService().getHost(h2h.one());
            Host h2 = servicesBundle.hostService().getHost(h2h.two());
            links.add(createEdgeLink(h1, true));
            links.add(createEdgeLink(h2, true));
        }
        return links;
    }

    private void updateHighlights(Highlights highlights, Iterable<Link> links) {
        for (Link link : links) {
            ensureNodePresent(highlights, link.src().elementId());
            ensureNodePresent(highlights, link.dst().elementId());
        }
    }

    private void ensureNodePresent(Highlights highlights, ElementId eid) {
        String id = eid.toString();
        NodeHighlight nh = highlights.getNode(id);
        if (nh == null) {
            if (eid instanceof DeviceId) {
                nh = new DeviceHighlight(id);
                highlights.add((DeviceHighlight) nh);
            } else if (eid instanceof HostId) {
                nh = new HostHighlight(id);
                highlights.add((HostHighlight) nh);
            }
        }
    }

    // Extracts links from the specified flow rule intent resources
    private Collection<Link> linkResources(Intent installable) {
        ImmutableList.Builder<Link> builder = ImmutableList.builder();
        installable.resources().stream().filter(r -> r instanceof Link)
                .forEach(r -> builder.add((Link) r));
        return builder.build();
    }

    private void processLinks(TrafficLinkMap linkMap, Iterable<Link> links,
                              Flavor flavor, boolean isOptical,
                              boolean showTraffic) {
        if (links != null) {
            for (Link link : links) {

                TrafficLink tlink = linkMap.add(link);
                tlink.tagFlavor(flavor);
                tlink.optical(isOptical);
                if (showTraffic) {
                    tlink.addLoad(getLinkFlowLoad(link));
                    tlink.antMarch(true);
                }
            }
        }
    }

    private void colorLinks(Highlights highlights, TrafficLinkMap linkMap) {
        for (TrafficLink tlink : linkMap.biLinks()) {
            highlights.add(tlink.highlight(StatsType.TAGGED));
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
                    case ALL_FLOW_TRAFFIC:
                        sendAllFlowTraffic();
                        break;
                    case ALL_PORT_TRAFFIC:
                        sendAllPortTraffic();
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
