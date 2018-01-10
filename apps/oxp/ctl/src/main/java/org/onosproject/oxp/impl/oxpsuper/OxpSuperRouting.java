package org.onosproject.oxp.impl.oxpsuper;

import org.apache.felix.scr.annotations.*;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.onlab.packet.*;
import org.onlab.packet.MacAddress;
import org.onosproject.net.*;
import org.onosproject.oxp.OXPDomain;
import org.onosproject.oxp.OxpDomainMessageListener;
import org.onosproject.oxp.oxpsuper.OxpSuperController;
import org.onosproject.oxp.oxpsuper.OxpSuperTopoService;
import org.onosproject.oxp.protocol.*;
import org.onosproject.oxp.protocol.ver10.OXPForwardingReplyVer10;
import org.onosproject.oxp.types.*;
import org.projectfloodlight.openflow.protocol.*;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.*;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.slf4j.Logger;

import java.util.*;

import static org.onlab.packet.Ethernet.TYPE_ARP;
import static org.onlab.packet.Ethernet.TYPE_IPV4;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Created by cr on 16-9-5.
 */
@Component(immediate = true)
public class OxpSuperRouting {

    private final Logger log = getLogger(getClass());


    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private OxpSuperController superController;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    private OxpSuperTopoService topoService;

    private OxpDomainMessageListener domainMessageListener = new InternalDomainMsgListener();

    @Activate
    public void actviate() {
        superController.addMessageListener(domainMessageListener);
    }

    @Deactivate
    public void deactivate() {
        superController.removeMessageListener(domainMessageListener);
    }

    private void processArp(DeviceId deviceId, Ethernet eth, PortNumber inPort, long xid) {
        ARP arp = (ARP) eth.getPayload();
        IpAddress target = Ip4Address.valueOf(arp.getTargetProtocolAddress());
        IpAddress sender = Ip4Address.valueOf(arp.getSenderProtocolAddress());
        Set<OXPHost> hosts = topoService.getHostsByIp(target);
        if (hosts.isEmpty()) {
            flood(eth, xid);
            return;
        };
        OXPHost host = (OXPHost) hosts.toArray()[0];
        HostId hostId = HostId.hostId(MacAddress.valueOf(host.getMacAddress().getLong()));
        DeviceId hostLocation = topoService.getHostLocation(hostId);
        OXPDomain domain = superController.getOxpDomain(deviceId);
        if (null == hostLocation) return;
        switch (arp.getOpCode()) {
            case ARP.OP_REPLY:
                packetOut(hostLocation, inPort, PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()), eth, xid);
                break;
            case ARP.OP_REQUEST:
                Ethernet reply = ARP.buildArpReply(Ip4Address.valueOf(arp.getTargetProtocolAddress()), MacAddress.valueOf(host.getMacAddress().getLong()), eth);
                packetOut(deviceId, inPort, PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()), reply, xid);
                break;
            default:
                return;
        }
    }


    private void processIpv4(DeviceId deviceId, Ethernet eth, PortNumber inPort, long xid, long cookie) {
        IPv4 iPv4 = (IPv4) eth.getPayload();
        IpAddress srcIp = Ip4Address.valueOf(iPv4.getSourceAddress());
        IpAddress target = Ip4Address.valueOf(iPv4.getDestinationAddress());
        Set<OXPHost> dstHosts = topoService.getHostsByIp(target);
        if (dstHosts.isEmpty()) {
            flood(eth, xid);
            return;
        }
        OXPHost host = (OXPHost) dstHosts.toArray()[0];
        HostId hostId = HostId.hostId(MacAddress.valueOf(host.getMacAddress().getLong()));
        DeviceId srcDeviceId = deviceId;
        DeviceId dstDeviceId = topoService.getHostLocation(hostId);
        OXPDomain srcDomain = superController.getOxpDomain(deviceId);
        if (null == dstDeviceId) {
            flood(eth, xid);
            return;
        }
        //若在同一域内
        if (srcDeviceId.equals(dstDeviceId)) {
            // 安装流表 inport: inport, outPort:local
//            OFFlowMod fm = buildFlowMod(srcDomain, inPort, PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()),
//                    srcIp, target, xid, cookie);
//            OFActionOutput.Builder action = srcDomain.ofFactory().actions().buildOutput()
//                    .setPort(OFPort.of(OXPVport.LOCAL.getPortNumber()));
//            List<OFAction> actions = new ArrayList<>();
//            Match.Builder mBuilder = srcDomain.ofFactory().buildMatch();
//            mBuilder.setExact(MatchField.IN_PORT,
//                    OFPort.of(OXPVport.LOCAL.getPortNumber()));
//            mBuilder.setExact(MatchField.IPV4_SRC,
//                    IPv4Address.of(srcIp.getIp4Address().toInt()));
//            mBuilder.setExact(MatchField.IPV4_DST,
//                    IPv4Address.of(target.getIp4Address().toInt()));
//            Match match = mBuilder.build();
//            OFFlowAdd fm = srcDomain.ofFactory().buildFlowAdd()
//                    .setXid(xid)
//                    .setCookie(U64.of(cookie))
//                    .setBufferId(OFBufferId.NO_BUFFER)
//                    .setActions(actions)
//                    .setMatch(match)
//                    .setFlags(Collections.singleton(OFFlowModFlags.SEND_FLOW_REM))
//                    .setPriority(10)
//                    .build();
            //installFlow(deviceId, fm);
            installFlow(deviceId, srcIp, target, inPort, PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()),
                    IpAddress.valueOf("255.255.255.255"), eth.getEtherType(), (byte) 0, xid, cookie);
            packetOut(deviceId, inPort, PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()), eth, xid);
            return;
        }
        // TODO use loadbalance path instead
        Set<Path> paths = null;
        Set<DisjointPath> dPaths = null;
        Path path = null;
        if (superController.isLoadBalance()) {
            paths = topoService.getLoadBalancePaths(srcDeviceId, dstDeviceId);
            if (paths.isEmpty()) return;
            path = (Path) paths.toArray()[0];
        } else {
            dPaths = topoService.getDisjointPaths(srcDeviceId, dstDeviceId);
            if (dPaths.isEmpty()) return;
            path = ((DisjointPath)dPaths.toArray()[0]).primary();
        }
        // 安装
        Link formerLink = null;
        for (Link link : path.links()) {
            if (link.src().equals(path.src())) {
//                OFFlowMod fm = buildFlowMod(superController.getOxpDomain(link.src().deviceId()), inPort, link.src().port(),
//                        srcIp, target, xid, cookie);
//                installFlow(link.src().deviceId(), fm);
                installFlow(link.src().deviceId(), srcIp, target, inPort, link.src().port(),
                        IpAddress.valueOf("255.255.255.255"), eth.getEtherType(), (byte) 0, xid, cookie);

            } else {
//                OFFlowMod fmFommer = buildFlowMod(superController.getOxpDomain(link.src().deviceId()), formerLink.dst().port(), link.src().port(),
//                        srcIp, target, xid, cookie);
//                installFlow(link.src().deviceId(), fmFommer);
                installFlow(link.src().deviceId(), srcIp, target, formerLink.dst().port(), link.src().port(),
                        IpAddress.valueOf("255.255.255.255"), eth.getEtherType(), (byte) 0, xid, cookie);
            }
            if (link.dst().equals(path.dst())) {
//                OFFlowMod fmLatter = buildFlowMod(superController.getOxpDomain(link.dst().deviceId()), link.dst().port(),
//                        PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()),
//                        srcIp, target, xid, cookie);
//                installFlow(link.dst().deviceId(), fmLatter);
                installFlow(link.dst().deviceId(), srcIp, target, link.dst().port(), PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()),
                        IpAddress.valueOf("255.255.255.255"), eth.getEtherType(), (byte) 0, xid, cookie);
                packetOut(link.dst().deviceId(), link.dst().port(), PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()), eth, xid);
            }
            formerLink = link;
        }
//        packetOut(superController.getOxpDomain(formerLink.dst().deviceId()).getDeviceId(),
//                formerLink.dst().port(), PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()), eth, xid);
    }

    private void packetOut(DeviceId deviceId,PortNumber inPort, PortNumber outPort, Ethernet eth, long xid) {
        OXPDomain domain = superController.getOxpDomain(deviceId);
        if (domain.isCompressedMode()) {
            superController.sendSbpPacketOut(deviceId, outPort, eth.serialize());
        } else {
            OFActionOutput act = domain.ofFactory().actions()
                    .buildOutput()
                    .setPort(OFPort.of((int) outPort.toLong()))
                    .build();
            OFPacketOut.Builder builder = domain.ofFactory().buildPacketOut();
            OFPacketOut pktout = builder.setXid(xid)
                    .setBufferId(OFBufferId.NO_BUFFER)
                    .setInPort(OFPort.of((int) inPort.toLong()))
                    .setActions(Collections.singletonList(act))
                    .setData(eth.serialize())
                    .build();
            ChannelBuffer buffer = ChannelBuffers.dynamicBuffer();
            pktout.writeTo(buffer);
            Set<OXPSbpFlags> sbpFlagses = new HashSet<>();
            sbpFlagses.add(OXPSbpFlags.DATA_EXIST);
            OXPSbp oxpSbp = domain.factory().buildSbp()
                    .setSbpCmpType(OXPSbpCmpType.NORMAL)
                    .setFlags(sbpFlagses)
                    .setSbpData(OXPSbpData.read(buffer, buffer.readableBytes(), domain.getOxpVersion()))
                    .build();
            superController.sendMsg(deviceId, oxpSbp);
        }

    }

    private void flood(Ethernet eth, long xid) {
        for (OXPDomain domain : superController.getOxpDomains()) {
            packetOut(domain.getDeviceId(), PortNumber.portNumber(OXPVport.LOCAL.getPortNumber()),
                    PortNumber.portNumber(OXPVport.FLOOD.getPortNumber()), eth, xid);
        }
    }

    private void installFlow(DeviceId deviceId, IpAddress srcIp, IpAddress dstIp,
                             PortNumber srcPort, PortNumber dstPort,
                             IpAddress mask, short ethType, byte qos,
                             long xid, long cookie) {
        OXPDomain domain = superController.getOxpDomain(deviceId);
        if (domain.isCompressedMode()) {
            superController.sendSbpFwdReply(deviceId, srcIp, dstIp,
                    srcPort, dstPort, mask,
                    ethType, qos);
        } else {
            OFFlowMod fm = buildFlowMod(domain, srcPort, dstPort,
                    srcIp, dstIp, xid, cookie);
            installFlow(deviceId, fm);
        }
    }

    private void installFlow(DeviceId deviceId, OFFlowMod flowMod) {
        OXPDomain domain = superController.getOxpDomain(deviceId);
        ChannelBuffer buffer = ChannelBuffers.dynamicBuffer();
        flowMod.writeTo(buffer);
        Set<OXPSbpFlags> sbpFlagses = new HashSet<>();
        sbpFlagses.add(OXPSbpFlags.DATA_EXIST);
        OXPSbp oxpSbp = domain.factory().buildSbp()
                .setSbpCmpType(OXPSbpCmpType.NORMAL)
                .setFlags(sbpFlagses)
                .setSbpData(OXPSbpData.read(buffer, buffer.readableBytes(), domain.getOxpVersion()))
                .build();
        superController.sendMsg(deviceId, oxpSbp);
    }

    private OFFlowMod buildFlowMod(OXPDomain srcDomain, PortNumber inPort, PortNumber outPort,
                              IpAddress srcIp, IpAddress dstIP,
                              long xid, long cookie) {
        // 安装流表 inport: inport, outPort:local
        OFActionOutput.Builder action = srcDomain.ofFactory().actions().buildOutput()
                .setPort(OFPort.of((int) outPort.toLong()));
        List<OFAction> actions = new ArrayList<>();
        actions.add(action.build());
        Match.Builder mBuilder = srcDomain.ofFactory().buildMatch();
        mBuilder.setExact(MatchField.IN_PORT,
                OFPort.of((int) inPort.toLong()));
        mBuilder.setExact(MatchField.IPV4_SRC,
                IPv4Address.of(srcIp.getIp4Address().toInt()));
        mBuilder.setExact(MatchField.IPV4_DST,
                IPv4Address.of(dstIP.getIp4Address().toInt()));
        mBuilder.setExact(MatchField.ETH_TYPE, EthType.IPv4);
        Match match = mBuilder.build();
        OFFlowAdd fm = srcDomain.ofFactory().buildFlowAdd()
                .setXid(xid)
                .setCookie(U64.of(cookie))
                .setBufferId(OFBufferId.NO_BUFFER)
                .setActions(actions)
                .setMatch(match)
                .setFlags(Collections.singleton(OFFlowModFlags.SEND_FLOW_REM))
                .setPriority(10)
                .build();
        return fm;
    }

    class InternalDomainMsgListener implements OxpDomainMessageListener {
        @Override
        public void handleIncomingMessage(DeviceId deviceId, OXPMessage msg) {
            if (msg.getType() == OXPType.OXPT_SBP) {
                OXPSbp sbp = (OXPSbp) msg;
                long xid = 0;
                long cookie = U64.ZERO.getValue();
                PortNumber inPort = null;
                Ethernet eth = null;
                if (sbp.getSbpCmpType().equals(OXPSbpCmpType.NORMAL)) {
                    OFMessage ofMsg = superController.parseOfMessage(sbp);
                    //只处理packet-in消息
                    if (null == ofMsg || ofMsg.getType() != OFType.PACKET_IN) {
                        return;
                    }
                    OFPacketIn packetIn = (OFPacketIn) ofMsg;
                    xid = packetIn.getXid();
                    inPort = PortNumber.portNumber(packetIn.getMatch().get(MatchField.IN_PORT).getPortNumber());
                    eth = superController.parseEthernet(packetIn.getData());
                    cookie = packetIn.getCookie().getValue();
                } else if (sbp.getSbpCmpType().equals(OXPSbpCmpType.FORWARDING_REQUEST)) {
                    OXPForwardingRequest sbpCmpFwdReq = (OXPForwardingRequest)sbp.getSbpCmpData();
                    inPort = PortNumber.portNumber(sbpCmpFwdReq.getInport());
                    eth = superController.parseEthernet(sbpCmpFwdReq.getData());
                }

                if (null == eth) {
                    return;
                }
                if (eth.getEtherType() == TYPE_ARP) {
                    processArp(deviceId, eth, inPort, xid);
                    return;
                }
                if (eth.getEtherType() == TYPE_IPV4) {
                    processIpv4(deviceId, eth, inPort, xid, cookie);
                    return;
                }

                return;
            }
        }

        @Override
        public void handleOutGoingMessage(DeviceId deviceId, List<OXPMessage> msgs) {

        }
    }
}
