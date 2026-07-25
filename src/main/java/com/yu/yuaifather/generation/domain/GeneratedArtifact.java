package com.yu.yuaifather.generation.domain;

import com.yu.yuaifather.model.enums.CodeGenTypeEnum;
import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 生成链路统一文件产物。
 */
@Value
@Builder
public class GeneratedArtifact {
    String generationId;
    Long appId;
    CodeGenTypeEnum codeGenType;
    Path workingDirectory;
    List<String> files;
    String entryFile;
    Map<String, Object> metadata;
}
