package vn.banklab.core.observability.application;

import java.util.Map;

public interface SnapshotQuery {
    Map<String, Long> read();
}
