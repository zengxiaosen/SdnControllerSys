package org.onosproject.net.statistic.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Created by root on 18-3-28.
 */
public class DBHelper {
    public static final String url = "jdbc:mysql://127.0.0.1/taotao";
    public static final String name = "com.mysql.jdbc.Driver";
    public static final String user = "zengxiaosen";
    public static final String password = "031730";

    public Connection conn = null;
    public PreparedStatement pst = null;

    public DBHelper(String sql){
        try{
            Class.forName(name);
            conn = DriverManager.getConnection(url, user, password);
            pst = conn.prepareStatement(sql);
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    public void close(){
        try{
            this.conn.close();
            this.pst.close();

        }catch (SQLException e){
            e.printStackTrace();
        }
    }
}
