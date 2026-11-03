package vn.banklab.core.notification.application;

public record SourceRecord(String topic,int partition,long offset,String key,String value) {
    public String identity() { return topic+":"+partition+":"+offset; }
}
