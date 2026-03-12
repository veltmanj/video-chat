package nl.nextend.videobackoffice.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
public class BackofficeHttpAccessLogFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(BackofficeHttpAccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!shouldLog(path)) {
            return chain.filter(exchange);
        }

        long startedAt = System.nanoTime();
        HttpMethod method = exchange.getRequest().getMethod();
        return chain.filter(exchange)
            .doFinally(signalType -> {
                HttpStatusCode statusCode = exchange.getResponse().getStatusCode();
                int status = statusCode == null ? 200 : statusCode.value();
                long durationMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
                log.info(
                    "REST {} {} status={} durationMs={}",
                    method == null ? "UNKNOWN" : method.name(),
                    path,
                    status,
                    durationMs
                );
            });
    }

    private boolean shouldLog(String path) {
        return path.startsWith("/api/")
            || path.equals("/api/rooms")
            || path.startsWith("/social/");
    }
}
