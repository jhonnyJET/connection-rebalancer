package api.dto.in;

public record PersistentSession(String userId, String sessionId, String hostId) {
}
