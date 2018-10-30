package org.onosproject.core;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.net.Link;
import org.onosproject.net.Path;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.slf4j.LoggerFactory.getLogger;

@Component(immediate = true)
@Service
public class PathChoiceUtil implements PathChoiceItf {

    private final Logger log = getLogger(getClass());

    @Override
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


    @Override
    public boolean getPathCanChooseFlag(double flowbw, long IntraLinkLoadBw) {
        if(flowbw > IntraLinkLoadBw){
            //log.info("flow speed too large");
            return false;
        }else{
            //log.info("flow is enough to put");
            return true;
        }
    }

    @Override
    public double getSumLinksRestBwAfterAddFlow(List<Double> allPathLinksRestBwAfterAddFlow) {
        double sumLinksRestBwAfterAddFlow = 0;
        for(int k1=0; k1<allPathLinksRestBwAfterAddFlow.size(); k1++){
            double t = allPathLinksRestBwAfterAddFlow.get(k1);
            sumLinksRestBwAfterAddFlow += t;
        }
        return sumLinksRestBwAfterAddFlow;
    }

    @Override
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


    @Override
    public double getFlowBw(Double curFlowSpeed) {
        //init with a small score
        double flowbw = 10.0;
        if(curFlowSpeed > 0){
            flowbw = curFlowSpeed;
        }
        return flowbw;
    }



}
