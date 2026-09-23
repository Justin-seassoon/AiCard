package com.aicard.ota.config;

import com.aicard.ota.service.OtaFileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OTA 装配：存储目录、签名密钥、URL 有效期、公网基础地址，均经环境变量/配置注入。 */
@Configuration
public class OtaConfig {

    @Value("${ota.storage-dir:/data/ota}")
    private String storageDir;

    @Value("${ota.sign-secret:ota-demo-sign-secret}")
    private String signSecret;

    @Value("${ota.url-expires-sec:3600}")
    private long urlExpiresSec;

    @Value("${ota.public-base-url:}")
    private String publicBaseUrl;

    @Bean
    public OtaFileService otaFileService() {
        return new OtaFileService(storageDir, signSecret, urlExpiresSec, publicBaseUrl);
    }
}
