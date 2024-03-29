package com.netflix.spinnaker.tide.actor.classiclink

import akka.actor.{Props, Cancellable, ActorLogging}
import akka.contrib.pattern.ClusterSharding
import akka.persistence.{RecoveryCompleted, PersistentActor}
import com.netflix.spinnaker.tide.actor.TaskActorObject
import com.netflix.spinnaker.tide.actor.classiclink.AttachClassicLinkActor.{EndOfLife, AttachClassicLinkTask}
import com.netflix.spinnaker.tide.actor.classiclink.ClassicLinkInstancesActor.{GetInstancesNeedingClassicLinkAttached, InstancesNeedingClassicLinkAttached}
import com.netflix.spinnaker.tide.actor.service.CloudDriverActor
import com.netflix.spinnaker.tide.actor.task.TaskActor._
import com.netflix.spinnaker.tide.actor.task.TaskDirector._
import com.netflix.spinnaker.tide.actor.task.{TaskActor, TaskProtocol}
import com.netflix.spinnaker.tide.model.AwsApi._
import com.netflix.spinnaker.tide.model._
import scala.concurrent.duration.DurationInt

class AttachClassicLinkActor extends PersistentActor with ActorLogging {

  override def persistenceId: String = self.path.name

  private implicit val dispatcher = context.dispatcher
  def scheduler = context.system.scheduler
  private var pollForUnattachedInstances: Option[Cancellable] = None
  private var taskLifeTime: Option[Cancellable] = None

  var task: AttachClassicLinkTask = _
  var taskId: String = _

  val clusterSharding = ClusterSharding.get(context.system)

  def sendTaskEvent(taskEvent: TaskProtocol) = {
    val taskCluster = ClusterSharding.get(context.system).shardRegion(TaskActor.typeName)
    taskCluster ! taskEvent
  }

  override def preRestart(reason: Throwable, message: Option[Any]) = {
    reason.printStackTrace()
    sendTaskEvent(TaskFailure(taskId, task, reason.getMessage, Option(reason)))
    super.preRestart(reason, message)
  }

  def pollForInstances(): Unit = {
    val classicLinkInstancesCluster = clusterSharding.shardRegion(ClassicLinkInstancesActor.typeName)
    val getInstances = GetInstancesNeedingClassicLinkAttached(task.location, Option(task.batchCount))
    pollForUnattachedInstances = Option(scheduler.schedule(0 seconds, 15 seconds, classicLinkInstancesCluster, getInstances))
    taskLifeTime = Option(scheduler.scheduleOnce(2 hours, self, EndOfLife()))
  }

  override def receiveCommand: Receive = {

    case ContinueTask(ExecuteTask(_, _: AttachClassicLinkTask, _)) => pollForInstances()

    case event @ ExecuteTask(_, _: AttachClassicLinkTask, _) =>
      persist(event) { e =>
        updateState(e)
        pollForInstances()
      }

    case event: InstancesNeedingClassicLinkAttached =>
      val instanceIdsToAttach = event.nonclassicLinkInstanceIds
      val cloudDriver = clusterSharding.shardRegion(CloudDriverActor.typeName)
      val attachCommand = AttachClassicLinkVpc(event.classicLinkVpcId, event.classicLinkSecurityGroupIds)
      if (instanceIdsToAttach.nonEmpty) {
        sendTaskEvent(Log(taskId, s"Attaching $attachCommand to $instanceIdsToAttach"))
        instanceIdsToAttach.foreach { instanceId =>
          if (!task.dryRun) {
            val awsReference = AwsReference(task.location, InstanceIdentity(instanceId))
            cloudDriver ! AwsResourceProtocol(awsReference, attachCommand)
          }
        }
      }

    case event: TaskComplete =>
      persist(event) { it =>
        pollForUnattachedInstances.foreach(_.cancel())
        taskLifeTime.foreach(_.cancel())
        if (!task.dryRun) {
          val logMessage = event match {
            case taskSuccess: TaskSuccess => "Task complete."
            case taskFailure: TaskCancel => "Task canceled."
            case taskFailure: TaskFailure => s"Failure: ${taskFailure.message}"
          }
          sendTaskEvent(Log(taskId, logMessage))
        }
      }

    case event: EndOfLife =>
      sendTaskEvent(TaskSuccess(taskId, task, NoResult()))
      sendTaskEvent(RestartTask(taskId))

  }

  def updateState(event: Any) = {
    event match {
      case ExecuteTask(newTaskId, newTask: AttachClassicLinkTask, _) =>
        taskId = newTaskId
        task = newTask
      case _ => Nil
    }
  }

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
    case event =>
      updateState(event)
  }

}

sealed trait AttachClassicLinkProtocol extends Serializable

object AttachClassicLinkActor extends TaskActorObject {
  val props = Props[AttachClassicLinkActor]

  case class AttachClassicLinkTask(location: AwsLocation,
                                   batchCount: Integer = 100,
                                   dryRun: Boolean = false) extends TaskDescription with AttachClassicLinkProtocol {
    override def taskType: String = "AttachClassicLinkTask"
    override def executionActorTypeName: String = typeName
    override def summary: String = s"Attach classic link in $location (batchCount: $batchCount, dryRun: $dryRun)."
  }

  case class EndOfLife()
}

