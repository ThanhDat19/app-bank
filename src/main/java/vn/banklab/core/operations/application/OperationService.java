package vn.banklab.core.operations.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.account.application.AccountOperationAccess;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.reversal.application.ReversalService;
import vn.banklab.core.shared.application.BusinessException;
import static vn.banklab.core.shared.application.BusinessException.Kind.*;

@Service
@PreAuthorize("hasRole('OPERATOR')")
@Transactional(isolation = Isolation.READ_COMMITTED)
public class OperationService {
    private final OperationRepository requests;
    private final CurrentActorProvider actors;
    private final AccountOperationAccess accounts;
    private final ReversalService reversals;
    private final JsonMapper json;
    public OperationService(OperationRepository requests, CurrentActorProvider actors, AccountOperationAccess accounts,
                            ReversalService reversals, JsonMapper json) {
        this.requests = requests; this.actors = actors; this.accounts = accounts; this.reversals = reversals; this.json = json;
    }
    public OperationRequest create(String key, String type, UUID target, Long accountVersion, String reason) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw error("IDEMPOTENCY_KEY_INVALID", RULE_VIOLATION);
        validate(type, target, accountVersion, reason);
        var actor = actors.currentActor();
        String hash = hash(type + "\n" + target + "\n" + accountVersion + "\n" + reason);
        var replay = requests.replay(actor, key);
        if (replay.isPresent()) return same(replay.get(), hash);
        if (!"REVERSAL".equals(type)) accounts.validateMaker(target);
        return same(requests.claim(actor, key, hash, type, target, accountVersion, reason), hash);
    }
    public OperationRequest approve(UUID id, long version) {
        var stored = locked(id); var request = stored.request(); var actor = actors.currentActor();
        if (stored.madeBy(actor)) throw error("OPERATION_SELF_APPROVAL_FORBIDDEN", RULE_VIOLATION);
        if ("APPROVED".equals(request.status())) {
            if (!stored.checkedBy(actor)) throw error("OPERATION_ALREADY_DECIDED", CONFLICT);
            // Lost response retry uses the original pending version; no execution is repeated.
            if (version != 0 && version != request.version()) throw error("OPERATION_VERSION_CONFLICT", CONFLICT);
            return request;
        }
        pending(request, version);
        if (!"REVERSAL".equals(request.type())) accounts.validateMaker(request.targetId());
        requests.begin(id, actor);
        Object result = "REVERSAL".equals(request.type())
                ? reversals.create(id.toString(), request.targetId(), request.reason())
                : accounts.execute(request.targetId(), request.expectedAccountVersion(), request.type(), request.reason());
        return requests.finish(id, "APPROVED", actor, "APPROVED", json.writeValueAsString(result));
    }
    public OperationRequest decide(UUID id, long version, boolean cancel, String reason) {
        var stored = locked(id); var actor = actors.currentActor();
        if (cancel != stored.madeBy(actor)) throw error(cancel ? "OPERATION_NOT_MAKER" : "OPERATION_SELF_APPROVAL_FORBIDDEN", RULE_VIOLATION);
        String terminal = cancel ? "CANCELLED" : "REJECTED";
        String expected = cancel ? "MAKER_WITHDREW" : "NOT_JUSTIFIED";
        if (!expected.equals(reason)) throw error("OPERATION_REASON_INVALID", RULE_VIOLATION);
        var request = stored.request();
        if (terminal.equals(request.status()) && (cancel || stored.checkedBy(actor))) {
            if (version != 0 && version != request.version()) throw error("OPERATION_VERSION_CONFLICT", CONFLICT);
            return request;
        }
        pending(request, version);
        return requests.finish(id, terminal, actor, reason, null);
    }
    @Transactional(readOnly = true)
    public OperationRequest get(UUID id) { return requests.find(id, false).orElseThrow(() -> error("OPERATION_NOT_FOUND", NOT_FOUND)).request(); }
    @Transactional(readOnly = true)
    public List<OperationRequest> list(String status, int limit) {
        if (!Set.of("PENDING", "APPROVED", "REJECTED", "CANCELLED").contains(status)) throw error("OPERATION_STATUS_INVALID", RULE_VIOLATION);
        bounds(limit); return requests.list(status, limit);
    }
    @Transactional(readOnly = true)
    public List<OperationRepository.History> history(UUID id, int limit) { get(id); bounds(limit); return requests.history(id, limit); }
    private OperationRepository.Stored locked(UUID id) { return requests.find(id, true).orElseThrow(() -> error("OPERATION_NOT_FOUND", NOT_FOUND)); }
    private static OperationRequest same(OperationRepository.Stored stored, String hash) {
        if (!stored.hash().equals(hash)) throw error("IDEMPOTENCY_CONFLICT", CONFLICT); return stored.request();
    }
    private static void pending(OperationRequest request, long version) {
        if (request.version() != version) throw error("OPERATION_VERSION_CONFLICT", CONFLICT);
        if (!"PENDING".equals(request.status())) throw error("OPERATION_ALREADY_DECIDED", CONFLICT);
    }
    private static void bounds(int limit) { if (limit < 1 || limit > 100) throw error("OPERATION_LIMIT_INVALID", RULE_VIOLATION); }
    private static void validate(String type, UUID target, Long version, String reason) {
        if (target == null || type == null || reason == null) throw error("OPERATION_REQUEST_INVALID", RULE_VIOLATION);
        boolean valid = switch (type) {
            case "FREEZE" -> version != null && version >= 0 && Set.of("CUSTOMER_REQUEST", "SECURITY_REVIEW").contains(reason);
            case "UNFREEZE" -> version != null && version >= 0 && "REVIEW_CLEARED".equals(reason);
            case "REVERSAL" -> version == null && Set.of("OPERATOR_ERROR", "DUPLICATE_PROCESSING").contains(reason);
            default -> false;
        };
        if (!valid) throw error("OPERATION_REQUEST_INVALID", RULE_VIOLATION);
    }
    private static BusinessException error(String code, BusinessException.Kind kind) { return new BusinessException(code, kind); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
