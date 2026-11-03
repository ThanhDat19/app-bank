package vn.banklab.core.external.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.risk.application.TransferRiskGate;

public interface ExternalPaymentRepository {
    record History(long version,String state,long partnerVersion,String partnerState,String problem,Instant occurredAt) { }
    Optional<ExternalPayment> findKey(Actor actor,String key);
    Optional<ExternalPayment> owned(Actor actor,UUID id,boolean lock);
    ExternalPayment claim(Actor actor,String key,String hash,UUID account,UUID settlement,String beneficiary,String amount,UUID instance,TransferRiskGate.Approval approval);
    ExternalPayment update(UUID id,String state,BigDecimal reserved,long partnerVersion,String partnerState,String problem,UUID journal);
    List<History> history(UUID id,int limit);
}
