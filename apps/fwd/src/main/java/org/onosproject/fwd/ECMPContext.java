package org.onosproject.fwd;

import com.google.common.collect.Maps;
import org.onosproject.net.Path;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ECMPContext {

    private Map<String, Integer> flowDistributionMap;
    private static Object innerLock;

    private ECMPContext() {
        flowDistributionMap = Maps.newConcurrentMap();
        innerLock = new Object();
    }

    private static ECMPContext ecmpContext = new ECMPContext();

    public static ECMPContext BuildECMPContext() {
        return ecmpContext;
    }


    public Map<String, Integer> getFlowDistributionMap() {
        return flowDistributionMap;
    }

    public void setFlowDistributionMap(Map<String, Integer> flowDistributionMap) {
        synchronized (innerLock) {
            this.flowDistributionMap = flowDistributionMap;
        }
    }


}
