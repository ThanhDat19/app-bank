package vn.banklab.core.transfer.application;

import java.time.Instant;
import java.util.UUID;

public record TransferRiskDecision(UUID transferId,String outcome,String policyVersion,String reason,
                                   Instant assessedAt,Instant expiresAt) { }
