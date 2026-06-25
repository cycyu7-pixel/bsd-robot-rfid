package com.cyu.inlayrfid.entity.dto;

/**
 * 天线功率修改请求参数。
 */
public class AntennaPowerDTO {

    /**
     * 天线功率，单位 dBm，范围 0~33。
     */
    private Integer power;

    public Integer getPower() {
        return power;
    }

    public void setPower(Integer power) {
        this.power = power;
    }
}