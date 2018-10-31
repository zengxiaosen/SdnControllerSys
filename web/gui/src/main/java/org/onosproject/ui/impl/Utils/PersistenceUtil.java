package org.onosproject.ui.impl.Utils;

import org.onosproject.ui.impl.Exceptions.APIException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

import static org.onosproject.ui.impl.constCollection.constCollect.*;

public class PersistenceUtil {


    public static void persistenceLog(double linkBwUsedRateStandardDeviation, double linkBwStandardDeviation, double linkMeanTrafficBwUsedRate, double linkMeanTrafficBandWidth) throws Exception{
        File csvBandWidthUsedRateStandardDeviation = new File(CSVPATH_BANDWIDTH_USEDRATE_STANDARDDEVIATION);
        File csvlinkBwStandardDeviation = new File(CSVPATH_LINK_BW_STANDARDDEVIATION);
        File csvlinkMeanTrafficBwUsedRate = new File(CSVPATH_LINK_MEAN_TRAFFIC_BW_USEDRATE);
        File csvlinkMeanTrafficBandWidth = new File(CSVPATH_LINK_MEAN_TRAFFIC_BANDWITH);

        checkExist(csvBandWidthUsedRateStandardDeviation);
        checkExist(csvlinkBwStandardDeviation);
        checkExist(csvlinkMeanTrafficBwUsedRate);
        checkExist(csvlinkMeanTrafficBandWidth);

        boolean blinkBwUsedRateStandardDeviation = appendData(csvBandWidthUsedRateStandardDeviation, linkBwUsedRateStandardDeviation+"");
        boolean blinkBwStandardDeviation = appendData(csvlinkBwStandardDeviation, linkBwStandardDeviation + "");
        boolean blinkMeanTrafficBwUsedRate = appendData(csvlinkMeanTrafficBwUsedRate, linkMeanTrafficBwUsedRate+"");
        boolean blinkMeanTrafficBandWidth = appendData(csvlinkMeanTrafficBandWidth, linkMeanTrafficBandWidth + "");
        if(!blinkBwUsedRateStandardDeviation || !blinkBwStandardDeviation || !blinkMeanTrafficBwUsedRate || !blinkMeanTrafficBandWidth){
            throw new APIException(PERSISTENCE_ERR_CODE, "persistenceLog exception");
        }
    }


    private static void checkExist(File file) {
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

    private static boolean appendData(File csvFile, String data){
        try{
            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvFile, true), "GBK"), 1024);
            bw.write(data);
            bw.write("\n");
            //bw.flush();
            bw.close();
            return true;
        }catch (Exception e){
            e.printStackTrace();
        }
        return false;
    }
}
