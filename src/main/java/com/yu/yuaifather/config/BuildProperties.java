package com.yu.yuaifather.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 构建配置。绑定 {@code codegen.build.*}。
 * <p>
 * 2C4G 单机部署时用于把 Vue 项目构建串行化并放宽超时，避免并发构建把内存打满。
 * 所有默认值均与改造前行为一致。
 */
@Data
@Component
@ConfigurationProperties(prefix = "codegen.build")
public class BuildProperties {

    /** 获取构建锁的最长等待秒数（排队保护），0=无限等待。 */
    private int lockWaitSeconds = 300;

    /** {@code npm install} 超时秒数（原值 300）。 */
    private int installTimeoutSeconds = 300;

    /** {@code npm run build} 超时秒数（原值 180）。 */
    private int buildTimeoutSeconds = 180;

    /** 构建成功后是否删除 node_modules 以节省磁盘（默认关闭，保持旧行为）。 */
    private boolean cleanupNodeModules = false;
}
