package vn.banklab.core.external.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.ledger.application.LedgerExternalAccess;
import vn.banklab.core.ledger.application.LedgerTransferAccess;
import vn.banklab.core.risk.application.TransferRiskGate;
import vn.banklab.core.shared.application.BusinessException;

/** All state/reservation/money writes are short local transactions. No network capability in this bean. */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
@Transactional(isolation=Isolation.READ_COMMITTED)
public class ExternalPaymentStates {
    public record Dispatch(ExternalPayment payment,boolean submit) { }
    private final ExternalPaymentRepository repository;
    private final CurrentActorProvider actors;
    private final LedgerExternalAccess reservations;
    private final LedgerTransferAccess ledger;
    public ExternalPaymentStates(ExternalPaymentRepository repository,CurrentActorProvider actors,LedgerExternalAccess reservations,LedgerTransferAccess ledger){
        this.repository=repository;this.actors=actors;this.reservations=reservations;this.ledger=ledger;
    }
    @Transactional(readOnly=true)
    public Optional<ExternalPayment> replay(String key,String hash){
        return repository.findKey(actors.currentActor(),key).map(p->{matching(p,hash);return p;});
    }
    @Transactional(readOnly=true)
    public ExternalPayment get(UUID id){return owned(id,false);}
    public ExternalPayment prepare(String key,String hash,UUID account,UUID settlement,String beneficiary,String amount,UUID instance,TransferRiskGate.Approval approval){
        var p=repository.claim(actors.currentActor(),key,hash,account,settlement,beneficiary,amount,instance,approval);matching(p,hash);
        if(!p.state().equals("CREATING"))return p;
        fresh(approval);var gross=reservations.prepare(account,settlement,new BigDecimal(amount));fresh(approval);
        return repository.update(p.id(),"PREPARED",gross,0,null,null,null);
    }
    public Dispatch beginDispatch(UUID id){
        var p=owned(id,true);
        if(!p.state().equals("PREPARED"))return new Dispatch(p,false);
        reservations.dispatch(p.accountId(),p.settlementAccountId(),new BigDecimal(p.amount()),new BigDecimal(p.reservedAmount()));
        return new Dispatch(repository.update(id,"UNKNOWN",null,0,null,null,null),true);
    }
    public ExternalPayment cancel(UUID id){
        var p=owned(id,true);if(p.state().equals("CANCELLED"))return p;
        if(!p.state().equals("PREPARED"))throw conflict("EXTERNAL_STATE_INVALID");
        return repository.update(id,"CANCELLED",null,0,null,null,null);
    }
    public ExternalPayment apply(UUID id,PartnerGateway.Result result){return observe(id,result,true,null);}
    public ExternalPayment defer(UUID id,PartnerGateway.Result result){return observe(id,result,false,"EXTERNAL_SETTLEMENT_BLOCKED");}
    private ExternalPayment observe(UUID id,PartnerGateway.Result result,boolean settle,String blocked){
        var p=owned(id,true);if(p.terminal())return p;
        if(!p.state().equals("UNKNOWN"))throw conflict("EXTERNAL_STATE_INVALID");
        var o=result.observation();String problem=result.problem();
        if(o!=null){
            problem=valid(p,o);
            if(problem==null&&o.version()<p.partnerVersion())return p;
            if(problem==null&&o.version()==p.partnerVersion()&&!Objects.equals(o.state(),p.partnerState()))problem="EXTERNAL_PARTNER_CONFLICT";
        }
        if(problem!=null||o==null){
            if(problem==null)problem="EXTERNAL_PARTNER_INVALID_RESPONSE";
            if(Objects.equals(problem,p.problem()))return p;
            return repository.update(id,"UNKNOWN",null,p.partnerVersion(),p.partnerState(),problem,null);
        }
        if(!settle)return repository.update(id,"UNKNOWN",null,o.version(),o.state(),blocked,null);
        if(o.state().equals("FAILED"))return repository.update(id,"FAILED",null,o.version(),o.state(),null,null);
        if(o.state().equals("PENDING")){
            if(p.partnerVersion()==o.version()&&p.problem()==null)return p;
            return repository.update(id,"UNKNOWN",null,o.version(),o.state(),null,null);
        }
        repository.update(id,"SETTLING",null,o.version(),o.state(),null,null);
        UUID journal=ledger.post(p.accountId(),p.settlementAccountId(),id,new BigDecimal(p.amount()));
        return repository.update(id,"SUCCEEDED",null,o.version(),o.state(),null,journal);
    }
    private String valid(ExternalPayment p,PartnerGateway.Observation o){
        if(!p.partnerInstance().equals(o.instanceId()))return "EXTERNAL_PARTNER_INSTANCE_CHANGED";
        if(!p.id().equals(o.paymentId())||!p.requestHash().equals(o.requestHash())||!p.amount().equals(o.amount())
                ||!"VND".equals(o.currency())||!p.beneficiary().equals(o.beneficiary()))return "EXTERNAL_PARTNER_INVALID_RESPONSE";
        if(!((o.version()==1&&"PENDING".equals(o.state()))||(o.version()==2&&Set.of("SUCCEEDED","FAILED").contains(o.state()))))return "EXTERNAL_PARTNER_INVALID_RESPONSE";
        return null;
    }
    @Transactional(readOnly=true)
    public List<ExternalPaymentRepository.History> history(UUID id,int limit){
        owned(id,false);if(limit<1||limit>100)throw new BusinessException("EXTERNAL_HISTORY_LIMIT_INVALID",BusinessException.Kind.RULE_VIOLATION);
        return repository.history(id,limit);
    }
    private ExternalPayment owned(UUID id,boolean lock){return repository.owned(actors.currentActor(),id,lock).orElseThrow(()->new BusinessException("EXTERNAL_PAYMENT_NOT_FOUND",BusinessException.Kind.NOT_FOUND));}
    private static void matching(ExternalPayment p,String hash){if(!p.requestHash().equals(hash))throw conflict("IDEMPOTENCY_CONFLICT");}
    private static void fresh(TransferRiskGate.Approval a){if(a.expiresAt()!=null&&!Instant.now().isBefore(a.expiresAt()))throw conflict("RISK_APPROVAL_EXPIRED");}
    private static BusinessException conflict(String code){return new BusinessException(code,BusinessException.Kind.CONFLICT);}
}
