package funnel
package chemist

import concurrent.duration._
import scalaz.concurrent.Strategy
import scalaz.concurrent.Task
import Sharding.Distribution
import scalaz.{-\/,==>>,\/}
import scalaz.std.string._
import scalaz.std.set._
import scalaz.syntax.monad._
import scalaz.stream.{Sink, Channel, Process, Process1, async}
import java.net.URI
import TargetLifecycle._
import funnel.internals._
import metrics._

/**
 * A Repository acts as our ledger of our current view of the state of
 * the world.  This includes Flasks which are able to monitor Targets,
 * and a list of Targets to be monitored, including the Flasks themsevles.
 */
trait Repository {
  import RepoEvent._
  /**
   * Maps IDs to the Instances, and the details of their last state change
   */
  type InstanceM = URI        ==>> StateChange
  type FlaskM    = FlaskID    ==>> Flask

  /**
   * for any possible state of a target, a map of instances in that state
   */
  type StateM    = TargetState ==>> InstanceM

  /////////////// audit operations //////////////////

  /**
   * The most recent state changes
   */
  def historicalPlatformEvents: Task[Seq[PlatformEvent]]
  def historicalRepoEvents: Task[Seq[RepoEvent]]

  /**
   * the most recent mirroring errors
   */
  def errors: Task[Seq[Error]]

  /**
   * Render the current state of the world, as chemist sees it
   */
  def states: Task[Map[TargetLifecycle.TargetState, Map[URI, RepoEvent.StateChange]]]

  def keySink(uri: URI, keys: Set[Key[Any]]): Task[Unit]
  def errorSink(e: Error): Task[Unit]
  def platformHandler(a: PlatformEvent): Task[Unit]

  /////////////// instance operations ///////////////

  def targetState(instanceId: URI): TargetState
  def updateState(instanceId: URI, state: TargetState, change: StateChange): Task[Unit]
  def instance(id: URI): Option[Target]
  def flask(id: FlaskID): Option[Flask]
  def processRepoEvent(event: RepoEvent): Task[Unit]
  def instances: Task[Seq[(URI, StateChange)]]

  /////////////// flask operations ///////////////

  def distribution: Task[Distribution]
  def mergeDistribution(d: Distribution): Task[Distribution]
  def mergeExistingDistribution(d: Distribution): Task[Distribution]
  def assignedTargets(flask: FlaskID): Task[Set[Target]]
  def unassignedTargets: Task[Set[Target]]
  def unmonitorableTargets: Task[List[URI]]

  def repoCommands: Process[Task, RepoCommand]
}

import journal.Logger

class StatefulRepository extends Repository {
  import RepoEvent._
  import TargetState._
  private val log = Logger[StatefulRepository]

  /**
   * stores the mapping between flasks and their assigned workload
   */
  private val D = new Ref[Distribution](Distribution.empty)

  /**
   * stores a key-value map of uri -> state-change
   */
  val targets = new Ref[InstanceM](==>>.empty)
  val knownFlasks  = new Ref[FlaskM](==>>.empty)

  private val emptyMap: InstanceM = ==>>.empty
  val stateMaps = new Ref[StateM](==>>(
    Unknown -> emptyMap,
    Unmonitored -> emptyMap,
    Assigned -> emptyMap,
    Monitored -> emptyMap,
    Problematic -> emptyMap,
    DoubleAssigned -> emptyMap,
    DoubleMonitored -> emptyMap,
    Investigating -> emptyMap,
    Fin -> emptyMap))

  /**
   * stores lifecycle events to serve as an audit log that
   * retains the last 100 scalling events
   */
  private[chemist] val historyStack = new BoundedStack[PlatformEvent](2000)
  private[chemist] val repoHistoryStack = new BoundedStack[RepoEvent](2000)

  /**
   * stores the list of errors we have gotten from flasks, most recent
   * first.
   */
  private[chemist] val errorStack = new BoundedStack[Error](500)

  /////////////// audit operations //////////////////

  def historicalPlatformEvents: Task[Seq[PlatformEvent]] =
    Task.delay(historyStack.toSeq.toList.sortWith {
      case(x, y) => x.time.compareTo(y.time) < 0 })

  def historicalRepoEvents: Task[Seq[RepoEvent]] =
    Task.delay(repoHistoryStack.toSeq.toList)

  def errors: Task[Seq[Error]] =
    Task.delay(errorStack.toSeq.toList)

  def states: Task[Map[TargetLifecycle.TargetState, Map[URI, RepoEvent.StateChange]]] = Task.delay {
    stateMaps.get.toList.map {
      case (k,v) => k -> v.toList.toMap
    }.toMap
  }

  def keySink(uri: URI, keys: Set[Key[Any]]): Task[Unit] = Task.now(())

  def errorSink(e: Error): Task[Unit] = errorStack.push(e)

  /////////////// instance operations ///////////////

  def instances: Task[Seq[(URI, StateChange)]] =
    Task.delay(targets.get.toList)

  def unassignedTargets: Task[Set[Target]] =
    Task.delay(stateMaps.get.lookup(TargetState.Unmonitored).fold(Set.empty[Target])(m => m.values.map(_.msg.target).toSet))

  def unmonitorableTargets: Task[List[URI]] =
    Task.delay(stateMaps.get.lookup(TargetState.Unmonitorable).fold(List.empty[URI])(m => m.values.map(_.msg.target.uri)))

  def assignedTargets(flask: FlaskID): Task[Set[Target]] =
    D.get.lookup(flask) match {
      case None => Task.fail(InstanceNotFoundException(flask.value, "Flask"))
      case Some(t) => Task.now(t)
    }

  /////////////// target lifecycle ///////////////

  /**
   * Handle the Actions emitted from the Platform
   */
  def platformHandler(a: PlatformEvent): Task[Unit] = {
    historyStack.push(a).flatMap{ _ =>
      val lifecycle = TargetLifecycle.process(this) _
      a match {
        case PlatformEvent.NewTarget(target) =>
          Task.delay(log.info("platformHandler -- new target: " + target)) >>
          lifecycle(TargetLifecycle.Discovery(target, System.currentTimeMillis), targetState(target.uri))

        case PlatformEvent.NewFlask(f) =>
          Task.delay(log.info("platformHandler -- new flask: " + f)) >>
          Task.delay {
            D.update(_.updateAppend(f.id, Set.empty))
            knownFlasks.update(_.insert(f.id, f))
          } >>
          repoCommandsQ.enqueueOne(RepoCommand.Telemetry(f))

        case PlatformEvent.TerminatedFlask(i) =>
          // This one is a little weird, we are enqueueing this to ourseles
          // we should probably eliminate this re-enqueing
          Task.delay(log.info("platformHandler -- terminated flask: " + i)) >>
          repoCommandsQ.enqueueOne(RepoCommand.ReassignWork(i))

        case PlatformEvent.TerminatedTarget(i) => {
          val target = targets.get.lookup(i)
          target.map { t =>
            Task.delay {
              targets.update(_.delete(i))
              stateMaps.update(_.update(t.to, m => Some(m.delete(i))))
              ()
            }
          }.getOrElse(Task.now(()))
        }

        case PlatformEvent.Monitored(f, i) =>
          log.info(s"platformHandler -- $i monitored by $f")
          val target = targets.get.lookup(i)
          target.map { t =>
            lifecycle(TargetLifecycle.Confirmation(t.msg.target, f, System.currentTimeMillis), t.to)
          } getOrElse Task.delay(log.error(s"platformHandler -- never heard of target $i"))

        case PlatformEvent.Unmonitored(f, i) => {
          log.info(s"platformHandler -- $i no longer monitored by by $f")
          val target = targets.get.lookup(i)
          target.map { t =>
            // TODO: make sure we handle correctly all the cases where this might arrive (possibly unexpectedly)
            lifecycle(TargetLifecycle.Unmonitoring(t.msg.target, f, System.currentTimeMillis), t.to)
          } getOrElse {
            Task.delay(log.error(s"platformHandler -- encounterd an unknown target: $i"))
          }
        }

        case PlatformEvent.Problem(f, i, msg) => {
          log.warn(s"platformHandler -- $i no exception from  $f: $msg")
          val target = targets.get.lookup(i)
          target.map { t =>
            // TODO: make sure we handle correctly all the cases where this might arrive (possibly unexpectedly)
            lifecycle(TargetLifecycle.Investigate(t.msg.target, System.currentTimeMillis, 0), t.to)
          } getOrElse {
            Task.delay(log.error(s"platformHandler -- target $i had a problem, but never heard of it"))
          }
        }

        case PlatformEvent.Assigned(fl, t) =>
          Task.delay(log.info(s"platformHandler -- $t assigned to $fl")) >>
          lifecycle(TargetLifecycle.Assignment(t, fl, System.currentTimeMillis), targetState(t.uri))

        case PlatformEvent.NoOp =>
          Task.now(())
      }
    }
  }.attempt.flatMap(_.fold(
      e => Task.delay {
        log.error(s"Error processing platform event $a: $e")
        PlatformEventFailures.increment
      },
      _ => Task.now(())
    ))

  def processRepoEvent(re: RepoEvent): Task[Unit] =
    for {
      _ <- repoHistoryStack.push(re)
      _ <- Task.delay(log.info(s"lifecycle: executing event: $re"))
      _ <- re match {
        case sc @ StateChange(from,to,msg) =>
          for {
            _ <- Task.delay {
              val id = msg.target.uri
              targets.update(_.insert(id, sc))
              stateMaps.update(_.update(from, (m => Some(m.delete(id)))))
              stateMaps.update(_.update(to, (m => Some(m.insert(id, sc)))))
              AssignedHosts.set(stateMaps.get.lookup(TargetState.Assigned).size)
              DoubleMonitoredHosts.set(stateMaps.get.lookup(TargetState.DoubleMonitored).size)
              UnknownHosts.set(stateMaps.get.lookup(TargetState.Unknown).size)
              UnmonitoredHosts.set(stateMaps.get.lookup(TargetState.Unmonitored).size)
              UnmonitorableHosts.set(stateMaps.get.lookup(TargetState.Unmonitorable).size)
              MonitoredHosts.set(stateMaps.get.lookup(TargetState.Monitored).size)
              DoubleAssignedHosts.set(stateMaps.get.lookup(TargetState.DoubleAssigned).size)
              ProblematicHosts.set(stateMaps.get.lookup(TargetState.Problematic).size)
              InvestigatingHosts.set(stateMaps.get.lookup(TargetState.Investigating).size)
              FinHosts.set(stateMaps.get.lookup(TargetState.Fin).size)
            }
            _ <- to match {
              case Unmonitored => Task.delay {
                  log.debug("lifecycle: updating repository...")
                } >> repoCommandsQ.enqueueOne(RepoCommand.Monitor(sc.msg.target))

              // TODO when we implement flask transition we need to hanle double monitored stuff
              case other => Task.delay {
                log.debug(s"lifecycle: reached the unhandled state change: $other")
              }
            }
          } yield ()
        case NewFlask(flask) => Task {
          knownFlasks.update(_ + (flask.id -> flask))
        }
      }
    } yield ()

  // outbound events to be consumed by Sharding
  val repoCommandsQ: async.mutable.Queue[RepoCommand] =
    async.unboundedQueue(Strategy.Executor(Chemist.serverPool))

  val repoCommands: Process[Task, RepoCommand] =
    repoCommandsQ.dequeue

  /**
   * determine the current perceived state of a Target
   */
  def targetState(id: URI): TargetState =
    targets.get.lookup(id).fold[TargetState](TargetState.Unknown)(_.to)

  def updateState(instanceId: URI, state: TargetState, change: StateChange): Task[Unit] = Task.delay {
    stateMaps.update(				// Update a Ref to a Map of a Map to a case class
      _.update(state,
        im => Some(im.update(instanceId,
          sc => Some(change)
        ))
      )
    )
  }

  def instance(id: URI): Option[Target] =
    targets.get.lookup(id).map(_.msg.target)

  def flask(id: FlaskID): Option[Flask] =
    knownFlasks.get.lookup(id)

  /////////////// flask operations ///////////////

  def distribution: Task[Distribution] =
    Task.now(D.get)

  def mergeDistribution(d: Distribution): Task[Distribution] =
    Task.delay(D.update(_.unionWith(d)(_ ++ _)))

  def mergeExistingDistribution(d: Distribution): Task[Distribution] =
    Task.delay {
      d.toList.foreach {
        case (fl, ts) =>
          ts.foreach { t =>
            val sc = StateChange(TargetState.Unknown, TargetState.Monitored, Confirmation(t, fl, System.currentTimeMillis))
            stateMaps.update(_.map(_.delete(t.uri)))
            stateMaps.update(_.update(TargetState.Monitored, m => Some(m.insert(t.uri, sc))))
            targets.update(_.insert(t.uri, sc))
          }
      }
    } >> mergeDistribution(d)
}
