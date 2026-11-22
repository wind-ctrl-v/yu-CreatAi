package com.yu.yuaifather.agent.core;

import com.yu.yuaifather.model.dto.agent.CriticReport;
import com.yu.yuaifather.model.dto.agent.DesignEvidence;
import com.yu.yuaifather.model.dto.agent.DesignSpec;
import com.yu.yuaifather.model.dto.agent.GenerationPlan;
import com.yu.yuaifather.model.dto.agent.ProjectBrandContext;
import com.yu.yuaifather.model.dto.agent.PromptContext;
import com.yu.yuaifather.model.dto.agent.RequirementDoc;
import com.yu.yuaifather.model.dto.agent.UserDesignContext;
import com.yu.yuaifather.service.generation.GeneratedArtifact;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 共享黑板（Blackboard）。
 * <p>
 * 多 Agent 协作的共享状态存储，线程安全。各 Agent 通过语义化方法读写中间产物：
 * <ul>
 *   <li>ProductAgent → {@link #putRequirementDoc(RequirementDoc)}</li>
 *   <li>ArchitectAgent → {@link #putPlan(GenerationPlan)}</li>
 *   <li>DesignTokenAgent → {@link #putDesignSpec(DesignSpec)}</li>
 *   <li>UserPreferenceAgent → {@link #putUserDesignContext(UserDesignContext)}</li>
 *   <li>ImageAgent → {@link #putImageMap(Map)}</li>
 *   <li>CriticAgent → {@link #putCriticReport(CriticReport)}</li>
 * </ul>
 */
public class Blackboard {

    private static final String KEY_REQUIREMENT_DOC = "requirementDoc";
    private static final String KEY_PLAN = "plan";
    private static final String KEY_IMAGE_MAP = "imageMap";
    private static final String KEY_CRITIC_REPORT = "criticReport";
    private static final String KEY_GENERATED_FILES = "generatedFiles";
    private static final String KEY_GENERATED_ARTIFACT = "generatedArtifact";
    private static final String KEY_DEPLOY_URL = "deployUrl";
    private static final String KEY_DESIGN_SPEC = "designSpec";
    private static final String KEY_DESIGN_EVIDENCE = "designEvidence";
    private static final String KEY_USER_DESIGN_CONTEXT = "userDesignContext";
    private static final String KEY_PROJECT_BRAND_CONTEXT = "projectBrandContext";
    private static final String KEY_PROMPT_CONTEXT = "promptContext";
    private static final String KEY_RAG_TRACE_ID = "ragTraceId";

    private final Map<String, Object> store = new ConcurrentHashMap<>();

    // ---------- requirementDoc ----------

    public void putRequirementDoc(RequirementDoc doc) {
        if (doc != null) {
            store.put(KEY_REQUIREMENT_DOC, doc);
        }
    }

    public RequirementDoc getRequirementDoc() {
        return (RequirementDoc) store.get(KEY_REQUIREMENT_DOC);
    }

    // ---------- plan ----------

    public void putPlan(GenerationPlan plan) {
        if (plan != null) {
            store.put(KEY_PLAN, plan);
        }
    }

    public GenerationPlan getPlan() {
        return (GenerationPlan) store.get(KEY_PLAN);
    }

    // ---------- imageMap ----------

    /**
     * 写入 imageMap（slotId/keyword → 真实图片 URL）。
     * 内部包裹为 ConcurrentHashMap 保证线程安全。
     */
    @SuppressWarnings("unchecked")
    public void putImageMap(Map<String, String> imageMap) {
        if (imageMap == null) {
            return;
        }
        Map<String, String> safe = (Map<String, String>) store.computeIfAbsent(
                KEY_IMAGE_MAP, k -> new ConcurrentHashMap<String, String>());
        safe.putAll(imageMap);
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getImageMap() {
        Map<String, String> m = (Map<String, String>) store.get(KEY_IMAGE_MAP);
        return m == null ? Collections.emptyMap() : m;
    }

    // ---------- criticReport ----------

    public void putCriticReport(CriticReport report) {
        if (report != null) {
            store.put(KEY_CRITIC_REPORT, report);
        }
    }

    public CriticReport getCriticReport() {
        return (CriticReport) store.get(KEY_CRITIC_REPORT);
    }

    // ---------- designSpec ----------

    /**
     * 写入设计规范（由 DesignTokenAgent 产出），供 StyleAgent / CoderAgent 读取。
     */
    public void putDesignSpec(DesignSpec spec) {
        if (spec != null) {
            store.put(KEY_DESIGN_SPEC, spec);
        }
    }

    /**
     * 读取设计规范，可能为 null（未执行 DesignTokenAgent 或加载失败且未写入兜底）。
     */
    public DesignSpec getDesignSpec() {
        return (DesignSpec) store.get(KEY_DESIGN_SPEC);
    }

    // ---------- designEvidence ----------

    /**
     * 写入运行时召回的受控设计证据（由 DesignRetrieveAgent 产出，阶段三）。
     */
    public void putDesignEvidence(DesignEvidence evidence) {
        if (evidence != null) {
            store.put(KEY_DESIGN_EVIDENCE, evidence);
        }
    }

    /**
     * 读取运行时召回证据，可能为 null（未执行检索 / 检索关闭 / 召回为空）。
     */
    public DesignEvidence getDesignEvidence() {
        return (DesignEvidence) store.get(KEY_DESIGN_EVIDENCE);
    }

    // ---------- generatedFiles ----------

    @SuppressWarnings("unchecked")
    public void addGeneratedFile(String relativePath) {
        if (relativePath == null) {
            return;
        }
        Map<String, Boolean> files = (Map<String, Boolean>) store.computeIfAbsent(
                KEY_GENERATED_FILES, k -> new ConcurrentHashMap<String, Boolean>());
        files.put(relativePath, Boolean.TRUE);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Boolean> getGeneratedFiles() {
        Map<String, Boolean> m = (Map<String, Boolean>) store.get(KEY_GENERATED_FILES);
        return m == null ? Collections.emptyMap() : m;
    }

    // ---------- generatedArtifact ----------

    public void putGeneratedArtifact(GeneratedArtifact artifact) {
        if (artifact != null) {
            store.put(KEY_GENERATED_ARTIFACT, artifact);
        }
    }

    public GeneratedArtifact getGeneratedArtifact() {
        return (GeneratedArtifact) store.get(KEY_GENERATED_ARTIFACT);
    }

    // ---------- deployUrl ----------

    public void putDeployUrl(String url) {
        if (url != null) {
            store.put(KEY_DEPLOY_URL, url);
        }
    }

    public String getDeployUrl() {
        return (String) store.get(KEY_DEPLOY_URL);
    }

    // ---------- userDesignContext ----------

    /**
     * 写入用户设计上下文（由 UserPreferenceAgent 产出，阶段一）。
     * <p>
     * 包含用户偏好画像和权重数据，供 DesignRetrieveAgent、CoderAgent、StyleAgent 消费。
     */
    public void putUserDesignContext(UserDesignContext context) {
        if (context != null) {
            store.put(KEY_USER_DESIGN_CONTEXT, context);
        }
    }

    /**
     * 读取用户设计上下文，可能为 null（未执行 UserPreferenceAgent 或用户无偏好数据）。
     */
    public UserDesignContext getUserDesignContext() {
        return (UserDesignContext) store.get(KEY_USER_DESIGN_CONTEXT);
    }

    // ---------- projectBrandContext ----------

    /**
     * 写入项目品牌上下文（由 ProjectPreferenceAgent 产出，阶段三）。
     */
    public void putProjectBrandContext(ProjectBrandContext context) {
        if (context != null) {
            store.put(KEY_PROJECT_BRAND_CONTEXT, context);
        }
    }

    /**
     * 读取项目品牌上下文，可能为 null。
     */
    public ProjectBrandContext getProjectBrandContext() {
        return (ProjectBrandContext) store.get(KEY_PROJECT_BRAND_CONTEXT);
    }

    // ---------- promptContext ----------

    /**
     * 写入压缩后的统一设计上下文，供页面、代码和样式 Agent 消费。
     */
    public void putPromptContext(PromptContext context) {
        if (context != null) {
            store.put(KEY_PROMPT_CONTEXT, context);
        }
    }

    /**
     * 读取统一设计上下文，可能为 null（装配失败或尚未执行装配）。
     */
    public PromptContext getPromptContext() {
        return (PromptContext) store.get(KEY_PROMPT_CONTEXT);
    }

    // ---------- ragTraceId ----------

    /**
     * 写入 RAG 检索追踪 ID（由 GenerationOrchestrator 在保存 RagTrace 后写入，供 CriticAgent 回填）。
     */
    public void putRagTraceId(Long ragTraceId) {
        if (ragTraceId != null) {
            store.put(KEY_RAG_TRACE_ID, ragTraceId);
        }
    }

    public Long getRagTraceId() {
        return (Long) store.get(KEY_RAG_TRACE_ID);
    }

    /**
     * 获取黑板的快照（仅用于调试与日志输出，不包含敏感数据时使用）。
     */
    public Map<String, Object> snapshot() {
        return new HashMap<>(store);
    }
}
