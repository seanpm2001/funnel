package oncue.svc.funnel
package zeromq

import scalaz.concurrent.Task
import scalaz.stream.Process
import scala.concurrent.duration._

object Settings {
  // we use a space in /tmp rather than /var/run because the
  // sbt process does not have permissions to access /var/run
  val socket = "/tmp/funnel-perf.socket"
}

object PerfMultiJvmPusher1 extends Pusher("pusher-1", Settings.socket)

object PerfMultiJvmPusher2 extends Pusher("pusher-2", Settings.socket)

object PerfMultiJvmPusher3 extends Pusher("pusher-3", Settings.socket)

object PerfMultiJvmPuller {
  import scalaz.stream.io
  import scalaz.stream.Channel
  import java.util.concurrent.atomic.AtomicLong
  import concurrent.duration.FiniteDuration

  val received = new AtomicLong(0L)

  def main(args: Array[String]): Unit = {
    Ø.log.info(s"Booting Puller...")

    val start = System.currentTimeMillis

    val E = Endpoint(`Pull+Bind`, Address(IPC, host = Settings.socket))

    // val alive = new AtomicBoolean(true)
    // val K = Process.repeatEval(Task.delay(alive.get))

    val ledger: Channel[Task, String, Unit] = io.channel(
      _ => Task {
        val i = received.incrementAndGet
        val time = FiniteDuration(System.currentTimeMillis - start, "milliseconds").toSeconds
        if(i % 10000 == 0) println(s"Pulled $i values in $time seconds.") // print it out every 1k increment
        else ()
      }
    )

    Ø.link(E)(Ø.monitoring.alive)(Ø.receive)
      .map(_.toString)
      .through(ledger)
      .run.runAsync(_ => ())

    while(received.get < 2999999){ }

    val finish = System.currentTimeMillis

    Ø.log.debug("Puller - Stopping the task...")

    val seconds = FiniteDuration(finish - start, "milliseconds").toSeconds
    val bytes = Fixtures.data.length * received.get
    val megabits = bytes.toDouble / Fixtures.megabitInBytes

    Ø.log.info("=================================================")
    Ø.log.info(s"duration  = $seconds seconds")
    Ø.log.info(s"msg/sec   = ${received.get.toDouble / seconds}")
    Ø.log.info(s"megabits  = $megabits")
    Ø.log.info(s"data mb/s = ${megabits.toDouble / seconds}")
    Ø.log.info("=================================================")


  }
}
