package dev.bob.openmarket.auth.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bob.openmarket.auth.domain.AuditLog;
import dev.bob.openmarket.auth.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Append-only audit trail for admin actions. `record` runs inside the
 * caller's transaction (REQUIRED), so the action and its audit row commit
 * or roll back together.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogs;
    private final ObjectMapper mapper;

    public AuditService(AuditLogRepository auditLogs, ObjectMapper mapper) {
        this.auditLogs = auditLogs;
        this.mapper = mapper;
    }

    @Transactional
    public void record(UUID actorId, String action, UUID targetUserId,
                       Map<String, Object> details, String ip) {
        AuditLog log = new AuditLog();
        log.setActorId(actorId);
        log.setAction(action);
        log.setTargetUserId(targetUserId);
        log.setDetails(details == null ? null : toJson(details));
        log.setIp(ip);
        auditLogs.save(log);
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize audit details", e);
        }
    }
}
