package infrastructure.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import domain.GrpcPersistentSession;
import domain.GrpcSessionRepository;
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
public class RedisGrpcSessionRepository implements GrpcSessionRepository {
    private static final TypeReference<GrpcPersistentSession> dsValueType = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Integer>> dsPubSubValueType = new TypeReference<>() {
    };

    private final ValueCommands<String, GrpcPersistentSession> valueCommands;
    private final KeyCommands<String> grpcSessionKeyCommands;
    private final PubSubCommands<Map<String, Integer>> pubSubCommand;

    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public RedisGrpcSessionRepository(RedisDataSource ds, ObjectMapper objectMapper) {
        this.valueCommands = ds.value(dsValueType);
        this.grpcSessionKeyCommands = ds.key();
        this.objectMapper = objectMapper;
        this.pubSubCommand = ds.pubsub(dsPubSubValueType);
    }

    @Override
    public List<GrpcPersistentSession> findAllSessions() {
        Iterable<String> keys = grpcSessionKeyCommands.keys("GrpcSession#*");
        Map<String, GrpcPersistentSession> result = new HashMap<>();

        try {
            for (String key : keys) {
                var value = valueCommands.get(key);
                result.put(key, value);
            }
        } catch (Exception e) {
            Logger.getAnonymousLogger().log(Level.SEVERE, "Error reading gRPC sessions from Redis: " + e.getMessage(), e);
        }

        return List.copyOf(result.values());
    }

    @Override
    public void dropGrpcSessions(Map<String, Integer> numberOfConnectionsToDrop) {
        Logger.getAnonymousLogger().log(Level.INFO,
                "Publishing drop-persistent-grpc-sessions command with data: " + numberOfConnectionsToDrop);
        pubSubCommand.publish("drop-persistent-grpc-sessions", numberOfConnectionsToDrop);
    }
}
