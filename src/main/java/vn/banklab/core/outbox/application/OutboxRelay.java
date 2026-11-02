package vn.banklab.core.outbox.application;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelay {
    public record Result(int claimed,int delivered,int failed,int stale) { }
    private final OutboxRepository repository;
    public OutboxRelay(OutboxRepository repository) { this.repository=repository; }

    /** Claim/ack/retry have their own short transactions; never publish under a transaction. */
    @Transactional(propagation=Propagation.NEVER)
    public Result deliverBatch(OutboxPublisher publisher,int batchSize,long leaseMillis) {
        Objects.requireNonNull(publisher);
        var claims=repository.claim(batchSize,leaseMillis);
        int delivered=0,failed=0,stale=0;
        for(var claim:claims) {
            try {
                publisher.publish(claim.event());
            } catch(Exception failure) {
                failed++;
                if(!repository.retry(claim.event().eventId(),claim.token(),retryDelay(claim.attempt()))) stale++;
                if(failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break; // Remaining claims become recoverable when their leases expire.
                }
                continue;
            }
            // If ack fails after transport accepted, keep the lease; redelivery uses the same eventId.
            if(repository.acknowledge(claim.event().eventId(),claim.token())) delivered++; else stale++;
        }
        return new Result(claims.size(),delivered,failed,stale);
    }

    public static long retryDelay(int attempt) {
        return Math.min(300000L,1000L << Math.max(0,Math.min(attempt-1,9)));
    }
}
