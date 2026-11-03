package vn.banklab.risksimulator;

import java.net.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.Test;
import vn.banklab.risk.v1.*;
import static org.assertj.core.api.Assertions.*;

class RiskSimulatorIT {
    @Test void realExecutableRunsWithoutDatabaseOrIamAndUsesGeneratedProtocol()throws Exception{
        int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        Path artifact=Path.of(System.getProperty("bank.risk-jar"));
        try(var jar=new JarFile(artifact.toFile())){
            assertThat(jar.getManifest().getMainAttributes().getValue("Start-Class")).isEqualTo("vn.banklab.risksimulator.RiskSimulatorMain");
            assertThat(jar.getJarEntry("BOOT-INF/classes/risk/v1/risk.proto")).isNotNull();
            assertThat(jar.getJarEntry("BOOT-INF/classes/vn/banklab/risk/v1/RiskEvaluatorGrpc.class")).isNotNull();
        }
        boolean windows=System.getProperty("os.name").startsWith("Windows");
        Path java=Path.of(System.getProperty("java.home"),"bin",windows?"java.exe":"java");
        var builder=new ProcessBuilder(java.toString(),"-jar",artifact.toAbsolutePath().toString());
        builder.environment().put("RISK_SIMULATOR_PORT",String.valueOf(port));builder.environment().put("RISK_SIMULATOR_MAX_AMOUNT","99999999999999999999");
        for(String name:new String[]{"DB_URL","DB_USERNAME","DB_PASSWORD","OIDC_ISSUER_URI","OIDC_JWK_SET_URI","OIDC_AUDIENCE"})builder.environment().remove(name);
        builder.redirectErrorStream(true).redirectOutput(Path.of("target","risk-simulator-process.log").toFile());
        var process=builder.start();var channel=NettyChannelBuilder.forAddress("127.0.0.1",port).usePlaintext().build();
        try{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);AssessTransferResponse result=null;
            while(System.nanoTime()<until){
                assertThat(process.isAlive()).as("Standalone simulator startup; see target/risk-simulator-process.log").isTrue();
                try{result=RiskEvaluatorGrpc.newBlockingStub(channel).withDeadlineAfter(300,TimeUnit.MILLISECONDS).assessTransfer(request("99999999999999999999"));break;}
                catch(StatusRuntimeException starting){Thread.sleep(100);channel.resetConnectBackoff();}
            }
            assertThat(result).isNotNull();assertThat(result.getOutcome()).isEqualTo(Outcome.APPROVE);
            assertThat(result.getPolicyVersion()).isEqualTo("amount-cap-v1-99999999999999999999");
            assertThatThrownBy(()->RiskEvaluatorGrpc.newBlockingStub(channel).withDeadlineAfter(2,TimeUnit.SECONDS).assessTransfer(request("1.5")))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        }finally{channel.shutdownNow().awaitTermination(3,TimeUnit.SECONDS);process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
    @Test void deterministicCapRejectsAboveThresholdOverRealTcp()throws Exception{
        var server=io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1",0)).addService(new AmountRiskService("100")).build().start();
        var channel=NettyChannelBuilder.forAddress("127.0.0.1",server.getPort()).usePlaintext().build();
        try{
            var stub=RiskEvaluatorGrpc.newBlockingStub(channel).withDeadlineAfter(3,TimeUnit.SECONDS);
            assertThat(stub.assessTransfer(request("100")).getOutcome()).isEqualTo(Outcome.APPROVE);
            var rejected=stub.assessTransfer(request("101"));assertThat(rejected.getOutcome()).isEqualTo(Outcome.REJECT);assertThat(rejected.getReason()).isEqualTo(Reason.AMOUNT_LIMIT_EXCEEDED);
            for(String amount:new String[]{"0","01","-1","100000000000000000000"})assertThatThrownBy(()->stub.assessTransfer(request(amount))).isInstanceOfSatisfying(StatusRuntimeException.class,e->assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        }finally{channel.shutdownNow();server.shutdownNow();}
    }
    private static AssessTransferRequest request(String amount){return AssessTransferRequest.newBuilder().setRequestId("a".repeat(64)).setAmount(amount).setCurrency("VND").build();}
}
