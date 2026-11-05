package vn.banklab.core.observability;

import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import vn.banklab.core.support.BenchmarkSummary;
import static org.assertj.core.api.Assertions.*;

class BenchmarkSummaryTest {
    @Test void reportsNearestRankTailAndThroughputWithoutMutatingSamples(){
        long[] samples=LongStream.rangeClosed(1,100).map(n->(101-n)*1_000_000).toArray();
        var report=BenchmarkSummary.summarize(samples,10_000_000_000L);
        assertThat(report.p50Millis()).isEqualTo(50);assertThat(report.p95Millis()).isEqualTo(95);assertThat(report.p99Millis()).isEqualTo(99);
        assertThat(report.maxMillis()).isEqualTo(100);assertThat(report.requestsPerSecond()).isEqualTo(10);assertThat(samples[0]).isEqualTo(100_000_000);
    }
    @Test void oneRequestDoesNotInventTailResolution(){
        var report=BenchmarkSummary.summarize(new long[]{3_000_000},10_000_000);
        assertThat(report.p50Millis()).isEqualTo(3);assertThat(report.p99Millis()).isEqualTo(3);
        assertThatThrownBy(()->BenchmarkSummary.summarize(new long[0],1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->BenchmarkSummary.summarize(new long[]{-1},1)).isInstanceOf(IllegalArgumentException.class);
    }
}
