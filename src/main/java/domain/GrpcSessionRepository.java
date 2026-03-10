package domain;

import java.util.List;
import java.util.Map;

public interface GrpcSessionRepository {
    List<GrpcPersistentSession> findAllSessions();
    void dropGrpcSessions(Map<String, Integer> numberOfConnectionsToDrop);
}
