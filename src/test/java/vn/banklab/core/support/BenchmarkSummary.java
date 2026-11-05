package vn.banklab.core.support;

import java.util.Arrays;

/** Exact empirical nearest-rank client percentiles; no extrapolation or timing pass threshold. */
public final class BenchmarkSummary {
    private BenchmarkSummary() { }
    public record Distribution(int requests, double elapsedSeconds, double requestsPerSecond,
                               double p50Millis, double p95Millis, double p99Millis, double maxMillis) { }
    public static Distribution summarize(long[] nanos, long elapsedNanos) {
        if(nanos.length==0 || elapsedNanos<=0 || Arrays.stream(nanos).anyMatch(n->n<0))throw new IllegalArgumentException("Invalid benchmark samples");
        long[] sorted=nanos.clone();Arrays.sort(sorted);
        return new Distribution(sorted.length,elapsedNanos/1e9,sorted.length/(elapsedNanos/1e9),
                percentile(sorted,0.5)/1e6,percentile(sorted,0.95)/1e6,percentile(sorted,0.99)/1e6,sorted[sorted.length-1]/1e6);
    }
    private static long percentile(long[] sorted,double quantile){return sorted[(int)Math.ceil(sorted.length*quantile)-1];}
}
