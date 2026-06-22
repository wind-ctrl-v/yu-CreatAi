package com.yu.yuaifather.model.dto.fingerprint;

import com.yu.yuaifather.model.dto.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 指纹分页查询请求（管理员）。
 *
 * @author yu
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class FingerprintQueryRequest extends PageRequest implements Serializable {

    /**
     * 风格画像（startup / enterprise / default 等）
     */
    private String styleProfile;

    /**
     * 治理状态（CANDIDATE / APPROVED / REJECTED / ARCHIVED）
     */
    private String status;

    /**
     * 来源类型（EXTRACTED_FINGERPRINT / SELF_FED_FINGERPRINT）
     */
    private String sourceType;

    /**
     * 调性关键词（模糊匹配）
     */
    private String tone;

    private static final long serialVersionUID = 1L;
}
