package com.yu.yuaifather.model.enums;

/**
 * 生成链路类型。
 * <ul>
 *   <li>{@link #V2}：主链路 GenerationOrchestrator。</li>
 *   <li>{@link #V1}：兼容链路 OrchestratorAgent。</li>
 *   <li>{@link #CLASSIC}：最终兜底 AiCodeGeneratorFacade。</li>
 * </ul>
 */
public enum PipelineType {
    V2,
    V1,
    CLASSIC
}
