package com.hmdp.dto;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?> list;       // 滚动分页的数据类型
    private Long minTime;       // 上一页查询结果的最小时间
    private Integer offset;     // 便宜量
}
