package domain;

import java.util.List;
import java.util.Map;

public interface WsSessionRepository {
    List<PersistentSession> findAllSessions();
    void dropWsSessions(Map<String, Integer> numberOfConnectionsToDrop);
}
