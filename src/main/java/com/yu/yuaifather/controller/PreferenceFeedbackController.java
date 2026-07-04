package com.yu.yuaifather.controller;

import com.yu.yuaifather.common.BaseResponse;
import com.yu.yuaifather.common.ResultUtils;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.exception.ThrowUtils;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.service.NegativeFeedbackService;
import com.yu.yuaifather.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 偏好反馈接口（P0-残留-1 补全）。
 * <p>
 * 提供前端负反馈事件埋点入口：
 * <ul>
 *   <li>POST /preference/feedback/dislike — 显式点踩</li>
 *   <li>POST /preference/feedback/event  — 通用负反馈事件（UNDO/REVERT/ABANDON/REJECT）</li>
 * </ul>
 */
@RestController
@RequestMapping("/preference/feedback")
@Slf4j
public class PreferenceFeedbackController {

    @Autowired
    private NegativeFeedbackService negativeFeedbackService;

    @Autowired
    private UserService userService;

    /**
     * 显式点踩接口。
     * <p>
     * Body: { appId, reason?, tags? }
     *
     * @param body    请求体
     * @param request HTTP 请求（获取登录用户）
     */
    @PostMapping("/dislike")
    public BaseResponse<Void> dislike(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Long appId = parseLong(body.get("appId"));
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");

        String reason = body.get("reason") != null ? body.get("reason").toString() : null;
        @SuppressWarnings("unchecked")
        List<String> tags = body.get("tags") != null ? (List<String>) body.get("tags") : null;

        negativeFeedbackService.recordDislike(loginUser.getId(), appId, reason, tags);
        return ResultUtils.success(null);
    }

    /**
     * 通用负反馈事件埋点接口。
     * <p>
     * Body: { appId, eventType, metadata? }
     *
     * @param body    请求体
     * @param request HTTP 请求
     */
    @PostMapping("/event")
    public BaseResponse<Void> recordEvent(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Long appId = parseLong(body.get("appId"));
        String eventType = body.get("eventType") != null ? body.get("eventType").toString().toLowerCase() : null;

        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        ThrowUtils.throwIf(eventType == null || eventType.isBlank(), ErrorCode.PARAMS_ERROR, "事件类型不能为空");

        // 校验事件类型白名单
        if (!isValidNegativeEventType(eventType)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的事件类型: " + eventType);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = body.get("metadata") != null ? (Map<String, Object>) body.get("metadata") : null;

        negativeFeedbackService.recordNegativeEvent(loginUser.getId(), appId, eventType, metadata);
        return ResultUtils.success(null);
    }

    /** 校验事件类型是否在负反馈白名单内 */
    private boolean isValidNegativeEventType(String eventType) {
        return switch (eventType) {
            case "undo", "revert_to_generated", "abandon",
                 "reject_suggestion", "explicit_dislike" -> true;
            default -> false;
        };
    }

    private Long parseLong(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
