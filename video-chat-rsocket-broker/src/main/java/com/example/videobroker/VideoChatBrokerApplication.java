package com.example.videobroker;

import com.example.videobroker.config.BackofficeRoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication(proxyBeanMethods = false)
@EnableConfigurationProperties(BackofficeRoutingProperties.class)
public class VideoChatBrokerApplication {

    private static final Logger log = LoggerFactory.getLogger(VideoChatBrokerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(VideoChatBrokerApplication.class, args);
    }

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> logStartupEndpoints(
        @Value("${spring.rsocket.server.mapping-path:/rsocket}") String mappingPath
    ) {
        return event -> {
            int serverPort = event.getWebServer().getPort();
            String normalizedPath = mappingPath.startsWith("/") ? mappingPath : "/" + mappingPath;
            log.info("RSocket broker ready at ws://localhost:{}{}", serverPort, normalizedPath);
            log.info("Health endpoint: http://localhost:{}/actuator/health", serverPort);
        };
    }
}
