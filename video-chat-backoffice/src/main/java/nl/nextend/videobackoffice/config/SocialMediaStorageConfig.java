package nl.nextend.videobackoffice.config;

import io.minio.MinioClient;
import nl.nextend.videobackoffice.social.media.MinioSocialMediaStorage;
import nl.nextend.videobackoffice.social.media.SocialMediaStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class SocialMediaStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "backoffice.social.media.enabled", havingValue = "true")
    @ConditionalOnExpression("T(org.springframework.util.StringUtils).hasText('${backoffice.social.media.endpoint:}')")
    @ConditionalOnMissingBean(SocialMediaStorage.class)
    SocialMediaStorage socialMediaStorage(BackofficeSocialProperties properties) {
        BackofficeSocialProperties.Media media = properties.getMedia();
        MinioClient minioClient = MinioClient.builder()
            .endpoint(media.getEndpoint())
            .credentials(media.getAccessKey(), media.getSecretKey())
            .build();
        return new MinioSocialMediaStorage(minioClient, properties);
    }
}
