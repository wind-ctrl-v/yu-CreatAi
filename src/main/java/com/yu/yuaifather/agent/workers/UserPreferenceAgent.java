package com.yu.yuaifather.agent.workers;

import com.yu.yuaifather.agent.core.Agent;
import com.yu.yuaifather.agent.core.AgentContext;
import com.yu.yuaifather.agent.core.AgentEvent;
import com.yu.yuaifather.ai.model.dto.UserDesignContext;
import com.yu.yuaifather.service.UserPreferenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/**
 * UserPreferenceAgent：用户偏好加载 Worker（阶段一新增）。
 *
 * @deprecated 已重命名为 {@link com.yu.yuaifather.tool.system.UserPreferenceTool}，请使用 UserPreferenceTool 替代。
 */
@Deprecated
@Slf4j
@Component
public class UserPreferenceAgent implements Agent {

    public static final String AGENT_NAME = "UserPreferenceAgent";

    @Value("${agent.personalization.enabled:false}")
    private boolean personalizationEnabled;

    private final UserPreferenceService userPreferenceService;

    @Autowired
    public UserPreferenceAgent(UserPreferenceService userPreferenceService) {
        this.userPreferenceService = userPreferenceService;
    }

    @Override
    public String name() {
        return AGENT_NAME;
    }

    @Override
    public Flux<AgentEvent> handle(AgentContext ctx) {
        return Flux.<AgentEvent>create(sink -> {
            if (!personalizationEnabled) {
                sink.next(AgentEvent.status(AGENT_NAME, "个性化偏好未启用"));
                sink.complete();
                return;
            }

            sink.next(AgentEvent.status(AGENT_NAME, "加载用户设计偏好"));

            try {
                Long userId = resolveUserId(ctx);
                if (userId == null) {
                    sink.next(AgentEvent.status(AGENT_NAME, "无用户身份，跳过偏好加载"));
                    sink.complete();
                    return;
                }

                UserDesignContext context = userPreferenceService.loadUserDesignContext(userId);
                ctx.getBlackboard().putUserDesignContext(context);

                if (context.hasPreferences()) {
                    sink.next(AgentEvent.status(AGENT_NAME,
                            "用户偏好加载完成 styles=" + safeListSize(context.getPreferredStyles())
                                    + " colors=" + safeListSize(context.getPreferredColors())
                                    + " weights=" + safeMapSize(context.getPreferenceWeights())));
                } else {
                    sink.next(AgentEvent.status(AGENT_NAME, "用户尚无偏好数据"));
                }
            } catch (Exception e) {
                log.warn("UserPreferenceAgent 加载偏好异常 userId={} msg={}",
                        resolveUserId(ctx), e.getMessage());
                sink.next(AgentEvent.status(AGENT_NAME, "偏好加载异常，降级跳过"));
            }
            sink.complete();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Long resolveUserId(AgentContext ctx) {
        if (ctx.getLoginUser() != null) {
            return ctx.getLoginUser().getId();
        }
        return null;
    }

    private int safeListSize(java.util.List<?> list) {
        return list == null ? 0 : list.size();
    }

    private int safeMapSize(java.util.Map<?, ?> map) {
        return map == null ? 0 : map.size();
    }
}
