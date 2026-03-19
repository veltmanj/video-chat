package nl.nextend.videobroker;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

import nl.nextend.videobroker.config.BackofficeRoutingProperties;
import nl.nextend.videobroker.config.BrokerJwtProperties;

@SpringBootApplication(proxyBeanMethods = false)
@EnableConfigurationProperties({BackofficeRoutingProperties.class, BrokerJwtProperties.class})
/**
 * Entry point for the room event broker.
 *
 * <p>The broker exposes RSocket routes for publish/stream semantics and optionally mirrors events to
 * one or more backoffice services.
 */
public class VideoChatBrokerApplication {

    private static final Logger log = LoggerFactory.getLogger(VideoChatBrokerApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(VideoChatBrokerApplication.class, args);
    }

    @Bean
    /**
     * Shared UTC clock used when the broker has to stamp events that arrived without a timestamp.
     */
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    /**
     * Logs the effective local endpoints after startup so operational checks can verify the process
     * without opening the code or config.
     */
    ApplicationListener<WebServerInitializedEvent> logStartupEndpoints(
        @Value("${spring.rsocket.server.mapping-path:/rsocket}") String mappingPath,
        @Value("${server.http2.enabled:false}") boolean http2Enabled
    ) {
        return event -> {
            int serverPort = event.getWebServer().getPort();
            String normalizedPath = mappingPath.startsWith("/") ? mappingPath : "/" + mappingPath;
            log.info("RSocket broker ready at ws://localhost:{}{} (HTTP/2 {})", serverPort, normalizedPath, http2Enabled ? "enabled" : "disabled");
            log.info("Health endpoint: http://localhost:{}/actuator/health", serverPort);
        };
    }
}
