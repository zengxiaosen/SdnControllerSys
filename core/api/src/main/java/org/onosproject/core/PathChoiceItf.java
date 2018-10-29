package org.onosproject.core;

import java.util.List;

public interface PathChoiceItf {

    double getSdRaw(List<Double> allPathLinksRestBwAfterAddFlow, double meanLinksResBwAfterAdd);
}
