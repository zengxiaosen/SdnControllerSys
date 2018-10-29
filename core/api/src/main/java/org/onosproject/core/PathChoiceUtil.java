package org.onosproject.core;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Service;

import java.util.List;

@Component(immediate = true)
@Service
public class PathChoiceUtil implements PathChoiceItf {

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
}
