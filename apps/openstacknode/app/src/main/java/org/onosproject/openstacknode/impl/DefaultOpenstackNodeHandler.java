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
 */
package org.onosproject.openstacknode.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.IpAddress;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.ControllerNode;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.behaviour.BridgeConfig;
import org.onosproject.net.behaviour.BridgeDescription;
import org.onosproject.net.behaviour.BridgeName;
import org.onosproject.net.behaviour.ControllerInfo;
import org.onosproject.net.behaviour.DefaultBridgeDescription;
import org.onosproject.net.behaviour.DefaultTunnelDescription;
import org.onosproject.net.behaviour.ExtensionTreatmentResolver;
import org.onosproject.net.behaviour.InterfaceConfig;
import org.onosproject.net.behaviour.TunnelDescription;
import org.onosproject.net.behaviour.TunnelEndPoints;
import org.onosproject.net.behaviour.TunnelKeys;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.instructions.ExtensionPropertyException;
import org.onosproject.net.flow.instructions.ExtensionTreatment;

import org.onosproject.openstacknode.api.NodeState;
import org.onosproject.openstacknode.api.OpenstackNode;
import org.onosproject.openstacknode.api.OpenstackNodeAdminService;
import org.onosproject.openstacknode.api.OpenstackNodeEvent;
import org.onosproject.openstacknode.api.OpenstackNodeHandler;
import org.onosproject.openstacknode.api.OpenstackNodeListener;
import org.onosproject.openstacknode.api.OpenstackNodeService;
import org.onosproject.ovsdb.controller.OvsdbClientService;
import org.onosproject.ovsdb.controller.OvsdbController;
import org.onosproject.ovsdb.controller.OvsdbNodeId;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;

import java.util.Dictionary;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.packet.TpPort.tpPort;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.net.AnnotationKeys.PORT_NAME;
import static org.onosproject.net.flow.instructions.ExtensionTreatmentType.ExtensionTreatmentTypes.NICIRA_SET_TUNNEL_DST;
import static org.onosproject.openstacknode.api.Constants.DEFAULT_TUNNEL;
import static org.onosproject.openstacknode.api.Constants.INTEGRATION_BRIDGE;
import static org.onosproject.openstacknode.api.NodeState.COMPLETE;
import static org.onosproject.openstacknode.api.NodeState.DEVICE_CREATED;
import static org.onosproject.openstacknode.api.NodeState.INCOMPLETE;
import static org.onosproject.openstacknode.api.OpenstackNode.NodeType.GATEWAY;
import static org.onosproject.openstacknode.api.OpenstackNodeService.APP_ID;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Service bootstraps openstack node based on its type.
 */
@Component(immediate = true)
public class DefaultOpenstackNodeHandler implements OpenstackNodeHandler {

    private final Logger log = getLogger(getClass());

    private static final String OVSDB_PORT = "ovsdbPortNum";
    private static final int DEFAULT_OVSDB_PORT = 6640;
    private static final String DEFAULT_OF_PROTO = "tcp";
    private static final int DEFAULT_OFPORT = 6653;
    private static final int DPID_BEGIN = 3;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceAdminService deviceAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OvsdbController ovsdbController;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OpenstackNodeService osNodeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OpenstackNodeAdminService osNodeAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService componentConfigService;

    @Property(name = OVSDB_PORT, intValue = DEFAULT_OVSDB_PORT,
            label = "OVSDB server listen port")
    private int ovsdbPort = DEFAULT_OVSDB_PORT;

    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler", log));

    private final DeviceListener ovsdbListener = new InternalOvsdbListener();
    private final DeviceListener bridgeListener = new InternalBridgeListener();
    private final OpenstackNodeListener osNodeListener = new InternalOpenstackNodeListener();

    private ApplicationId appId;
    private NodeId localNode;

    @Activate
    protected void activate() {
        appId = coreService.getAppId(APP_ID);
        localNode = clusterService.getLocalNode().id();

        componentConfigService.registerProperties(getClass());
        leadershipService.runForLeadership(appId.name());
        deviceService.addListener(ovsdbListener);
        deviceService.addListener(bridgeListener);
        osNodeService.addListener(osNodeListener);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        osNodeService.removeListener(osNodeListener);
        deviceService.removeListener(bridgeListener);
        deviceService.removeListener(ovsdbListener);
        componentConfigService.unregisterProperties(getClass(), false);
        leadershipService.withdraw(appId.name());
        eventExecutor.shutdown();

        log.info("Stopped");
    }

    @Modified
    protected void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        int updatedOvsdbPort = Tools.getIntegerProperty(properties, OVSDB_PORT);
        if (!Objects.equals(updatedOvsdbPort, ovsdbPort)) {
            ovsdbPort = updatedOvsdbPort;
        }

        log.info("Modified");
    }

    @Override
    public void processInitState(OpenstackNode osNode) {
        if (!isOvsdbConnected(osNode)) {
            ovsdbController.connect(osNode.managementIp(), tpPort(ovsdbPort));
            return;
        }
        if (!deviceService.isAvailable(osNode.intgBridge())) {
            createBridge(osNode, INTEGRATION_BRIDGE, osNode.intgBridge());
        }
    }

    @Override
    public void processDeviceCreatedState(OpenstackNode osNode) {
        try {
            if (!isOvsdbConnected(osNode)) {
                ovsdbController.connect(osNode.managementIp(), tpPort(ovsdbPort));
                return;
            }

            if (osNode.type() == GATEWAY) {
                addSystemInterface(osNode, INTEGRATION_BRIDGE, osNode.uplinkPort());
            }

            if (osNode.dataIp() != null &&
                    !isIntfEnabled(osNode, DEFAULT_TUNNEL)) {
                createTunnelInterface(osNode);
            }

            if (osNode.vlanIntf() != null &&
                    !isIntfEnabled(osNode, osNode.vlanIntf())) {
                addSystemInterface(osNode, INTEGRATION_BRIDGE, osNode.vlanIntf());
            }
        } catch (Exception e) {
            log.error("Exception occurred because of {}", e.toString());
        }
    }

    @Override
    public void processCompleteState(OpenstackNode osNode) {
        OvsdbClientService ovsdbClient = ovsdbController.getOvsdbClient(
                new OvsdbNodeId(osNode.managementIp(), DEFAULT_OVSDB_PORT));
        if (ovsdbClient != null && ovsdbClient.isConnected()) {
            ovsdbClient.disconnect();
        }
    }

    @Override
    public void processIncompleteState(OpenstackNode osNode) {
        //TODO
    }

    /**
     * Checks whether the controller has a connection with an OVSDB that resides
     * inside the given openstack node.
     *
     * @param osNode openstack node
     * @return true if the controller is connected to the OVSDB, false otherwise
     */
    private boolean isOvsdbConnected(OpenstackNode osNode) {
        OvsdbNodeId ovsdb = new OvsdbNodeId(osNode.managementIp(), ovsdbPort);
        OvsdbClientService client = ovsdbController.getOvsdbClient(ovsdb);
        return deviceService.isAvailable(osNode.ovsdb()) &&
                client != null &&
                client.isConnected();
    }

    /**
     * Creates a bridge with a given name on a given openstack node.
     *
     * @param osNode openstack node
     * @param bridgeName bridge name
     * @param deviceId device identifier
     */
    private void createBridge(OpenstackNode osNode, String bridgeName, DeviceId deviceId) {
        Device device = deviceService.getDevice(osNode.ovsdb());
        if (device == null || !device.is(BridgeConfig.class)) {
            log.error("Failed to create integration bridge on {}", osNode.ovsdb());
            return;
        }

        Set<IpAddress> controllerIps = clusterService.getNodes().stream()
                    .map(ControllerNode::ip)
                    .collect(Collectors.toSet());

        List<ControllerInfo> controllers = controllerIps.stream()
                .map(ip -> new ControllerInfo(ip, DEFAULT_OFPORT, DEFAULT_OF_PROTO))
                .collect(Collectors.toList());

        String dpid = deviceId.toString().substring(DPID_BEGIN);

        BridgeDescription bridgeDesc = DefaultBridgeDescription.builder()
                .name(bridgeName)
                .failMode(BridgeDescription.FailMode.SECURE)
                .datapathId(dpid)
                .disableInBand()
                .controllers(controllers)
                .build();

        BridgeConfig bridgeConfig = device.as(BridgeConfig.class);
        bridgeConfig.addBridge(bridgeDesc);
    }

    /**
     * Adds a network interface (aka port) into a given bridge of openstack node.
     *
     * @param osNode openstack node
     * @param bridgeName bridge name
     * @param intfName interface name
     */
    private void addSystemInterface(OpenstackNode osNode, String bridgeName, String intfName) {
        Device device = deviceService.getDevice(osNode.ovsdb());
        if (device == null || !device.is(BridgeConfig.class)) {
            return;
        }
        BridgeConfig bridgeConfig =  device.as(BridgeConfig.class);
        bridgeConfig.addPort(BridgeName.bridgeName(bridgeName), intfName);
    }

    /**
     * Creates a tunnel interface in a given openstack node.
     *
     * @param osNode openstack node
     */
    private void createTunnelInterface(OpenstackNode osNode) {
        if (isIntfEnabled(osNode, DEFAULT_TUNNEL)) {
            return;
        }

        Device device = deviceService.getDevice(osNode.ovsdb());
        if (device == null || !device.is(InterfaceConfig.class)) {
            log.error("Failed to create tunnel interface on {}", osNode.ovsdb());
            return;
        }

        TunnelDescription tunnelDesc = DefaultTunnelDescription.builder()
                .deviceId(INTEGRATION_BRIDGE)
                .ifaceName(DEFAULT_TUNNEL)
                .type(TunnelDescription.Type.VXLAN)
                .remote(TunnelEndPoints.flowTunnelEndpoint())
                .key(TunnelKeys.flowTunnelKey())
                .build();

        InterfaceConfig ifaceConfig = device.as(InterfaceConfig.class);
        ifaceConfig.addTunnelMode(DEFAULT_TUNNEL, tunnelDesc);
    }

    private ExtensionTreatment tunnelDstTreatment(DeviceId deviceId, IpAddress remoteIp) {
        Device device = deviceService.getDevice(deviceId);
        if (device != null && !device.is(ExtensionTreatmentResolver.class)) {
            log.error("The extension treatment is not supported");
            return null;
        }

        if (device == null) {
            return null;
        }

        ExtensionTreatmentResolver resolver = device.as(ExtensionTreatmentResolver.class);
        ExtensionTreatment treatment = resolver.getExtensionInstruction(NICIRA_SET_TUNNEL_DST.type());
        try {
            treatment.setPropertyValue("tunnelDst", remoteIp.getIp4Address());
            return treatment;
        } catch (ExtensionPropertyException e) {
            log.warn("Failed to get tunnelDst extension treatment for {}", deviceId);
            return null;
        }
    }

    /**
     * Checks whether a given network interface in a given openstack node is enabled or not.
     *
     * @param osNode openstack node
     * @param intf network interface name
     * @return true if the given interface is enabled, false otherwise
     */
    private boolean isIntfEnabled(OpenstackNode osNode, String intf) {
        return deviceService.isAvailable(osNode.intgBridge()) &&
                deviceService.getPorts(osNode.intgBridge()).stream()
                        .anyMatch(port -> Objects.equals(
                                port.annotations().value(PORT_NAME), intf) &&
                                port.isEnabled());
    }

    /**
     * Checks whether all requirements for this state are fulfilled or not.
     *
     * @param osNode openstack node
     * @return true if all requirements are fulfilled, false otherwise
     */
    private boolean isCurrentStateDone(OpenstackNode osNode) {
        switch (osNode.state()) {
            case INIT:
                return deviceService.isAvailable(osNode.intgBridge());
            case DEVICE_CREATED:
                if (osNode.dataIp() != null &&
                        !isIntfEnabled(osNode, DEFAULT_TUNNEL)) {
                    return false;
                }
                if (osNode.vlanIntf() != null &&
                        !isIntfEnabled(osNode, osNode.vlanIntf())) {
                    return false;
                }
                if (osNode.type() == GATEWAY &&
                        !isIntfEnabled(osNode, osNode.uplinkPort())) {
                    return false;
                }
                return true;
            case COMPLETE:
            case INCOMPLETE:
                // always return false
                // run init CLI to re-trigger node bootstrap
                return false;
            default:
                return true;
        }
    }

    /**
     * Configures the openstack node with new state.
     *
     * @param osNode openstack node
     * @param newState a new state
     */
    private void setState(OpenstackNode osNode, NodeState newState) {
        if (osNode.state() == newState) {
            return;
        }
        OpenstackNode updated = osNode.updateState(newState);
        osNodeAdminService.updateNode(updated);
        log.info("Changed {} state: {}", osNode.hostname(), newState);
    }

    /**
     * Bootstraps a new openstack node.
     *
     * @param osNode openstack node
     */
    private void bootstrapNode(OpenstackNode osNode) {
        if (isCurrentStateDone(osNode)) {
            setState(osNode, osNode.state().nextState());
        } else {
            log.trace("Processing {} state for {}", osNode.state(), osNode.hostname());
            osNode.state().process(this, osNode);
        }
    }

    /**
     * An internal OVSDB listener. This listener is used for listening the
     * network facing events from OVSDB device. If a new OVSDB device is detected,
     * ONOS tries to bootstrap the openstack node.
     */
    private class InternalOvsdbListener implements DeviceListener {

        @Override
        public boolean isRelevant(DeviceEvent event) {
            NodeId leader = leadershipService.getLeader(appId.name());
            return Objects.equals(localNode, leader) &&
                    event.subject().type() == Device.Type.CONTROLLER &&
                    osNodeService.node(event.subject().id()) != null;
        }

        @Override
        public void event(DeviceEvent event) {
            Device device = event.subject();
            OpenstackNode osNode = osNodeService.node(device.id());

            switch (event.type()) {
                case DEVICE_AVAILABILITY_CHANGED:
                case DEVICE_ADDED:
                    eventExecutor.execute(() -> {
                        if (deviceService.isAvailable(device.id())) {
                            log.debug("OVSDB {} detected", device.id());
                            bootstrapNode(osNode);
                        } else if (osNode.state() == COMPLETE) {
                            log.debug("Removing OVSDB {}", device.id());
                            deviceAdminService.removeDevice(device.id());
                        }
                    });
                    break;
                case PORT_ADDED:
                case PORT_REMOVED:
                case DEVICE_REMOVED:
                default:
                    // do nothing
                    break;
            }
        }
    }

    /**
     * An internal integration bridge listener. This listener is used for
     * listening the events from integration bridge. To listen the events from
     * other types of bridge such as provider bridge or tunnel bridge, we need
     * to augment OpenstackNodeService.node() method.
     */
    private class InternalBridgeListener implements DeviceListener {

        @Override
        public boolean isRelevant(DeviceEvent event) {
            NodeId leader = leadershipService.getLeader(appId.name());
            return Objects.equals(localNode, leader) &&
                    event.subject().type() == Device.Type.SWITCH &&
                    osNodeService.node(event.subject().id()) != null;
        }

        @Override
        public void event(DeviceEvent event) {
            Device device = event.subject();
            OpenstackNode osNode = osNodeService.node(device.id());

            switch (event.type()) {
                case DEVICE_AVAILABILITY_CHANGED:
                case DEVICE_ADDED:
                    eventExecutor.execute(() -> {
                        if (deviceService.isAvailable(device.id())) {
                            log.debug("Integration bridge created on {}", osNode.hostname());
                            bootstrapNode(osNode);
                        } else if (osNode.state() == COMPLETE) {
                            log.warn("Device {} disconnected", device.id());
                            setState(osNode, INCOMPLETE);
                        }
                    });
                    break;
                case PORT_ADDED:
                    eventExecutor.execute(() -> {
                        Port port = event.port();
                        String portName = port.annotations().value(PORT_NAME);
                        if (osNode.state() == DEVICE_CREATED && (
                                Objects.equals(portName, DEFAULT_TUNNEL) ||
                                Objects.equals(portName, osNode.vlanIntf()) ||
                                Objects.equals(portName, osNode.uplinkPort()))) {
                            log.debug("Interface {} added to {}", portName, event.subject().id());
                            bootstrapNode(osNode);
                        }
                    });
                    break;
                case PORT_REMOVED:
                    eventExecutor.execute(() -> {
                        Port port = event.port();
                        String portName = port.annotations().value(PORT_NAME);
                        if (osNode.state() == COMPLETE && (
                                Objects.equals(portName, DEFAULT_TUNNEL) ||
                                Objects.equals(portName, osNode.vlanIntf()) ||
                                        Objects.equals(portName, osNode.uplinkPort()))) {
                            log.warn("Interface {} removed from {}", portName, event.subject().id());
                            setState(osNode, INCOMPLETE);
                        }
                    });
                    break;
                case PORT_UPDATED:
                case DEVICE_REMOVED:
                default:
                    // do nothing
                    break;
            }
        }
    }

    /**
     * An internal openstack node listener.
     * The notification is triggered by OpenstackNodeStore.
     */
    private class InternalOpenstackNodeListener implements OpenstackNodeListener {

        @Override
        public boolean isRelevant(OpenstackNodeEvent event) {
            NodeId leader = leadershipService.getLeader(appId.name());
            return Objects.equals(localNode, leader);
        }

        @Override
        public void event(OpenstackNodeEvent event) {
            switch (event.type()) {
                case OPENSTACK_NODE_CREATED:
                case OPENSTACK_NODE_UPDATED:
                    eventExecutor.execute(() -> bootstrapNode(event.subject()));
                    break;
                case OPENSTACK_NODE_COMPLETE:
                    break;
                case OPENSTACK_NODE_REMOVED:
                    break;
                default:
                    break;
            }
        }
    }
}
