package com.cyu.inlayrfid.entity.vo;

/**
 * 读取开关操作结果。
 */
public class OperationVO {

    /**
     * 当前是否正在读取。
     */
    private boolean reading;

    public OperationVO() {
    }

    public OperationVO(boolean reading) {
        this.reading = reading;
    }

    public boolean isReading() {
        return reading;
    }

    public void setReading(boolean reading) {
        this.reading = reading;
    }
}