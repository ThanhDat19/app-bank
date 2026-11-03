package vn.banklab.core.reconciliation.application;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.shared.application.BusinessException;

@Service
public class ReconciliationService {
    public record Report(ReconciliationRepository.Run run,List<ReconciliationRepository.RuleCount> summary) { }
    public record Page<T>(List<T> items,Long nextCursor) { }
    private final ReconciliationRepository repository;
    private final CurrentActorProvider actors;
    private final int grace;
    private final JsonMapper json=JsonMapper.builder().build();
    public ReconciliationService(ReconciliationRepository repository,CurrentActorProvider actors,
                                 @Value("${bank.reconciliation.grace-seconds:300}") int grace) {
        if(grace<1 || grace>86400) throw new IllegalArgumentException("Invalid reconciliation grace period");
        this.repository=repository;this.actors=actors;this.grace=grace;
    }
    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional(isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Report run() { return execute(actors.currentActor(),"OPERATOR"); }

    /** Trusted scheduler entry point; not mapped to HTTP. */
    @Transactional(isolation=Isolation.REPEATABLE_READ,timeout=30)
    public Report runScheduled() { return execute(new Actor("urn:bank-lab:system","reconciliation-scheduler",Set.of("SYSTEM")),"SYSTEM"); }

    private Report execute(Actor actor,String type) {
        if(!repository.tryLock()) throw new BusinessException("RECONCILIATION_IN_PROGRESS",BusinessException.Kind.CONFLICT);
        UUID id=UUID.randomUUID();repository.start(id,actor,type,grace);repository.scan(id);
        UUID after=null;
        while(true) {
            var entries=repository.hashes(after);
            if(entries.isEmpty()) break;
            for(var entry:entries) {
                String hash;
                try {hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(entry.event())));}
                catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
                if(!hash.equals(entry.storedHash())) repository.hashMismatch(id,entry.event().eventId());
            }
            after=entries.getLast().event().eventId();
        }
        repository.complete(id);return report(id);
    }
    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional(readOnly=true)
    public Report get(UUID id) { return report(id); }
    private Report report(UUID id) { return new Report(existing(id),repository.summary(id)); }
    private ReconciliationRepository.Run existing(UUID id) { return repository.find(id).orElseThrow(()->new BusinessException("RECONCILIATION_NOT_FOUND",BusinessException.Kind.NOT_FOUND)); }
    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional(readOnly=true)
    public Page<ReconciliationRepository.Finding> findings(UUID id,long after,int limit) {
        existing(id);validate(limit,after>=0);
        var rows=repository.findings(id,after,limit+1);boolean more=rows.size()>limit;
        var items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new Page<>(items,more?items.getLast().id():null);
    }
    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional(readOnly=true)
    public Page<ReconciliationRepository.Audit> audit(long before,int limit) {
        validate(limit,before>0);var rows=repository.audit(before,limit+1);boolean more=rows.size()>limit;
        var items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new Page<>(items,more?items.getLast().id():null);
    }
    private void validate(int limit,boolean cursor) {
        if(limit<1 || limit>200 || !cursor) throw new BusinessException("RECONCILIATION_PAGE_INVALID",BusinessException.Kind.RULE_VIOLATION);
    }
}
