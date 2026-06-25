package com.cyu.inlayrfid.entity.vo;

/**
 * 天线配置展示对象。
 */
public class AntennaVO {

    /**
     * 天线编号。
     */
    private int id;

    /**
     * SDK 原始功率，单位 0.1 dBm。
     */
    private int power;

    /**
     * 前端展示功率，单位 dBm。
     */
    private double powerDbm;

    /**
     * 天线是否启用。
     */
    private boolean enabled;

    public AntennaVO() {
    }

    public AntennaVO(int id, int power, double powerDbm, boolean enabled) {
        this.id = id;
        this.power = power;
        this.powerDbm = powerDbm;
        this.enabled = enabled;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getPower() {
        return power;
    }

    public void setPower(int power) {
        this.power = power;
    }

    public double getPowerDbm() {
        return powerDbm;
    }

    public void setPowerDbm(double powerDbm) {
        this.powerDbm = powerDbm;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}