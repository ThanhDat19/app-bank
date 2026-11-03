package vn.banklab.core.notification.application;

@FunctionalInterface
public interface DeadLetterPublisher {
    record DeadLetter(String sourceTopic,int sourcePartition,long sourceOffset,String payloadHash,String errorCode) { }
    void publish(DeadLetter letter) throws Exception;
}
