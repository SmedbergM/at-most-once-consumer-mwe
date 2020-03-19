package smedbergm

import scala.concurrent.duration._
import java.time.{Duration => JDuration}

package object mwe {
  private[mwe] implicit class ConvertibleDuration(jDuration: JDuration) {
    def asScala: FiniteDuration = {
      jDuration.toNanos.nanos
    }
  }
}
