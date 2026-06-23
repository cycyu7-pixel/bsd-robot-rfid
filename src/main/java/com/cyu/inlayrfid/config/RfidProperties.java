package com.cyu.inlayrfid.config;

import com.inlaylink.rfid.bean.config.Session;
import com.inlaylink.rfid.bean.config.Target;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RFID 配置属性（映射 application.yml 中 rfid.* 配置）
 * <p>
 * 通过启动类上的 {@code @EnableConfigurationProperties(RfidProperties.class)} 注册成 Bean。
 */
@ConfigurationProperties(prefix = "rfid")
public class RfidProperties {

    /** 串口路径，"auto" 表示自动扫描 */
    private String serialPort = "auto";

    /** 串口波特率 */
    private int baudRate = 115200;

    /** 天线配置列表（每根天线独立功率） */
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

    // ===== Inner classes =====

    public static class Antenna {
        private int id = 0;
        private int power = 2000;

        public Antenna() {}
        public Antenna(int id, int power) { this.id = id; this.power = power; }

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public int getPower() { return power; }
        public void setPower(int power) { this.power = power; }

        @Override
        public String toString() { return "ANT" + id + "@" + (power / 100.0) + "dBm"; }
    }

    public static class Query {
        private Session session = Session.S0;
        private Target target = Target.AB;

        public Session getSession() { return session; }
        public void setSession(Session session) { this.session = session; }
        public Target getTarget() { return target; }
        public void setTarget(Target target) { this.target = target; }
    }

    public static class Q {
        private int init = 5;
        private int max = 9;
        private int min = 0;

        public int getInit() { return init; }
        public void setInit(int init) { this.init = init; }
        public int getMax() { return max; }
        public void setMax(int max) { this.max = max; }
        public int getMin() { return min; }
        public void setMin(int min) { this.min = min; }
    }

    public static class Reconnect {
        /** 是否开启自动重连 */
        private boolean enabled = true;
        /** 重连间隔（秒） */
        private int intervalSeconds = 5;
        /** 最大尝试次数，<=0 表示无限重试 */
        private int maxAttempts = 0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    // ===== Getters/Setters =====

    public String getSerialPort() { return serialPort; }
    public void setSerialPort(String serialPort) { this.serialPort = serialPort; }
    public int getBaudRate() { return baudRate; }
    public void setBaudRate(int baudRate) { this.baudRate = baudRate; }
    public Antenna getAntenna() {
        // 兼容老代码：取第一根作为默认
        return antennas == null || antennas.isEmpty() ? new Antenna() : antennas.get(0);
    }
    public List<Antenna> getAntennas() { return antennas; }
    public void setAntennas(List<Antenna> antennas) { this.antennas = antennas; }
    public Query getQuery() { return query; }
    public void setQuery(Query query) { this.query = query; }
    public Q getQ() { return q; }
    public void setQ(Q q) { this.q = q; }
    public Reconnect getReconnect() { return reconnect; }
    public void setReconnect(Reconnect reconnect) { this.reconnect = reconnect; }
}
