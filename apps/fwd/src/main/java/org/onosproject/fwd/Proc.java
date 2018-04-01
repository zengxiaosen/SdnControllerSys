package org.onosproject.fwd;

import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Created by root on 18-3-28.
 */
//just test
public class Proc extends Thread{
    private final Logger log = getLogger(getClass());
    public void run(){
        for(int i=0; i< 10; i++){
            log.info("processor: " + i);

        }
    }
}
