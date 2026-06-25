package com.cyu.inlayrfid.entity.vo;

import java.util.List;

/**
 * RFID 当前运行状态。
 */
public class RfidStatusVO {

    /**
     * 读写器是否已连接。
     */
    private boolean connected;

    /**
     * 当前是否正在读取。
     */
    private boolean reading;

    /**
     * 实际连接成功的串口。
     */
    private String actualSerialPort;

    /**
     * SDK 标签回调总次数，包含重复读取。
     */
    private long totalReads;

    /**
     * 已读取到的不同 EPC 数量。
     */
    private int uniqueCount;

    /**
     * 最新标签事件序号。
     */
    private long latestSeq;

    /**
     * 最近一次收到 SDK 标签回调的时间戳，毫秒。
     */
    private long lastTagCallbackTime;

    /**
     * 距离最近一次标签回调已经过去的秒数。
     */
    private long lastTagCallbackAgoSeconds;

    /**
     * 最近一次自动或手动重启读取的时间戳，毫秒。
     */
    private long lastReadingRestartTime;

    /**
     * 天线配置列表。
     */
    private List<AntennaVO> antennas;

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isReading() {
        return reading;
    }

    public void setReading(boolean reading) {
        this.reading = reading;
    }

    public String getActualSerialPort() {
        return actualSerialPort;
    }

    public void setActualSerialPort(String actualSerialPort) {
        this.actualSerialPort = actualSerialPort;
    }

    public long getTotalReads() {
        return totalReads;
    }

    public void setTotalReads(long totalReads) {
        this.totalReads = totalReads;
    }

    public int getUniqueCount() {
        return uniqueCount;
    }

    public void setUniqueCount(int uniqueCount) {
        this.uniqueCount = uniqueCount;
    }

    public long getLatestSeq() {
        return latestSeq;
    }

    public void setLatestSeq(long latestSeq) {
        this.latestSeq = latestSeq;
    }

    public long getLastTagCallbackTime() {
        return lastTagCallbackTime;
    }

    public void setLastTagCallbackTime(long lastTagCallbackTime) {
        this.lastTagCallbackTime = lastTagCallbackTime;
    }

    public long getLastTagCallbackAgoSeconds() {
        return lastTagCallbackAgoSeconds;
    }

    public void setLastTagCallbackAgoSeconds(long lastTagCallbackAgoSeconds) {
        this.lastTagCallbackAgoSeconds = lastTagCallbackAgoSeconds;
    }

    public long getLastReadingRestartTime() {
        return lastReadingRestartTime;
    }

    public void setLastReadingRestartTime(long lastReadingRestartTime) {
        this.lastReadingRestartTime = lastReadingRestartTime;
    }

    public List<AntennaVO> getAntennas() {
        return antennas;
    }

    public void setAntennas(List<AntennaVO> antennas) {
        this.antennas = antennas;
    }
}