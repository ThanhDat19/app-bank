package vn.banklab.risksimulator;

import java.math.BigInteger;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import vn.banklab.risk.v1.*;

/** Deterministic simulation, not a production fraud/AML decision. No database or caller identity. */
public final class AmountRiskService extends RiskEvaluatorGrpc.RiskEvaluatorImplBase {
    private final BigInteger cap;
    private final String policyVersion;

    public AmountRiskService(String cap) {
        if (cap == null || !cap.matches("[1-9][0-9]{0,19}")) throw new IllegalArgumentException("Invalid risk cap");
        this.cap = new BigInteger(cap); policyVersion = "amount-cap-v1-" + cap;
    }

    @Override public void assessTransfer(AssessTransferRequest request, StreamObserver<AssessTransferResponse> reply) {
        if (!request.getRequestId().matches("[a-f0-9]{64}") || !request.getAmount().matches("[1-9][0-9]{0,19}")
                || !request.getCurrency().equals("VND")) {
            reply.onError(Status.INVALID_ARGUMENT.withDescription("Invalid risk request").asRuntimeException()); return;
        }
        if (Context.current().isCancelled()) return;
        boolean approved = new BigInteger(request.getAmount()).compareTo(cap) <= 0;
        reply.onNext(AssessTransferResponse.newBuilder().setRequestId(request.getRequestId())
                .setOutcome(approved ? Outcome.APPROVE : Outcome.REJECT).setPolicyVersion(policyVersion)
                .setReason(approved ? Reason.AMOUNT_WITHIN_LIMIT : Reason.AMOUNT_LIMIT_EXCEEDED).build());
        reply.onCompleted();
    }
}
