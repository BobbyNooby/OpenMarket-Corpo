package dev.bob.openmarket.admin;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the datasource from a libpq-style {@code DATABASE_URL}
 * (postgresql://user:pass@host:port/db) — the raw {@code jdbc:} + libpq-URL
 * prefix trick doesn't fly: the PostgreSQL JDBC driver only accepts
 * {@code jdbc:postgresql:} URLs. Mirrors auth's DatabaseConfig, minus the
 * currentSchema pin (admin's Flyway baseline owns the public schema).
 */
@Configuration
public class DatabaseConfig {

    @Value("${DATABASE_URL:}")
    private String databaseUrl;

    @Value("${POSTGRES_HOST:localhost}")
    private String pgHost;

    @Value("${POSTGRES_PORT:5432}")
    private String pgPort;

    @Value("${POSTGRES_USER:om}")
    private String pgUser;

    @Value("${POSTGRES_PASSWORD:devpassword123}")
    private String pgPassword;

    /** Explicit sslmode override; empty keeps the remote/localhost heuristic. */
    @Value("${DATABASE_SSLMODE:}")
    private String databaseSslMode;

    @Bean
    public DataSource dataSource() {
        HikariDataSource ds = new HikariDataSource();
        if (!databaseUrl.isBlank()) {
            URI uri = URI.create(databaseUrl);
            String[] userInfo = uri.getRawUserInfo() != null
                ? uri.getRawUserInfo().split(":", 2)
                : new String[] {"", ""};
            ds.setUsername(decode(userInfo[0]));
            ds.setPassword(userInfo.length > 1 ? decode(userInfo[1]) : "");
            ds.setJdbcUrl(toJdbc(uri.getHost(), uri.getPort(), stripLeadingSlash(uri.getPath())));
        } else {
            ds.setUsername(pgUser);
            ds.setPassword(pgPassword);
            ds.setJdbcUrl(toJdbc(pgHost, Integer.parseInt(pgPort), "admin_db"));
        }
        ds.setMaximumPoolSize(5);
        return ds;
    }

    private String toJdbc(String host, int port, String db) {
        boolean remote = !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
        String sslMode = !databaseSslMode.isBlank() ? databaseSslMode : (remote ? "require" : "disable");
        return "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=" + sslMode;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String stripLeadingSlash(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }
}
