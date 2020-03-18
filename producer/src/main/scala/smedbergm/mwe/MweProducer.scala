package smedbergm.mwe

import scala.concurrent.Future
import scala.util.{Failure, Success}

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.ProducerSettings
import akka.kafka.scaladsl.Producer
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{IntegerSerializer, Serializer, StringSerializer}

object MweProducer extends App with LazyLogging {
  val applicationConfig: Config = ConfigFactory.load()

  object IntSerializer extends Serializer[Int] {
    private val jSerializer = new IntegerSerializer
    override def serialize(topic: String, data: Int): Array[Byte] = {
      jSerializer.serialize(topic, new Integer(data))
    }
  }
  val kafkaProducerSettings: ProducerSettings[Int, String] = ProducerSettings(
    applicationConfig.getConfig("akka.kafka.producer"),
    IntSerializer,
    new StringSerializer
  )

  val actorSystem = ActorSystem("producer_system")
  import actorSystem.dispatcher
  implicit val materializer: Materializer = ActorMaterializer()(actorSystem)

  val nRecords = applicationConfig.getInt("mwe-producer.nrecords")

  val messageStream: Future[Done] = Source.fromIterator { () =>
    (0 until nRecords).toIterator
  }.mapAsync(7){ n =>
    Future.successful(new ProducerRecord[Int, String](Common.topicName, n, f"Message #${n}%02d"))
  }.runWith(Producer.plainSink(kafkaProducerSettings))

  messageStream.onComplete {
    case Success(_) =>
      logger.info(s"Completed sending ${nRecords} messages to kafka")
      actorSystem.terminate()
    case Failure(exc) =>
      logger.error("Error sending to kafka", exc)
      actorSystem.terminate()
  }
}

