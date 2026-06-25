package com.cyu.inlayrfid.entity.vo;

import java.util.List;

/**
 * RFID 标签事件增量查询结果。
 */
public class TagEventsVO {

    /**
     * 当前最新事件序号。
     */
    private long latestSeq;

    /**
     * 新增标签事件列表。
     */
    private List<TagEventVO> items;

    public TagEventsVO() {
    }

    public TagEventsVO(long latestSeq, List<TagEventVO> items) {
        this.latestSeq = latestSeq;
        this.items = items;
    }

    public long getLatestSeq() {
        return latestSeq;
    }

    public void setLatestSeq(long latestSeq) {
        this.latestSeq = latestSeq;
    }

    public List<TagEventVO> getItems() {
        return items;
    }

    public void setItems(List<TagEventVO> items) {
        this.items = items;
    }
}