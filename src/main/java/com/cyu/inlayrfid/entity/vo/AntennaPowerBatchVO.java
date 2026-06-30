package com.cyu.inlayrfid.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一修改所有天线功率结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AntennaPowerBatchVO {

    /**
     * 设置后的统一功率，单位 dBm。
     */
    private int powerDbm;

    /**
     * 每个天线端口的配置应用结果。
     */
    private List<AntennaSetResultVO> results;
}
