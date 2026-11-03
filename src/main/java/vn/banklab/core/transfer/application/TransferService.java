package vn.banklab.core.transfer.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.risk.application.TransferRiskGate;
import vn.banklab.core.shared.application.BusinessException;

@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class TransferService {
    private final TransferRepository transfers;
    private final CurrentActorProvider actors;
    private final TransferPostingService posting;
    private final TransferRiskGate risk;
    private final AccountFinancialAccess accounts;

    public TransferService(TransferRepository transfers, CurrentActorProvider actors, TransferPostingService posting,
                           TransferRiskGate risk,AccountFinancialAccess accounts) {
        this.transfers = transfers;
        this.actors = actors;
        this.posting = posting;this.risk = risk;this.accounts = accounts;
    }

    @Transactional(propagation=Propagation.NEVER)
    public TransferReceipt create(String key, UUID sourceId, UUID destinationId, String amount, String currency) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new BusinessException("IDEMPOTENCY_KEY_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        if (sourceId == null || destinationId == null || amount == null || !amount.matches("[1-9][0-9]{0,19}") || !"VND".equals(currency)) {
            throw new BusinessException("TRANSFER_REQUEST_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        if (sourceId.equals(destinationId)) {
            throw new BusinessException("TRANSFER_ACCOUNTS_INVALID", BusinessException.Kind.RULE_VIOLATION);
        }
        var actor = actors.currentActor();
        var hash = hash("transfer:v1\n" + sourceId + "\n" + destinationId + "\nVND\n" + amount);
        var replay=posting.replay(key,hash);
        if(replay.isPresent())return replay.get();
        accounts.owned(sourceId); // No private request leaves the core before ownership authorization.
        var requestId=hash("risk:v1\n"+actor.issuer()+"\n"+actor.subject()+"\n"+key+"\n"+hash);
        TransferRiskGate.Approval approval;
        try { approval=risk.assess(requestId,amount); }
        catch(BusinessException rejected) {
            // A simultaneous attempt may have committed while this caller's remote preflight failed.
            var completed=posting.replay(key,hash);
            if(completed.isPresent())return completed.get();
            throw rejected;
        }
        return posting.post(key,hash,sourceId,destinationId,amount,approval);
    }

    @Transactional(readOnly = true)
    public TransferReceipt get(UUID id) {
        return transfers.findOwned(id, actors.currentActor()).orElseThrow(() ->
                new BusinessException("TRANSFER_NOT_FOUND", BusinessException.Kind.NOT_FOUND));
    }

    @Transactional(readOnly=true)
    public TransferRiskDecision riskDecision(UUID id) {
        return transfers.findRiskOwned(id,actors.currentActor()).orElseThrow(()->
                new BusinessException("TRANSFER_RISK_DECISION_NOT_FOUND",BusinessException.Kind.NOT_FOUND));
    }

    /** Internal operator reversal eligibility; sender-only HTTP lookup remains unchanged. */
    @PreAuthorize("hasRole('OPERATOR')")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean hasPostedJournal(UUID id) {
        return transfers.hasPostedJournal(id);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
