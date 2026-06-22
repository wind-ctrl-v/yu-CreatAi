package com.yu.yuaifather.agent.codegen.fileops;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.yu.yuaifather.constant.AppConstant;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 工具基类
 * 定义所有工具的通用接口
 */
public abstract class BaseTool {

    /**
     * 获取工具的英文名称（对应方法名）
     *
     * @return 工具英文名称
     */
    public abstract String getToolName();

    /**
     * 获取工具的中文显示名称
     *
     * @return 工具中文名称
     */
    public abstract String getDisplayName();

    /**
     * 生成工具请求时的返回值（显示给用户）
     *
     * @return 工具请求显示内容
     */
    public String generateToolRequestResponse() {
        return String.format("\n\n[选择工具] %s\n\n", getDisplayName());
    }

    /**
     * 生成工具执行结果格式（保存到数据库）
     *
     * @param arguments 工具执行参数
     * @return 格式化的工具执行结果
     */
    public abstract String generateToolExecutedResult(JSONObject arguments);

    /**
     * 计算当前应用对应的项目根目录（绝对、规范化路径）。
     *
     * @param appId 应用 ID
     * @return 项目根目录的绝对规范化路径
     */
    protected Path getProjectRoot(Long appId) {
        String projectDirName = "vue_project_" + appId;
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 将用户/AI 传入的相对路径解析为项目目录内的安全绝对路径，防止路径遍历攻击。
     * <p>
     * 安全策略：
     * <ul>
     *     <li>始终以应用对应的项目根目录为基准解析，忽略外部传入的绝对路径前缀；</li>
     *     <li>解析后调用 {@code normalize()} 消除 {@code ../}、{@code ./} 等片段；</li>
     *     <li>校验最终路径必须仍位于项目根目录之内，否则视为非法越权访问。</li>
     * </ul>
     *
     * @param relativePath 相对路径（可为 null 或空，表示项目根目录）
     * @param appId        应用 ID
     * @return 校验通过的安全绝对路径
     * @throws SecurityException 当解析后的路径逃逸出项目根目录时抛出
     */
    protected Path resolveSafePath(String relativePath, Long appId) {
        Path projectRoot = getProjectRoot(appId);
        // 即使传入绝对路径，resolve 后也会通过下面的 startsWith 校验拦截
        String userPath = StrUtil.isBlank(relativePath) ? "" : relativePath;
        Path resolved = projectRoot.resolve(userPath).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new SecurityException("非法的文件路径，禁止访问项目目录之外的资源: " + relativePath);
        }
        return resolved;
    }
}
