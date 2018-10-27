package org.onosproject.net.statistic.impl;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;

import java.io.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Created by root on 18-3-28.
 * redis的包加不進來，先用mysql
 */
public class ProToRedis extends Thread {
    private final Logger log = getLogger(getClass());

    String s;
    ConcurrentHashMap<String, String> flowIdFlowRate;
    public ProToRedis(String s, ConcurrentHashMap<String, String> flowIdFlowRate) {
        this.s = s;
        this.flowIdFlowRate = flowIdFlowRate;
    }


    //310000413e7182|of:0000000000000bbd|0b/s
    public void run(){
        //synchronized (ProToRedis.class){
            String[] flowId_deviceId_flowRate = StringUtils.split(s.trim(), "|");

            log.info(flowId_deviceId_flowRate[0] + "|" + flowId_deviceId_flowRate[1]);
            log.info(flowId_deviceId_flowRate[2]);
            StringBuffer sb_key = new StringBuffer();
            sb_key.append(flowId_deviceId_flowRate[0]).append("|").append(flowId_deviceId_flowRate[1]);
            //String s_key = sb_key.toString().trim();
            String s_key = flowId_deviceId_flowRate[0];
            String s_value = flowId_deviceId_flowRate[2].toString().trim();
            //unique
            File csvFile = new File("/home/lihaifeng/flowIdFlowRate.csv");


            //disunique
            //File csvFile1 = new File("/home/zengxiaosen/flowId_flowRate_all.csv");
            //checkExist(csvFile);
            //boolean b = appendData(csvFile, standard_deviation+"");
            //如果是沒有這個key就append，有這個key就更改
            boolean b = updateData(flowIdFlowRate, csvFile, s_key, s_value);

//            if(b == true){
//                log.info("update追加写成功..");
//            }else{
//                log.info("update追加写失败..");
//            }


            try{

            }catch (Exception e){
                e.printStackTrace();
            }

        //}
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
     * change to multi_thread exchange data
     * @param flowIdFlowRate
     * @param csvFile
     * @param s_key
     * @param s_value
     * @return
     */
    public boolean updateData(ConcurrentHashMap<String, String> flowIdFlowRate, File csvFile, String s_key, String s_value){
        //如果是沒有這個key就append，有這個key就更改

        try {
            //read
            FileInputStream fis = new FileInputStream(csvFile);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis));
            String line = null;
            int ifhavingkey = 0;
            int rowhavingkey = 0;
//            while((line = br.readLine()) != null){
//                if(line.contains(s_key)){
//                    ifhavingkey = 1;
//
//                    break;
//                }else{
//                    continue;
//                }
//            }
//            fis.close();
//            br.close();

            //write
//            BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(csvFile, true), "GBK"), 1024);
//            if(ifhavingkey == 0){
//                bw.write(s_key+","+s_value);
//                bw.write("\n");
//            }else{
//                //含有，udate值
//                //這裏暫時把新的flowrate寫到高行,reactiveforwarding 模塊讀最新值就好
//                bw.write(s_key+","+s_value);
//                bw.write("\n");
//
//            }
            flowIdFlowRate.put(s_key, s_value);

//            bw.close();
            return true;


        } catch (Exception e) {
            e.printStackTrace();
        }

        return false;
    }
}
