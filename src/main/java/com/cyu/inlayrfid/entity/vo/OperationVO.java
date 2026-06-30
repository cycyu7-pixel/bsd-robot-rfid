package com.cyu.inlayrfid.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 读取开关操作结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OperationVO {

    /**
     * 当前是否正在读取。
     */
    private boolean reading;
}
