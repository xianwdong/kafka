/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.tests;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KGroupedStream;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.Serialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;

import java.time.Duration;
import java.util.Properties;

public class SmokeTestClient extends SmokeTestUtil {

    private final Properties streamsProperties;

    private Thread thread;
    private KafkaStreams streams;
    private boolean uncaughtException = false;

    public SmokeTestClient(final Properties streamsProperties) {
        super();
        this.streamsProperties = streamsProperties;
    }

    public void start() {
        streams = createKafkaStreams(streamsProperties);
        streams.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(final Thread t, final Throwable e) {
                System.out.println("SMOKE-TEST-CLIENT-EXCEPTION");
                uncaughtException = true;
                e.printStackTrace();
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                close();
            }
        }));

        thread = new Thread() {
            public void run() {
                streams.start();
            }
        };
        thread.start();
    }

    public void close() {
        streams.close(Duration.ofSeconds(5));
        // do not remove these printouts since they are needed for health scripts
        if (!uncaughtException) {
            System.out.println("SMOKE-TEST-CLIENT-CLOSED");
        }
        try {
            thread.join();
        } catch (final Exception ex) {
            // do not remove these printouts since they are needed for health scripts
            System.out.println("SMOKE-TEST-CLIENT-EXCEPTION");
            // ignore
        }
    }

    private static Properties getStreamsConfig(final Properties props) {
        final Properties fullProps = new Properties(props);
        fullProps.put(StreamsConfig.APPLICATION_ID_CONFIG, "SmokeTest");
        fullProps.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 3);
        fullProps.put(StreamsConfig.NUM_STANDBY_REPLICAS_CONFIG, 2);
        fullProps.put(StreamsConfig.BUFFERED_RECORDS_PER_PARTITION_CONFIG, 100);
        fullProps.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        fullProps.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 3);
        fullProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        fullProps.put(ProducerConfig.ACKS_CONFIG, "all");

        fullProps.putAll(props);
        return fullProps;
    }

    private static KafkaStreams createKafkaStreams(final Properties props) {
        final StreamsBuilder builder = new StreamsBuilder();
        final Consumed<String, Integer> stringIntConsumed = Consumed.with(stringSerde, intSerde);
        final KStream<String, Integer> source = builder.stream("data", stringIntConsumed);
        source.filterNot((k, v) -> k.equals("flush"))
              .to("echo", Produced.with(stringSerde, intSerde));
        final KStream<String, Integer> data = source.filter((key, value) -> value == null || value != END);
        data.process(SmokeTestUtil.printProcessorSupplier("data"));

        // min
        final KGroupedStream<String, Integer> groupedData =
            data.groupByKey(Serialized.with(stringSerde, intSerde));

        final KTable<Windowed<String>, Integer> minAggregation = groupedData
            .windowedBy(TimeWindows.of(Duration.ofDays(1)).grace(Duration.ofMinutes(1)))
            .aggregate(
                () -> Integer.MAX_VALUE,
                (aggKey, value, aggregate) -> (value < aggregate) ? value : aggregate,
                Materialized
                    .<String, Integer, WindowStore<Bytes, byte[]>>as("uwin-min")
                    .withValueSerde(intSerde)
                    .withRetention(Duration.ofHours(25))
            );

        minAggregation
            .toStream()
            .filterNot((k, v) -> k.key().equals("flush"))
            .map((key, value) -> new KeyValue<>(key.toString(), value))
            .to("min-raw", Produced.with(stringSerde, intSerde));

        minAggregation
            .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
            .toStream()
            .filterNot((k, v) -> k.key().equals("flush"))
            .map((key, value) -> new KeyValue<>(key.toString(), value))
            .to("min-suppressed", Produced.with(stringSerde, intSerde));

        minAggregation
            .toStream(new Unwindow<>())
            .filterNot((k, v) -> k.equals("flush"))
            .to("min", Produced.with(stringSerde, intSerde));

        final KTable<String, Integer> minTable = builder.table(
            "min",
            Consumed.with(stringSerde, intSerde),
            Materialized.as("minStoreName"));
        minTable.toStream().process(SmokeTestUtil.printProcessorSupplier("min"));

        // max
        groupedData
            .windowedBy(TimeWindows.of(Duration.ofDays(2)))
            .aggregate(
                () -> Integer.MIN_VALUE,
                (aggKey, value, aggregate) -> (value > aggregate) ? value : aggregate,
                Materialized.<String, Integer, WindowStore<Bytes, byte[]>>as("uwin-max").withValueSerde(intSerde))
            .toStream(new Unwindow<>())
            .filterNot((k, v) -> k.equals("flush"))
            .to("max", Produced.with(stringSerde, intSerde));

        final KTable<String, Integer> maxTable = builder.table(
            "max",
            Consumed.with(stringSerde, intSerde),
            Materialized.as("maxStoreName"));
        maxTable.toStream().process(SmokeTestUtil.printProcessorSupplier("max"));

        // sum
        groupedData
            .windowedBy(TimeWindows.of(Duration.ofDays(2)))
            .aggregate(
                () -> 0L,
                (aggKey, value, aggregate) -> (long) value + aggregate,
                Materialized.<String, Long, WindowStore<Bytes, byte[]>>as("win-sum").withValueSerde(longSerde))
            .toStream(new Unwindow<>())
            .filterNot((k, v) -> k.equals("flush"))
            .to("sum", Produced.with(stringSerde, longSerde));

        final Consumed<String, Long> stringLongConsumed = Consumed.with(stringSerde, longSerde);
        final KTable<String, Long> sumTable = builder.table("sum", stringLongConsumed);
        sumTable.toStream().process(SmokeTestUtil.printProcessorSupplier("sum"));

        // cnt
        groupedData
            .windowedBy(TimeWindows.of(Duration.ofDays(2)))
            .count(Materialized.as("uwin-cnt"))
            .toStream(new Unwindow<>())
            .filterNot((k, v) -> k.equals("flush"))
            .to("cnt", Produced.with(stringSerde, longSerde));

        final KTable<String, Long> cntTable = builder.table(
            "cnt",
            Consumed.with(stringSerde, longSerde),
            Materialized.as("cntStoreName"));
        cntTable.toStream().process(SmokeTestUtil.printProcessorSupplier("cnt"));

        // dif
        maxTable
            .join(
                minTable,
                (value1, value2) -> value1 - value2)
            .toStream()
            .filterNot((k, v) -> k.equals("flush"))
            .to("dif", Produced.with(stringSerde, intSerde));

        // avg
        sumTable
            .join(
                cntTable,
                (value1, value2) -> (double) value1 / (double) value2)
            .toStream()
            .filterNot((k, v) -> k.equals("flush"))
            .to("avg", Produced.with(stringSerde, doubleSerde));

        // test repartition
        final Agg agg = new Agg();
        cntTable.groupBy(agg.selector(), Serialized.with(stringSerde, longSerde))
            .aggregate(agg.init(), agg.adder(), agg.remover(),
                Materialized.<String, Long>as(Stores.inMemoryKeyValueStore("cntByCnt"))
                    .withKeySerde(Serdes.String())
                    .withValueSerde(Serdes.Long()))
            .toStream()
            .to("tagg", Produced.with(stringSerde, longSerde));

        final KafkaStreams streamsClient = new KafkaStreams(builder.build(), getStreamsConfig(props));
        streamsClient.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(final Thread t, final Throwable e) {
                System.out.println("FATAL: An unexpected exception is encountered on thread " + t + ": " + e);
                streamsClient.close(Duration.ofSeconds(30));
            }
        });

        return streamsClient;
    }
}
