package domain;

import java.util.List;
import java.util.Map;

public interface SseSessionRepository {
    List<SsePersistentSession> findAllSessions();
    void dropSseSessions(Map<String, Integer> numberOfConnectionsToDrop);
}
