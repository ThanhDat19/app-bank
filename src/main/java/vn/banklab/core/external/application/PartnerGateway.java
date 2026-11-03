package vn.banklab.core.external.application;

import java.util.UUID;

public interface PartnerGateway {
    record Observation(UUID instanceId,UUID paymentId,String requestHash,String amount,String currency,String beneficiary,String state,long version) { }
    record Result(Observation observation,String problem) { }
    UUID instance();
    Result submit(ExternalPayment payment);
    Result query(ExternalPayment payment);
}
