package com.cyu.inlayrfid.config;

import com.inlaylink.rfid.bean.config.Session;
import com.inlaylink.rfid.bean.config.Target;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RFID 配置属性（映射 application.yml 中 rfid.* 配置）
 * <p>
 * 通过启动类上的 {@code @EnableConfigurationProperties(RfidProperties.class)} 注册成 Bean。
 */
@Data
@ConfigurationProperties(prefix = "rfid")
public class RfidProperties {

    /** 串口路径，"auto" 表示自动扫描 */
    private String serialPort = "auto";

    /** 串口波特率 */
    private int baudRate = 115200;

    /** 天线端口配置列表，运行时功率按统一值下发 */
    private List<Antenna> antennas = new ArrayList<>(Arrays.asList(
            new Antenna(0, 1500),
            new Antenna(1, 1500),
            new Antenna(2, 1500),
            new Antenna(3, 1500)
    ));

    /** 查询配置 */
    private Query query = new Query();

    /** Q 值配置 */
    private Q q = new Q();

    /** 连接重试配置 */
    private Reconnect reconnect = new Reconnect();

    /** 盘点配置 */
    private Inventory inventory = new Inventory();

    /**
     * 兼容老代码：取第一根作为默认天线。
     */
    public Antenna getAntenna() {
        return antennas == null || antennas.isEmpty() ? new Antenna() : antennas.get(0);
    }

    /**
     * 天线配置。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Antenna {
        private int id = 0;
        private int power = 2000;

        @Override
        public String toString() {
            return "ANT" + id + "@" + (power / 100.0) + "dBm";
        }
    }

    /**
     * 查询配置。
     */
    @Data
    public static class Query {
        private Session session = Session.S0;
        private Target target = Target.AB;
    }

    /**
     * Q 值配置。
     */
    @Data
    public static class Q {
        private int init = 5;
        private int max = 9;
        private int min = 0;
    }

    /**
     * 连接重试配置。
     */
    @Data
    public static class Reconnect {
        /** 是否开启自动重连 */
        private boolean enabled = true;
        /** 重连间隔（秒） */
        private int intervalSeconds = 5;
        /** 最大尝试次数，<=0 表示无限重试 */
        private int maxAttempts = 0;
    }

    /**
     * 盘点配置。
     */
    @Data
    public static class Inventory {
        /** 读写器连接成功后是否自动开始读取（当前业务固定手动读取，保留该字段用于兼容旧配置） */
        private boolean autoStart = false;
    }
}
