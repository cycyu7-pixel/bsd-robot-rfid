package com.cyu.inlayrfid.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一次性扫描请求的立即响应。
 */
@Data
@AllArgsConstructor
public class ScanResponseVO {

    /**
     * 扫描请求唯一标识，回调时携带此 ID 供调用方匹配。
     */
    private String requestId;
}