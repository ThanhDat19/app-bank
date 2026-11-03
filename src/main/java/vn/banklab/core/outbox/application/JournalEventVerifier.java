package vn.banklab.core.outbox.application;

import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
public class JournalEventVerifier {
    public record Verified(OutboxEvent event,String hash) { }
    private final OutboxRepository events;
    private final JsonMapper json=JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    public JournalEventVerifier(OutboxRepository events) { this.events=events; }

    @Transactional(propagation=Propagation.MANDATORY)
    public Verified verify(String key,String raw) {
        try {
            if(raw==null || raw.length()>262144) throw new IllegalArgumentException("INVALID_EVENT");
            var candidate=json.readValue(raw,OutboxEvent.class);
            if(candidate.eventId()==null || candidate.aggregateId()==null || !candidate.aggregateId().toString().equals(key)
                    || candidate.schemaVersion()!=1 || candidate.aggregateVersion()!=1) throw new IllegalArgumentException("INVALID_EVENT");
            var expected=events.find(candidate.eventId()).orElseThrow(()->new IllegalArgumentException("INVALID_EVENT"));
            var normalized=new OutboxEvent(candidate.eventId(),candidate.aggregateId(),candidate.aggregateType(),candidate.aggregateVersion(),
                    candidate.eventType(),candidate.schemaVersion(),candidate.occurredAt(),expected.payload());
            if(!expected.equals(normalized) || !json.readTree(expected.payload()).equals(json.readTree(candidate.payload())))
                throw new IllegalArgumentException("INVALID_EVENT");
            return new Verified(expected,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(expected))));
        } catch(org.springframework.dao.DataAccessException failure) { throw failure;
        } catch(Exception failure) { throw new IllegalArgumentException("INVALID_EVENT"); }
    }
}
