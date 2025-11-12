package com.example.delahuerta.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.util.StringUtils;

public class RailwayMysqlEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(RailwayMysqlEnvironmentPostProcessor.class);

    private static final String PROPERTY_SOURCE_NAME = "railwayMysqlOverrides";
    private static final int DEFAULT_PORT = 3306;

    private static final Map<String, String> DEFAULT_OPTIONS;
    private static final List<String> CONNECTION_STRING_VARIABLES = List.of(
            "MYSQL_URL",
            "MYSQL_PUBLIC_URL",
            "DATABASE_URL",
            "CLEARDB_DATABASE_URL",
            "JAWSDB_URL",
            "JAWSDB_MARIA_URL");

    private static final List<String> FALLBACK_PROXY_HOST_VARIABLES = List.of(
            "RAILWAY_TCP_PROXY_DOMAIN",
            "RAILWAY_TCP_PROXY_HOST",
            "RAILWAY_TCP_HOST");

    private static final List<String> FALLBACK_PROXY_PORT_VARIABLES = List.of(
            "RAILWAY_TCP_PROXY_PORT",
            "RAILWAY_TCP_APPLICATION_PORT",
            "RAILWAY_TCP_PORT");

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
        ParsedMysqlUrl parsed = resolveConfiguration(environment);
        if (parsed == null) {
            if (logger.isWarnEnabled()) {
                logger.warn("No Railway MySQL environment variables detected. Expected one of {} or host/port variables such as MYSQLHOST.",
                        CONNECTION_STRING_VARIABLES);
            }
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
        maybeSetHikariFailFastTimeout(environment, overrides);

        MutablePropertySources propertySources = environment.getPropertySources();
        MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, overrides);
        if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
            propertySources.replace(PROPERTY_SOURCE_NAME, propertySource);
        } else {
            propertySources.addFirst(propertySource);
        }

        if (logger.isInfoEnabled()) {
            logger.info("Detected MySQL configuration from {} (host: {}, port: {}, database: {}, user provided: {})",
                    parsed.source,
                    parsed.host,
                    parsed.port,
                    parsed.database != null ? parsed.database : "<default>",
                    parsed.username != null);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Effective JDBC URL (sanitized): {}", parsed.jdbcUrl);
        }
    }

    private void maybeSetHikariFailFastTimeout(ConfigurableEnvironment environment, Map<String, Object> overrides) {
        String propertyName = "spring.datasource.hikari.initialization-fail-timeout";
        if (environment.containsProperty(propertyName)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Respecting existing value for {}", propertyName);
            }
            return;
        }
        overrides.put(propertyName, "0");
        if (logger.isInfoEnabled()) {
            logger.info("Configured {}=0 to keep retrying while the MySQL service becomes available", propertyName);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private ParsedMysqlUrl resolveConfiguration(ConfigurableEnvironment environment) {
        ParsedMysqlUrl parsed = resolveFromConnectionString(environment);
        if (parsed != null) {
            return enrichWithExplicitCredentials(parsed, environment);
        }
        return resolveFromDiscreteVariables(environment);
    }

    private ParsedMysqlUrl resolveFromConnectionString(ConfigurableEnvironment environment) {
        for (String variable : CONNECTION_STRING_VARIABLES) {
            String value = environment.getProperty(variable);
            if (!StringUtils.hasText(value)) {
                continue;
            }
            ParsedMysqlUrl parsed = parseConnectionString(value.trim(), variable);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private ParsedMysqlUrl enrichWithExplicitCredentials(ParsedMysqlUrl parsed, ConfigurableEnvironment environment) {
        String username = parsed.username;
        if (username == null) {
            username = firstNonEmptyAllowEmpty(environment, "MYSQLUSER", "MYSQL_USER");
        }
        String password = parsed.password;
        if (password == null) {
            password = firstNonEmptyAllowEmpty(environment,
                    "MYSQLPASSWORD",
                    "MYSQL_PASSWORD",
                    "MYSQL_ROOT_PASSWORD");
        }
        if (Objects.equals(username, parsed.username) && Objects.equals(password, parsed.password)) {
            return parsed;
        }
        return new ParsedMysqlUrl(parsed.jdbcUrl, username, password, parsed.host, parsed.port, parsed.database, parsed.source);
    }

    private ParsedMysqlUrl resolveFromDiscreteVariables(ConfigurableEnvironment environment) {
        String host = firstNonEmpty(environment,
                "MYSQLHOST",
                "MYSQLHOSTNAME",
                "MYSQL_HOST",
                "MYSQL_HOSTNAME");
        if (!StringUtils.hasText(host)) {
            host = firstNonEmpty(environment, FALLBACK_PROXY_HOST_VARIABLES.toArray(String[]::new));
        }
        if (!StringUtils.hasText(host)) {
            return null;
        }

        String portValue = firstNonEmpty(environment, "MYSQLPORT", "MYSQL_PORT");
        if (!StringUtils.hasText(portValue)) {
            portValue = firstNonEmpty(environment, FALLBACK_PROXY_PORT_VARIABLES.toArray(String[]::new));
        }
        int port = parsePort(portValue);

        String database = firstNonEmpty(environment, "MYSQLDATABASE", "MYSQL_DATABASE");
        String username = firstNonEmptyAllowEmpty(environment,
                "MYSQLUSER",
                "MYSQL_USER",
                "MYSQLUSERNAME",
                "MYSQL_USERNAME");
        String password = firstNonEmptyAllowEmpty(environment,
                "MYSQLPASSWORD",
                "MYSQL_PASSWORD",
                "MYSQL_ROOT_PASSWORD");

        String jdbcUrl = buildJdbcUrl(host, port, database, Map.of());
        return new ParsedMysqlUrl(jdbcUrl, username, password, host, port, database, "environment variables");
    }

    private ParsedMysqlUrl parseConnectionString(String rawValue, String source) {
        String candidate = rawValue;
        if (candidate.startsWith("jdbc:")) {
            candidate = candidate.substring("jdbc:".length());
        }

        URI uri = buildUri(candidate);
        if (uri == null) {
            return null;
        }

        String scheme = uri.getScheme();
        if (scheme != null && !"mysql".equalsIgnoreCase(scheme)) {
            return null;
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            return null;
        }

        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
        String database = Optional.ofNullable(uri.getPath())
                .map(path -> path.startsWith("/") ? path.substring(1) : path)
                .filter(StringUtils::hasText)
                .orElse(null);

        Map<String, String> parameters = parseParameters(uri.getRawQuery());

        String username = null;
        String password = null;
        String userInfo = uri.getUserInfo();
        if (StringUtils.hasText(userInfo)) {
            String[] parts = userInfo.split(":", 2);
            username = parts[0];
            if (parts.length > 1) {
                password = parts[1];
            }
        }

        String jdbcUrl = buildJdbcUrl(host, port, database, parameters);
        return new ParsedMysqlUrl(jdbcUrl, username, password, host, port, database, source);
    }

    private URI buildUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            int schemeIndex = value.indexOf("://");
            if (schemeIndex < 0) {
                return null;
            }
            String scheme = value.substring(0, schemeIndex);
            String remainder = value.substring(schemeIndex + 3);
            int atIndex = remainder.indexOf('@');
            if (atIndex < 0) {
                return null;
            }
            String userInfo = remainder.substring(0, atIndex);
            String hostAndPath = remainder.substring(atIndex + 1);
            String encodedUserInfo = encodeUserInfo(userInfo);
            String rebuilt = scheme + "://" + encodedUserInfo + "@" + hostAndPath;
            try {
                return new URI(rebuilt);
            } catch (URISyntaxException ignored) {
                return null;
            }
        }
    }

    private String buildJdbcUrl(String host, int port, String database, Map<String, String> originalParameters) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (originalParameters != null) {
            for (Map.Entry<String, String> entry : originalParameters.entrySet()) {
                String key = entry.getKey();
                if (!StringUtils.hasText(key)) {
                    continue;
                }
                parameters.putIfAbsent(key, entry.getValue() == null ? "" : entry.getValue());
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

        StringBuilder jdbcUrl = new StringBuilder("jdbc:mysql://").append(host);
        if (port > 0) {
            jdbcUrl.append(':').append(port);
        }
        if (StringUtils.hasText(database)) {
            jdbcUrl.append('/').append(database);
        }
        if (!parameters.isEmpty()) {
            jdbcUrl.append('?');
            jdbcUrl.append(parameters.entrySet().stream()
                    .map(entry -> entry.getKey() + (entry.getValue().isEmpty() ? "" : "=" + entry.getValue()))
                    .collect(Collectors.joining("&")));
        }
        return jdbcUrl.toString();
    }

    private Map<String, String> parseParameters(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        if (!StringUtils.hasText(query)) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            if (!StringUtils.hasText(pair)) {
                continue;
            }
            String[] pieces = pair.split("=", 2);
            String key = pieces[0];
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String value = pieces.length > 1 ? pieces[1] : "";
            parameters.putIfAbsent(key, value);
        }
        return parameters;
    }

    private String firstNonEmpty(ConfigurableEnvironment environment, String... keys) {
        return Arrays.stream(keys)
                .map(environment::getProperty)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private String firstNonEmptyAllowEmpty(ConfigurableEnvironment environment, String... keys) {
        return Arrays.stream(keys)
                .map(environment::getProperty)
                .filter(Objects::nonNull)
                .map(String::trim)
                .findFirst()
                .orElse(null);
    }

    private int parsePort(String value) {
        if (!StringUtils.hasText(value)) {
            return DEFAULT_PORT;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : DEFAULT_PORT;
        } catch (NumberFormatException ex) {
            return DEFAULT_PORT;
        }
    }

    private String encodeUserInfo(String value) {
        StringBuilder encoded = new StringBuilder();
        for (char ch : value.toCharArray()) {
            if (isUserInfoSafe(ch)) {
                encoded.append(ch);
            } else {
                encoded.append(String.format(Locale.ROOT, "%%%02X", (int) ch));
            }
        }
        return encoded.toString();
    }

    private boolean isUserInfoSafe(char ch) {
        return Character.isLetterOrDigit(ch)
                || ch == '-'
                || ch == '_'
                || ch == '.'
                || ch == '~'
                || ch == ':';
    }

    private static final class ParsedMysqlUrl {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final String host;
        private final int port;
        private final String database;
        private final String source;

        private ParsedMysqlUrl(String jdbcUrl, String username, String password,
                String host, int port, String database, String source) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.host = host;
            this.port = port;
            this.database = database;
            this.source = source;
        }
    }
}
