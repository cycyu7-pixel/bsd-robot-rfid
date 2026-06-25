package com.cyu.inlayrfid.entity.vo;

/**
 * 单根天线设置结果。
 */
public class AntennaSetResultVO {

    /**
     * 天线编号。
     */
    private int antId;

    /**
     * 是否设置成功。
     */
    private boolean success;

    public AntennaSetResultVO() {
    }

    public AntennaSetResultVO(int antId, boolean success) {
        this.antId = antId;
        this.success = success;
    }

    public int getAntId() {
        return antId;
    }

    public void setAntId(int antId) {
        this.antId = antId;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }
}