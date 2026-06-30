package com.cyu.inlayrfid.entity.vo;

import lombok.Data;

/**
 * RFID 当前运行状态。
 */
@Data
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
     * 最近一次手动重启读取的时间戳，毫秒。
     */
    private long lastReadingRestartTime;

    /**
     * 当前统一天线功率，SDK 原始单位，1500 = 15 dBm。
     */
    private int antennaPower;

    /**
     * 当前统一天线功率，单位 dBm。
     */
    private double antennaPowerDbm;

    /**
     * 当前配置的天线端口数量。
     */
    private int antennaCount;

    /**
     * 当前所有配置天线端口的功率是否一致。
     */
    private boolean antennaPowerUniform;
}
