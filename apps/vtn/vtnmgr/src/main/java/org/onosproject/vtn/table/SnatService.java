/*
 * Copyright 2015-present Open Networking Foundation
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
package org.onosproject.vtn.table;

import org.onlab.packet.IpAddress;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.MacAddress;
import org.onosproject.net.DeviceId;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.vtnrsc.SegmentationId;

/**
 * SnatService interface provides the rules in SNAT table which is Table(40) for ovs pipeline.
 * SNAT means Source Network Address Translation, it is acronym for network terminology.
 * Handle the upward flows.
 * SnatService接口提供了SNAT表中的规则，该规则是ovs pipeline的表（40）。SNAT表示源网络地址转换，
 * 它是网络术语的首字母缩写。 处理上层的流。
 * router修改返回的数据包，将其转发给真正的VM实例，这个行为被称为 Source NAT
 */
public interface SnatService {

    /**
     * Assemble the SNAT table rules.组装SNAT表规则
     * Match: ipv4 type, vnid, destination ip and source ip.
     * Action: set eth_src, set eth_dst, set ip_src, set vnid and goto L2Forward Table(50).
     *
     * @param deviceId Device Id
     * @param matchVni the vni of L3 network
     * @param srcIP source ip
     * @param dstIP destination ip
     * @param ethDst external gateway mac
     * @param ethSrc external port mac
     * @param ipSrc floating ip
     * @param actionVni external network VNI，外部网络的VNI
     * @param type the operation type of the flow rules
     */
    void programSnatSameSegmentRules(DeviceId deviceId, SegmentationId matchVni,
                          IpAddress srcIP, IpAddress dstIP, MacAddress ethDst,
                          MacAddress ethSrc, IpAddress ipSrc,
                          SegmentationId actionVni, Objective.Operation type);
    /**
     * Assemble the SNAT table rules.
     * Match: ipv4 type, vnid and source ip.
     * Action: set eth_src, set eth_dst, set ip_src, set vnid and goto L2Forward Table(50).
     *
     * @param deviceId Device Id
     * @param matchVni the vni of L3 network
     * @param srcIP source ip
     * @param ethDst external gateway mac
     * @param ethSrc external port mac
     * @param ipSrc floating ip
     * @param actionVni external network VNI
     * @param type the operation type of the flow rules
     */
    void programSnatDiffSegmentRules(DeviceId deviceId, SegmentationId matchVni,
                          IpAddress srcIP, MacAddress ethDst,
                          MacAddress ethSrc, IpAddress ipSrc,
                          SegmentationId actionVni, Objective.Operation type);

    /**
     * Assemble the SNAT table rules.
     * Match: ipv4 type, vnid, destination ip and source ip.
     * Action: upload to controller.
     *
     * @param deviceId Device Id
     * @param matchVni the vni of L3 network
     * @param srcIP source ip
     * @param dstIP destination ip
     * @param prefix prefix
     * @param type the operation type of the flow rules
     */
    void programSnatSameSegmentUploadControllerRules(DeviceId deviceId,
                                                     SegmentationId matchVni,
                                                     IpAddress srcIP,
                                                     IpAddress dstIP,
                                                     IpPrefix prefix,
                                                     Objective.Operation type);

    /**
     * Remove the SNAT table rules.
     *
     * @param deviceId Device Id
     * @param selector selector of rules
     * @param treatment treatment of rules
     * @param priority priority of rules
     * @param type the operation type of the flow rules
     */
    void removeSnatRules(DeviceId deviceId, TrafficSelector selector,
                         TrafficTreatment treatment, int priority,
                         Objective.Operation type);
}
