package vn.banklab.core.external.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.dao.DataAccessException;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.risk.application.TransferRiskGate;
import vn.banklab.core.shared.application.BusinessException;

@Service
@PreAuthorize("hasRole('CUSTOMER')")
@Transactional(propagation=Propagation.NEVER)
public class ExternalPaymentService {
    private final ExternalPaymentStates states;
    private final PartnerGateway partner;
    private final ExternalOptions options;
    private final AccountFinancialAccess accounts;
    private final CurrentActorProvider actors;
    private final TransferRiskGate risk;
    public ExternalPaymentService(ExternalPaymentStates states,PartnerGateway partner,ExternalOptions options,AccountFinancialAccess accounts,CurrentActorProvider actors,TransferRiskGate risk){
        this.states=states;this.partner=partner;this.options=options;this.accounts=accounts;this.actors=actors;this.risk=risk;
    }
    public ExternalPayment create(String key,UUID account,String beneficiary,String amount,String currency){
        if(key==null||!key.matches("[A-Za-z0-9._:-]{1,128}"))throw rule("IDEMPOTENCY_KEY_INVALID");
        if(account==null||beneficiary==null||!beneficiary.matches("SIM_[A-Z0-9_-]{1,40}")||amount==null||!amount.matches("[1-9][0-9]{0,19}")||!"VND".equals(currency))throw rule("EXTERNAL_REQUEST_INVALID");
        String hash=hash("external:v1\n"+account+"\n"+beneficiary+"\n"+amount+"\nVND");
        var replay=states.replay(key,hash);if(replay.isPresent())return replay.get();
        accounts.owned(account);enabled();if(account.equals(options.settlementAccount()))throw rule("EXTERNAL_ACCOUNTS_INVALID");
        UUID instance=partner.instance();var actor=actors.currentActor();
        var approval=risk.assess(hash("external-risk:v1\n"+actor.issuer()+"\n"+actor.subject()+"\n"+key+"\n"+hash),amount);
        return states.prepare(key,hash,account,options.settlementAccount(),beneficiary,amount,instance,approval);
    }
    public ExternalPayment dispatch(UUID id){
        var existing=states.get(id);if(existing.terminal())return existing;enabled();
        var claim=states.beginDispatch(id);if(claim.payment().terminal())return claim.payment();
        return apply(id,claim.submit()?partner.submit(claim.payment()):partner.query(claim.payment()));
    }
    public ExternalPayment reconcile(UUID id){
        var p=states.get(id);if(!p.state().equals("UNKNOWN"))return p;enabled();return apply(id,partner.query(p));
    }
    private ExternalPayment apply(UUID id,PartnerGateway.Result result){
        try{return states.apply(id,result);}catch(BusinessException|DataAccessException blocked){return states.defer(id,result);}
    }
    private void enabled(){if(!options.enabled())throw new BusinessException("EXTERNAL_DISABLED",BusinessException.Kind.DEPENDENCY_UNAVAILABLE);}
    private static BusinessException rule(String code){return new BusinessException(code,BusinessException.Kind.RULE_VIOLATION);}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
