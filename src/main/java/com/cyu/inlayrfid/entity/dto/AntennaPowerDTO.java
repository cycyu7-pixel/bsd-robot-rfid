package com.cyu.inlayrfid.entity.dto;

import lombok.Data;

/**
 * 天线功率修改请求参数。
 */
@Data
public class AntennaPowerDTO {

    /**
     * 天线功率，单位 dBm，范围 0~33。
     */
    private Integer power;
}
