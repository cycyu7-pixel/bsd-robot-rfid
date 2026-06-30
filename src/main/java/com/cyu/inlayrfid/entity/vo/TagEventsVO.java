package com.cyu.inlayrfid.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * RFID 标签事件增量查询结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagEventsVO {

    /**
     * 当前最新事件序号。
     */
    private long latestSeq;

    /**
     * 新增标签事件列表。
     */
    private List<TagEventVO> items;
}
