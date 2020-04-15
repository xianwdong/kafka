package com.test;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class TestProducer {

    public static void main(String[] args) throws ExecutionException, InterruptedException {
        String brokerList = "localhost:9092";
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokerList);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, "1");
        properties.put(ProducerConfig.ACKS_CONFIG, "-1");
        KafkaProducer<String, String> producer = new KafkaProducer<>(properties, new StringSerializer(), new StringSerializer());

        ProducerRecord<String, String> data = new ProducerRecord<>("test", "hello world");
        Future<RecordMetadata> future = producer.send(data);
        RecordMetadata recordMetadata = future.get();
        System.out.println(recordMetadata);
    }

}
