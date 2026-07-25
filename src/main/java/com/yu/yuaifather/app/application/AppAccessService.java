package com.yu.yuaifather.app.application;

import com.yu.yuaifather.constant.UserConstant;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.service.AppService;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 应用访问权限的唯一校验入口。
 */
@Service
public class AppAccessService {

    private final AppService appService;

    public AppAccessService(AppService appService) {
        this.appService = appService;
    }

    public App requireExisting(Long appId) {
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用 ID 无效");
        }
        App app = appService.getById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        return app;
    }

    public App requireOwner(Long appId, Long userId) {
        App app = requireExisting(appId);
        if (userId == null || !Objects.equals(app.getUserId(), userId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该应用");
        }
        return app;
    }

    public App requireOwnerOrAdmin(Long appId, User user) {
        App app = requireExisting(appId);
        boolean isOwner = user != null && Objects.equals(app.getUserId(), user.getId());
        boolean isAdmin = user != null && UserConstant.ADMIN_ROLE.equals(user.getUserRole());
        if (!isOwner && !isAdmin) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该应用");
        }
        return app;
    }
}
