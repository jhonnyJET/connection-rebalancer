package infrastructure.redis.dto;

public record PersistentSession(String userId, String sessionId, String hostId) {
}
