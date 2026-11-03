package vn.banklab.core.transfer.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.ledger.application.LedgerTransferAccess;
import vn.banklab.core.risk.application.TransferRiskGate;
import vn.banklab.core.shared.application.BusinessException;

/** Sole transactional transfer writer. The remote risk gate runs before entering this bean. */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class TransferPostingService {
    private final TransferRepository transfers;
    private final CurrentActorProvider actors;
    private final LedgerTransferAccess ledger;
    public TransferPostingService(TransferRepository transfers,CurrentActorProvider actors,LedgerTransferAccess ledger) {
        this.transfers=transfers;this.actors=actors;this.ledger=ledger;
    }

    @Transactional(readOnly=true)
    public Optional<TransferReceipt> replay(String key,String hash) {
        return transfers.findByKey(actors.currentActor(),key).map(claim->{
            matching(claim,hash);
            if(claim.receipt()==null)throw new IllegalStateException("Committed transfer must have a receipt");
            return claim.receipt();
        });
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public TransferReceipt post(String key,String hash,UUID source,UUID destination,String amount,TransferRiskGate.Approval approval) {
        var claim=transfers.claim(actors.currentActor(),key,hash,source,destination,new BigDecimal(amount));
        matching(claim,hash);
        if(claim.receipt()!=null)return claim.receipt(); // A concurrent winner's original decision remains authoritative.
        fresh(approval);
        var journal=ledger.post(source,destination,claim.id(),new BigDecimal(amount));
        fresh(approval); // Recheck after any wait for account locks and final funds/state/fee validation.
        var receipt=transfers.complete(claim.id(),journal);
        transfers.recordRisk(claim.id(),hash,approval);
        return receipt;
    }

    private static void matching(TransferRepository.Claim claim,String hash) {
        if(!claim.requestHash().equals(hash))throw new BusinessException("IDEMPOTENCY_CONFLICT",BusinessException.Kind.CONFLICT);
    }
    private static void fresh(TransferRiskGate.Approval approval) {
        if(approval.expiresAt()!=null && !Instant.now().isBefore(approval.expiresAt()))
            throw new BusinessException("RISK_APPROVAL_EXPIRED",BusinessException.Kind.CONFLICT);
    }
}
