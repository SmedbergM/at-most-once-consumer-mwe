package smedbergm.mwe

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Random, Try}

import akka.NotUsed
import akka.actor.{Actor, ActorSystem, Props}
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.scaladsl.{Committer, Consumer}
import akka.kafka._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.common.serialization.{Deserializer, IntegerDeserializer, StringDeserializer}

object AtMostOnceConsumer extends App with LazyLogging {
  val applicationConfig: Config = ConfigFactory.load()
  object IntDeserializer extends Deserializer[Int] {
    private val jDeserializer = new IntegerDeserializer
    override def deserialize(topic: String, data: Array[Byte]): Int = {
      val jInt = jDeserializer.deserialize(topic, data)
      jInt.intValue()
    }
  }
  val kafkaConsumerSettings: ConsumerSettings[Int, String] = ConsumerSettings(
    applicationConfig.getConfig("akka.kafka.consumer"),
    IntDeserializer,
    new StringDeserializer
  )
  .withGroupId(Common.groupName)
  .withPollInterval(3.seconds)

  val subscription: AutoSubscription = Subscriptions.topics(Common.topicName)

  val actorSystem = ActorSystem("consumer_system")
  import actorSystem.dispatcher
  implicit val materializer = ActorMaterializer()(actorSystem)
  val props = Props(new ConsumerActor)
  val consumerActor = actorSystem.actorOf(props)

  val (taskLengthMin: Int, taskLengthDelta: Int) = {
    val taskLengthConfig = applicationConfig.getConfig("mwe-consumer.task-length-seconds")
    (taskLengthConfig.getInt("min"), taskLengthConfig.getInt("delta"))
  }

  val committerSettings: CommitterSettings = {
    CommitterSettings(actorSystem)
      .withMaxBatch(1)
      .withMaxInterval(150 milliseconds)
      .withParallelism(4)
      .withDelivery(CommitDelivery.waitForAck)
  }
  val committerFlow: Flow[ConsumerMessage.CommittableMessage[Int, String], (Int, String), NotUsed] = {
    Flow[ConsumerMessage.CommittableMessage[Int, String]].flatMapConcat { msg =>
      logger.info(s"Pre-commit: ${msg.record.key()} -> ${msg.record.value()}")
      Source.single(msg.committableOffset)
        .via(Committer.batchFlow(committerSettings))
        .map(_ => msg.record.key() -> msg.record.value())
    }
  }

  Consumer.committablePartitionedSource(kafkaConsumerSettings, subscription).flatMapMerge(breadth = 5, { case (_, src) =>
    src.via(committerFlow)
  }).mapAsyncUnordered(4) { case (key, value) =>
    logger.info(s"Processing committed record ${key} -> ${value}")
    val mfc = new MessageFromConsumer(key, value)
    val delay: FiniteDuration = (taskLengthMin + Random.nextInt(taskLengthDelta)).seconds
    actorSystem.scheduler.scheduleOnce(delay, consumerActor, mfc)
    mfc.consumed
  }.toMat(Sink.foreach { v =>
    logger.info(s"Sinking value: ${v}")
  })(Keep.both)
    .mapMaterializedValue(DrainingControl.apply)
    .run()
}

class MessageFromConsumer(val key: Int, val message: String) {
  private val p: Promise[String] = Promise()
  def complete(e: () => Unit): Unit = {
    p.tryComplete(Try{
      e()
      message
    })
  }
  def consumed: Future[String] = p.future
}

class ConsumerActor extends Actor with LazyLogging {
  override val receive: Receive = {
    case msg: MessageFromConsumer =>
      msg.complete{ () =>
        logger.info(s"Completing message ${msg.key} -> ${msg.message}")
      }
    case msg =>
      logger.info(s"Unexpected message type ${msg.getClass.getSimpleName}")
  }
}
