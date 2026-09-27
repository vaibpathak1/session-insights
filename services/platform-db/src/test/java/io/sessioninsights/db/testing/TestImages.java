package io.sessioninsights.db.testing;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Container images for integration tests, read from the repository's {@code .env.example}
 * so tests always use the same images as {@code docker-compose.yml}.
 */
public final class TestImages {

    private static final Properties ENV = load();

    public static final String POSTGRES = ENV.getProperty("POSTGRES_IMAGE");
    public static final String CLICKHOUSE = ENV.getProperty("CLICKHOUSE_IMAGE");
    public static final String KAFKA = ENV.getProperty("KAFKA_IMAGE");

    private TestImages() {
    }

    private static Properties load() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve(".env.example"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException(".env.example not found above " + Path.of("").toAbsolutePath());
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(dir.resolve(".env.example"))) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties;
    }
}
