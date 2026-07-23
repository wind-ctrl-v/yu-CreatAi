package com.yu.yuaifather.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.yu.yuaifather.app.application.AppAccessService;
import com.yu.yuaifather.auth.AuthCheck;
import com.yu.yuaifather.common.BaseResponse;
import com.yu.yuaifather.common.ResultUtils;
import com.yu.yuaifather.model.dto.DeleteRequest;
import com.yu.yuaifather.model.dto.app.AppAddRequest;
import com.yu.yuaifather.model.dto.app.AppAdminUpdateRequest;
import com.yu.yuaifather.model.dto.app.AppDeployRequest;
import com.yu.yuaifather.model.dto.app.AppQueryRequest;
import com.yu.yuaifather.model.dto.app.AppUpdateRequest;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.constant.UserConstant;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.exception.ThrowUtils;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.vo.AppVO;
import com.yu.yuaifather.ratelimiter.RateLimit;
import com.yu.yuaifather.ratelimiter.RateLimitType;
import com.yu.yuaifather.service.AppBehaviorService;
import com.yu.yuaifather.service.ChatHistoryService;
import com.yu.yuaifather.service.ProjectDownloadService;
import com.yu.yuaifather.service.UserService;
import com.yu.yuaifather.model.enums.ChatHistoryMessageTypeEnum;
import com.yu.yuaifather.service.PromptPreferenceExtractor;
import com.yu.yuaifather.service.IncrementalEditService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.service.AppService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 应用 控制层。
 *
 * @author yu
 */
@RestController
@RequestMapping("/app")
@Slf4j
public class AppController {

    @Autowired
    private AppService appService;

    @Autowired
    private AppAccessService appAccessService;

    @Autowired
    private UserService userService;

    @Resource
    private ProjectDownloadService projectDownloadService;

    @Autowired
    private AppBehaviorService appBehaviorService;

    @Autowired
    private PromptPreferenceExtractor promptPreferenceExtractor;

    @Autowired
    private ChatHistoryService chatHistoryService;

    @Autowired
    private IncrementalEditService incrementalEditService;

    @Autowired
    @Qualifier("preferenceExecutor")
    private Executor preferenceExecutor;

    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        Long appId = appService.createApp(appAddRequest, loginUser);

        // 异步从提示词中提取用户偏好标签（不阻塞创建流程）
        String prompt = appAddRequest.getInitPrompt();
        if (StrUtil.isNotBlank(prompt)) {
            preferenceExecutor.execute(() -> promptPreferenceExtractor.extractAndRecord(loginUser.getId(), prompt));
        }

        return ResultUtils.success(appId);
    }


    /**
     * 更新应用（用户只能更新自己的应用名称）
     *
     * @param appUpdateRequest 更新请求
     * @param request          请求
     * @return 更新结果
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        if (appUpdateRequest == null || appUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = appUpdateRequest.getId();
        appAccessService.requireOwner(id, loginUser.getId());
        App app = new App();
        app.setId(id);
        app.setAppName(appUpdateRequest.getAppName());
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }


    /**
     * 删除应用（用户只能删除自己的应用）
     *
     * @param deleteRequest 删除请求
     * @param request       请求
     * @return 删除结果
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        App oldApp = appAccessService.requireOwnerOrAdmin(id, loginUser);
        boolean result = appService.removeById(id);

        // 删除 = 用户对该应用设计不满意 → 记录 page_deleted 负反馈事件（P0-2 + N-3 边界控制）
        if (result) {
            appBehaviorService.recordPageDeletedEvent(loginUser.getId(), id, oldApp.getInitPrompt(),
                    deleteRequest.getDeleteReason());
        }
        return ResultUtils.success(result);
    }


    /**
     * 根据 id 获取应用详情
     *
     * @param id      应用 id
     * @return 应用详情
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类（包含用户信息）
        return ResultUtils.success(appService.getAppVO(app));
    }



    /**
     * 分页获取当前用户创建的应用列表
     *
     * @param appQueryRequest 查询请求
     * @param request         请求
     * @return 应用列表
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询当前用户的应用
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }


    /**
     * 分页获取精选应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 精选应用列表
     */
    @PostMapping("/good/list/page/vo")
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        // 分页查询
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }


    /**
     * 管理员删除应用
     *
     * @param deleteRequest 删除请求
     * @return 删除结果
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = deleteRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        boolean result = appService.removeById(id);
        return ResultUtils.success(result);
    }

    /**
     * 管理员更新应用
     *
     * @param appAdminUpdateRequest 更新请求
     * @return 更新结果
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        if (appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = appAdminUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        App app = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, app);
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 管理员分页获取应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 应用列表
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }


    /**
     * 管理员根据 id 获取应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(appService.getAppVO(app));
    }


    /**
     * 应用聊天生成代码（流式 SSE）
     *
     * @param appId   应用 ID
     * @param message 用户消息
     * @param request 请求对象
     * @return 生成结果流
     */

    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI 对话请求过于频繁，请稍后再试")
    @GetMapping(value = "/chat/gen/code", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatToGenCode(@RequestParam Long appId,
                                                      @RequestParam String message,
                                                      @RequestParam(required = false) String mode,
                                                      HttpServletRequest request) {
        // 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 获取当前登录用户并统一校验应用所有权
        User loginUser = userService.getLoginUser(request);
        appAccessService.requireOwner(appId, loginUser.getId());
        // 调用服务生成代码（流式）。mode 为前端请求模式（不可信意图），由服务端按套餐封顶。
        Flux<String> contentFlux = appService.chatToGenCode(appId, message, mode, loginUser);
        // 转换为 ServerSentEvent 格式
        return contentFlux
                .map(chunk -> {
                    // 将内容包装成JSON对象
                    Map<String, String> wrapper = Map.of("d", chunk);
                    String jsonData = JSONUtil.toJsonStr(wrapper);
                    return ServerSentEvent.<String>builder()
                            .data(jsonData)
                            .build();
                })
                .concatWith(Mono.just(
                        // 发送结束事件
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ));
    }

    /**
     * 增量编辑流式 SSE
     * 实时推送工具调用过程（readDir → readFile → modifyFile）和 AI 文本响应。
     * 同时将用户消息和 AI 响应持久化到 chat_history，刷新页面不丢失对话。
     */
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60,
            message = "修改请求过于频繁，请稍后再试")
    @GetMapping(value = "/incremental-edit-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> incrementalEditStream(@RequestParam Long appId,
                                                                @RequestParam String instruction,
                                                                HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        ThrowUtils.throwIf(StrUtil.isBlank(instruction), ErrorCode.PARAMS_ERROR, "修改指令不能为空");
        appAccessService.requireOwner(appId, loginUser.getId());

        // 保存用户消息到对话历史
        try {
            chatHistoryService.addChatMessage(appId, instruction,
                    ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        } catch (Exception e) {
            log.warn("增量编辑用户消息写入历史失败 appId={}", appId, e);
        }

        // 异步从修改指令中提取用户偏好（颜色/风格/布局标签），不阻塞主流程
        final Long currentUserId = loginUser.getId();
        preferenceExecutor.execute(() -> {
            try {
                promptPreferenceExtractor.extractAndRecord(currentUserId, instruction);
            } catch (Exception e) {
                log.warn("增量编辑偏好提取失败 appId={}", appId, e);
            }
        });

        // 收集 AI 响应的完整文本，用于流结束后持久化
        StringBuilder aiResponseBuffer = new StringBuilder();
        Flux<String> contentFlux = incrementalEditService.incrementalEditStream(appId, instruction);
        return contentFlux
                .map(chunk -> {
                    // 收集 ai_response 文本
                    collectAiText(chunk, aiResponseBuffer);
                    Map<String, String> wrapper = Map.of("d", chunk);
                    return ServerSentEvent.<String>builder()
                            .data(JSONUtil.toJsonStr(wrapper))
                            .build();
                })
                .concatWith(Mono.just(
                        ServerSentEvent.<String>builder()
                                .event("done")
                                .data("")
                                .build()
                ))
                .doOnTerminate(() -> {
                    // 流结束后保存 AI 响应到对话历史
                    String aiText = aiResponseBuffer.toString().trim();
                    if (StrUtil.isNotBlank(aiText)) {
                        try {
                            chatHistoryService.addChatMessage(appId, aiText,
                                    ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                        } catch (Exception e) {
                            log.warn("增量编辑 AI 响应写入历史失败 appId={}", appId, e);
                        }
                    }
                });
    }

    /** 从 SSE chunk（JSON）中提取 ai_response 文本收集到 buffer */
    private void collectAiText(String chunk, StringBuilder buffer) {
        try {
            Map<?, ?> msg = JSONUtil.toBean(chunk, Map.class);
            if ("ai_response".equals(msg.get("type"))) {
                Object data = msg.get("data");
                if (data != null) {
                    buffer.append(data.toString());
                }
            }
        } catch (Exception ignored) {
            // 非 JSON chunk 忽略
        }
    }

    /**
     * 应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署 URL
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户并统一校验应用所有权
        User loginUser = userService.getLoginUser(request);
        appAccessService.requireOwner(appId, loginUser.getId());
        // 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }




    /**
     * 下载应用代码
     *
     * @param appId    应用ID
     * @param request  请求
     * @param response 响应
     */
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID无效");
        User loginUser = userService.getLoginUser(request);
        App app = appAccessService.requireOwner(appId, loginUser.getId());
        // 解析并校验代码生成目录（生成目录，非部署目录）
        String sourceDirPath = projectDownloadService.resolveAndValidateSourceDir(app);
        // 5. 生成下载文件名（不建议添加中文内容）
        String downloadFileName = String.valueOf(appId);
        // 6. 调用通用下载服务
        projectDownloadService.downloadProjectAsZip(sourceDirPath, downloadFileName, response);

        // 7. 记录用户下载行为（偏好闭环：page_exported 事件，权重 +0.2）
        appBehaviorService.recordPageExportedEvent(loginUser.getId(), appId);
    }


    /**
     * 增量编辑：对已生成的 Vue 项目原地修改文件，不重新生成整个项目。
     */
    @PostMapping("/incremental-edit")
    public BaseResponse<Map<String, Object>> incrementalEdit(@RequestBody Map<String, Object> body,
                                                              HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        Long appId = body.get("appId") != null ? Long.valueOf(body.get("appId").toString()) : null;
        String instruction = (String) body.get("instruction");
        ThrowUtils.throwIf(appId == null || StrUtil.isBlank(instruction), ErrorCode.PARAMS_ERROR);
        appAccessService.requireOwner(appId, loginUser.getId());

        String result = incrementalEditService.incrementalEdit(appId, instruction);
        Map<String, Object> data = new java.util.HashMap<>();
        boolean success = appBehaviorService.judgeIncrementalEditSuccess(result);
        data.put("success", success);
        data.put("message", result);
        return ResultUtils.success(data);
    }


}
