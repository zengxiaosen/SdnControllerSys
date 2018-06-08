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
package org.onosproject.net.statistic.impl;

import com.google.common.base.MoreObjects;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.GroupId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Link;
import org.onosproject.net.Path;

import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleListener;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.statistic.DefaultLoad;
import org.onosproject.net.statistic.Load;
import org.onosproject.net.statistic.StatisticService;
import org.onosproject.net.statistic.StatisticStore;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;
import static org.onosproject.security.AppGuard.checkPermission;
import static org.onosproject.security.AppPermission.Type.*;

////
import com.google.common.base.MoreObjects;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.GroupId;
import org.onosproject.incubator.net.PortStatisticsService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Link;
import org.onosproject.net.Path;

import org.onosproject.net.flow.*;
import org.onosproject.net.statistic.DefaultLoad;
import org.onosproject.net.statistic.Load;
import org.onosproject.net.statistic.StatisticService;
import org.onosproject.net.statistic.StatisticStore;
import org.slf4j.Logger;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.slf4j.LoggerFactory.getLogger;
import static org.onosproject.security.AppGuard.checkPermission;
import static org.onosproject.security.AppPermission.Type.*;



/**
 * Provides an implementation of the Statistic Service.
 */
@Component(immediate = true)
@Service
public class StatisticManager implements StatisticService {

    private final Logger log = getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StatisticStore statisticStore;
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PortStatisticsService portStatisticsService;

    private final InternalFlowRuleListener listener = new InternalFlowRuleListener();
    private static ConcurrentHashMap<String, String> flowId_flowRate = new ConcurrentHashMap<>();
    private static ReadWriteLock rw1 = new ReentrantReadWriteLock();
    @Override
    public ConcurrentHashMap<String, String> getFlowId_flowRate() {
        rw1.readLock().lock();
        ConcurrentHashMap<String, String> temp = new ConcurrentHashMap<>(flowId_flowRate);
        rw1.readLock().unlock();
        return temp;
        //rw1.readLock().unlock();
    }

    @Override
    public void setFlowId_flowRate(ConcurrentHashMap<String, String> flowId_flowRate) {
        rw1.writeLock().lock();
        try{
            StatisticManager.flowId_flowRate = flowId_flowRate;
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            rw1.writeLock().unlock();
        }
    }

    public void setFlowId_flowRateKV(String key, String value){
        rw1.writeLock().lock();
        try{
            flowId_flowRate.put(key, value);
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            rw1.writeLock().unlock();
        }
    }





    @Activate
    public void activate() {
        flowRuleService.addListener(listener);
        log.info("Started");

    }

    @Deactivate
    public void deactivate() {
        flowRuleService.removeListener(listener);
        log.info("Stopped");
    }

    @Override
    public Load load(Link link) {
        checkPermission(STATISTIC_READ);

        return load(link.src());
    }
    @Override
    public  Load vportload(ConnectPoint connectPoint) {
        return portStatisticsService.load(connectPoint);
    }

    @Override
    public Load load(Link link, ApplicationId appId, Optional<GroupId> groupId) {
        checkPermission(STATISTIC_READ);

        Statistics stats = getStatistics(link.src());
        if (!stats.isValid()) {
            return new DefaultLoad();
        }

        ImmutableSet<FlowEntry> current = FluentIterable.from(stats.current())
                .filter(hasApplicationId(appId))
                .filter(hasGroupId(groupId))
                .toSet();
        ImmutableSet<FlowEntry> previous = FluentIterable.from(stats.previous())
                .filter(hasApplicationId(appId))
                .filter(hasGroupId(groupId))
                .toSet();

        return new DefaultLoad(aggregate(current), aggregate(previous));
    }

    @Override
    public Load load(ConnectPoint connectPoint) {
        checkPermission(STATISTIC_READ);

        return loadInternal(connectPoint);
    }

    @Override
    public Link max(Path path) {
        checkPermission(STATISTIC_READ);

        if (path.links().isEmpty()) {
            return null;
        }
        Load maxLoad = new DefaultLoad();
        Link maxLink = null;
        for (Link link : path.links()) {
            Load load = loadInternal(link.src());
            if (load.rate() > maxLoad.rate()) {
                maxLoad = load;
                maxLink = link;
            }
        }
        return maxLink;
    }

    @Override
    public Link min(Path path) {
        checkPermission(STATISTIC_READ);

        if (path.links().isEmpty()) {
            return null;
        }
        Load minLoad = new DefaultLoad();
        Link minLink = null;
        for (Link link : path.links()) {
            Load load = loadInternal(link.src());
            if (load.rate() < minLoad.rate()) {
                minLoad = load;
                minLink = link;
            }
        }
        return minLink;
    }

    @Override
    public FlowRule highestHitter(ConnectPoint connectPoint) {
        checkPermission(STATISTIC_READ);

        Set<FlowEntry> hitters = statisticStore.getCurrentStatistic(connectPoint);
        if (hitters.isEmpty()) {
            return null;
        }

        FlowEntry max = hitters.iterator().next();
        for (FlowEntry entry : hitters) {
            if (entry.bytes() > max.bytes()) {
                max = entry;
            }
        }
        return max;
    }

    /**
     * 自研，每流統計模塊
     * @param connectPoint
     * @return
     */
    private Load loadInternal(ConnectPoint connectPoint) {
        Statistics stats = getStatistics(connectPoint);

        if (!stats.isValid()) {

            return new DefaultLoad();
        }
        HashMap<String, Long> flowCurrent_idBytes1 = new HashMap<>();
        for(FlowEntry flowEntry1 : stats.current){
//            log.info("currentFlowId: " + flowEntry1.id().toString());
//            log.info("currentFlowBytes: " + flowEntry1.bytes() + "");

            flowCurrent_idBytes1.put(flowEntry1.id().toString().trim(), flowEntry1.bytes());
        }

        HashMap<String, Long> flowCurrent_idBytes2 = new HashMap<>();
        for(FlowEntry flowEntry2 : stats.previous){
//            log.info("previousFlowId: " + flowEntry2.id().toString());
//            log.info("previousFlowBytes: " + flowEntry2.bytes());
            flowCurrent_idBytes2.put(flowEntry2.id().toString().trim(), flowEntry2.bytes());
        }

        for(Map.Entry<String, Long> stringLongEntry : flowCurrent_idBytes1.entrySet()){
            String key = stringLongEntry.getKey();//flowId
            if(flowCurrent_idBytes2.containsKey(key)){
                long flowBytesNow = stringLongEntry.getValue();
                long flowBytesPrevious = flowCurrent_idBytes2.get(key);
                long bytesTrans = flowBytesNow - flowBytesPrevious;
                long flowRate = bytesTrans / 5;
                String flowRateString = String.valueOf(flowRate);
                StringBuffer sb = new StringBuffer();
                sb.append(key).append("|").append(connectPoint.deviceId().toString()).append("|").append(flowRateString).append("b/s");
                //sb:flowId|deviceId|flowRate
//                log.info("flowId|deviceId|flowRate:");
//                log.info(sb.toString());
                //update flow information to file
//                File csvFile = new File("/home/lihaifeng/deviceId_FlowId_FlowRate.csv");
//                checkExist(csvFile);
                /**
                 * sb:flowId|deviceId|flowRate
                 * 取出文件中的所有flowId，如果有，同時deviceid一楊，則更新flowRate
                 * 否則append添加
                 */
                //async
//                ProToRedis proToRedis = new ProToRedis(sb.toString(), flowId_flowRate);
//                proToRedis.start();
                StringBuffer sbkey = new StringBuffer();
                sbkey.append(key).append("|").append(connectPoint.deviceId().toString());

                //log.info(sbkey.toString()+"|"+flowRateString+"b/s");
                //flowId_flowRate.put(sbkey.toString(), flowRateString+"b/s");
                setFlowId_flowRateKV(sbkey.toString(), flowRateString+"b/s");
                //log.info("flowId_flowRate.size: " + flowId_flowRate.size());

            }
        }


        return new DefaultLoad(aggregate(stats.current), aggregate(stats.previous));
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

    /**
     * sb:flowId|deviceId|flowRate
     * 取出文件中的所有flowId，如果有，同時deviceid一楊，則更新flowRate
     * 否則append添加
     *
     * 這裏是直接append，待修復！！！
     * 方法:只能read then write
     */

    public boolean appendData(File csvFile, String data, String filePath){
        try{
            BufferedReader br = new BufferedReader(new FileReader(filePath));
            StringBuffer sb = new StringBuffer();
            String temp = null;
            int line = 0;
            temp = br.readLine();
            while(temp != null){
                String FlowId = StringUtils.split(temp.trim(), "|")[0];
                if(temp.contains(FlowId.trim())){
                    continue;
                }else{
                    sb.append(temp).append("\n");
                }
                line ++;
                temp = br.readLine();
            }
            br.close();


            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvFile, true), "GBK"), 1024);
            bw.write(sb.toString());
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
     * Returns statistics of the specified port.
     *
     * @param connectPoint port to query
     * @return statistics
     */
    private Statistics getStatistics(ConnectPoint connectPoint) {
        Set<FlowEntry> current;
        Set<FlowEntry> previous;

        synchronized (statisticStore) {
            current = getCurrentStatistic(connectPoint);
            previous = getPreviousStatistic(connectPoint);
        }




        return new Statistics(current, previous);
    }

    /**
     * Returns the current statistic of the specified port.

     * @param connectPoint port to query
     * @return set of flow entries
     */
    private Set<FlowEntry> getCurrentStatistic(ConnectPoint connectPoint) {
        Set<FlowEntry> stats = statisticStore.getCurrentStatistic(connectPoint);
        if (stats == null) {
            return Collections.emptySet();
        } else {
            return stats;
        }
    }

    /**
     * Returns the previous statistic of the specified port.
     *
     * @param connectPoint port to query
     * @return set of flow entries
     */
    private Set<FlowEntry> getPreviousStatistic(ConnectPoint connectPoint) {
        Set<FlowEntry> stats = statisticStore.getPreviousStatistic(connectPoint);
        if (stats == null) {
            return Collections.emptySet();
        } else {
            return stats;
        }
    }

    // TODO: make aggregation function generic by passing a function
    // (applying Java 8 Stream API?)
    /**
     * Aggregates a set of values.
     * @param values the values to aggregate
     * @return a long value
     */
    private long aggregate(Set<FlowEntry> values) {
        long sum = 0;
        for (FlowEntry f : values) {
            sum += f.bytes();
        }
        return sum;
    }

    /**
     * Internal flow rule event listener.
     */
    private class InternalFlowRuleListener implements FlowRuleListener {

        @Override
        public void event(FlowRuleEvent event) {
            FlowRule rule = event.subject();
            switch (event.type()) {
                case RULE_ADDED:
                case RULE_UPDATED:
                    if (rule instanceof FlowEntry) {
                        statisticStore.addOrUpdateStatistic((FlowEntry) rule);
                    }
                    break;
                case RULE_ADD_REQUESTED:
                    statisticStore.prepareForStatistics(rule);
                    break;
                case RULE_REMOVE_REQUESTED:
                    statisticStore.removeFromStatistics(rule);
                    break;
                case RULE_REMOVED:
                    break;
                default:
                    log.warn("Unknown flow rule event {}", event);
            }
        }
    }

    /**
     * Internal data class holding two set of flow entries.
     */
    private static class Statistics {
        private final ImmutableSet<FlowEntry> current;
        private final ImmutableSet<FlowEntry> previous;

        public Statistics(Set<FlowEntry> current, Set<FlowEntry> previous) {
            this.current = ImmutableSet.copyOf(checkNotNull(current));
            this.previous = ImmutableSet.copyOf(checkNotNull(previous));
        }

        /**
         * Returns flow entries as the current value.
         *
         * @return flow entries as the current value
         */
        public ImmutableSet<FlowEntry> current() {
            return current;
        }

        /**
         * Returns flow entries as the previous value.
         *
         * @return flow entries as the previous value
         */
        public ImmutableSet<FlowEntry> previous() {
            return previous;
        }

        /**
         * Validates values are not empty.
         *
         * @return false if either of the sets is empty. Otherwise, true.
         */
        public boolean isValid() {
            return !(current.isEmpty() || previous.isEmpty());
        }

        @Override
        public int hashCode() {
            return Objects.hash(current, previous);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Statistics)) {
                return false;
            }
            final Statistics other = (Statistics) obj;
            return Objects.equals(this.current, other.current) && Objects.equals(this.previous, other.previous);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .add("current", current)
                    .add("previous", previous)
                    .toString();
        }
    }

    /**
     * Creates a predicate that checks the application ID of a flow entry is the same as
     * the specified application ID.
     *
     * @param appId application ID to be checked
     * @return predicate
     */
    private static Predicate<FlowEntry> hasApplicationId(ApplicationId appId) {
        return flowEntry -> flowEntry.appId() == appId.id();
    }

    /**
     * Create a predicate that checks the group ID of a flow entry is the same as
     * the specified group ID.
     *
     * @param groupId group ID to be checked
     * @return predicate
     */
    private static Predicate<FlowEntry> hasGroupId(Optional<GroupId> groupId) {
        return flowEntry -> {
            if (!groupId.isPresent()) {
                return false;
            }
            // FIXME: The left hand type and right hand type don't match
            // FlowEntry.groupId() still returns a short value, not int.
            return flowEntry.groupId().equals(groupId.get());
        };
    }
}
