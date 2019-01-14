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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.*;
//apache 的OSGi框架felix
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
import org.onosproject.core.*;
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
import org.onosproject.store.primitives.resources.impl.AtomixAtomicCounterMapOperations;
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
import java.util.concurrent.Callable;
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
import sun.security.provider.MD5;
import sun.security.rsa.RSASignature;

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

    private static Map<String, Integer> srcDstMacEcmp = new ConcurrentHashMap<>();

    //默认超时时间
    private static final int DEFAULT_TIMEOUT = 5;
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

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PathChoiceItf pathChoiceItf;
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

    private static final double bwLevel = 100000;
    private static final long MAX_REST_BW = 100*1000000;

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


    private class ReactivePacketProcessor implements PacketProcessor {

        /**
         * @param context packet processing context
         */
        @Override
        public void process(PacketContext context) {

            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            String curProtocol = String.valueOf(ethPkt.getEtherType());

            if (ethPkt == null) {
                return;
            }

            MacAddress macAddress = ethPkt.getSourceMAC();
            ReactiveForwardMetrics macMetrics = null;
            macMetrics = createCounter(macAddress);
            inPacket(macMetrics);//增加packet_in数据包的计数

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
            HostId id_src = HostId.hostId(macAddress);
            MacAddress macAddress1 = ethPkt.getDestinationMAC();
            HostId id = HostId.hostId(macAddress1);

            // Do not process LLDP MAC address in any way.不处理LLDP的mac地址
            if (id.mac().isLldp()) {
                droppedPacket(macMetrics);
                return;
            }

            if (ignoreIpv4McastPackets && ethPkt.getEtherType() == Ethernet.TYPE_IPV4) {
                if (id.mac().isMulticast()) {
                    return;
                }
            }
            Host src = hostService.getHost(id_src);
            // 如果主机服务中没有这个主机，flood然后丢弃
            Host dst = hostService.getHost(id);
            if (dst == null) {
                flood(context, macMetrics);
                return;
            }

            if (pkt.receivedFrom().deviceId().equals(dst.location().deviceId())) {
                if (!context.inPacket().receivedFrom().port().equals(dst.location().port())) {
                    installRule(context, dst.location().port(), macMetrics);
                }
                return;
            }
            Double curFlowSpeed = getCurFlowSpeed();
            Set<Path> paths =
                    topologyService.getPaths(topologyService.currentTopology(),
                                             pkt.receivedFrom().deviceId(),
                                             dst.location().deviceId());

            if (paths.isEmpty()) {
                flood(context, macMetrics);
                return;
            }

            Set<Path> PathsChoise = Sets.newHashSet();

            int choise = 1;
            if(choise == 0){
                Set<Path> PathsFSEM = PathsDecisionFESM(paths, pkt.receivedFrom().port());
                PathsChoise = PathsFSEM;
            }else if(choise == 1){

                Set<Path> PathsMyDefined = PathsDecisionMyDefined(curFlowSpeed, paths, pkt.receivedFrom().port());
                PathsChoise = PathsMyDefined;
            }
            else if(choise == 2){
                FlowContext flowContext = new FlowContext(src, dst, curProtocol);
                Set<Path> pathsECMP = PathsDecisionECMP(paths, flowContext, pkt.receivedFrom().port());
                PathsChoise = pathsECMP;
            }

            // Otherwise, pick a path that does not lead back to where we
            // came from; if no such path, flood and bail.
            Path path = pickForwardPathIfPossible(PathsChoise, pkt.receivedFrom().port());
            if (path == null) {
                log.warn("Don't know where to go from here {} for {} -> {}",
                        pkt.receivedFrom(), ethPkt.getSourceMAC(), ethPkt.getDestinationMAC());
                flood(context, macMetrics);
                return;
            }

            installRule(context, path.src().port(), macMetrics);
        }


        private Double getCurFlowSpeed() {
            Map<String, String> FlowIdFlowRate = statisticService.getFlowIdFlowRate();
            //init with a small number
            Double curFlowSpeed = 0.0;
            //curFlowSpeed = MatchAndComputeThisFlowRate(FlowId_FlowRate, macAddress, macAddress1, LinksResult, curSwitchConnectionPoint);
            //isBigFlow = ifBigFlowProcess(FlowId_FlowRate, macAddress, macAddress1, LinksResult, curSwitchConnectionPoint);
            return  curFlowSpeed;
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



        private long getVportLoadCapability(ConnectPoint connectPoint) {
            long vportCurSpeed = 0;
            if(connectPoint != null && statisticService.vportload(connectPoint) != null){
                //rate : bytes/s result : b/s

                vportCurSpeed = statisticService.vportload(connectPoint).rate() * 8 ;
            }
            return vportCurSpeed;
        }


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
                //log.info("path i : " + indexOfPathInPaths);
                //log.info("links rest bw : " + sb.toString());
                pathIndexLinksRestBwOfPaths.put(indexOfPathInPaths, sb.toString());
                indexOfPathInPaths ++;

            }
            return pathIndexLinksRestBwOfPaths;
        }



        private  Set<Path> PathsDecisionMyDefined(Double curFlowSpeed, Set<Path> paths, PortNumber srcPort) {


            Set<Path> result = Sets.newHashSet();
            Map<Integer, Path> indexPath = Maps.newLinkedHashMap();
            Path finalPath = null;

            int i=0;
            double maxScore = 0.0;
            double flowbw = pathChoiceItf.getFlowBw(curFlowSpeed);

            /**
             * pre add the flowbw to path
             * compute the standard deviation of all link in all reachable path
             */
            Map<Path, Integer> pathIndexOfPaths = Maps.newHashMap();
            Map<Integer, String> pathIndexLinksRestBwOfPaths = getPathIndexLinksRestBwOfPaths(paths, pathIndexOfPaths);

            for(Path path : paths){

                Integer curPathIndex = pathIndexOfPaths.get(path);
                List<Double> otherPathLinksRestBw = pathChoiceItf.getOtherPathLinksRestBw(pathIndexOfPaths, curPathIndex, pathIndexLinksRestBwOfPaths);
                List<Double> allPathLinksRestBwAfterAddFlow = Lists.newArrayList(otherPathLinksRestBw);
                indexPath.put(i, path);

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
                    pathCanChooseFlag = pathChoiceItf.getPathCanChooseFlag(flowbw, IntraLinkLoadBw);

                    //pre add the flowBw to curPath
                    Double theAddRestBw = flowbw;
                    Double thisLinkResBw = Double.valueOf(IntraLinkLoadBw);
                    Double thisLinkResBwUpdate = getThisLinkResBwUpdate(thisLinkResBw, theAddRestBw);

                    allPathLinksRestBwAfterAddFlow.add(thisLinkResBwUpdate);
                    allLinkOfPathRestBandWidth += IntraLinkRestBw;
                    allBwRestAfterAddFlow += allBwRestAfterAddFlow;

                    if(IntraLinkRestBw < ChokePointRestBandWidth){
                        ChokePointRestBandWidth = IntraLinkRestBw;
                    }
                    j++;
                }

                double pathlinksSize = path.links().size();
                double pathMeanRestBw = allLinkOfPathRestBandWidth / pathlinksSize;

                int allPathLinkSize = allPathLinksRestBwAfterAddFlow.size();
                double sumLinksRestBwAfterAddFlow = pathChoiceItf.getSumLinksRestBwAfterAddFlow(allPathLinksRestBwAfterAddFlow);

                double meanLinksResBwAfterAdd = sumLinksRestBwAfterAddFlow / allPathLinkSize;
                double sum = pathChoiceItf.getSdRaw(allPathLinksRestBwAfterAddFlow, meanLinksResBwAfterAdd);
                double AllRestBWSdAfterPreAdd = Math.sqrt(sum)/allPathLinkSize;

                double fChokeLinkRestBw = (double)(Math.log((double)ChokePointRestBandWidth + 1));
                double fPathMeanRestBw = (double)(Math.log((double)pathMeanRestBw + 1));
                double fAllRestBwSdAfterPreAdd = 1.0/(double)(Math.log((double)AllRestBWSdAfterPreAdd + 1) + 1);
                double resultScore = fChokeLinkRestBw * 5 + fPathMeanRestBw * 5;

                Path prePickPath = checkLeadBackSrc(path, srcPort);
                //if lead back to the src port
                if(prePickPath == null) {
                    resultScore = 0;
                }

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
                result.add(indexPath.get(0));
            }else{
                result.add(finalPath);
            }
            return result;

        }

        private  Set<Path> PathsDecisionECMP(Set<Path> paths, FlowContext flowContext, PortNumber srcPort){

            Set<Path> result = Sets.newHashSet();
            String flowCtx = flowContext.toString();
            List<Path> pathList = Lists.newLinkedList(paths);
            AlternativePathsContext EcmpPathsCtx = getAltnativePathCtx(paths, srcPort);//map(id, path), size
            ECMPContext ctx = ECMPContext.BuildECMPContext();
            Map<String, Integer> flowDistributionMap = ctx.getFlowDistributionMap();
            try {
                if (!flowDistributionMap.containsKey(flowCtx)) {
                    flowDistributionMap.put(flowCtx, 0);
                    result.add(pathList.get(0));
                    ctx.setFlowDistributionMap(flowDistributionMap);
                    return result;
                } else {
                    int AlternativePathSize = (int)EcmpPathsCtx.getSize();
                    int objectIndex = (flowDistributionMap.get(flowCtx) + 1) % AlternativePathSize;
                    flowDistributionMap.put(flowCtx, objectIndex);
                    ctx.setFlowDistributionMap(flowDistributionMap);
                    result.add(pathList.get(objectIndex));
                }
            } catch (Exception e) {
                return ExceptionLogs(paths);
            }

            return result;
        }

        private AlternativePathsContext getAltnativePathCtx(Set<Path> paths, PortNumber srcPort) {
            AlternativePathsContext ecmpPathsCtx = new AlternativePathsContext();
            Map<Long, Path> pathIndexMap = ecmpPathsCtx.getPathIndex();
            long index = 0;
            for (Path path : paths) {
                Path prePickPath = checkLeadBackSrc(path, srcPort);
                //filter if lead back to the src port
                if(prePickPath != null) {
                    pathIndexMap.put(index, path);
                    index++;
                    //log.info("getAltnativePathCtx , cache[index,path], index: " + index + ", path: " + path.toString());
                }
            }
            ecmpPathsCtx.setPathIndex(pathIndexMap);
            ecmpPathsCtx.setSize(index);
            return ecmpPathsCtx;
        }

        private Path checkLeadBackSrc(Path path, PortNumber srcPort) {
            Set<Path> testPathDstPort = Sets.newHashSet();
            testPathDstPort.add(path);
            Path prePickPath =  pickForwardPathIfPossible(testPathDstPort, srcPort);
            return prePickPath;
        }

        private Double getThisLinkResBwUpdate(Double thisLinkResBw, Double theAddRestBw) {
            Double thisLinkResBwUpdate = thisLinkResBw - theAddRestBw;
            if(thisLinkResBwUpdate < 0){
                thisLinkResBwUpdate = 0.0;
            }
            return thisLinkResBwUpdate;
        }

        private Set<Path> ExceptionLogs(Set<Path> paths) {
            log.warn("ArithmeticException");
            //only the way back, lazy process in next function
            return paths;
        }

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
        private  Set<Path> PathsDecisionFESM(Set<Path> paths, PortNumber srcPort) {

            //flowStatisticService.loadSummaryPortInternal()
            Set<Path> result = Sets.newHashSet();
            Map<Integer, Path> indexPath = Maps.newLinkedHashMap();
            Path finalPath = null;
            int i=0;
            double maxScore = 0.0;

            for(Path path : paths){
                int j=0;
                indexPath.put(i, path);
                ComputeFactorContext computeFactorContext = new ComputeFactorContext();
                computeFactorContext.sethObject(computeHObject(path));
                for(Link link : path.links()){

                    long IntraLinkLoadBw = getIntraLinkLoadBw(link.src(), link.dst());

                    /**
                     *
                     * link context:
                     * some statistic of the src of this link:
                     * packetsReceived_src, packetsSent_src
                     * bytesReceived_src, bytesSent_src
                     * rx_dropped_src, tx_dropped_src, rx_tx_dropped_src (tx:transport, rx:receive)
                     *
                     * some statistic of the dst of this link:
                     * packetsReceived_dst, packetsSent_dst
                     * bytesReceived_dst, bytesSent_dst
                     * rx_dropped_dst, tx_dropped_dst, rx_tx_dropped_dst
                     *
                     */
                    LinkContext linkContext = new LinkContext(link);
                    linkContext.InitLinkContext();

                    /**
                     * U = (h, p, b, r)
                     * h denotes the hop count
                     * p denotes the transmitting packet count
                     * b denotes the byte count of the critical
                     * r denotes the forwarding rate of the critical port
                     */
                    if(IntraLinkLoadBw > computeFactorContext.rObject){
                        computeFactorContext.pObject = Math.max(linkContext.packetsSent_src, linkContext.packetsReceived_dst);
                        computeFactorContext.bObject = Math.max(linkContext.bytesSent_src, linkContext.bytesReceived_dst);
                        computeFactorContext.rObject = IntraLinkLoadBw;
                    }

                    j++;
                }


                /**
                 *
                 * U = (h, p, b, r) ： 一条路径
                 * h: hObject
                 * p: pObject
                 * b: bObject
                 * r: rObject
                 *
                 * 特征工程
                 * rh = 1.0/(e^h)
                 * rp = 1.0/log(p+0.1)
                 * rb = 1.0/log(b+0.1)
                 * rr = 1.0/(1+e^(-r/50.0))
                 *
                 * the matrix R can be presented as the column vector for one path:
                 * R = (rh, rp, rb, rr)
                 * weight = (0.4, 0.15, 0.15, 0.3)
                 */

                double resultScore = getResultScore(computeFactorContext);
                Path prePickPath = checkLeadBackSrc(path, srcPort);
                //if lead back to the src port
                if(prePickPath == null) {
                    i ++;
                } else {
                    if(resultScore > maxScore){
                        finalPath = path;
                    }
                    i++;
                }
            }

            return packResult(result, finalPath, indexPath);
        }

        private Set<Path> packResult(Set<Path> result, Path finalPath, Map<Integer, Path> indexPath) {
            //result.add(indexPath.get(0));
            if(finalPath == null){
                result.add(indexPath.get(0));
            }else{
                result.add(finalPath);
            }
            return result;
        }

        private long computeHObject(Path path) {
            int rPathLength = path.links().size();
            long hObject = (long)rPathLength;
            return hObject;
        }

        private double getResultScore(ComputeFactorContext computeFactorContext) {

            double rh = 1.0 / (double)(Math.exp((double)computeFactorContext.hObject));
            double rp = 1.0 / (double)(Math.log((double)(computeFactorContext.pObject + 0.1)));
            double rb = 1.0 / (double)(Math.log((double)(computeFactorContext.bObject + 0.1)));
            double rr = 1.0 / (double)(1 + Math.exp((double)((0-computeFactorContext.rObject) / 50.0)));

            double a1 = 0.4;
            double a2 = 0.15;
            double a3 = 0.15;
            double a4 = 0.3;

            double b1 = a1 * rh;
            double b2 = a2 * rp;
            double b3 = a3 * rb;
            double b4 = a4 * rr;

            double resultScore = b1 + b2 + b3 + b4;

            return resultScore;
        }

        private class ComputeFactorContext {

            private long pObject;
            private long bObject;
            private long rObject;
            private long hObject;


            public long gethObject() {
                return hObject;
            }

            public void sethObject(long hObject) {
                this.hObject = hObject;
            }

            public long getpObject() {
                return pObject;
            }

            public void setpObject(long pObject) {
                this.pObject = pObject;
            }

            public long getbObject() {
                return bObject;
            }

            public void setbObject(long bObject) {
                this.bObject = bObject;
            }

            public long getrObject() {
                return rObject;
            }

            public void setrObject(long rObject) {
                this.rObject = rObject;
            }

            public ComputeFactorContext() {
                this.pObject = 0;
                this.bObject = 0;
                this.rObject = 0;
                this.hObject = 0;
            }


        }

        private class LinkContext {
            private Link link;
            private long packetsReceived_src;
            private long packetsSent_src;
            private long bytesReceived_src;
            private long bytesSent_src;
            private long rx_dropped_src;
            private long tx_dropped_src;
            private long rx_tx_dropped_src;

            private long packetsReceived_dst;
            private long packetsSent_dst;
            private long bytesReceived_dst;
            private long bytesSent_dst;
            private long rx_dropped_dst;
            private long tx_dropped_dst;
            private long rx_tx_dropped_dst = 0;

            public LinkContext(Link link) {
                this.link = link;
                this.packetsReceived_src = 0;
                this.packetsSent_src = 0;
                this.bytesReceived_src = 0;
                this.bytesSent_src = 0;
                this.rx_dropped_src = 0;
                this.tx_dropped_src = 0;
                this.rx_tx_dropped_src = 0;

                this.packetsReceived_dst = 0;
                this.packetsSent_dst = 0;
                this.bytesReceived_dst = 0;
                this.bytesSent_dst = 0;
                this.rx_dropped_dst = 0;
                this.tx_dropped_dst = 0;
            }

            public void InitLinkContext() {
                InitLinkSrcNodeContext();
                InitLinkDstNodeContext();

            }

            private void InitLinkSrcNodeContext() {
                if(link.src()!=null &&  link.src().deviceId() != null && link.src().port() !=null && flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    packetsReceived_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsReceived();
                }

                if(link.src()!=null && link.src().deviceId() !=null && link.src().port() != null && flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    packetsSent_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsSent();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    bytesReceived_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).bytesReceived();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    bytesSent_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).bytesSent();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    rx_dropped_src = flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsRxDropped();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()) != null){
                    flowStatisticService.getDeviceService().getStatisticsForPort(link.src().deviceId(), link.src().port()).packetsTxDropped();
                }
                rx_tx_dropped_src = rx_dropped_src+tx_dropped_src;
            }

            private void InitLinkDstNodeContext() {
                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    packetsReceived_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsReceived();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    packetsSent_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsSent();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    bytesReceived_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).bytesReceived();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    bytesSent_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).bytesSent();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    rx_dropped_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsRxDropped();
                }

                if(flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()) != null){
                    tx_dropped_dst = flowStatisticService.getDeviceService().getStatisticsForPort(link.dst().deviceId(), link.dst().port()).packetsTxDropped();
                }
                rx_tx_dropped_dst = tx_dropped_dst+rx_dropped_dst;
            }


        }

        private class FlowContext {
            private Host src;
            private Host dst;
            private String curProtocol;

            public FlowContext(Host src, Host dst, String curProtocol) {
                this.src = src;
                this.dst = dst;
                this.curProtocol = curProtocol;
            }

            @Override
            public String toString() {
                return "FlowContext{" +
                        "src=" + src +
                        ", dst=" + dst +
                        ", curProtocol='" + curProtocol + '\'' +
                        '}';
            }

            public Host getSrc() {
                return src;
            }

            public void setSrc(Host src) {
                this.src = src;
            }

            public Host getDst() {
                return dst;
            }

            public void setDst(Host dst) {
                this.dst = dst;
            }

            public String getCurProtocol() {
                return curProtocol;
            }

            public void setCurProtocol(String curProtocol) {
                this.curProtocol = curProtocol;
            }
        }

        private class AlternativePathsContext {
            private Map<Long, Path> pathIndex;
            private long size;
            public AlternativePathsContext() {
                this.pathIndex = Maps.newLinkedHashMap();
                this.size = 0;
            }

            public Map<Long, Path> getPathIndex() {
                return pathIndex;
            }

            public void setPathIndex(Map<Long, Path> pathIndex) {
                this.pathIndex = pathIndex;
            }

            public long getSize() {
                return size;
            }

            public void setSize(long size) {
                this.size = size;
            }
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

    // Selects a path from the given set that does not lead back to the src
    private Path pickForwardPathIfPossible(Set<Path> paths, PortNumber notToPort) {
        for (Path path : paths) {
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
