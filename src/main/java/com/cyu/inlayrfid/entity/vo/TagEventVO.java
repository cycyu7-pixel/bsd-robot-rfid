package com.cyu.inlayrfid.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RFID 标签读取事件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
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
}
