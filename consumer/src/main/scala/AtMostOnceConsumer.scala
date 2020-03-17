import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Random, Try}

import akka.actor.{Actor, ActorSystem, Props}
import akka.kafka.scaladsl.Consumer
import akka.kafka.scaladsl.Consumer.DrainingControl
import akka.kafka.{ConsumerSettings, Subscription, Subscriptions}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.common.serialization.{Deserializer, IntegerDeserializer, StringDeserializer}
import smedbergm.mwe.Common

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

  val subscription: Subscription = Subscriptions.topics(Common.topicName)

  val actorSystem = ActorSystem("consumer_system")
  import actorSystem.dispatcher
  implicit val materializer = ActorMaterializer()(actorSystem)
  val props = Props(new ConsumerActor)
  val consumerActor = actorSystem.actorOf(props)

  val (taskLengthMin: Int, taskLengthDelta: Int) = {
    val taskLengthConfig = applicationConfig.getConfig("mwe-consumer.task-length-seconds")
    (taskLengthConfig.getInt("min"), taskLengthConfig.getInt("delta"))
  }

  val kafkaConsumer = Consumer.atMostOnceSource(kafkaConsumerSettings, subscription)
    .mapAsyncUnordered(4){ record =>
      logger.info(s"Read record ${record.key()} -> ${record.value()}")
      val message = new MessageFromConsumer(record.key(), record.value())
      val delay: FiniteDuration = (taskLengthMin + Random.nextInt(taskLengthDelta)).seconds
      actorSystem.scheduler.scheduleOnce(delay, consumerActor, message)
      message.consumed
    }.toMat(Sink.foreach { v =>
      logger.info(s"Sinking value ${v}")
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
