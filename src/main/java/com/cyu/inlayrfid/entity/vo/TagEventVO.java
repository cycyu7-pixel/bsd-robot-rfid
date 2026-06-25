package com.cyu.inlayrfid.entity.vo;

/**
 * RFID 标签读取事件。
 */
public class TagEventVO {

    /**
     * 标签事件递增序号。
     */
    private long seq;

    /**
     * 标签 EPC 编码。
     */
    private String epc;

    /**
     * 信号强度，单位 dBm。
     */
    private float rssi;

    /**
     * 读取到该标签的天线编号。
     */
    private int antenna;

    /**
     * 读取时间戳，毫秒。
     */
    private long timestamp;

    public TagEventVO() {
    }

    public TagEventVO(long seq, String epc, float rssi, int antenna, long timestamp) {
        this.seq = seq;
        this.epc = epc;
        this.rssi = rssi;
        this.antenna = antenna;
        this.timestamp = timestamp;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getEpc() {
        return epc;
    }

    public void setEpc(String epc) {
        this.epc = epc;
    }

    public float getRssi() {
        return rssi;
    }

    public void setRssi(float rssi) {
        this.rssi = rssi;
    }

    public int getAntenna() {
        return antenna;
    }

    public void setAntenna(int antenna) {
        this.antenna = antenna;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}