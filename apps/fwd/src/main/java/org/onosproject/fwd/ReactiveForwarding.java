/*
 * Copyright 2014-present Open Networking Foundation
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
//apache 的OSGi框架felix
import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;

//onlab提供的网络数据包的类
import org.onlab.packet.Ethernet;
import org.onlab.packet.ICMP;
import org.onlab.packet.ICMP6;
import org.onlab.packet.IPv4;
import org.onlab.packet.IPv6;
import org.onlab.packet.Ip4Prefix;
import org.onlab.packet.Ip6Prefix;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TCP;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onlab.packet.VlanId;

import org.onlab.util.KryoNamespace;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.event.Event;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.onosproject.net.PortNumber;
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
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.topology.TopologyEvent;
import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;

//onos存储相关的类
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.MultiValuedTimestamp;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.WallClockTimestamp;

//OSGi定义的组件的上下文环境
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;

import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
//导入的静态方法
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.slf4j.LoggerFactory.getLogger;

//
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
import java.io.*;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang.StringUtils;
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
import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Sample reactive forwarding application.
 * 被动转发应用样例
 */
//声明此类作为一个组件来激活，并且强制立即激活
@Component(immediate = true)
@Service(value = ReactiveForwarding.class)
public class ReactiveForwarding {

    private static ConcurrentHashMap<String, Integer> srcDstMacEcmp = new ConcurrentHashMap<>();

    //默认超时时间
    private static final int DEFAULT_TIMEOUT = 10;
    //默认优先级
    private static final int DEFAULT_PRIORITY = 10;

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;
    //将服务标记为应用程序所依赖的服务，应用程序激活前，保证必须有一个服务的实例被加载
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


    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StatisticService statisticService;
    //本类的一个内部私有类
    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    private  EventuallyConsistentMap<MacAddress, ReactiveForwardMetrics> metrics;

    private ApplicationId appId;

    //property注解定义组件可以通过ComponentContext().getProperties()得到的属性
    //只转发packet-out消息，默认为假
    @Property(name = "packetOutOnly", boolValue = false,
            label = "Enable packet-out only forwarding; default is false")
    private boolean packetOutOnly = false;
    //第一个数据包使用OFPP_TABLE端口转发，而不是使用真实的端口，默认为假
    @Property(name = "packetOutOfppTable", boolValue = false,
            label = "Enable first packet forwarding using OFPP_TABLE port " +
                    "instead of PacketOut with actual port; default is false")
    private boolean packetOutOfppTable = false;

    //配置安装的流规则的Flow Timeout,默认是10s
    @Property(name = "flowTimeout", intValue = DEFAULT_TIMEOUT,
            label = "Configure Flow Timeout for installed flow rules; " +
                    "default is 10 sec")
    private int flowTimeout = DEFAULT_TIMEOUT;

    //配置安装的流规则的优先级，默认是10,最大的优先级
    @Property(name = "flowPriority", intValue = DEFAULT_PRIORITY,
            label = "Configure Flow Priority for installed flow rules; " +
                    "default is 10")
    private int flowPriority = DEFAULT_PRIORITY;

    //开启IPV6转发，默认为假
    @Property(name = "ipv6Forwarding", boolValue = false,
            label = "Enable IPv6 forwarding; default is false")
    private boolean ipv6Forwarding = false;

    //只匹配目的mac地址，默认为假
    @Property(name = "matchDstMacOnly", boolValue = false,
            label = "Enable matching Dst Mac Only; default is false")
    private boolean matchDstMacOnly = false;

    //匹配Vlan ID,默认为假
    @Property(name = "matchVlanId", boolValue = false,
            label = "Enable matching Vlan ID; default is false")
    private boolean matchVlanId = false;

    //匹配IPv4地址，默认为假
    @Property(name = "matchIpv4Address", boolValue = false,
            label = "Enable matching IPv4 Addresses; default is false")
    private boolean matchIpv4Address = false;

    //匹配ipv4的DSCP和ENC，默认为假
    @Property(name = "matchIpv4Dscp", boolValue = false,
            label = "Enable matching IPv4 DSCP and ECN; default is false")
    private boolean matchIpv4Dscp = false;
    //匹配Ipv6的地址，默认为假
    @Property(name = "matchIpv6Address", boolValue = false,
            label = "Enable matching IPv6 Addresses; default is false")
    private boolean matchIpv6Address = false;

    //匹配IPv6流标签，默认为假
    @Property(name = "matchIpv6FlowLabel", boolValue = false,
            label = "Enable matching IPv6 FlowLabel; default is false")
    private boolean matchIpv6FlowLabel = false;

    //匹配TCP，DUP端口号，默认为假
    @Property(name = "matchTcpUdpPorts", boolValue = false,
            label = "Enable matching TCP/UDP ports; default is false")
    private boolean matchTcpUdpPorts = false;

    //匹配ICMPv4和ICMPv6字段，默认为假
    @Property(name = "matchIcmpFields", boolValue = false,
            label = "Enable matching ICMPv4 and ICMPv6 fields; " +
                    "default is false")
    private boolean matchIcmpFields = false;

    //忽略（不转发）IP4多路广播，默认为假
    @Property(name = "ignoreIPv4Multicast", boolValue = false,
            label = "Ignore (do not forward) IPv4 multicast packets; default is false")
    private boolean ignoreIpv4McastPackets = false;

    //记录被动转发的度量，默认为假
    @Property(name = "recordMetrics", boolValue = false,
            label = "Enable record metrics for reactive forwarding")
    private boolean recordMetrics = false;

    //拓扑监听器
    private final TopologyListener topologyListener = new InternalTopologyListener();
    //java的线程执行接口
    private ExecutorService blackHoleExecutor;

    //OSGi的组件启动时自动调用的方法，
    @Activate
    public void activate(ComponentContext context) {
        //Kryo是一个快速序列化，反序列化的工具。
        //定义名称空间
        KryoNamespace.Builder metricSerializer = KryoNamespace.newBuilder()
                .register(KryoNamespaces.API)
                .register(ReactiveForwardMetrics.class)
                .register(MultiValuedTimestamp.class);
        //根据名称空间和时间戳生成存储服务，
        metrics =  storageService.<MacAddress, ReactiveForwardMetrics> eventuallyConsistentMapBuilder()
                .withName("metrics-fwd")
                .withSerializer(metricSerializer)
                .withTimestampProvider((key, metricsData) -> new
                        MultiValuedTimestamp<>(new WallClockTimestamp(), System.nanoTime()))
                .build();
        //java.util.concurrent，黑洞执行类
        blackHoleExecutor = newSingleThreadExecutor(groupedThreads("onos/app/fwd",
                                                                   "black-hole-fixer",
                                                                   log));

        cfgService.registerProperties(getClass());//ComponentConfigService
        appId = coreService.registerApplication("org.onosproject.fwd");

        packetService.addProcessor(processor, PacketProcessor.director(2));
        topologyService.addListener(topologyListener);
        readComponentConfiguration(context);
        requestIntercepts();//截取请求

        log.info("Started", appId.id());
    }

    @Deactivate
    public void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        withdrawIntercepts();//
        flowRuleService.removeFlowRulesById(appId);
        packetService.removeProcessor(processor);
        topologyService.removeListener(topologyListener);
        blackHoleExecutor.shutdown();
        blackHoleExecutor = null;
        processor = null;
        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        readComponentConfiguration(context);
        requestIntercepts();//截取请求
    }

    /**
     * Request packet in via packet service.
     * 通过数据包服务请求获得数据包，在inactivate方法中被调用
     */
    private void requestIntercepts() {
        //构建流量选择器
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        //选择以太网类型
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
     * 取消数据包服务的拦截请求，在deactivate方法中被调用
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
     *从component配置上下文中提取属性,在invactivate方法中被调用
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
     * 负责在他们路径上的数据包的处理
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

                vportCurSpeed = statisticService.vportload(connectPoint).rate() * 8 ;
            }
            return vportCurSpeed;
        }

        /**
         * 这个方法是用来设定link最大的负载能达到多少
         * @param connectPoint
         * @return
         */

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
//            log.info("aaaaa : " + getVportLoadCapability(srcConnectPoint));
//            log.info("bbbbb : " + getVportLoadCapability(dstConnectPoint));
            return Long.max(getVportLoadCapability(srcConnectPoint), getVportLoadCapability(dstConnectPoint));
        }

        /**
         *
         * @param srcConnectPoint
         * @param dstConnectPoint
         * @return
         */
        private long getIntraLinkMaxBw(ConnectPoint srcConnectPoint, ConnectPoint dstConnectPoint) {
            //return Long.min(getVportMaxCapability(srcConnectPoint), getVportMaxCapability(dstConnectPoint));
            return 100 * 1000000;
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
        public void process(PacketContext context) {
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
             *
             */
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();//从数据包中解析出以太网的信息
            String curProtocol = String.valueOf(ethPkt.getEtherType());

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
            inPacket(macMetrics);//增加packet_in数据包的计数

            // Bail if this is deemed to be a control packet.如果这被认为是一个控制包，释放。
            if (isControlPacket(ethPkt)) {
                droppedPacket(macMetrics);//只是增加丢弃数据包的计数
                return;
            }

            // Skip IPv6 multicast packet when IPv6 forward is disabled.
            if (!ipv6Forwarding && isIpv6Multicast(ethPkt)) {
                droppedPacket(macMetrics);
                return;
            }
            //HostId包含Mac和VlanId
            // HostId id = HostId.hostId(ethPkt.getDestinationMAC());//得到目的主机的mac
            HostId id_src = HostId.hostId(macAddress);
            MacAddress macAddress1 = ethPkt.getDestinationMAC();
            HostId id = HostId.hostId(macAddress1);

            // Do not process LLDP MAC address in any way.不处理LLDP的mac地址
            if (id.mac().isLldp()) {
                droppedPacket(macMetrics);
                return;
            }

            // Do not process IPv4 multicast packets, let mfwd handle them，不处理IPv4多播数据包
            if (ignoreIpv4McastPackets && ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                if (id.mac().isMulticast()) {
                    return;
                }
            }
            Host src = hostService.getHost(id_src);
            // Do we know who this is for? If not, flood and bail.如果主机服务中没有这个主机，flood然后丢弃
            Host dst = hostService.getHost(id);
            if (dst == null) {
                flood(context, macMetrics);
                return;
            }



            // Are we on an edge switch that our destination is on? If so,
            // simply forward out to the destination and bail.

//            log.info("---------------------------　每一次选路信息　------------------------");
//            log.info("源host:"+ id_src.mac().toString());
//            log.info("目的host:"+ id.mac().toString());

            // Are we on an edge switch that our destination is on? If so,
            // simply forward out to the destination and bail.如果是目的主机链接的边缘交换机发过来的，简单安装流规则，然后释放。
            if (pkt.receivedFrom().deviceId().equals(dst.location().deviceId())) {
                if (!context.inPacket().receivedFrom().port().equals(dst.location().port())) {
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

            /**
             * 根据 LinksResult中每条link，算出它的maxBandwidthCapacity
             * 然后持久化到文件中
             */

            //这里的size是64,是双向的
            //log.info("allLinks: LinksResult.size(): " + LinksResult.size());

            //packetIn
//            DeviceId dstDeviceId = dst.location().deviceId();
//            DeviceId srcDeviceId = src.location().deviceId();

            // Otherwise, get a set of paths that lead from here to the
            // destination edge switch.如果不是边缘交换机，则通过拓扑服务，得到从这里到达目地边缘交换机的路径集合。
            Set<Path> paths =
                    topologyService.getPaths(topologyService.currentTopology(),
                                             pkt.receivedFrom().deviceId(),
                                             dst.location().deviceId());



            if (paths.isEmpty()) {
                // If there are no paths, flood and bail.//如果得到的路径为空，则flood然后释放
                flood(context, macMetrics);
                return;
            }


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
            //log.info("allLinks: LinksResult.size(): " + LinksResult.size());

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
             * 从而等价于遍历所有但凡交换机流表项目有的流
             * 然后轮询遍历所有流的源目mac是否和packetin的流相同
             * 如果相同则取此流的流速分析
             * for example:
             * A->B->C, C产生packetIn，应该取B中flow的流速判断是不是大流，因为此时C的流表项中根本就没有该匹配了多少字节数，而B中有，
             * 所以用B的流出速度模拟C的流入速度
             * 若流速较大则为大流
             * 否则，则为小流
             *
             * 大流选路采用PLLB算法（避免大流汇聚）
             * 小流选路采用hash均分的方式
             */

            ConnectPoint curSwitchConnectionPoint = pkt.receivedFrom();
            //log.info("=============================================");

//            if(isFlowFound == true){
//                log.info("找到packetIn所对应的流，源mac为" + macAddress.toString() + ", 目的mac为" + macAddress1.toString() + ", FlowId为" + ObjectFlowId);
//            }

            /**
             * 选择最优路径
             * 来自trustCom: FESM
             */

            /**
             * PathsDecision_FESM
             *
             */

            /**
             * 0 fesm
             * 1 pllb
             * 2 dijkstra
             * 3 ecmp
             */

            Set<Path> Paths_Choise = Sets.newHashSet();

            int choise = 0;
            if(choise == 0){
                Set<Path> Paths_FESM = PathsDecision_FESM(paths, pkt.receivedFrom().deviceId(),
                        dst.location().deviceId(),
                        src.location().deviceId(),
                        LinksResult);
                Paths_Choise = Paths_FESM;
            }else if(choise == 1){
                ConcurrentHashMap<String, String> FlowIdFlowRate = statisticService.getFlowIdFlowRate();

                //ConcurrentHashMap<String, String> FlowId_FlowRate = new ConcurrentHashMap<>();


                boolean isBigFlow = true;
                //init with a small number
                Double curFlowSpeed1 = 10.0;
                //curFlowSpeed1 = MatchAndComputeThisFlowRate(FlowId_FlowRate, macAddress, macAddress1, LinksResult, curSwitchConnectionPoint);
//                isBigFlow = ifBigFlowProcess(FlowId_FlowRate, macAddress, macAddress1, LinksResult, curSwitchConnectionPoint);

                Set<Path> Paths_PLLB = PathsDecision_PLLB(curFlowSpeed1, isBigFlow, paths, pkt.receivedFrom().deviceId(),
                        dst.location().deviceId(),
                        src.location().deviceId(),
                        LinksResult);
                Paths_Choise = Paths_PLLB;
            }
            else if(choise == 2){

                Set<Path> paths_ecmp = PathsDecision_ECMP(paths,
                        src.location().deviceId().toString(), dst.location().deviceId().toString(),
                        src.location().port().toString(), dst.location().port().toString(), curProtocol);
                Paths_Choise = paths_ecmp;
            }



//
//            for(int k4=0; k4 < 1; k4++){
//                log.info("Paths_Choise.size() : " + Paths_Choise.size());
//            }



            /**
             *
             * 根据负载均衡策略模块的结果
             * 执行流表下发模块
             *
             */

            // Otherwise, pick a path that does not lead back to where we
            // came from; if no such path, flood and bail.
            /**
             * 第一個參數：
             *
             * Paths_PLLB : PLLB
             * Paths_FESM : FESM
             */
//            Set<Path> mypllb_pathset = Paths_PLLB != null ? Paths_PLLB : paths_DijkStra;
//            Set<Path> myfesm_pathset = Paths_FESM != null ? Paths_FESM : paths_DijkStra;

            // Otherwise, pick a path that does not lead back to where we
            // came from; if no such path, flood and bail.如果存在路径的话，从给定集合中选择一条不返回指定端口的路径。
//            Path path = Paths_Choise.size() == 0 ? pickForwardPathIfPossible(paths, pkt.receivedFrom().port())
//                    : pickForwardPathIfPossible(Paths_Choise, pkt.receivedFrom().port());

            Path path = pickForwardPathIfPossible(paths, pkt.receivedFrom().port());

//            Path path = pickForwardPathIfPossible(paths, pkt.receivedFrom().port());

            if (path == null) {
                log.warn("Don't know where to go from here {} for {} -> {}",
                        pkt.receivedFrom(), ethPkt.getSourceMAC(), ethPkt.getDestinationMAC());
                flood(context, macMetrics);
                return;
            }

            // Otherwise forward and be done with it.最后安装流规则
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
             * 我已經寫在統計模塊了。。。。
             */

            //log.info("=====================================================================================================================================");

        }

        private  Set<Path> PathsDecision_ECMP(Set<Path> paths, String srcMac, String dstMac, String srcPort, String dstPort,String protocol){
            Set<Path> result = new HashSet<>();
            String srcDstMac = srcMac.trim() + dstMac.trim();
            int indexPath = 0;
            if(srcDstMacEcmp.get(srcDstMac) == null){
                srcDstMacEcmp.put(srcDstMac, indexPath);
            }else{
                indexPath = (srcDstMacEcmp.get(srcDstMac) + 1) % paths.size();
                srcDstMacEcmp.put(srcDstMac, indexPath);
            }

            int j=0;
            for(Path path : paths){
                if(j == indexPath){
                    result.add(path);
                }
            }
            return result;
        }




        private  Double MatchAndComputeThisFlowRate(ConcurrentHashMap<String, String> flowIdFlowRateMap, MacAddress macAddress, MacAddress macAddress1, LinkedList<Link> LinksResult, ConnectPoint curSwitchConnectionPoint) {
            boolean result = true;

            //body
            String resultflowRate = "";

            String ObjectFlowId = "";
            //init with a very small number
            String ObjectFlowSpeed = "10b/s";
            for(Link link : LinksResult){

                DeviceId deviceId_src = link.src().deviceId();
                DeviceId deviceId_dst = link.dst().deviceId();





                for (FlowEntry r : flowRuleService.getFlowEntries(deviceId_src)) {
                    //log.info(r.deviceId()+","+deviceId_src+","+deviceId_dst);
                    //测试结果：r.deviceId() == deviceId_src
                    boolean matchesSrc = false, matchesDst = false;
                    boolean matchSrcAndDst = false;
                    /**
                     * 这条link的src交换机是否有目标流
                     */
                    matchSrcAndDst = ismatchSrcAndDst(r, matchesSrc, matchesDst, macAddress, macAddress1);

                    /**
                     * macAddress: ethPkt.getSourceMAC()
                     * macAddress1 = ethPkt.getDestinationMAC();
                     *
                     * 计算该流流入C的速度：
                     * for example:
                     * A->B->C, C产生packetIn，应该取B中flow的流速判断是不是大流，因为此时C的流表项中根本就没有该匹配了多少字节数，而B中有，
                     * 所以用B的流出速度模拟C的流入速度
                     * 若流速较大则为大流
                     *
                     * 找到B->C这个link的计算方法：
                     * matchSrcAndDst是判断link是否有目标流，但不一定就是B->C这条link
                     * 找到这个link中的src有目标流，并且link的dst应该是curSwitch
                     *
                     * && link.dst().deviceId().toString().equals(curSwitchConnectionPoint.deviceId().toString())
                     *
                     */
                    //log.info(r.toString());
                    //&& link.dst().deviceId().toString().equals(curSwitchConnectionPoint.deviceId().toString())
                    if (matchSrcAndDst == true ) {
                        //log.info("找到packetIn所对应的流，源mac为" + macAddress.toString() + ", 目的mac为" + macAddress1.toString() + ", FlowId为" + r.id().toString());
                        ObjectFlowId = r.id().toString();
                        //log.info(r.toString());

                        //read file update by monitor module
                        String flowRateOutOfB2 = getflowRateFromMonitorModule2(ObjectFlowId, flowIdFlowRateMap);
//                        log.info("matchSrcAndDst == true");
//                        log.info("flowRateOutOfB2: "+ flowRateOutOfB2);
                        resultflowRate = flowRateOutOfB2;

                    }
                }

            }



            String flowSpeedEtl;
            /**
             * if resultflowRate is null : means the big flow monitor function is not init by the website
             * using the solution track same as big flow
             */
            if(resultflowRate == null ||  resultflowRate.equals("")){
                resultflowRate = "10b/s";
            }
            flowSpeedEtl = resultflowRate.substring(0, resultflowRate.indexOf("b"));

            Double resultFlowSpeed = Double.valueOf(flowSpeedEtl);
            return resultFlowSpeed;
        }



        private  boolean ifBigFlowProcess(ConcurrentHashMap<String, String> flowIdFlowRate, MacAddress macAddress, MacAddress macAddress1, LinkedList<Link> LinksResult, ConnectPoint curSwitchConnectionPoint) {
            boolean result = true;

            //body
            String resultflowRate = "";

            String ObjectFlowId = "";
            //init with a very small number
            String ObjectFlowSpeed = "10b/s";
            for(Link link : LinksResult){

                DeviceId deviceId_src = link.src().deviceId();
                DeviceId deviceId_dst = link.dst().deviceId();





                for (FlowEntry r : flowRuleService.getFlowEntries(deviceId_src)) {
                    //log.info(r.deviceId()+","+deviceId_src+","+deviceId_dst);
                    //测试结果：r.deviceId() == deviceId_src
                    boolean matchesSrc = false, matchesDst = false;
                    boolean matchSrcAndDst = false;
                    /**
                     * 这条link的src交换机是否有目标流
                     */
                    matchSrcAndDst = ismatchSrcAndDst(r, matchesSrc, matchesDst, macAddress, macAddress1);

                    /**
                     * macAddress: ethPkt.getSourceMAC()
                     * macAddress1 = ethPkt.getDestinationMAC();
                     *
                     * 计算该流流入C的速度：
                     * for example:
                     * A->B->C, C产生packetIn，应该取B中flow的流速判断是不是大流，因为此时C的流表项中根本就没有该匹配了多少字节数，而B中有，
                     * 所以用B的流出速度模拟C的流入速度
                     * 若流速较大则为大流
                     *
                     * 找到B->C这个link的计算方法：
                     * matchSrcAndDst是判断link是否有目标流，但不一定就是B->C这条link
                     * 找到这个link中的src有目标流，并且link的dst应该是curSwitch
                     *
                     * && link.dst().deviceId().toString().equals(curSwitchConnectionPoint.deviceId().toString())
                     *
                     */
                    //log.info(r.toString());
                    //&& link.dst().deviceId().toString().equals(curSwitchConnectionPoint.deviceId().toString())
                    if (matchSrcAndDst == true ) {
                        //log.info("找到packetIn所对应的流，源mac为" + macAddress.toString() + ", 目的mac为" + macAddress1.toString() + ", FlowId为" + r.id().toString());
                        ObjectFlowId = r.id().toString();
                        //log.info(r.toString());
                        //计算流速
                        //long flowRateOutOfB = r.bytes() / r.life();//约等于flowRateInOfC


                        //read file update by monitor module
                        String flowRateOutOfB = getflowRateFromMonitorModule3(ObjectFlowId, flowIdFlowRate);
                        resultflowRate = flowRateOutOfB;


                        //ObjectFlowSpeed赋值

                        //break;
                    }
                }

            }
            //大小流评判标准
            //1M/s
            Double Strench = 9.0;
            /**
             * small flow : 1b-100b
             */
            String flowSpeedEtl;
            /**
             * if resultflowRate is null : means the big flow monitor function is not init by the website
             * using the solution track same as big flow
             */
            if(resultflowRate == null ||  resultflowRate.equals("")){
                return true;
            }
            flowSpeedEtl = resultflowRate.substring(0, resultflowRate.indexOf("b"));

            //log.info("------" + resultflowRate);
            if(Double.valueOf(flowSpeedEtl) < Strench && Double.valueOf(flowSpeedEtl) > 1.0){
                result = false;
            }

            //result
            return result;
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


        public String getflowRateFromMonitorModule3(String ObjectFlowId, ConcurrentHashMap<String, String> curSwitch_deviceId){
            //如果是沒有這個key就append，有這個key就更改
            String resultFLowRate = "10b/s";
            for(Map.Entry<String, String> entry : curSwitch_deviceId.entrySet()){
                String entrykey = entry.getKey();
                String entryValue = entry.getValue();
                for(int i=0; i< 3; i++){
                    log.info("map.size: " + curSwitch_deviceId.size());
                    log.info(entrykey);
                    log.info(entryValue);
                }
                if(entrykey.contains(ObjectFlowId)){
                    //log.info("match...");
                    resultFLowRate = entryValue;
                }
            }


            return resultFLowRate;
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
                    //log.info("match...");
                    resultFLowRate = entryValue;
                }
            }


            return resultFLowRate;
        }

        public String getflowRateFromMonitorModule(File csvFile, String ObjectFlowId, String curSwitch_deviceId){
            //如果是沒有這個key就append，有這個key就更改
            String resultFLowRate = "10b/s";
            try {
                //read
                FileInputStream fis = new FileInputStream(csvFile);
                BufferedReader br = new BufferedReader(new InputStreamReader(fis));
                String line = null;
                int ifhavingkey = 0;
                int rowhavingkey = 0;
                while((line = br.readLine()) != null){
                    if(line.contains(ObjectFlowId)){
                        ifhavingkey = 1;
                        //log.info("找到flowrate===判斷大小流模塊");
                        //get the flow rate
                        String[] result = StringUtils.split(line, ",");
                        String flowRate = result[1];
                        if(!flowRate.equals("0b/s")){
                            resultFLowRate = flowRate;
                        }

                    }
                }
                fis.close();
                br.close();
                if(ifhavingkey == 0){
                    //log.info("沒有找到flowrate===判斷大小流模塊");
                }


            } catch (Exception e) {
                e.printStackTrace();
            }

            return resultFLowRate;
        }

        private boolean ismatchSrcAndDst(FlowEntry r, boolean matchesSrc, boolean matchesDst, MacAddress macAddress, MacAddress macAddress1) {
            boolean result = false;
            for (Instruction i : r.treatment().allInstructions()) {
                if (i.type() == Instruction.Type.OUTPUT) {
                    // if the flow has matching src and dst
                    for (Criterion cr : r.selector().criteria()) {
                        if (cr.type() == Criterion.Type.ETH_DST) {
                            if (((EthCriterion) cr).mac().equals(macAddress1)) {
                                matchesDst = true;
                            }
                        } else if (cr.type() == Criterion.Type.ETH_SRC) {
                            if (((EthCriterion) cr).mac().equals(macAddress)) {
                                matchesSrc = true;
                            }
                        }
                    }
                }
            }

            if (matchesDst && matchesSrc) {
                result = true;
            }
            return result;
        }


        private  Set<Path> PathsDecision_PLLB(Double curFlowSpeed, boolean isBigFlow, Set<Path> paths, DeviceId recvId, DeviceId dstid, DeviceId srcId, LinkedList<Link> LinksResult) {

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
                Double flowbw = 10.0;
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
                        long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
                        sb.append(IntraLinkRestBw+"|");
                    }
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

//                    long IntraLinkMaxBw = getIntraLinkMaxBw(link.src(), link.dst()); //bps
//                    long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
//                    double IntraLinkCapability = getIntraLinkCapability(link.src(), link.dst());

                        arrayList.add((double)IntraLinkLoadBw);
                        allLinkOfPath_BandWidth += IntraLinkLoadBw;
                        long IntraLinkRestBw = getIntraLinkRestBw(link.src(), link.dst());
                        if(flowbw > IntraLinkRestBw){
                            ifPathCanChoose = 0;
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
                         * the choke point link means(the min restBandWidth)
                         * b denotes the byte count of the critical
                         * r denotes the forwarding rate
                         */
                        if(IntraLinkRestBw < ChokePointRestBandWidth){
                            //choise the choke point
                            ChokeLinkPassbytes = Math.max(bytesSent_src, bytesReceived_dst);
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
                    //feature_preAddFlowToThisPath_AllStandardDeviation * 0
                    double resultScore = feature_ChokePointRestBandWidth * 1   + feature_pathMeanRestBw * 1;
                    //double resultScore = (ChokePointRestBandWidth*0.4 + pathMeanRestBw*0.2 + 2)*10/(0.4*preAddFlowToThisPath_AllStandardDeviation + 1);
                    //log.info("resultScore: "+ resultScore);

                    //there are some links not satisfy the flow bw
                    if(ifPathCanChoose == 0){
                        // not choose this path
                        //resultScore = 0;
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


        private  Set<Path> PathsDecision_FESM(Set<Path> paths, DeviceId deviceId, DeviceId id, DeviceId deviceId1, LinkedList<Link> LinksResult) {

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

    // Indicates whether this is a control packet, e.g. LLDP, BDDP，判断数据包是否是一个控制数据包
    private boolean isControlPacket(Ethernet eth) {
        short type = eth.getEtherType();
        return type == Ethernet.TYPE_LLDP || type == Ethernet.TYPE_BSN;
    }

    // Indicated whether this is an IPv6 multicast packet.判断是否是IP6广播数据包
    private boolean isIpv6Multicast(Ethernet eth) {
        return eth.getEtherType() == Ethernet.TYPE_IPV6 && eth.isMulticast();
    }

    // Selects a path from the given set that does not lead back to the
    // specified port if possible.如果可能的话，从给定集合中选择一条不返回指定端口的路径。
    private Path pickForwardPathIfPossible(Set<Path> paths, PortNumber notToPort) {
        for (Path path : paths) {
//            log.info(path.src().port().toString());
//            log.info(notToPort.toString());
            if (!path.src().port().equals(notToPort)) {
                return path;
            }
        }
        return null;
    }

    // Floods the specified packet if permissible.如果允许的话，对该数据包泛洪
    private void flood(PacketContext context, ReactiveForwardMetrics macMetrics) {
        if (topologyService.isBroadcastPoint(topologyService.currentTopology(),
                                             context.inPacket().receivedFrom())) {
            packetOut(context, PortNumber.FLOOD, macMetrics);
        } else {
            context.block();
        }
    }

    // Sends a packet out the specified port.从指定的端口发送数据包,由数据包的上下文发送数据包
    private void packetOut(PacketContext context, PortNumber portNumber, ReactiveForwardMetrics macMetrics) {
        replyPacket(macMetrics);//只是一个简单的计数
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

    // Install a rule forwarding the packet to the specified port.安装一条流转发数据包到指定的端口
    private void installRule(PacketContext context, PortNumber portNumber, ReactiveForwardMetrics macMetrics) {
        //
        // We don't support (yet) buffer IDs in the Flow Service so
        // packet out first.我们现在还不支持流服务的缓存ID，所以，先转发出去
        //
        Ethernet inPkt = context.inPacket().parsed();
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

        // If PacketOutOnly or ARP packet than forward directly to output port
        // 如果是PacketOutOnly或者ARP数据包，直接转发到输出端口
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

        //流量处理器，负责添加流表中的指令
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(portNumber)
                .build();

        //构建流规则对象，输入流量处理器treatement，selectorBuilder,优先级，appid,流的持续时间
        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(flowPriority)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(flowTimeout)
                .add();


        //通过流对象服务，转发出转发对象。在指定的设备上安装流规则
        flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(),
                                     forwardingObjective);
        forwardPacket(macMetrics);//增加转发数据包的计数
        //
        // If packetOutOfppTable
        //  Send packet back to the OpenFlow pipeline to match installed flow
        // Else
        //  Send packet direction on the appropriate port
        //如果packetOutOfppTable为真，那么将数据包转发到交换机的TABLE端口，流水线的开始。
        if (packetOutOfppTable) {
            packetOut(context, PortNumber.TABLE, macMetrics);
        } else {
            packetOut(context, portNumber, macMetrics);
        }
    }

    //拓扑监听器的实现类
    private class InternalTopologyListener implements TopologyListener {
        @Override
        public void event(TopologyEvent event) {
            List<Event> reasons = event.reasons();
            if (reasons != null) {
                reasons.forEach(re -> {
                    if (re instanceof LinkEvent) {
                        LinkEvent le = (LinkEvent) re;
                        //如果出现了链路删除并且黑洞执行不是空，那么
                        if (le.type() == LinkEvent.Type.LINK_REMOVED && blackHoleExecutor != null) {
                            blackHoleExecutor.submit(() -> fixBlackhole(le.subject().src()));
                        }
                    }
                });
            }
        }
    }

    //修复黑洞？？
    private void fixBlackhole(ConnectPoint egress) {
        Set<FlowEntry> rules = getFlowRulesFrom(egress);//从指定的点获得所有的流规则
        Set<SrcDstPair> pairs = findSrcDstPairs(rules);

        Map<DeviceId, Set<Path>> srcPaths = new HashMap<>();

        for (SrcDstPair sd : pairs) {
            // get the edge deviceID for the src host
            Host srcHost = hostService.getHost(HostId.hostId(sd.src));
            Host dstHost = hostService.getHost(HostId.hostId(sd.dst));
            if (srcHost != null && dstHost != null) {
                DeviceId srcId = srcHost.location().deviceId();
                DeviceId dstId = dstHost.location().deviceId();
                log.trace("SRC ID is {}, DST ID is {}", srcId, dstId);

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
        log.trace("Searching for flow rules to remove from: {}", id);
        log.trace("Removing flows w/ SRC={}, DST={}", pair.src, pair.dst);
        for (FlowEntry r : flowRuleService.getFlowEntries(id)) {
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
                log.trace("Removed flow rule from device: {}", id);
                flowRuleService.removeFlowRules((FlowRule) r);
            }
        }

    }

    // Returns a set of src/dst MAC pairs extracted from the specified set of flow entries
    //从指定的流表中找到所有的src/dst mac地址对
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

    //创造计数器
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

    //增加转发计数
    private void  forwardPacket(ReactiveForwardMetrics macmetrics) {
        if (recordMetrics) {
            macmetrics.incrementForwardedPacket();
            metrics.put(macmetrics.getMacAddress(), macmetrics);
        }
    }

    //增加收到的数据包的计数
    private void inPacket(ReactiveForwardMetrics macmetrics) {
        if (recordMetrics) {
            macmetrics.incrementInPacket();
            metrics.put(macmetrics.getMacAddress(), macmetrics);
        }
    }

    //增加回复的数据包的计数
    private void replyPacket(ReactiveForwardMetrics macmetrics) {
        if (recordMetrics) {
            macmetrics.incremnetReplyPacket();//增加返回数据包的计数
            metrics.put(macmetrics.getMacAddress(), macmetrics);
        }
    }

    //增加丢弃数据包的计数
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

    //从指定的链接点获得所有的流规则
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
    //源和目的MAC地址的包装类
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
