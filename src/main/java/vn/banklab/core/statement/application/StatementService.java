package vn.banklab.core.statement.application;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.banklab.core.account.application.AccountFinancialAccess;
import vn.banklab.core.ledger.application.LedgerHistoryAccess;
import vn.banklab.core.shared.application.BusinessException;

@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class StatementService {
    public record Page(UUID snapshotId,List<StatementRepository.Entry> items,Long nextAfterSequence) { }
    private final StatementRepository repository;
    private final LedgerHistoryAccess ledger;
    private final AccountFinancialAccess accounts;
    private final int maximum;
    public StatementService(StatementRepository repository,LedgerHistoryAccess ledger,AccountFinancialAccess accounts,
                            @Value("${bank.statements.max-entries:10000}") int maximum){
        if(maximum<1 || maximum>100000)throw new IllegalArgumentException("Invalid history snapshot entry cap");
        this.repository=repository;this.ledger=ledger;this.accounts=accounts;this.maximum=maximum;
    }
    @Transactional(isolation=Isolation.REPEATABLE_READ,timeout=30)
    public StatementRepository.Header history(UUID accountId){return create(accountId,null,null,null);}
    @Transactional(isolation=Isolation.REPEATABLE_READ,timeout=30)
    public StatementRepository.Header statement(UUID accountId,LocalDate fromDate,LocalDate toDate,String timezone){
        if(fromDate==null || toDate==null || timezone==null)throw rule("STATEMENT_RANGE_INVALID");
        return create(accountId,fromDate,toDate,timezone);
    }
    private StatementRepository.Header create(UUID accountId,LocalDate fromDate,LocalDate toDate,String timezone){
        var link=ledger.owned(accountId);Instant from=null,to=null;String mode=timezone==null?"HISTORY":"STATEMENT",zone="UTC";
        if(timezone!=null){
            if(!ZoneId.getAvailableZoneIds().contains(timezone))throw rule("STATEMENT_TIMEZONE_INVALID");
            if(fromDate.getYear()<1 || toDate.getYear()>9999 || !toDate.isAfter(fromDate)||ChronoUnit.DAYS.between(fromDate,toDate)>366)throw rule("STATEMENT_RANGE_INVALID");
            try {var tz=ZoneId.of(timezone);from=fromDate.atStartOfDay(tz).toInstant();to=toDate.atStartOfDay(tz).toInstant();}
            catch(DateTimeException failure){throw rule("STATEMENT_RANGE_INVALID");}
            if(!from.isBefore(repository.snapshotTime()))throw rule("STATEMENT_RANGE_INVALID");zone=timezone;
        }
        UUID id=UUID.randomUUID();repository.start(id,accountId,link.ledgerId(),mode,zone,fromDate,toDate,from,to);
        if(repository.copy(id,maximum)>maximum)throw rule("HISTORY_TOO_LARGE");repository.complete(id);
        return repository.find(accountId,id).orElseThrow();
    }
    @Transactional(readOnly=true)
    public StatementRepository.Header get(UUID accountId,UUID id){accounts.owned(accountId);return existing(accountId,id);}
    private StatementRepository.Header existing(UUID accountId,UUID id){return repository.find(accountId,id).orElseThrow(()->new BusinessException("ACCOUNT_SNAPSHOT_NOT_FOUND",BusinessException.Kind.NOT_FOUND));}
    @Transactional(readOnly=true)
    public Page entries(UUID accountId,UUID id,long after,int limit){
        accounts.owned(accountId);existing(accountId,id);
        if(after<0 || limit<1 || limit>200)throw rule("HISTORY_PAGE_INVALID");
        var rows=repository.entries(id,after,limit+1);boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        return new Page(id,items,more?items.getLast().sequence():null);
    }
    @Transactional(readOnly=true,timeout=30)
    public String csv(UUID accountId,UUID id){
        accounts.owned(accountId);existing(accountId,id);
        var body=new StringBuilder("sequence,postedAt,journalId,referenceId,kind,side,amount,delta,runningBalance\r\n");long after=0;
        while(true){var rows=repository.entries(id,after,200);if(rows.isEmpty())break;
            for(var e:rows)body.append(e.sequence()).append(',').append(e.postedAt()).append(',').append(e.journalId()).append(',').append(e.referenceId())
                    .append(',').append(e.kind()).append(',').append(e.side()).append(',').append(e.amount()).append(',').append(e.delta()).append(',').append(e.runningBalance()).append("\r\n");
            after=rows.getLast().sequence();
        }return body.toString();
    }
    private static BusinessException rule(String code){return new BusinessException(code,BusinessException.Kind.RULE_VIOLATION);}
}
