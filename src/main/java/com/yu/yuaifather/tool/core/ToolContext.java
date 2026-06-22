package com.yu.yuaifather.tool.core;

import java.util.Set;

/**
 * Tool 调用上下文，承载请求级别元数据与权限信息。
 * <p>
 * <b>关键安全原则</b>：userId / projectId / teamId 由系统注入，LLM Agent 不可传入。
 * 所有 Tool 都从 ToolContext 获取身份信息，而非信任调用方传入的参数。
 * <p>
 * 使用模式：
 * <pre>
 * ToolContext tc = ToolContext.builder()
 *     .requestId(request.getRequestId())
 *     .currentUserId(loginUser.getId())
 *     .currentProjectId(appId)     // 当前架构中 appId 同时作为项目标识
 *     .generationMode("standard")
 *     .build();
 * </pre>
 */
public class ToolContext {

    /** 请求 ID（全链路追踪）。 */
    private String requestId;

    /** 当前用户 ID（系统注入，LLM 不可传）。 */
    private Long currentUserId;

    /** 当前项目 ID（系统注入，LLM 不可传）。 */
    private Long currentProjectId;

    /** 当前团队 ID（系统注入）。 */
    private Long currentTeamId;

    /** 权限集合（系统注入）。 */
    private Set<String> permissions;

    /** 生成模式：low_cost / standard / high_quality。 */
    private String generationMode;

    // ---- Builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ToolContext ctx = new ToolContext();

        public Builder requestId(String requestId) {
            ctx.requestId = requestId;
            return this;
        }

        public Builder currentUserId(Long currentUserId) {
            ctx.currentUserId = currentUserId;
            return this;
        }

        public Builder currentProjectId(Long currentProjectId) {
            ctx.currentProjectId = currentProjectId;
            return this;
        }

        public Builder currentTeamId(Long currentTeamId) {
            ctx.currentTeamId = currentTeamId;
            return this;
        }

        public Builder permissions(Set<String> permissions) {
            ctx.permissions = permissions;
            return this;
        }

        public Builder generationMode(String generationMode) {
            ctx.generationMode = generationMode;
            return this;
        }

        public ToolContext build() {
            return ctx;
        }
    }

    // ---- Getters (no setters, immutable after build) ----

    public String getRequestId() {
        return requestId;
    }

    public Long getCurrentUserId() {
        return currentUserId;
    }

    public Long getCurrentProjectId() {
        return currentProjectId;
    }

    public Long getCurrentTeamId() {
        return currentTeamId;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public String getGenerationMode() {
        return generationMode;
    }
}
