package vn.banklab.risksimulator;

import java.net.InetSocketAddress;
import java.util.concurrent.*;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

/** Separate executable, deliberately loopback-only. Shares a build, never starts Spring/DB/IAM. */
public final class RiskSimulatorMain {
    private RiskSimulatorMain() { }
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("RISK_SIMULATOR_PORT", "9091"));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid risk port");
        String cap = System.getenv().getOrDefault("RISK_SIMULATOR_MAX_AMOUNT", "100000000");
        var workers = new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(64));
        Server server = NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",port))
                .executor(workers).maxInboundMessageSize(4096).maxConcurrentCallsPerConnection(16)
                .addService(new AmountRiskService(cap)).build();
        try {
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {server.shutdownNow();workers.shutdownNow();},"risk-shutdown"));
            System.out.println("Risk simulator listening on 127.0.0.1:" + port);
            server.awaitTermination();
        } finally { server.shutdownNow(); workers.shutdownNow(); }
    }
}
