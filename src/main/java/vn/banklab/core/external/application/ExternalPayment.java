package vn.banklab.core.external.application;

import java.time.Instant;
import java.util.UUID;

public record ExternalPayment(UUID id,String requestHash,UUID accountId,UUID settlementAccountId,String beneficiary,
        String amount,String reservedAmount,UUID partnerInstance,String riskOutcome,String riskPolicy,String state,
        long version,long partnerVersion,String partnerState,String problem,UUID journalId,Instant createdAt,Instant updatedAt){
    public boolean terminal(){return state.equals("SUCCEEDED")||state.equals("FAILED")||state.equals("CANCELLED");}
}
