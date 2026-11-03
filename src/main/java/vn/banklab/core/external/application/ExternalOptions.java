package vn.banklab.core.external.application;
import java.util.UUID;
public record ExternalOptions(boolean enabled,int port,int timeoutMillis,UUID settlementAccount) { }
