package com.example.delahuerta.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

public class RailwayMysqlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "railwayMysqlOverrides";
    private static final Map<String, String> DEFAULT_OPTIONS;

    static {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("useSSL", "false");
        defaults.put("serverTimezone", "UTC");
        defaults.put("allowPublicKeyRetrieval", "true");
        defaults.put("useUnicode", "true");
        defaults.put("characterEncoding", "utf8");
        DEFAULT_OPTIONS = Map.copyOf(defaults);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String mysqlUrl = environment.getProperty("MYSQL_URL");
        if (!StringUtils.hasText(mysqlUrl)) {
            return;
        }

        ParsedMysqlUrl parsed = parse(mysqlUrl);
        if (parsed == null) {
            return;
        }

        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("spring.datasource.url", parsed.jdbcUrl);

        if (parsed.username != null) {
            overrides.put("spring.datasource.username", parsed.username);
        }
        if (parsed.password != null) {
            overrides.put("spring.datasource.password", parsed.password);
        }

        if (overrides.isEmpty()) {
            return;
        }

        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, overrides));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private ParsedMysqlUrl parse(String mysqlUrl) {
        try {
            URI uri = new URI(mysqlUrl);
            if (!"mysql".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }

            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                return null;
            }

            int port = uri.getPort() > 0 ? uri.getPort() : 3306;
            String database = Optional.ofNullable(uri.getPath())
                    .map(path -> path.startsWith("/") ? path.substring(1) : path)
                    .filter(StringUtils::hasText)
                    .orElse(null);

            StringBuilder jdbcUrl = new StringBuilder("jdbc:mysql://")
                    .append(host)
                    .append(':')
                    .append(port);
            if (database != null) {
                jdbcUrl.append("/").append(database);
            }

            String query = uri.getQuery();
            jdbcUrl.append('?').append(buildQuery(query));

            String username = null;
            String password = null;
            String userInfo = uri.getUserInfo();
            if (StringUtils.hasText(userInfo)) {
                String[] pieces = userInfo.split(":", 2);
                username = pieces[0];
                if (pieces.length > 1) {
                    password = pieces[1];
                }
            }

            return new ParsedMysqlUrl(jdbcUrl.toString(), username, password);
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private String buildQuery(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (StringUtils.hasText(query)) {
            for (String pair : query.split("&")) {
                if (!StringUtils.hasText(pair)) {
                    continue;
                }
                String[] pieces = pair.split("=", 2);
                if (!StringUtils.hasText(pieces[0])) {
                    continue;
                }
                parameters.putIfAbsent(pieces[0], pieces.length > 1 ? pieces[1] : "");
            }
        }

        for (Map.Entry<String, String> entry : DEFAULT_OPTIONS.entrySet()) {
            String key = entry.getKey();
            boolean present = parameters.keySet().stream()
                    .anyMatch(existing -> existing.equalsIgnoreCase(key));
            if (!present) {
                parameters.put(key, entry.getValue());
            }
        }

        return parameters.entrySet().stream()
                .map(entry -> entry.getKey() + (entry.getValue().isEmpty() ? "" : "=" + entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static final class ParsedMysqlUrl {
        private final String jdbcUrl;
        private final String username;
        private final String password;

        private ParsedMysqlUrl(String jdbcUrl, String username, String password) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
        }
    }
}
