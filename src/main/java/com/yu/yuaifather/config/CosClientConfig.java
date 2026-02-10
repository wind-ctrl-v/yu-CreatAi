package com.yu.yuaifather.config;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.auth.COSCredentials;
import com.qcloud.cos.region.Region;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 腾讯云COS配置类
 *  用于配置腾讯云对象存储服务(COS)的客户端连接信息
 *
 * @author yupi
 */
@Configuration
@ConfigurationProperties(prefix = "cos.client")
@Data
public class CosClientConfig {

    /**
     * 域名
     * 用于访问COS服务的基础域名
     */
    private String host;

    /**
     * secretId
     * 腾讯云API密钥的ID部分，用于身份验证
     */
    private String secretId;

    /**
     * 密钥（注意不要泄露）
     * 腾讯云API密钥的Key部分，用于身份验证
     * 请妥善保管，不要泄露给他人
     */
    private String secretKey;

    /**
     * 区域
     * COS服务的部署区域，如 ap-beijing、ap-shanghai 等
     * 区域代码请参照腾讯云官方文档
     */
    private String region;

    /**
     * 桶名
     * COS服务中存储对象的容器名称
     */
    private String bucket;

    /**
     * 创建并配置COS客户端实例
     *
     * @return COSClient 返回配置好的COS客户端实例
     * @throws IllegalArgumentException 如果配置信息不完整
     */
    @Bean
    public COSClient cosClient() {
        // 初始化用户身份信息(secretId, secretKey)
        // 使用BasicCOSCredentials类创建凭证对象
        COSCredentials cred = new BasicCOSCredentials(secretId, secretKey);
        // 设置bucket的区域, COS地域的简称请参照 https://www.qcloud.com/document/product/436/6224
        ClientConfig clientConfig = new ClientConfig(new Region(region));
        // 生成cos客户端
        return new COSClient(cred, clientConfig);
    }
}
