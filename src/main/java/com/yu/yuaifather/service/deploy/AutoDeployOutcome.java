package com.yu.yuaifather.service.deploy;

/**
 * 生成完成后的本地自动部署结果。
 * <p>
 * 失败只携带用户可见提示，不把异常抛回生成链路。
 */
public record AutoDeployOutcome(boolean success, String deployUrl) {

    /** 与手动「部署」按钮对应的失败提示，前端可直接展示。 */
    public static final String USER_WARNING = "自动部署失败，请手动点击「部署」";

    /** 本地部署开始（生成已成功，封面与 deployKey 即将写入）。 */
    public static final String STARTED_EVENT = "auto-deploy-started";

    /** 本地部署失败。生成结果保留，用户可手动重试。 */
    public static final String FAILED_EVENT = "auto-deploy-failed";

    public static AutoDeployOutcome success(String deployUrl) {
        return new AutoDeployOutcome(true, deployUrl);
    }

    public static AutoDeployOutcome failed() {
        return new AutoDeployOutcome(false, null);
    }
}
