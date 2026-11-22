package com.yu.yuaifather.controller;

import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.yu.yuaifather.service.design.DesignExtractorAgent;
import com.yu.yuaifather.common.security.SafeUrlValidator;
import com.yu.yuaifather.auth.AuthCheck;
import com.yu.yuaifather.common.BaseResponse;
import com.yu.yuaifather.common.ResultUtils;
import com.yu.yuaifather.config.VectorStoreProperties;
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
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

    /** 向量存储配置（A 改造：store=pg/mysql；resync-max 控制单次重同步上限）。 */
    @Autowired
    private VectorStoreProperties vectorStoreProperties;

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_HTML_BYTES = 2 * 1024 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

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
     * 管理员手动重同步向量索引（A 改造：向量存储后端可选）。
     * <p>
     * 生产切 {@code agent.retrieve.vector.store=mysql} 后，用本接口为历史 APPROVED 指纹补建向量：
     * 按 {@code priority DESC → confidence DESC → updateTime DESC} 遍历 APPROVED 指纹，
     * 逐条调用 {@code VectorSyncWorker.syncOnApprove}。单次条数默认取
     * {@link VectorStoreProperties#getResyncMax()}，可用 {@code limit} 参数下调（不可超过该上限）。
     * <p>
     * 向量功能关闭时接口仍可用，返回 {@code enabled=false} 的统计信息（不报错）。
     *
     * @param limit 本次最多处理的指纹条数（可选，默认且最大为 resync-max）
     * @return 统计信息：enabled / limit / scanned / attempted / failedIds
     */
    @PostMapping("/admin/vector/resync")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Map<String, Object>> adminResyncVectors(
            @RequestParam(value = "limit", required = false) Integer limit) {
        int max = Math.max(1, vectorStoreProperties.getResyncMax());
        int effectiveLimit = (limit == null || limit <= 0) ? max : Math.min(limit, max);
        Map<String, Object> result = designFingerprintService.resyncVectors(effectiveLimit);
        log.info("管理员触发向量重同步 limit={} result={}", effectiveLimit, result);
        return ResultUtils.success(result);
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

        URI validatedUri = SafeUrlValidator.validate(url);
        String rawHtml = fetchExternalHtml(validatedUri);
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
        DesignFingerprint saved = designFingerprintService.saveCandidate(dto, false);
        Long id = saved != null ? saved.getId() : null;

        Map<String, Object> result = Map.of(
                "id", id != null ? id : 0,
                "styleProfile", dto.getStyleProfile() != null ? dto.getStyleProfile() : "unknown",
                "url", url
        );
        log.info("外部指纹录入成功 id={} host={}", id, validatedUri.getHost());
        return ResultUtils.success(result);
    }

    private String fetchExternalHtml(URI initialUri) {
        URI current = initialUri;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            current = SafeUrlValidator.validate(current.toString());
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();
            try {
                HttpResponse<java.io.InputStream> response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status >= 300 && status < 400) {
                    String location = response.headers().firstValue("location")
                            .orElseThrow(() -> new BusinessException(ErrorCode.OPERATION_ERROR,
                                    "重定向响应缺少 Location"));
                    current = current.resolve(location);
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页返回异常状态: " + status);
                }
                try (java.io.InputStream inputStream = response.body()) {
                    byte[] body = inputStream.readNBytes(MAX_HTML_BYTES + 1);
                    if (body.length > MAX_HTML_BYTES) {
                        throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页内容超过大小限制");
                    }
                    return new String(body, StandardCharsets.UTF_8);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页抓取被中断");
            } catch (Exception e) {
                log.warn("外部网页抓取失败 host={} type={}", current.getHost(), e.getClass().getSimpleName());
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页抓取失败");
            }
        }
        throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页重定向次数过多");
    }
}
