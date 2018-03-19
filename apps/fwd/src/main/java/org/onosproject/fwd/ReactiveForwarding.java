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
package org.onosproject.fwd;

import com.google.common.collect.ImmutableSet;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.*;
import org.onlab.util.KryoNamespace;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.Event;
import org.onosproject.net.*;
import org.onosproject.net.topology.TopologyEdge;
import org.onosproject.net.config.NetworkConfigService;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.criteria.EthCriterion;
import org.onosproject.net.flow.instructions.Instruction;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.OpticalPathIntent;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.statistic.FlowStatisticService;
import org.onosproject.net.statistic.StatisticService;
import org.onosproject.net.statistic.SummaryFlowEntryWithLoad;
import org.onosproject.net.topology.TopologyEvent;
import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.store.service.StorageService;
import org.osgi.service.component.ComponentContext;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.WallClockTimestamp;
import org.onosproject.store.service.MultiValuedTimestamp;
import org.osgi.service.jdbc.DataSourceFactory;
//import org.onosproject.incubator.net.PortStatisticsService;
import org.slf4j.Logger;
//import redis.clients.jedis.Jedis;
//import org.onosproject.incubator.net.PortStatisticsService;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.*;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Sample reactive forwarding application.
 */
@Component(immediate = true)
@Service(value = ReactiveForwarding.class)
public class ReactiveForwarding {

    private static final int DEFAULT_TIMEOUT = 10;
    private static final int DEFAULT_PRIORITY = 10;

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

//    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
//    protected PortStatisticsService portStatisticsService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StorageService storageService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowStatisticService flowStatisticService;

//    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
//    protected PortStatisticsService portStatisticsService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StatisticService statisticService;





    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    private  EventuallyConsistentMap<MacAddress, ReactiveForwardMetrics> metrics;

    private ApplicationId appId;

    @Property(name = "packetOutOnly", boolValue = false,
            label = "Enable packet-out only forwarding; default is false")
    private boolean packetOutOnly = false;

    @Property(name = "packetOutOfppTable", boolValue = false,
            label = "Enable first packet forwarding using OFPP_TABLE port " +
                    "instead of PacketOut with actual port; default is false")
    private boolean packetOutOfppTable = false;

    @Property(name = "flowTimeout", intValue = DEFAULT_TIMEOUT,
            label = "Configure Flow Timeout for installed flow rules; " +
                    "default is 10 sec")
    private int flowTimeout = DEFAULT_TIMEOUT;

    @Property(name = "flowPriority", intValue = DEFAULT_PRIORITY,
            label = "Configure Flow Priority for installed flow rules; " +
                    "default is 10")
    private int flowPriority = DEFAULT_PRIORITY;

    @Property(name = "ipv6Forwarding", boolValue = false,
            label = "Enable IPv6 forwarding; default is false")
    private boolean ipv6Forwarding = false;

    @Property(name = "matchDstMacOnly", boolValue = false,
            label = "Enable matching Dst Mac Only; default is false")
    private boolean matchDstMacOnly = false;

    @Property(name = "matchVlanId", boolValue = false,
            label = "Enable matching Vlan ID; default is false")
    private boolean matchVlanId = false;

    @Property(name = "matchIpv4Address", boolValue = false,
            label = "Enable matching IPv4 Addresses; default is false")
    private boolean matchIpv4Address = false;

    @Property(name = "matchIpv4Dscp", boolValue = false,
            label = "Enable matching IPv4 DSCP and ECN; default is false")
    private boolean matchIpv4Dscp = false;

    @Property(name = "matchIpv6Address", boolValue = false,
            label = "Enable matching IPv6 Addresses; default is false")
    private boolean matchIpv6Address = false;

    @Property(name = "matchIpv6FlowLabel", boolValue = false,
            label = "Enable matching IPv6 FlowLabel; default is false")
    private boolean matchIpv6FlowLabel = false;



    @Property(name = "matchTcpUdpPorts", boolValue = false,
            label = "Enable matching TCP/UDP ports; default is false")
    private boolean matchTcpUdpPorts = false;

    @Property(name = "matchIcmpFields", boolValue = false,
            label = "Enable matching ICMPv4 and ICMPv6 fields; " +
                    "default is false")
    private boolean matchIcmpFields = false;


    @Property(name = "ignoreIPv4Multicast", boolValue = false,
            label = "Ignore (do not forward) IPv4 multicast packets; default is false")
    private boolean ignoreIpv4McastPackets = false;

    @Property(name = "recordMetrics", boolValue = true,
            label = "Enable record metrics for reactive forwarding")
    private boolean recordMetrics = true;

    private final TopologyListener topologyListener = new InternalTopologyListener();


    @Activate
    public void activate(ComponentContext context) {
        KryoNamespace.Builder metricSerializer = KryoNamespace.newBuilder()
                .register(KryoNamespaces.API)
                .register(ReactiveForwardMetrics.class)
                .register(MultiValuedTimestamp.class);
        metrics =  storageService.<MacAddress, ReactiveForwardMetrics>eventuallyConsistentMapBuilder()
                .withName("metrics-fwd")
                .withSerializer(metricSerializer)
                .withTimestampProvider((key, metricsData) -> new
                        MultiValuedTimestamp<>(new WallClockTimestamp(), System.nanoTime()))
                .build();

        cfgService.registerProperties(getClass());
        appId = coreService.registerApplication("org.onosproject.fwd");
        //负责数据包在特定路径上转发的数据包处理器
        packetService.addProcessor(processor, PacketProcessor.director(2));
        topologyService.addListener(topologyListener);

        readComponentConfiguration(context);
        requestIntercepts();

        log.info("Started", appId.id());
    }

    @Deactivate
    public void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();
        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(processor);
        topologyService.removeListener(topologyListener);
        processor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        readComponentConfiguration(context);
        requestIntercepts();
    }

    /**
     * Request packet in via packet service.
     */
    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

        selector.matchEthType(Ethernet.TYPE_IPV6);
        if (ipv6Forwarding) {
            packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        } else {
            packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        }
    }

    /**
     * Cancel request for packet in via packet service.
     */
    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        selector.matchEthType(Ethernet.TYPE_IPV6);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
     * Extracts properties from the component configuration context.
     *
     * @param context the component context
     */
    private void readComponentConfiguration(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();

        Boolean packetOutOnlyEnabled =
                Tools.isPropertyEnabled(properties, "packetOutOnly");
        if (packetOutOnlyEnabled == null) {
            log.info("Packet-out is not configured, " +
                     "using current value of {}", packetOutOnly);
        } else {
            packetOutOnly = packetOutOnlyEnabled;
            log.info("Configured. Packet-out only forwarding is {}",
                    packetOutOnly ? "enabled" : "disabled");
        }

        Boolean packetOutOfppTableEnabled =
                Tools.isPropertyEnabled(properties, "packetOutOfppTable");
        if (packetOutOfppTableEnabled == null) {
            log.info("OFPP_TABLE port is not configured, " +
                     "using current value of {}", packetOutOfppTable);
        } else {
            packetOutOfppTable = packetOutOfppTableEnabled;
            log.info("Configured. Forwarding using OFPP_TABLE port is {}",
                    packetOutOfppTable ? "enabled" : "disabled");
        }

        Boolean ipv6ForwardingEnabled =
                Tools.isPropertyEnabled(properties, "ipv6Forwarding");
        if (ipv6ForwardingEnabled == null) {
            log.info("IPv6 forwarding is not configured, " +
                     "using current value of {}", ipv6Forwarding);
        } else {
            ipv6Forwarding = ipv6ForwardingEnabled;
            log.info("Configured. IPv6 forwarding is {}",
                    ipv6Forwarding ? "enabled" : "disabled");
        }

        Boolean matchDstMacOnlyEnabled =
                Tools.isPropertyEnabled(properties, "matchDstMacOnly");
        if (matchDstMacOnlyEnabled == null) {
            log.info("Match Dst MAC is not configured, " +
                     "using current value of {}", matchDstMacOnly);
        } else {
            matchDstMacOnly = matchDstMacOnlyEnabled;
            log.info("Configured. Match Dst MAC Only is {}",
                    matchDstMacOnly ? "enabled" : "disabled");
        }

        Boolean matchVlanIdEnabled =
                Tools.isPropertyEnabled(properties, "matchVlanId");
        if (matchVlanIdEnabled == null) {
            log.info("Matching Vlan ID is not configured, " +
                     "using current value of {}", matchVlanId);
        } else {
            matchVlanId = matchVlanIdEnabled;
            log.info("Configured. Matching Vlan ID is {}",
                    matchVlanId ? "enabled" : "disabled");
        }

        Boolean matchIpv4AddressEnabled =
                Tools.isPropertyEnabled(properties, "matchIpv4Address");
        if (matchIpv4AddressEnabled == null) {
            log.info("Matching IPv4 Address is not configured, " +
                     "using current value of {}", matchIpv4Address);
        } else {
            matchIpv4Address = matchIpv4AddressEnabled;
            log.info("Configured. Matching IPv4 Addresses is {}",
                    matchIpv4Address ? "enabled" : "disabled");
        }

        Boolean matchIpv4DscpEnabled =
                Tools.isPropertyEnabled(properties, "matchIpv4Dscp");
        if (matchIpv4DscpEnabled == null) {
            log.info("Matching IPv4 DSCP and ECN is not configured, " +
                     "using current value of {}", matchIpv4Dscp);
        } else {
            matchIpv4Dscp = matchIpv4DscpEnabled;
            log.info("Configured. Matching IPv4 DSCP and ECN is {}",
                    matchIpv4Dscp ? "enabled" : "disabled");
        }

        Boolean matchIpv6AddressEnabled =
                Tools.isPropertyEnabled(properties, "matchIpv6Address");
        if (matchIpv6AddressEnabled == null) {
            log.info("Matching IPv6 Address is not configured, " +
                     "using current value of {}", matchIpv6Address);
        } else {
            matchIpv6Address = matchIpv6AddressEnabled;
            log.info("Configured. Matching IPv6 Addresses is {}",
                    matchIpv6Address ? "enabled" : "disabled");
        }

        Boolean matchIpv6FlowLabelEnabled =
                Tools.isPropertyEnabled(properties, "matchIpv6FlowLabel");
        if (matchIpv6FlowLabelEnabled == null) {
            log.info("Matching IPv6 FlowLabel is not configured, " +
                     "using current value of {}", matchIpv6FlowLabel);
        } else {
            matchIpv6FlowLabel = matchIpv6FlowLabelEnabled;
            log.info("Configured. Matching IPv6 FlowLabel is {}",
                    matchIpv6FlowLabel ? "enabled" : "disabled");
        }

        Boolean matchTcpUdpPortsEnabled =
                Tools.isPropertyEnabled(properties, "matchTcpUdpPorts");
        if (matchTcpUdpPortsEnabled == null) {
            log.info("Matching TCP/UDP fields is not configured, " +
                     "using current value of {}", matchTcpUdpPorts);
        } else {
            matchTcpUdpPorts = matchTcpUdpPortsEnabled;
            log.info("Configured. Matching TCP/UDP fields is {}",
                    matchTcpUdpPorts ? "enabled" : "disabled");
        }

        Boolean matchIcmpFieldsEnabled =
                Tools.isPropertyEnabled(properties, "matchIcmpFields");
        if (matchIcmpFieldsEnabled == null) {
            log.info("Matching ICMP (v4 and v6) fields is not configured, " +
                     "using current value of {}", matchIcmpFields);
        } else {
            matchIcmpFields = matchIcmpFieldsEnabled;
            log.info("Configured. Matching ICMP (v4 and v6) fields is {}",
                    matchIcmpFields ? "enabled" : "disabled");
        }

        Boolean ignoreIpv4McastPacketsEnabled =
                Tools.isPropertyEnabled(properties, "ignoreIpv4McastPackets");
        if (ignoreIpv4McastPacketsEnabled == null) {
            log.info("Ignore IPv4 multi-cast packet is not configured, " +
                     "using current value of {}", ignoreIpv4McastPackets);
        } else {
            ignoreIpv4McastPackets = ignoreIpv4McastPacketsEnabled;
            log.info("Configured. Ignore IPv4 multicast packets is {}",
                    ignoreIpv4McastPackets ? "enabled" : "disabled");
        }
        Boolean recordMetricsEnabled =
                Tools.isPropertyEnabled(properties, "recordMetrics");
        if (recordMetricsEnabled == null) {
            log.info("IConfigured. Ignore record metrics  is {} ," +
                    "using current value of {}", recordMetrics);
        } else {
            recordMetrics = recordMetricsEnabled;
            log.info("Configured. record metrics  is {}",
                    recordMetrics ? "enabled" : "disabled");
        }

        flowTimeout = Tools.getIntegerProperty(properties, "flowTimeout", DEFAULT_TIMEOUT);
        log.info("Configured. Flow Timeout is configured to {} seconds", flowTimeout);

        flowPriority = Tools.getIntegerProperty(properties, "flowPriority", DEFAULT_PRIORITY);
        log.info("Configured. Flow Priority is configured to {}", flowPriority);
    }






    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
    private class ReactivePacketProcessor implements PacketProcessor {

        /**
         * 自研统计模块
         * 对端口带宽的统计信息
         * @param connectPoint
         * @return
         */


        private long getVportLoadCapability(ConnectPoint connectPoint) {
            long vportCurSpeed = 0;
            if(connectPoint != null && statisticService.vportload(connectPoint) != null){
                //rate : bytes/s result : b/s
                vportCurSpeed = statisticService.vportload(connectPoint).rate() ;
            }
            return vportCurSpeed;
        }

        /**
         * 这个方法是用来设定link最大的负载能达到多少
         * @param connectPoint
         * @return
         */
        //current port speed 应该是现在的port的流速，但onos代码有bug.....一直显示10G，没有任何变化
        //目前解决这个bug的办法，在mininet设定带宽最大只能是100Mbps
        private long getVportMaxCapability(ConnectPoint connectPoint) {
            Port port = deviceService.getPort(connectPoint.deviceId(), connectPoint.port());
            long vportMaxSpeed = 0;
            if(connectPoint != null){
                //vportMaxSpeed = port.portSpeed() * 1000000;  //portSpeed Mbps result : bps
                vportMaxSpeed = 100*1000000;
            }

            return vportMaxSpeed;
        }

        /**
         *
         * @param srcConnectPoint
         * @param dstConnectPoint
         * @return
         */
        private long getIntraLinkLoadBw(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
            return Long.min(getVportLoadCapability(srcConnectPoint), getVportLoadCapability(dstConnectPoint));
        }

        /**
         *
         * @param srcConnectPoint
         * @param dstConnectPoint
         * @return
         */
        private long getIntraLinkMaxBw(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
            return Long.min(getVportMaxCapability(srcConnectPoint), getVportMaxCapability(dstConnectPoint));
        }

        /**
         *
         * @param srcConnectPoint
         * @param dstConnectPoint
         * @return
         */
        private long getIntraLinkRestBw(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
            return getIntraLinkMaxBw(srcConnectPoint, dstConnectPoint) - getIntraLinkLoadBw(srcConnectPoint, dstConnectPoint);
        }

        /**
         *
         * @param srcConnectPoint
         * @param dstConnectPoint
         * @return
         */

        private Double getIntraLinkCapability(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
            return (Double.valueOf(getIntraLinkLoadBw(srcConnectPoint, dstConnectPoint)) / Double.valueOf(getIntraLinkMaxBw(srcConnectPoint, dstConnectPoint)) * 100);
        }


        //////////////////////////

        /**
         * 包处理
         * @param context packet processing context
         */

        @Override
        public synchronized void process(PacketContext context) {
            // Stop processing if the packet has been handled, since we
            // can't do any more to it.
            if (context.isHandled()) {
                return;
            }

            /**
             *
             * 收集网络信息
             * Packet_In 消息 上报
             *
             */

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt == null) {
                return;
            }

            /**
             * macAddress: ethPkt.getSourceMAC()
             * macAddress1 = ethPkt.getDestinationMAC();
             */

            MacAddress macAddress = ethPkt.getSourceMAC();
            ReactiveForwardMetrics macMetrics = null;
            macMetrics = createCounter(macAddress);
            inPacket(macMetrics);


            // Bail if this is deemed to be a control packet.
            if (isControlPacket(ethPkt)) {
                droppedPacket(macMetrics);
                return;
            }

            // Skip IPv6 multicast packet when IPv6 forward is disabled.
            if (!ipv6Forwarding && isIpv6Multicast(ethPkt)) {
                droppedPacket(macMetrics);
                return;
            }

            HostId id_src = HostId.hostId(macAddress);
            MacAddress macAddress1 = ethPkt.getDestinationMAC();
            HostId id = HostId.hostId(macAddress1);

            // Do not process link-local addresses in any way.
            if (id.mac().isLinkLocal()) {
                droppedPacket(macMetrics);
                return;
            }

            // Do not process IPv4 multicast packets, let mfwd handle them
            if (ignoreIpv4McastPackets && ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                if (id.mac().isMulticast()) {
                    return;
                }
            }
            Host src = hostService.getHost(id_src);
//            for(int i=0; i< 50; i++){
//                log.info("=====================" + src.toString());
//            }


            // Do we know who this is for? If not, flood and bail.
            Host dst = hostService.getHost(id);
            if (dst == null) {
                flood(context, macMetrics);
                return;
            }


            /**
             * 测试case： 取ip地址
             */

//            for(int i=0; i< 5; i++){
//                log.info("===ipv4地址！！！！！===================");
//            }
//
//            Set<IpAddress> result_src = dst.ipAddresses();
//            for(IpAddress ipAddress : result_src){
//                String ipV4String = ipAddress.getIp4Address().toString();
//                log.info(ipV4String);
//            }
//
//            for(int i=0; i< 5; i++){
//                log.info("==ipv4地址！！！！！=========================");
//            }




            // Are we on an edge switch that our destination is on? If so,
            // simply forward out to the destination and bail.

//            log.info("---------------------------　每一次选路信息　------------------------");
//            log.info("源host:"+ id_src.mac().toString());
//            log.info("目的host:"+ id.mac().toString());




            // 从同一个交换机进去和出去
            if (pkt.receivedFrom().deviceId().equals(dst.location().deviceId())) {

                if (!context.inPacket().receivedFrom().port().equals(dst.location().port())) {

                    //log.info("case1.............................");
                    installRule(context, dst.location().port(), macMetrics);
                }
                return;
            }





            /*
            注意：这里的src.location().deviceId()是边缘交换机，但是和Dijkstra的getPaths里面的src交换机地址不同
            因为getPaths的src交换机指的是此时发出packetIn的交换机
             */

            /**
             * 负载均衡决策模块
             * 选出TopK的决策路径
             * 目前版本：决策出最佳路径： 1条
             */

            /**
             *
             * 配合mininet
             * 此topologyService的getPaths1实现了
             * 拓扑管理模块 和 拓扑计算模块
             * 通过 IOC 技术继承进负载均衡模块当中
             *
             * 拓扑管理模块底层依赖 链路发现模块 传递的信息，方法： IOC 技术
             *
             */


/////////////////////////////////////////////////////////////////-just a test
            Set<TopologyEdge> topologyEdgeset = null;

            // Otherwise, get a set of paths that lead from here to the
            // destination edge switch.
            Set<Path> paths =
                    topologyService.getPaths(topologyService.currentTopology(),
                                             pkt.receivedFrom().deviceId(),
                                             dst.location().deviceId());

//            Set<Path> paths =
//                    topologyService.getPaths1(topologyService.currentTopology(),
//                            pkt.receivedFrom().deviceId(),
//                            dst.location().deviceId(),
//                            src.location().deviceId());
//            flowStatisticService.loadSummary(null);


            /**
             * 网络拓扑中的所有link
             */
            LinkedList<Link> LinksResult = topologyService.getAllPaths(topologyService.currentTopology());

            //Jedis jedidiss = new Jedis("127.0.0.1", 6379);

            /**
             * 根据 LinksResult中每条link，算出它的maxBandwidthCapacity
             * 然后持久化到文件中
             */

            //这里的size是64,是双向的
            log.info("allLinks: LinksResult.size(): " + LinksResult.size());

            //packetIn
//            DeviceId dstDeviceId = dst.location().deviceId();
//            DeviceId srcDeviceId = src.location().deviceId();


            /**
             * macAddress: ethPkt.getSourceMAC()
             * macAddress1 = ethPkt.getDestinationMAC();
             */

            /**
             * 根据linkResult得到所有的DeviceId
             */

            /**
             * 遍历所有link的src端交换机中所有的流表，得到此交换机中的流
             * 从而等价于遍历所有流
             * 然后轮询遍历所有流的源目mac是否和packetin的流相同
             * 如果相同则取此流的流速分析
             * 若流速较大则为大流
             * 否则，则为小流
             *
             * 大流选路采用PLLB算法（避免大流汇聚）
             * 小流选路采用hash均分的方式
             */

            boolean isFlowFound = false;
            String ObjectFlowId = "";
            String ObjectFlowSpeed = "";
            for(Link link : LinksResult){

                //log.info(link.src().toString() + "," + link.dst().toString());

//                long linkRestBandwidth = getIntraLinkRestBw(link.src(), link.dst());
//                long linkMaxBandwidth = getIntraLinkMaxBw(link.src(), link.dst());
//                long linkCurBandwidth = getIntraLinkLoadBw(link.src(), link.dst());
//                long VportSrc = getVportLoadCapability(link.src());
//                long VportDst = getVportLoadCapability(link.dst());
//                long linkload_src = statisticService.load(link.src()).rate();
//                long linkload_dst = statisticService.load(link.dst()).rate();
//                long maxCapacitySrc = getVportMaxCapability(link.src());
//                long maxCapacityDst = getVportMaxCapability(link.dst());


//                log.info("linkRestBandwidth(b): " + linkRestBandwidth);
//                log.info("linkMaxBandwidth(b): " + linkMaxBandwidth);
//                log.info("linkCurBandwidth(b): " + linkCurBandwidth);
//                log.info("VportSrc: " + VportSrc);
//                log.info("VportDst: " + VportDst);
//                log.info("maxCapacitySrc: " + maxCapacitySrc);
//                log.info("maxCapacityDst: " + maxCapacityDst);


//                都为0
//                log.info("linkload_src: " + linkload_src);
//                log.info("linkload_dst: " + linkload_dst);

                DeviceId deviceId_src = link.src().deviceId();
                DeviceId deviceId_dst = link.dst().deviceId();
                log.info("link_src=" + deviceId_src.toString() + ",link_dst=" +deviceId_dst.toString());
                for (FlowEntry r : flowRuleService.getFlowEntries(deviceId_src)) {

                    log.info("flowid: " + r.id().toString() + ", flowBytes: " + r.bytes());


                    boolean matchesSrc = false, matchesDst = false;


                    /**
                     * macAddress: ethPkt.getSourceMAC()
                     * macAddress1 = ethPkt.getDestinationMAC();
                     */

                    for (Instruction i : r.treatment().allInstructions()) {
                        if (i.type() == Instruction.Type.OUTPUT) {
                            // if the flow has matching src and dst
                            for (Criterion cr : r.selector().criteria()) {
                                if (cr.type() == Criterion.Type.ETH_DST) {


                                    //log.info(((EthCriterion) cr).mac().toString() + "--------" + macAddress1.toString());
                                    //((EthCriterion) cr).mac() : 00:00:00:00:00:02
                                    //deviceId_dst : of:00000000000007d1
                                    if (((EthCriterion) cr).mac().equals(macAddress1)) {
                                        matchesDst = true;
                                    }
                                } else if (cr.type() == Criterion.Type.ETH_SRC) {


                                    //log.info(((EthCriterion) cr).mac().toString() + "==============" + macAddress.toString());
                                    if (((EthCriterion) cr).mac().equals(macAddress)) {
                                        matchesSrc = true;
                                    }
                                }
                            }
                        }
                    }

                    if (matchesDst && matchesSrc) {
                        //log.info("找到packetIn所对应的流，源mac为" + macAddress.toString() + ", 目的mac为" + macAddress1.toString() + ", FlowId为" + r.id().toString());
                        ObjectFlowId = r.id().toString();
                        //计算流速
                        isFlowFound = true;
                        log.info("true");
                        //ObjectFlowSpeed赋值



                        break;


                    }



                }
                if(isFlowFound == true){
                    break;
                }
                log.info("======");

            }

            if(isFlowFound == true){
                log.info("找到packetIn所对应的流，源mac为" + macAddress.toString() + ", 目的mac为" + macAddress1.toString() + ", FlowId为" + ObjectFlowId);
            }

            /**
             * 选择最优路径
             * 来自trustCom
             */

            /**
             * PathsDecision_FESM
             *
             */

            Set<Path> Paths_FESM = PathsDecision_FESM(paths, pkt.receivedFrom().deviceId(),
                    dst.location().deviceId(),
                    src.location().deviceId(),
                    LinksResult);

            /**
             * PathsDecision_PLLB
             * 自研算法
             */
            Set<Path> Paths_PLLB = PathsDecision_PLLB(paths, pkt.receivedFrom().deviceId(),
                    dst.location().deviceId(),
                    src.location().deviceId(),
                    LinksResult);

            if (paths.isEmpty()) {
                // If there are no paths, flood and bail.
                flood(context, macMetrics);
                return;
            }




            /**
             *
             * 根据负载均衡策略模块的结果
             * 执行流表下发模块
             *
             */

            // Otherwise, pick a path that does not lead back to where we
            // came from; if no such path, flood and bail.
            // 原本第一个参数是paths
            Path path = pickForwardPathIfPossible(Paths_PLLB, pkt.receivedFrom().port());
            //Path path = pickForwardPathIfPossible(paths, pkt.receivedFrom().port());
            if (path == null) {
                log.warn("Don't know where to go from here {} for {} -> {}",
                         pkt.receivedFrom(), ethPkt.getSourceMAC(), ethPkt.getDestinationMAC());
                flood(context, macMetrics);
                return;
            }


            // Otherwise forward and be done with it.
            installRule(context, path.src().port(), macMetrics);


            /**
             * 评价指标监控计算模块 路径如下：
             * /root/onos/web/gui/src/main/java/org/onosproject/ui/impl/TrafficMonitor.java
             *
             * 目前的评价指标有：
             * 总体link负载的均衡度
             * 丢包率
             */



            /**
             * 评价指标---所有link负载的均衡度
             * 如果写在这里，就是在每次处理packetin的时候统计，不符合需求..
             */
            for(Link link : LinksResult){


            }
            log.info("=====================================================================================================================================");

        }


        private synchronized Set<Path> PathsDecision_PLLB(Set<Path> paths, DeviceId deviceId, DeviceId id, DeviceId deviceId1, LinkedList<Link> LinksResult) {



            //flowStatisticService.loadSummaryPortInternal()
            Set<Path> result = new HashSet<>();
            Map<Integer, Path> indexPath = new LinkedHashMap<>();
            //Path finalPath = paths.iterator().next();
            Path finalPath = null;

            int i=0;
            String sql = null;
            DBHelper db1 = null;
            ResultSet ret = null;

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
                 *  PathsDecision_PLLB
                 *
                 *  U = (h, p, b, r)
                 *
                 */
                long pObject = 0;
                long bObject = 0;
                long rObject = 0;
                double allLinkOfPath_BandWidth = 0;
                ArrayList<Double> arrayList = new ArrayList<>();
                for(Link link : path.links()){

                    //log.info("统计信息=====对于path " + i + " 的第 " + j + "条link： ");

                    /**
                     * 链路link 信息监控
                     *
                     * "link的负载(bps): " + IntraLinkLoadBw
                     *
                     */

                    long IntraLinkLoadBw = getIntraLinkLoadBw(link.src(), link.dst());


//                    long IntraLinkMaxBw = getIntraLinkMaxBw(link.src(), link.dst()); //bps
//                    long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
//                    double IntraLinkCapability = getIntraLinkCapability(link.src(), link.dst());

                    arrayList.add((double)IntraLinkLoadBw);
                    allLinkOfPath_BandWidth += IntraLinkLoadBw;


                    /**
                     * link 源端口和目的端口 信息监控
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
                        //rate
                        rObject = IntraLinkLoadBw;
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
                 * 遍历arraylist(一条path中所有link的带宽信息）
                 * 求负载的标准差（均衡度）
                 *
                 *
                 * 标准差：
                 * T= pow(bdInterval2_Sum, 1/2)
                 * bdInterval2_Sum = bdInterval2的累加/N
                 * bdInterval2 = pow(bdInterval, 2)
                 * bdInterval = Math.abs(value - pathMeanLoad)
                 * value: 遍历每条link，对应的负载（kbps）
                 * pathMeanLoad： 所有link的平均负载（kbps）
                 *
                 */
                double bdInterval2_Sum = 0;
                for(int k=0; k< arrayList.size(); k++){
                    double tempBandwidth = arrayList.get(k);
                    double bdInterval = Math.abs(tempBandwidth - pathMeanLoad);
                    log.info("link " + k + " : ");
                    log.info("选路阶段，bdInterval : " + bdInterval);
                    double bdInterval2 = Math.pow(bdInterval, 2);
                    log.info("选路阶段，bdInterval2 : " + bdInterval2);
                    bdInterval2_Sum += bdInterval2;
                }
                /**
                 * 方差
                 */
                double variance = bdInterval2_Sum / pathlinksSize;
                log.info("选路阶段，variance(方差）: " + variance);
                /**
                 * 标准差
                 */
                double standard_deviation = Math.pow(variance, 0.5);
                log.info("选路阶段，标准差(path所有link的负载均衡度）== " + standard_deviation);

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
                double rs = 1.0 / (double)(Math.log((double)(standard_deviation + 1)));
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

                double a1 = 0.2;
                //double a2 = 0.15;
                double a3 = 0.1;
                double a4 = 0.3;
                double a5 = 0.4;

                double b1 = a1 * rh;
                //double b2 = a2 * rp;
                double b3 = a3 * rb;
                double b4 = a4 * rr;
                double b5 = a5 * rs;
                double resultScore =  b1 + b3 + b4 + b5;
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


        private synchronized Set<Path> PathsDecision_FESM(Set<Path> paths, DeviceId deviceId, DeviceId id, DeviceId deviceId1, LinkedList<Link> LinksResult) {

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
            DBHelper db1 = null;
            ResultSet ret = null;

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

    }


    /**
     *
     * @param
     * @return
     */


    // Indicates whether this is a control packet, e.g. LLDP, BDDP
    private boolean isControlPacket(Ethernet eth) {
        short type = eth.getEtherType();
        return type == Ethernet.TYPE_LLDP || type == Ethernet.TYPE_BSN;
    }

    // Indicated whether this is an IPv6 multicast packet.
    private boolean isIpv6Multicast(Ethernet eth) {
        return eth.getEtherType() == Ethernet.TYPE_IPV6 && eth.isMulticast();
    }

    // Selects a path from the given set that does not lead back to the
    // specified port if possible.
    // 这里选很明显用了贪心
    private Path pickForwardPathIfPossible(Set<Path> paths, PortNumber notToPort) {
        Path lastPath = null;
        for (Path path : paths) {
            lastPath = path;
            if (!path.src().port().equals(notToPort)) {
                return path;
            }
        }
        return lastPath;
    }

    // Floods the specified packet if permissible.
    private void flood(PacketContext context, ReactiveForwardMetrics macMetrics) {
        if (topologyService.isBroadcastPoint(topologyService.currentTopology(),
                                             context.inPacket().receivedFrom())) {
            packetOut(context, PortNumber.FLOOD, macMetrics);
        } else {
            context.block();
        }
    }

    /**
     *
     * @param context
     * @param portNumber
     * @param macMetrics
     */
    // Sends a packet out the specified port.
    private void packetOut(PacketContext context, PortNumber portNumber, ReactiveForwardMetrics macMetrics) {
        replyPacket(macMetrics);
        context.treatmentBuilder().setOutput(portNumber);
        //OpenFlowCorePacketContext.send()
        context.send();
    }

    // Install a rule forwarding the packet to the specified port.
    private void installRule(PacketContext context, PortNumber portNumber, ReactiveForwardMetrics macMetrics) {
        //
        // We don't support (yet) buffer IDs in the Flow Service so
        // packet out first.
        //
        Ethernet inPkt = context.inPacket().parsed();
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

        // If PacketOutOnly or ARP packet than forward directly to output port
        if (packetOutOnly || inPkt.getEtherType() == Ethernet.TYPE_ARP) {
            packetOut(context, portNumber, macMetrics);
            return;
        }

        //
        // If matchDstMacOnly
        //    Create flows matching dstMac only
        // Else
        //    Create flows with default matching and include configured fields
        //
        if (matchDstMacOnly) {
            selectorBuilder.matchEthDst(inPkt.getDestinationMAC());
        } else {
            selectorBuilder.matchInPort(context.inPacket().receivedFrom().port())
                    .matchEthSrc(inPkt.getSourceMAC())
                    .matchEthDst(inPkt.getDestinationMAC());

            // If configured Match Vlan ID
            if (matchVlanId && inPkt.getVlanID() != Ethernet.VLAN_UNTAGGED) {
                selectorBuilder.matchVlanId(VlanId.vlanId(inPkt.getVlanID()));
            }

            //
            // If configured and EtherType is IPv4 - Match IPv4 and
            // TCP/UDP/ICMP fields
            //
            if (matchIpv4Address && inPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                IPv4 ipv4Packet = (IPv4) inPkt.getPayload();
                byte ipv4Protocol = ipv4Packet.getProtocol();
                Ip4Prefix matchIp4SrcPrefix =
                        Ip4Prefix.valueOf(ipv4Packet.getSourceAddress(),
                                          Ip4Prefix.MAX_MASK_LENGTH);
                Ip4Prefix matchIp4DstPrefix =
                        Ip4Prefix.valueOf(ipv4Packet.getDestinationAddress(),
                                          Ip4Prefix.MAX_MASK_LENGTH);
                selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPSrc(matchIp4SrcPrefix)
                        .matchIPDst(matchIp4DstPrefix);

                if (matchIpv4Dscp) {
                    byte dscp = ipv4Packet.getDscp();
                    byte ecn = ipv4Packet.getEcn();
                    selectorBuilder.matchIPDscp(dscp).matchIPEcn(ecn);
                }

                if (matchTcpUdpPorts && ipv4Protocol == IPv4.PROTOCOL_TCP) {
                    TCP tcpPacket = (TCP) ipv4Packet.getPayload();
                    selectorBuilder.matchIPProtocol(ipv4Protocol)
                            .matchTcpSrc(TpPort.tpPort(tcpPacket.getSourcePort()))
                            .matchTcpDst(TpPort.tpPort(tcpPacket.getDestinationPort()));
                }
                if (matchTcpUdpPorts && ipv4Protocol == IPv4.PROTOCOL_UDP) {
                    UDP udpPacket = (UDP) ipv4Packet.getPayload();
                    selectorBuilder.matchIPProtocol(ipv4Protocol)
                            .matchUdpSrc(TpPort.tpPort(udpPacket.getSourcePort()))
                            .matchUdpDst(TpPort.tpPort(udpPacket.getDestinationPort()));
                }
                if (matchIcmpFields && ipv4Protocol == IPv4.PROTOCOL_ICMP) {
                    ICMP icmpPacket = (ICMP) ipv4Packet.getPayload();
                    selectorBuilder.matchIPProtocol(ipv4Protocol)
                            .matchIcmpType(icmpPacket.getIcmpType())
                            .matchIcmpCode(icmpPacket.getIcmpCode());
                }
            }

            //
            // If configured and EtherType is IPv6 - Match IPv6 and
            // TCP/UDP/ICMP fields
            //
            if (matchIpv6Address && inPkt.getEtherType() == Ethernet.TYPE_IPV6) {
                IPv6 ipv6Packet = (IPv6) inPkt.getPayload();
                byte ipv6NextHeader = ipv6Packet.getNextHeader();
                Ip6Prefix matchIp6SrcPrefix =
                        Ip6Prefix.valueOf(ipv6Packet.getSourceAddress(),
                                          Ip6Prefix.MAX_MASK_LENGTH);
                Ip6Prefix matchIp6DstPrefix =
                        Ip6Prefix.valueOf(ipv6Packet.getDestinationAddress(),
                                          Ip6Prefix.MAX_MASK_LENGTH);
                selectorBuilder.matchEthType(Ethernet.TYPE_IPV6)
                        .matchIPv6Src(matchIp6SrcPrefix)
                        .matchIPv6Dst(matchIp6DstPrefix);

                if (matchIpv6FlowLabel) {
                    selectorBuilder.matchIPv6FlowLabel(ipv6Packet.getFlowLabel());
                }

                if (matchTcpUdpPorts && ipv6NextHeader == IPv6.PROTOCOL_TCP) {
                    TCP tcpPacket = (TCP) ipv6Packet.getPayload();
                    selectorBuilder.matchIPProtocol(ipv6NextHeader)
                            .matchTcpSrc(TpPort.tpPort(tcpPacket.getSourcePort()))
                            .matchTcpDst(TpPort.tpPort(tcpPacket.getDestinationPort()));
                }
                if (matchTcpUdpPorts && ipv6NextHeader == IPv6.PROTOCOL_UDP) {
                    UDP udpPacket = (UDP) ipv6Packet.getPayload();
                    selectorBuilder.matchIPProtocol(ipv6NextHeader)
                            .matchUdpSrc(TpPort.tpPort(udpPacket.getSourcePort()))
                            .matchUdpDst(TpPort.tpPort(udpPacket.getDestinationPort()));
                }
                if (matchIcmpFields && ipv6NextHeader == IPv6.PROTOCOL_ICMP6) {
                    ICMP6 icmp6Packet = (ICMP6) ipv6Packet.getPayload();
                    selectorBuilder.matchIPProtocol(ipv6NextHeader)
                            .matchIcmpv6Type(icmp6Packet.getIcmpType())
                            .matchIcmpv6Code(icmp6Packet.getIcmpCode());
                }
            }
        }
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(portNumber)
                .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(flowPriority)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(flowTimeout)
                .add();

        flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(),
                                     forwardingObjective);
        forwardPacket(macMetrics);
        //
        // If packetOutOfppTable
        //  Send packet back to the OpenFlow pipeline to match installed flow
        // Else
        //  Send packet direction on the appropriate port
        //
        if (packetOutOfppTable) {
            packetOut(context, PortNumber.TABLE, macMetrics);
        } else {
            packetOut(context, portNumber, macMetrics);
        }
    }


    private class InternalTopologyListener implements TopologyListener {
        @Override
        public void event(TopologyEvent event) {
            List<Event> reasons = event.reasons();
            if (reasons != null) {
                reasons.forEach(re -> {
                    if (re instanceof LinkEvent) {
                        LinkEvent le = (LinkEvent) re;
                        if (le.type() == LinkEvent.Type.LINK_REMOVED) {
                            fixBlackhole(le.subject().src());
                        }
                    }
                });
            }
        }
    }

    private void fixBlackhole(ConnectPoint egress) {
        Set<FlowEntry> rules = getFlowRulesFrom(egress);
        Set<SrcDstPair> pairs = findSrcDstPairs(rules);

        Map<DeviceId, Set<Path>> srcPaths = new HashMap<>();

        for (SrcDstPair sd : pairs) {
            // get the edge deviceID for the src host
            Host srcHost = hostService.getHost(HostId.hostId(sd.src));
            Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
            if (srcHost != null && dstHost != null) {
                DeviceId srcId = srcHost.location().deviceId();
                DeviceId dstId = dstHost.location().deviceId();
                log.trace("SRC ID is " + srcId + ", DST ID is " + dstId);

                cleanFlowRules(sd, egress.deviceId());

                Set<Path> shortestPaths = srcPaths.get(srcId);
                if (shortestPaths == null) {
                    shortestPaths = topologyService.getPaths(topologyService.currentTopology(),
                            egress.deviceId(), srcId);
                    srcPaths.put(srcId, shortestPaths);
                }
                backTrackBadNodes(shortestPaths, dstId, sd);
            }
        }
    }

    // Backtracks from link down event to remove flows that lead to blackhole
    private void backTrackBadNodes(Set<Path> shortestPaths, DeviceId dstId, SrcDstPair sd) {
        for (Path p : shortestPaths) {
            List<Link> pathLinks = p.links();
            for (int i = 0; i < pathLinks.size(); i = i + 1) {
                Link curLink = pathLinks.get(i);
                DeviceId curDevice = curLink.src().deviceId();

                // skipping the first link because this link's src has already been pruned beforehand
                if (i != 0) {
                    cleanFlowRules(sd, curDevice);
                }

                Set<Path> pathsFromCurDevice =
                        topologyService.getPaths(topologyService.currentTopology(),
                                                 curDevice, dstId);
                if (pickForwardPathIfPossible(pathsFromCurDevice, curLink.src().port()) != null) {
                    break;
                } else {
                    if (i + 1 == pathLinks.size()) {
                        cleanFlowRules(sd, curLink.dst().deviceId());
                    }
                }
            }
        }
    }

    // Removes flow rules off specified device with specific SrcDstPair
    private void cleanFlowRules(SrcDstPair pair, DeviceId id) {
        log.trace("Searching for flow rules to remove from: " + id);
        log.trace("Removing flows w/ SRC=" + pair.src + ", DST=" + pair.dst);
        for (FlowEntry r : flowRuleService.getFlowEntries(id)) {

            log.info("flowid: " + r.id().toString());
            log.info("flowBytes: "  + r.bytes());
            

            boolean matchesSrc = false, matchesDst = false;
            for (Instruction i : r.treatment().allInstructions()) {
                if (i.type() == Instruction.Type.OUTPUT) {
                    // if the flow has matching src and dst
                    for (Criterion cr : r.selector().criteria()) {
                        if (cr.type() == Criterion.Type.ETH_DST) {
                            if (((EthCriterion) cr).mac().equals(pair.dst)) {
                                matchesDst = true;
                            }
                        } else if (cr.type() == Criterion.Type.ETH_SRC) {
                            if (((EthCriterion) cr).mac().equals(pair.src)) {
                                matchesSrc = true;
                            }
                        }
                    }
                }
            }
            if (matchesDst && matchesSrc) {
                log.trace("Removed flow rule from device: " + id);
                flowRuleService.removeFlowRules((FlowRule) r);
            }
        }

    }

    // Returns a set of src/dst MAC pairs extracted from the specified set of flow entries
    private Set<SrcDstPair> findSrcDstPairs(Set<FlowEntry> rules) {
        ImmutableSet.Builder<SrcDstPair> builder = ImmutableSet.builder();
        for (FlowEntry r : rules) {
            MacAddress src = null, dst = null;
            for (Criterion cr : r.selector().criteria()) {
                if (cr.type() == Criterion.Type.ETH_DST) {
                    dst = ((EthCriterion) cr).mac();
                } else if (cr.type() == Criterion.Type.ETH_SRC) {
                    src = ((EthCriterion) cr).mac();
                }
            }
            builder.add(new SrcDstPair(src, dst));
        }
        return builder.build();
    }

    private ReactiveForwardMetrics createCounter(MacAddress macAddress) {
        ReactiveForwardMetrics macMetrics = null;
        if (recordMetrics) {
            macMetrics = metrics.compute(macAddress, (key, existingValue) -> {
                if (existingValue == null) {
                    return new ReactiveForwardMetrics(0L, 0L, 0L, 0L, macAddress);
                } else {
                    return existingValue;
                }
            });
        }
        return macMetrics;
    }

    private void  forwardPacket(ReactiveForwardMetrics macmetrics) {
        if (recordMetrics) {
            macmetrics.incrementForwardedPacket();
            metrics.put(macmetrics.getMacAddress(), macmetrics);
        }
    }

    private void inPacket(ReactiveForwardMetrics macmetrics) {
        if (recordMetrics) {
            macmetrics.incrementInPacket();
            metrics.put(macmetrics.getMacAddress(), macmetrics);
        }
    }

    private void replyPacket(ReactiveForwardMetrics macmetrics) {
        if (recordMetrics) {
            macmetrics.incremnetReplyPacket();
            metrics.put(macmetrics.getMacAddress(), macmetrics);
        }
    }

    private void droppedPacket(ReactiveForwardMetrics macmetrics) {
        if (recordMetrics) {
            macmetrics.incrementDroppedPacket();
            metrics.put(macmetrics.getMacAddress(), macmetrics);
        }
    }

    public EventuallyConsistentMap<MacAddress, ReactiveForwardMetrics> getMacAddress() {
        return metrics;
    }

    public void printMetric(MacAddress mac) {
        System.out.println("-----------------------------------------------------------------------------------------");
        System.out.println(" MACADDRESS \t\t\t\t\t\t Metrics");
        if (mac != null) {
            System.out.println(" " + mac + " \t\t\t " + metrics.get(mac));
        } else {
            for (MacAddress key : metrics.keySet()) {
                System.out.println(" " + key + " \t\t\t " + metrics.get(key));
            }
        }
    }

    private Set<FlowEntry> getFlowRulesFrom(ConnectPoint egress) {
        ImmutableSet.Builder<FlowEntry> builder = ImmutableSet.builder();
        flowRuleService.getFlowEntries(egress.deviceId()).forEach(r -> {
            if (r.appId() == appId.id()) {
                r.treatment().allInstructions().forEach(i -> {
                    if (i.type() == Instruction.Type.OUTPUT) {
                        if (((Instructions.OutputInstruction) i).port().equals(egress.port())) {
                            builder.add(r);
                        }
                    }
                });
            }
        });

        return builder.build();
    }

    // Wrapper class for a source and destination pair of MAC addresses
    private final class SrcDstPair {
        final MacAddress src;
        final MacAddress dst;

        private SrcDstPair(MacAddress src, MacAddress dst) {
            this.src = src;
            this.dst = dst;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SrcDstPair that = (SrcDstPair) o;
            return Objects.equals(src, that.src) &&
                    Objects.equals(dst, that.dst);
        }

        @Override
        public int hashCode() {
            return Objects.hash(src, dst);
        }
    }
}
