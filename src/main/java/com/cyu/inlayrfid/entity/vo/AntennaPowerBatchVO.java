package com.cyu.inlayrfid.entity.vo;

import java.util.List;

/**
 * 批量修改天线功率结果。
 */
public class AntennaPowerBatchVO {

    /**
     * 设置后的功率，单位 dBm。
     */
    private int powerDbm;

    /**
     * 每根天线的设置结果。
     */
    private List<AntennaSetResultVO> results;

    public AntennaPowerBatchVO() {
    }

    public AntennaPowerBatchVO(int powerDbm, List<AntennaSetResultVO> results) {
        this.powerDbm = powerDbm;
        this.results = results;
    }

    public int getPowerDbm() {
        return powerDbm;
    }

    public void setPowerDbm(int powerDbm) {
        this.powerDbm = powerDbm;
    }

    public List<AntennaSetResultVO> getResults() {
        return results;
    }

    public void setResults(List<AntennaSetResultVO> results) {
        this.results = results;
    }
}