package vn.banklab.core.messaging.infrastructure;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.outbox.application.OutboxEvent;
import vn.banklab.core.outbox.application.OutboxPublisher;
import vn.banklab.core.notification.application.DeadLetterPublisher;

/** Broker acknowledgement is required before either outbox ack or source-offset commit. */
public class KafkaTransport implements OutboxPublisher,DeadLetterPublisher,AutoCloseable {
    private final KafkaProducer<String,String> producer;
    private final String eventsTopic,dlqTopic;
    private final JsonMapper json=JsonMapper.builder().build();
    public KafkaTransport(String bootstrap,String eventsTopic,String dlqTopic) {
        if(bootstrap.isBlank() || eventsTopic.equals(dlqTopic)) throw new IllegalArgumentException("Invalid Kafka configuration");
        this.eventsTopic=eventsTopic;this.dlqTopic=dlqTopic;
        this.producer=new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,
                ProducerConfig.ACKS_CONFIG,"all",ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,true,
                ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION,1,
                ProducerConfig.MAX_BLOCK_MS_CONFIG,3000,ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,3000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,5000),new StringSerializer(),new StringSerializer());
    }
    @Override public void publish(OutboxEvent e) throws Exception { send(eventsTopic,e.aggregateId().toString(),json.writeValueAsString(e)); }
    @Override public void publish(DeadLetterPublisher.DeadLetter e) throws Exception {
        send(dlqTopic,e.sourceTopic()+":"+e.sourcePartition()+":"+e.sourceOffset(),json.writeValueAsString(e));
    }
    private void send(String topic,String key,String value) throws Exception {
        producer.send(new ProducerRecord<>(topic,key,value)).get(6,TimeUnit.SECONDS);
    }
    @Override public void close() { producer.close(Duration.ofSeconds(2)); }
}
