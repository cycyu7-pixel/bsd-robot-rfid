package com.cyu.inlayrfid.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 扫描完成后回调调用方时的数据荷载。
 * <p>
 * 外层由 {@link Result} 包装，通过 error 字段区分：
 * - error == null → 扫描成功，epc 为读取到的标签编码
 * - error != null → 扫描出错/超时，调用方应清缓存后重试
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScanCallbackVO {

    /**
     * 扫描请求唯一标识，与发起时的 requestId 一致。
     */
    private String requestId;

    /**
     * 读取到的标签 EPC 编码。
     * 仅当 error 为 null 时有效。
     */
    private String epc;

    /**
     * 错误信息，null 表示扫描正常完成，非 null 表示出错了。
     * 调用方收到 error 后应清空自己缓存的 requestId，等待下一次扫描。
     */
    private String error;
}