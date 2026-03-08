package nl.nextend.videobackoffice;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(proxyBeanMethods = false)
/**
 * Entry point for the backoffice service.
 *
 * <p>The service keeps a lightweight in-memory view of room activity that can be queried over HTTP
 * for operational dashboards or troubleshooting.
 */
public class VideoChatBackofficeApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoChatBackofficeApplication.class, args);
    }

    @Bean
    /**
     * Exposes a shared UTC clock so timestamp normalization stays deterministic and testable.
     */
    Clock applicationClock() {
        return Clock.systemUTC();
    }
}
