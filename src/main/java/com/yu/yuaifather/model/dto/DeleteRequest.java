package com.yu.yuaifather.model.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class DeleteRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 删除原因（N-3：删除触发负反馈的边界控制）
     * 可选值：test / correction / expire / dislike / unknown
     */
    private String deleteReason;

    private static final long serialVersionUID = 1L;
}
