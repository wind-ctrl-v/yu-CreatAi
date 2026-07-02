package com.yu.yuaifather.controller;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.yu.yuaifather.agent.workers.DesignExtractorAgent;
import com.yu.yuaifather.annotation.AuthCheck;
import com.yu.yuaifather.common.BaseResponse;
import com.yu.yuaifather.common.ResultUtils;
import com.yu.yuaifather.constant.UserConstant;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.exception.ThrowUtils;
import com.yu.yuaifather.model.dto.agent.DesignFingerprintDTO;
import com.yu.yuaifather.model.dto.fingerprint.FingerprintQueryRequest;
import com.yu.yuaifather.model.dto.fingerprint.FingerprintReviewRequest;
import com.yu.yuaifather.model.entity.DesignFingerprint;
import com.yu.yuaifather.model.enums.FingerprintStatus;
import com.yu.yuaifather.service.DesignFingerprintService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 设计指纹 管理接口（仅管理员可用）。
 * <p>
 * 提供指纹的分页查询、审核通过、拒绝、归档操作，配合前端审核列表页完成指纹治理。
 *
 * @author yu
 */
@Slf4j
@RestController
@RequestMapping("/fingerprint")
public class DesignFingerprintController {

    @Autowired
    private DesignFingerprintService designFingerprintService;

    @Autowired(required = false)
    private DesignExtractorAgent extractorAgent;

    private final RestTemplate restTemplate;

    public DesignFingerprintController() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        factory.setReadTimeout(30_000);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 管理员分页查询指纹列表。
     * <p>
     * 支持按 styleProfile / status / sourceType / tone 过滤，
     * 默认按 createTime 降序排列（最新入库的优先审核）。
     */
    @PostMapping("/admin/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<DesignFingerprint>> adminListFingerprintByPage(
            @RequestBody FingerprintQueryRequest req) {
        ThrowUtils.throwIf(req == null, ErrorCode.PARAMS_ERROR);
        int pageNum  = Math.max(1, req.getPageNum());
        int pageSize = Math.min(50, Math.max(1, req.getPageSize()));

        QueryWrapper qw = QueryWrapper.create();
        if (StrUtil.isNotBlank(req.getStyleProfile())) {
            qw.eq("styleProfile", req.getStyleProfile());
        }
        if (StrUtil.isNotBlank(req.getStatus())) {
            // 校验状态值合法性
            if (FingerprintStatus.getEnumByValue(req.getStatus()) == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "状态值不合法");
            }
            qw.eq("status", req.getStatus());
        }
        if (StrUtil.isNotBlank(req.getSourceType())) {
            qw.eq("sourceType", req.getSourceType());
        }
        if (StrUtil.isNotBlank(req.getTone())) {
            qw.like("tone", req.getTone().trim());
        }
        qw.orderBy("createTime", false);

        Page<DesignFingerprint> page = designFingerprintService.page(
                new Page<>(pageNum, pageSize), qw);
        return ResultUtils.success(page);
    }

    /**
     * 审核通过：CANDIDATE → APPROVED。
     * <p>
     * 置信度 < 0.7 或自产反哺超配额时由 Service 层拦截返回 false。
     */
    @PostMapping("/admin/approve")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> adminApproveFingerprint(
            @RequestBody FingerprintReviewRequest req) {
        ThrowUtils.throwIf(req == null || req.getId() == null, ErrorCode.PARAMS_ERROR);
        boolean ok = designFingerprintService.approve(req.getId());
        if (!ok) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "审核通过失败，请检查置信度（需 ≥ 0.7）或自产反哺配额是否超限");
        }
        return ResultUtils.success(true);
    }

    /**
     * 拒绝指纹：→ REJECTED，同时触发向量失效。
     */
    @PostMapping("/admin/reject")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> adminRejectFingerprint(
            @RequestBody FingerprintReviewRequest req) {
        ThrowUtils.throwIf(req == null || req.getId() == null, ErrorCode.PARAMS_ERROR);
        boolean ok = designFingerprintService.reject(req.getId(), req.getRemark());
        ThrowUtils.throwIf(!ok, ErrorCode.OPERATION_ERROR, "拒绝失败，指纹可能不存在");
        return ResultUtils.success(true);
    }

    /**
     * 归档指纹：APPROVED → ARCHIVED，同时触发向量失效。
     */
    @PostMapping("/admin/archive")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> adminArchiveFingerprint(
            @RequestBody FingerprintReviewRequest req) {
        ThrowUtils.throwIf(req == null || req.getId() == null, ErrorCode.PARAMS_ERROR);
        boolean ok = designFingerprintService.archive(req.getId(), req.getRemark());
        ThrowUtils.throwIf(!ok, ErrorCode.OPERATION_ERROR, "归档失败，指纹可能不存在或状态不正确");
        return ResultUtils.success(true);
    }

    /**
     * 获取指纹详情（管理员，含 fingerprintJson 原始字段）。
     */
    @GetMapping("/admin/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<DesignFingerprint> adminGetFingerprint(@RequestParam Long id) {
        ThrowUtils.throwIf(id == null || id <= 0, ErrorCode.PARAMS_ERROR);
        DesignFingerprint fp = designFingerprintService.getById(id);
        ThrowUtils.throwIf(fp == null, ErrorCode.NOT_FOUND_ERROR, "指纹不存在");
        return ResultUtils.success(fp);
    }

    /**
     * 管理员录入外部经典网页指纹（Awwwards/Dribbble 等）。
     * <p>
     * 抓取 URL 对应 HTML → 提炼六层指纹 → 以 EXTRACTED_FINGERPRINT 来源入库 CANDIDATE，
     * 之后走正常审核→APPROVE→向量同步流程。
     * </p>
     */
    @PostMapping("/admin/ingest-external")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Map<String, Object>> adminIngestExternalFingerprint(
            @RequestBody Map<String, String> body) {
        String url = body.get("url");
        String styleProfileHint = body.get("styleProfileHint");
        ThrowUtils.throwIf(StrUtil.isBlank(url), ErrorCode.PARAMS_ERROR, "URL 不能为空");

        String rawHtml;
        try {
            rawHtml = restTemplate.execute(url, HttpMethod.GET, null,
                    response -> new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("外部网页抓取失败 url={}", url, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页抓取失败: " + e.getMessage());
        }
        ThrowUtils.throwIf(StrUtil.isBlank(rawHtml), ErrorCode.OPERATION_ERROR, "抓取到的 HTML 为空");

        if (extractorAgent == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "DesignExtractorAgent 未初始化（LLM 不可用）");
        }

        // 提炼六层指纹
        DesignFingerprintDTO dto;
        try {
            dto = extractorAgent.extractFromHtml(rawHtml, styleProfileHint, "external:" + url);
        } catch (Exception e) {
            log.warn("外部网页指纹提炼失败 url={}", url, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "指纹提炼失败: " + e.getMessage());
        }

        // 入库 CANDIDATE（以 EXTRACTED_FINGERPRINT 来源，非 SELF_FED_FINGERPRINT）
        Long id = designFingerprintService.saveCandidate(dto, false);

        Map<String, Object> result = Map.of(
                "id", id != null ? id : 0,
                "styleProfile", dto.getStyleProfile() != null ? dto.getStyleProfile() : "unknown",
                "url", url
        );
        log.info("外部指纹录入成功 id={} url={}", id, url);
        return ResultUtils.success(result);
    }
}
