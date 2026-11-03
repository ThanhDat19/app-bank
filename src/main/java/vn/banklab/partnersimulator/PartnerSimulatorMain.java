package vn.banklab.partnersimulator;

import java.util.concurrent.CountDownLatch;

public final class PartnerSimulatorMain {
    private PartnerSimulatorMain(){ }
    public static void main(String[] args)throws Exception {
        int port=Integer.parseInt(System.getenv().getOrDefault("PARTNER_SIMULATOR_PORT","9092"));
        if(port<1||port>65535)throw new IllegalArgumentException("Invalid partner port");
        String controls=System.getenv().getOrDefault("PARTNER_SIMULATOR_TEST_CONTROLS","false");
        if(!controls.equals("true")&&!controls.equals("false"))throw new IllegalArgumentException("Invalid test controls flag");
        var simulator=new PartnerSimulator(port,Boolean.parseBoolean(controls),10000,250);
        Runtime.getRuntime().addShutdownHook(new Thread(simulator::close,"partner-shutdown"));
        try{simulator.start();System.out.println("Partner simulator listening on 127.0.0.1:"+port);new CountDownLatch(1).await();}
        finally{simulator.close();}
    }
}
