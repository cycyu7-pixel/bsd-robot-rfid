package com.cyu.inlayrfid.entity.vo;

/**
 * 单根天线功率修改结果。
 */
public class AntennaPowerVO {

    /**
     * 天线编号。
     */
    private int antId;

    /**
     * 设置后的功率，单位 dBm。
     */
    private int powerDbm;

    public AntennaPowerVO() {
    }

    public AntennaPowerVO(int antId, int powerDbm) {
        this.antId = antId;
        this.powerDbm = powerDbm;
    }

    public int getAntId() {
        return antId;
    }

    public void setAntId(int antId) {
        this.antId = antId;
    }

    public int getPowerDbm() {
        return powerDbm;
    }

    public void setPowerDbm(int powerDbm) {
        this.powerDbm = powerDbm;
    }
}