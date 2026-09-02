package dev.bob.openmarket.auth.config;

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
 * (postgresql://user:pass@host:port/db) when set — so auth works identically
 * against Supabase or Docker Postgres — and otherwise falls back to the
 * discrete POSTGRES_* vars. All tables live in the {@code auth} schema.
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

    /**
     * Explicit sslmode override. Empty (default) keeps the heuristic: remote
     * hosts get {@code require} (Supabase et al.), localhost gets {@code
     * disable}. Container-network hosts like compose's {@code postgres} are
     * "remote" by the heuristic but have no TLS — set {@code disable} there.
     */
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
            ds.setJdbcUrl(toJdbc(pgHost, Integer.parseInt(pgPort), "auth_db"));
        }
        ds.setMaximumPoolSize(10);
        return ds;
    }

    private String toJdbc(String host, int port, String db) {
        boolean remote = !(host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1"));
        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db + "?currentSchema=auth";
        String sslMode = !databaseSslMode.isBlank() ? databaseSslMode : (remote ? "require" : "disable");
        return url + "&sslmode=" + sslMode;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String stripLeadingSlash(String path) {
        return path != null && path.startsWith("/") ? path.substring(1) : path;
    }
}