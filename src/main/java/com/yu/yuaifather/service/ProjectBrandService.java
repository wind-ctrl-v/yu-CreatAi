package com.yu.yuaifather.service;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.yuaifather.model.dto.agent.ProjectBrandContext;
import com.yu.yuaifather.mapper.ProjectBrandProfileMapper;
import com.yu.yuaifather.model.entity.ProjectBrandProfile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 项目品牌服务（阶段三：项目品牌记忆）。
 * <p>
 * 加载项目品牌画像，组装为 ProjectBrandContext 供 Agent 消费。
 *
 * @author yu
 */
@Slf4j
@Service
public class ProjectBrandService {

    private final ProjectBrandProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    public ProjectBrandService(ProjectBrandProfileMapper profileMapper,
                               ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据 projectId 加载项目品牌上下文。
     *
     * @param projectId 项目ID
     * @return 项目品牌上下文，无品牌画像时返回空上下文
     */
    public ProjectBrandContext loadProjectBrandContext(Long projectId) {
        if (projectId == null) {
            return ProjectBrandContext.builder().projectId(null).build();
        }

        ProjectBrandProfile profile = profileMapper.selectOneByMap(
                Collections.singletonMap("projectId", projectId));

        if (profile == null) {
            return ProjectBrandContext.builder().projectId(projectId).build();
        }

        return ProjectBrandContext.builder()
                .projectId(projectId)
                .brandName(profile.getBrandName())
                .industry(profile.getIndustry())
                .targetAudience(profile.getTargetAudience())
                .brandTone(parseJsonList(profile.getBrandTone()))
                .brandColors(parseJsonMap(profile.getBrandColors()))
                .typographyPreference(parseJsonList(profile.getTypographyPreference()))
                .layoutPreference(parseJsonList(profile.getLayoutPreference()))
                .componentPreference(parseJsonList(profile.getComponentPreference()))
                .designGuidelines(parseJsonList(profile.getDesignGuidelines()))
                .forbiddenRules(parseJsonList(profile.getForbiddenRules()))
                .profileSummary(profile.getProfileSummary())
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        if (StrUtil.isBlank(json)) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.debug("解析 JSON 列表失败", e);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseJsonMap(String json) {
        if (StrUtil.isBlank(json)) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            log.debug("解析 JSON Map 失败", e);
            return Collections.emptyMap();
        }
    }
}
