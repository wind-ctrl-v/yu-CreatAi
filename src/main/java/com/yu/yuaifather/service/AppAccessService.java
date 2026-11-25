package com.yu.yuaifather.service;

import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.constant.UserConstant;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.model.entity.User;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 应用访问权限的唯一校验入口。
 * <p>
 * 查看（对话历史、生成用量）对作者、管理员，以及精选应用的登录用户开放。
 * 改名、生成、增量编辑、部署和下载仍仅作者；删除仍为作者或管理员。精选身份不能代替作者。
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

    /**
     * 打开应用页时的查看权：作者、管理员，或精选应用（{@code priority = 99}）的任意登录用户。
     */
    public App requireView(Long appId, User user) {
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        App app = requireExisting(appId);
        if (!canView(app, user)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权访问该应用");
        }
        return app;
    }

    /**
     * 作者、管理员可查看任意应用；其他登录用户只能查看精选应用。
     * 静态方法供对话历史调用，避免与 {@code AppService} 形成循环依赖。
     */
    public static boolean canView(App app, User user) {
        if (app == null || user == null) {
            return false;
        }
        if (user.getId() != null && Objects.equals(app.getUserId(), user.getId())) {
            return true;
        }
        if (UserConstant.ADMIN_ROLE.equals(user.getUserRole())) {
            return true;
        }
        return AppConstant.GOOD_APP_PRIORITY.equals(app.getPriority());
    }
}
