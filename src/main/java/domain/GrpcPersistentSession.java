package domain;

public record GrpcPersistentSession(String userId, String sessionId, String hostId) {
}
