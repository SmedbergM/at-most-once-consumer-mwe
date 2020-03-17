# Bug in at-most-once consumer

MWE demonstrating that a consumer group may consume messages more than once despite using the "at-most-once" consumer constructor.

## Build

```
$ sbt clean consumer/docker producer/docker
```

## Run:

```
$ docker-compose up -d kafka
$ docker exec -it <kakfa container id> bash
# /opt/kafka_2.11-0.10.1.0/bin/kafka-topics.sh --zookeeper kafka:2181 --create --topic mwe_topic --partitions 5 --replication 1
# exit
$ docker-compose run producer
```

You should see a container log ending like
```
2020-03-17 05:09:40.917 [producer_system-akka.actor.default-dispatcher-4] INFO  DummyProducer$ - Completed sending 100 messages to kafka
```

Next, start multiple instances of the consumer process. Each time the newest instance completes processing of its first batch of messages, we increase the replication to force a consumer group rebalance.

```
$ docker-compose up -d --scale consumer=2 consumer
```
Now wait (in a different terminal) for these two consumers to complete processing of at least once message:
```
$ docker-compose logs consumer | grep Sinking
```

```
$ docker-compose up -d --scale consumer=3 consumer
```

Keep stepping up until the number of consumers is at least 6 (ie greater than the number of partitions).