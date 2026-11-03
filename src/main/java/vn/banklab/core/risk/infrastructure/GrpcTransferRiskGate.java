package vn.banklab.core.risk.infrastructure;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.banklab.core.risk.application.TransferRiskGate;
import vn.banklab.core.shared.application.BusinessException;
import vn.banklab.risk.v1.*;

public final class GrpcTransferRiskGate implements TransferRiskGate, AutoCloseable {
    private final ManagedChannel channel;
    private final long deadlineMillis;
    private final long approvalMillis;

    public GrpcTransferRiskGate(String host,int port,long deadlineMillis,long approvalMillis) {
        channel=NettyChannelBuilder.forAddress(host,port).usePlaintext().disableRetry()
                .maxInboundMessageSize(4096).build();
        this.deadlineMillis=deadlineMillis;this.approvalMillis=approvalMillis;
    }
    @Override public Approval assess(String id,String amount) {
        if(TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Risk RPC must be outside a transaction");
        try {
            var result=RiskEvaluatorGrpc.newBlockingStub(channel).withDeadlineAfter(deadlineMillis,TimeUnit.MILLISECONDS)
                    .assessTransfer(AssessTransferRequest.newBuilder().setRequestId(id).setAmount(amount).setCurrency("VND").build());
            boolean approved=result.getOutcome()==Outcome.APPROVE && result.getReason()==Reason.AMOUNT_WITHIN_LIMIT;
            boolean rejected=result.getOutcome()==Outcome.REJECT && result.getReason()==Reason.AMOUNT_LIMIT_EXCEEDED;
            if(!result.getRequestId().equals(id) || !result.getPolicyVersion().matches("[A-Za-z0-9._-]{1,64}") || (!approved && !rejected))
                throw dependency("RISK_RESPONSE_INVALID");
            if(rejected) throw new BusinessException("RISK_REJECTED",BusinessException.Kind.RULE_VIOLATION);
            var assessed=Instant.now();
            return new Approval(id,"APPROVED",result.getPolicyVersion(),result.getReason().name(),assessed,assessed.plusMillis(approvalMillis));
        } catch(StatusRuntimeException failure) {
            throw dependency(failure.getStatus().getCode()==io.grpc.Status.Code.DEADLINE_EXCEEDED?"RISK_TIMEOUT":"RISK_UNAVAILABLE");
        }
    }
    private static BusinessException dependency(String code) {return new BusinessException(code,BusinessException.Kind.DEPENDENCY_UNAVAILABLE);}
    @Override public void close() {channel.shutdownNow();}
}
