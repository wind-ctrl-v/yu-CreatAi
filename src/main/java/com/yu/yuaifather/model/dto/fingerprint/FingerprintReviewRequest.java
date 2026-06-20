package com.yu.yuaifather.model.dto.fingerprint;

import lombok.Data;

import java.io.Serializable;

/**
 * 指纹审核操作请求（通过 / 拒绝 / 归档）。
 *
 * @author yu
 */
@Data
public class FingerprintReviewRequest implements Serializable {

    /**
     * 指纹 ID
     */
    private Long id;

    /**
     * 审核备注（拒绝/归档时建议填写）
     */
    private String remark;

    private static final long serialVersionUID = 1L;
}
