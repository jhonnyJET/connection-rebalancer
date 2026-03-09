package infrastructure.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import domain.SsePersistentSession;
import domain.SseSessionRepository;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class RedisSseSessionRepository implements SseSessionRepository {
    private static final TypeReference<SsePersistentSession> dsValueType = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Integer>> dsPubSubValueType = new TypeReference<>() {
    };

    private final ValueCommands<String, SsePersistentSession> valueCommands;
    private final KeyCommands<String> sseSessionKeyCommands;
    private final PubSubCommands<Map<String, Integer>> pubSubCommand;

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public RedisSseSessionRepository(RedisDataSource ds, ObjectMapper objectMapper) {
        this.valueCommands = ds.value(dsValueType);
        this.sseSessionKeyCommands = ds.key();
        this.objectMapper = objectMapper;
        this.pubSubCommand = ds.pubsub(dsPubSubValueType);
    }

    @Override
    public List<SsePersistentSession> findAllSessions() {
        Iterable<String> keys = sseSessionKeyCommands.keys("SseSession#*");
        Map<String, SsePersistentSession> result = new HashMap<>();

        try {
            for (String key : keys) {
                var value = valueCommands.get(key);
                result.put(key, value);
            }
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Error reading SSE sessions from Redis: " + e.getMessage(), e);
        }

        return List.copyOf(result.values());
    }

    @Override
    public void dropSseSessions(Map<String, Integer> numberOfConnectionsToDrop) {
        Logger.getAnonymousLogger().log(Level.INFO,
                "Publishing drop-persistent-sse-sessions command with data: " + numberOfConnectionsToDrop);
        pubSubCommand.publish("drop-persistent-sse-sessions", numberOfConnectionsToDrop);
    }
}
