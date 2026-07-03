package com.yu.yuaifather.service;

import com.yu.yuaifather.model.entity.App;
import jakarta.servlet.http.HttpServletResponse;

public interface ProjectDownloadService {

    /**
     * 下载项目为zip
     * @param projectPath
     * @param downloadFileName
     * @param response
     */
    void downloadProjectAsZip(String projectPath, String downloadFileName, HttpServletResponse response);

    /**
     * 解析并校验应用代码生成目录（非部署目录）是否存在，返回其绝对路径。
     * <p>
     * 从 Controller 下沉的路径构造 + 存在性校验逻辑（见包结构优化计划 2），
     * 供 {@code downloadProjectAsZip} 的调用方直接拿到可用路径，不再需要自行拼接 {@code File.separator}。
     *
     * @param app 应用实体（需已确认非空）
     * @return 代码生成目录绝对路径
     * @throws com.yu.yuaifather.exception.BusinessException 若代码目录不存在或不是目录
     */
    String resolveAndValidateSourceDir(App app);
}
