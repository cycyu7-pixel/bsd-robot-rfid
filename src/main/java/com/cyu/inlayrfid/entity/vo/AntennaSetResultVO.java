package com.cyu.inlayrfid.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 天线端口配置应用结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AntennaSetResultVO {

    /**
     * 天线端口编号。
     */
    private int antId;

    /**
     * 是否应用成功。
     */
    private boolean success;
}
