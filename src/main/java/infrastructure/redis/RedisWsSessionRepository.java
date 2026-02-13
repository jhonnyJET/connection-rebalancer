package infrastructure.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import domain.PersistentSession;
import domain.WsSessionRepository;
import io.quarkus.redis.datasource.ReactiveRedisDataSource;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.keys.ReactiveKeyCommands;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.redis.datasource.value.ReactiveValueCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.quarkus.vertx.http.runtime.devmode.Json;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class RedisWsSessionRepository implements WsSessionRepository {
    private static final TypeReference<PersistentSession> dsValueType = new TypeReference<>() {};
    private static final TypeReference<Map<String, Integer>> dsPubSubValueType = new TypeReference<>() {};
    private final ValueCommands<String, PersistentSession> valueCommands;
    private final ReactiveValueCommands<String, PersistentSession> reactiveValueCommands;
    private final ReactiveKeyCommands<String> reactiveWsSessionKeyCommands;
    private final KeyCommands<String> wsSessionKeyCommands;
    private final PubSubCommands<Map<String, Integer>> pubSubCommand;
    private final ObjectMapper objectMapper;

    public RedisWsSessionRepository(RedisDataSource ds, ReactiveRedisDataSource reactiveDS, ObjectMapper objectMapper) {
        this.valueCommands = ds.value(dsValueType);
        this.reactiveValueCommands = reactiveDS.value(dsValueType);
        this.reactiveWsSessionKeyCommands = reactiveDS.key();
        this.wsSessionKeyCommands = ds.key();
        this.objectMapper = objectMapper;
        this.pubSubCommand = ds.pubsub(dsPubSubValueType);
    }

    @Override
    public List<PersistentSession> findAllSessions() {
        // Use the imperative API's key commands
        Iterable<String> keys = wsSessionKeyCommands.keys("WsSession#*"); // Caution: Use with care in production
        Map<String, PersistentSession> result = new HashMap<>();

        try {
            for (String key : keys) {
                var value = valueCommands.get(key);
                result.put(key, value);
            }
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Error processing JSON: " + e.getMessage(), e);
        }

        return List.copyOf(result.values());
    }

    @Override
    public void dropWsSessions(Map<String, Integer> numberOfConnectionsToDrop) {
        Logger.getAnonymousLogger().log(Level.INFO, "Publishing drop-persistent-sessions command with data: " + numberOfConnectionsToDrop.toString());  
        pubSubCommand.publish("drop-persistent-sessions", numberOfConnectionsToDrop);
    }

    private String formattedKey(String key) {
        return String.format("%s#%s", "WsSession", key);
    }


}
