package org.onosproject.ui.impl;

import com.google.common.collect.Lists;
import org.apache.commons.lang.StringUtils;
import org.onosproject.net.Path;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;

import static org.slf4j.LoggerFactory.getLogger;

public class WebTrafficComputeBuilder {
    private final Logger log = getLogger(getClass());
    private final static Object syncLock = new Object();
    private static WebTrafficComputeBuilder singleBuilder = null;



    private WebTrafficComputeBuilder() {

    }

    public WebTrafficComputeBuilder build() {

        if(singleBuilder == null) {
            synchronized (syncLock){
                if(singleBuilder == null) {
                    singleBuilder = new WebTrafficComputeBuilder();
                }
            }
        }
        return singleBuilder;
    }





    public double getSdRaw(List<Double> allPathLinksRestBwAfterAddFlow, double meanLinksResBwAfterAdd) {
        double sum = 0;
        for(int k2=0; k2<allPathLinksRestBwAfterAddFlow.size(); k2++){
            double t2 = allPathLinksRestBwAfterAddFlow.get(k2);
            double t3 = t2-meanLinksResBwAfterAdd;
            double t4 = Math.pow(t3, 2);
            sum += t4;
        }
        return sum;
    }


    public boolean getPathCanChooseFlag(double flowbw, long IntraLinkLoadBw) {
        if(flowbw > IntraLinkLoadBw){
            log.info("flow speed too large");
            return false;
        }else{
            log.info("flow is enough to put");
            return true;
        }
    }


    public double getSumLinksRestBwAfterAddFlow(List<Double> allPathLinksRestBwAfterAddFlow) {
        double sumLinksRestBwAfterAddFlow = 0;
        for(int k1=0; k1<allPathLinksRestBwAfterAddFlow.size(); k1++){
            double t = allPathLinksRestBwAfterAddFlow.get(k1);
            sumLinksRestBwAfterAddFlow += t;
        }
        return sumLinksRestBwAfterAddFlow;
    }

    public List<Double> getOtherPathLinksRestBw(Map<Path, Integer> pathIndexOfPaths, Integer curPathIndex, Map<Integer, String> pathIndexLinksRestBwOfPaths) {
        List<Double> otherPathLinksRestBw = Lists.newArrayList();
        for(Map.Entry<Path, Integer> entry : pathIndexOfPaths.entrySet()){
            Path thisPath = entry.getKey();
            Integer thisPathIndex = entry.getValue();
            if(thisPathIndex != curPathIndex){
                // this is the other path needed to compute the rest Bw
                String alllinkRestBw_OfThisPath = pathIndexLinksRestBwOfPaths.get(thisPathIndex);
                //ETL: link1_RestBw|link2_RestBw|link3_RestBw....
                alllinkRestBw_OfThisPath = alllinkRestBw_OfThisPath.substring(0, alllinkRestBw_OfThisPath.length()-1);
                String[] alllinkRestBw = StringUtils.split(alllinkRestBw_OfThisPath, "|");
                for(String s : alllinkRestBw){
                    Double tmp = Double.valueOf(s);
                    otherPathLinksRestBw.add(tmp);
                }
            }

        }
        return otherPathLinksRestBw;
    }


    public double getFlowBw(Double curFlowSpeed) {
        //init with a small score
        double flowbw = 10.0;
        if(curFlowSpeed > 0){
            flowbw = curFlowSpeed;
        }
        return flowbw;
    }
}
