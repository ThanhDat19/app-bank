package vn.banklab.core.messaging.infrastructure;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import vn.banklab.core.notification.application.*;

/** One logical consumer; synchronized ownership across scheduled ticks and close. */
public class KafkaNotificationWorker implements AutoCloseable {
    private final KafkaConsumer<String,String> consumer;
    private final NotificationConsumer processor;
    private final DeadLetterPublisher dlq;
    public KafkaNotificationWorker(String bootstrap,String group,String topic,NotificationConsumer processor,DeadLetterPublisher dlq) {
        this.processor=processor;this.dlq=dlq;
        consumer=new KafkaConsumer<>(Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,ConsumerConfig.GROUP_ID_CONFIG,group,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,false,ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG,1,ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,5000,
                ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG,524288),new StringDeserializer(),new StringDeserializer());
        consumer.subscribe(List.of(topic));
    }
    public synchronized int pollOnce() throws Exception {
        consumer.resume(consumer.paused());
        int processed=0;
        for(var record:consumer.poll(Duration.ofMillis(200))) {
            var partition=new TopicPartition(record.topic(),record.partition());
            try {
                if(processor.process(new SourceRecord(record.topic(),record.partition(),record.offset(),record.key(),record.value()),dlq)) {
                    consumer.commitSync(Map.of(partition,new OffsetAndMetadata(record.offset()+1)));processed++;
                } else { consumer.seek(partition,record.offset());consumer.pause(List.of(partition)); }
            } catch(Exception failure) {
                // DB/DLT/commit failure: retain this source offset, never skip the record.
                if(consumer.assignment().contains(partition)) { consumer.seek(partition,record.offset());consumer.pause(List.of(partition)); }
                throw failure;
            }
        }
        return processed;
    }
    @Override public synchronized void close() { consumer.close(Duration.ofSeconds(2)); }
}
