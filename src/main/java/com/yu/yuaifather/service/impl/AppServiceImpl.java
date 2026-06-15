package com.yu.yuaifather.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.yu.yuaifather.ai.AiCodeGenTypeRoutingService;
import com.yu.yuaifather.ai.core.AiCodeGeneratorFacade;
import com.yu.yuaifather.ai.core.bulider.VueProjectBuilder;
import com.yu.yuaifather.ai.core.handler.StreamHandlerExecutor;
import com.yu.yuaifather.ai.enums.CodeGenTypeEnum;
import com.yu.yuaifather.common.AppAddRequest;
import com.yu.yuaifather.common.AppQueryRequest;
import com.yu.yuaifather.constant.AppConstant;
import com.yu.yuaifather.exception.BusinessException;
import com.yu.yuaifather.exception.ErrorCode;
import com.yu.yuaifather.exception.ThrowUtils;
import com.yu.yuaifather.model.entity.App;
import com.yu.yuaifather.mapper.AppMapper;
import com.yu.yuaifather.model.entity.User;
import com.yu.yuaifather.model.enums.ChatHistoryMessageTypeEnum;
import com.yu.yuaifather.model.vo.AppVO;
import com.yu.yuaifather.model.vo.UserVO;
import com.yu.yuaifather.service.AppService;
import com.yu.yuaifather.service.ChatHistoryService;
import com.yu.yuaifather.service.ScreenshotService;
import com.yu.yuaifather.service.UserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.io.File;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author yu
 */
@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App>  implements AppService{

    @Autowired
    private UserService userService;

    @Autowired
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Autowired
    private StreamHandlerExecutor streamHandlerExecutor;

    @Autowired
    private ScreenshotService screenshotService;

    @Autowired
    private VueProjectBuilder vueProjectBuilder;

    @Autowired
    private OrchestratorAgent orchestratorAgent;

    /** 多 Agent 流水线开关：仅对 VUE_PROJECT 生效，默认关闭，避免影响存量。 */
    @Value("${agent.orchestrator.enabled:false}")
    private boolean orchestratorEnabled;

    /**
     * 应用部署基础 URL，用于拼接部署访问地址。
     * 格式示例：{@code http://localhost:8123/api/static}
     * 通过 {@code app.deploy.base-url} 配置注入，生产环境替换为真实域名，避免重新编译。
     */
    @Value("${app.deploy.base-url:http://localhost:8123/api/static}")
    private String deployBaseUrl;

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }

    /**
     * 允许排序的字段白名单（对应数据库列名），防止 SQL/排序注入
     */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "id", "appName", "initPrompt", "codeGenType", "priority",
            "userId", "createTime", "updateTime", "editTime", "deployedTime"
    );

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        QueryWrapper queryWrapper = QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId);
        // 仅当排序字段合法（在白名单内）时才排序，防止 SQL 注入
        if (StrUtil.isNotBlank(sortField)) {
            ThrowUtils.throwIf(!ALLOWED_SORT_FIELDS.contains(sortField),
                    ErrorCode.PARAMS_ERROR, "不支持的排序字段: " + sortField);
            queryWrapper.orderBy(sortField, "ascend".equals(sortOrder));
        }
        return queryWrapper;
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }



    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {

        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // ... 前面省略
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        // 5. 通过校验后，添加用户消息到对话历史（写入失败仅记录日志，不阻断 AI 生成流程）
        try {
            chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        } catch (Exception e) {
            log.warn("用户消息写入历史失败，appId={}，不影响生成流程: {}", appId, e.getMessage());
        }
        // 6. 调用 AI 生成代码（流式）
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
        // 7. 收集 AI 响应内容并在完成后记录到对话历史
        return streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum);

    }

    /**
     * 将 OrchestratorAgent 输出的 AgentEvent 序列化为前端 SSE 文本，复用既有 StreamHandlerExecutor 的解析格式。
     * <ul>
     *   <li>TEXT_CHUNK / TOOL_CALL / TOOL_RESULT 已是 SSE 协议字符串，原样透传；</li>
     *   <li>STATUS_CHANGE / ERROR 以 AiResponseMessage 包装为可读文本。</li>
     * </ul>
     */
    private String toStreamPayload(AgentEvent event) {
        if (event.getType() == AgentEvent.EventType.TEXT_CHUNK
                || event.getType() == AgentEvent.EventType.TOOL_CALL
                || event.getType() == AgentEvent.EventType.TOOL_RESULT) {
            return event.getContent() == null ? "" : event.getContent();
        }
        String prefix = event.getType() == AgentEvent.EventType.ERROR ? "[错误]" : "[阶段]";
        String text = String.format("\n%s %s：%s\n", prefix,
                event.getAgentName() == null ? "" : event.getAgentName(),
                event.getContent() == null ? "" : event.getContent());
        return JSONUtil.toJsonStr(new AiResponseMessage(text));
    }



    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限部署该应用，仅本人可以部署
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        // 4. 获取代码生成类型，构建源目录路径
        String codeGenType = app.getCodeGenType();
        // deployKey 格式为 codeGenType_appId，与 StaticResourceController 的预览路径一致
        String deployKey = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + deployKey;
        // 5. 检查源目录是否存在
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "应用代码不存在，请先生成代码");
        }
        // 6. Vue 项目特殊处理：执行构建
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            // Vue 项目需要构建
            boolean buildSuccess = vueProjectBuilder.buildProject(sourceDirPath);
            ThrowUtils.throwIf(!buildSuccess, ErrorCode.SYSTEM_ERROR, "Vue 项目构建失败，请检查代码和依赖");
            // 检查 dist 目录是否存在
            File distDir = new File(sourceDirPath, "dist");
            ThrowUtils.throwIf(!distDir.exists(), ErrorCode.SYSTEM_ERROR, "Vue 项目构建完成但未生成 dist 目录");
            log.info("Vue 项目构建成功，dist 目录: {}", distDir.getAbsolutePath());
        }
        // 7. 更新应用的 deployKey 和部署时间
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean updateResult = this.updateById(updateApp);
        ThrowUtils.throwIf(!updateResult, ErrorCode.OPERATION_ERROR, "更新应用部署信息失败");

        // 8. 构建应用访问 URL（通过后端静态资源服务访问，deployBaseUrl 通过配置注入）
        String appDeployUrl = deployBaseUrl + "/" + deployKey + "/";

        generateAppScreenshotAsync(appId, appDeployUrl);
        return appDeployUrl;

    }


    /**
     * 异步生成应用截图并更新封面。
     * <p>
     * 使用虚拟线程异步执行，不阻塞部署主流程。
     * 截图失败时仅记录错误日志，不影响应用正常访问。
     * </p>
     *
     * @param appId  应用ID
     * @param appUrl 应用访问URL
     */
    @Override
    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        Thread.startVirtualThread(() -> {
            try {
                // 调用截图服务生成截图并上传
                String screenshotUrl = screenshotService.generateAndUploadScreenshot(appUrl);
                if (StrUtil.isBlank(screenshotUrl)) {
                    log.warn("截图 URL 为空，跳过封面更新 appId={}", appId);
                    return;
                }
                // 更新应用封面字段
                App updateApp = new App();
                updateApp.setId(appId);
                updateApp.setCover(screenshotUrl);
                boolean updated = this.updateById(updateApp);
                if (!updated) {
                    log.warn("更新应用封面失败 appId={}", appId);
                }
            } catch (Exception e) {
                // 截图或上传失败不影响应用正常使用，仅记录日志
                log.error("异步截图生成失败 appId={}: {}", appId, e.getMessage(), e);
            }
        });
    }


    /**
     * 删除应用时关联删除对话历史，事务保证原子性。
     * <p>
     * 若历史删除成功但应用删除失败，事务回滚，避免出现"历史已删但应用仍存在"的数据不一致情况。
     * </p>
     *
     * @param id 应用ID
     * @return 是否成功
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        // 转换为 Long 类型
        Long appId = Long.valueOf(id.toString());
        if (appId <= 0) {
            return false;
        }
        // 先删除关联的对话历史（在同一事务中，任一失败均触发回滚）
        chatHistoryService.deleteByAppId(appId);
        log.info("删除应用关联对话历史成功 appId={}", appId);
        // 删除应用
        return super.removeById(id);
    }


    @Resource
    private AiCodeGenTypeRoutingService aiCodeGenTypeRoutingService;

    @Override
    public Long createApp(AppAddRequest appAddRequest, User loginUser) {
        // 参数校验
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        // 构造入库对象
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称暂时为 initPrompt 前 12 位
        app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        // 使用 AI 智能选择代码生成类型
        CodeGenTypeEnum selectedCodeGenType = aiCodeGenTypeRoutingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectedCodeGenType.getValue());
        // 插入数据库
        boolean result = this.save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        log.info("应用创建成功，ID: {}, 类型: {}", app.getId(), selectedCodeGenType.getValue());
        return app.getId();
    }




}
