package org.onosproject.core;

import org.onosproject.net.Path;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface PathChoiceItf {

    double getSdRaw(List<Double> allPathLinksRestBwAfterAddFlow, double meanLinksResBwAfterAdd);
    boolean getPathCanChooseFlag(double flowbw, long IntraLinkLoadBw);
    double getSumLinksRestBwAfterAddFlow(List<Double> allPathLinksRestBwAfterAddFlow);
    List<Double> getOtherPathLinksRestBw(Map<Path, Integer> pathIndexOfPaths, Integer curPathIndex, Map<Integer, String> pathIndexLinksRestBwOfPaths);
    double getFlowBw(Double curFlowSpeed);
}
