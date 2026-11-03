package vn.banklab.core.statement.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StatementRepository {
    record Header(UUID id,UUID accountId,String mode,String currency,String timezone,LocalDate fromDate,LocalDate toDate,
                  Instant fromAt,Instant requestedToAt,Instant effectiveToAt,Instant snapshotAt,String status,
                  String openingBalance,String creditTotal,String debitTotal,String closingBalance,long entryCount,Instant completedAt,boolean interim) { }
    record Entry(long sequence,UUID postingId,UUID journalId,UUID referenceId,String kind,Instant postedAt,String side,String amount,String delta,String runningBalance) { }
    Instant snapshotTime();
    void start(UUID id,UUID accountId,UUID ledgerId,String mode,String zone,LocalDate fromDate,LocalDate toDate,Instant from,Instant to);
    long copy(UUID id,int maximum);
    void complete(UUID id);
    Optional<Header> find(UUID accountId,UUID id);
    List<Entry> entries(UUID id,long after,int limit);
}
