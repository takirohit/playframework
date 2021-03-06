/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package detailedtopics.configuration.threadpools

import play.api.libs.ws.WS
import play.api.mvc._
import play.api.test._
import play.api._
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import play.api.libs.concurrent.Akka
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.collection.JavaConverters._
import java.io.File
import org.specs2.execute.AsResult

object ThreadPoolsSpec extends PlaySpecification {

  "Play's thread pools" should {

    "make a global thread pool available" in new WithApplication() {
      contentAsString(Samples.someAsyncAction(FakeRequest())) must startWith("The response code was")
    }

    "have a global configuration" in {
      val config = """#default-config
        akka {
          fork-join-executor {
            # Settings this to 1 instead of 3 seems to improve performance.
            parallelism-factor = 1.0

            parallelism-max = 24

            # Setting this to LIFO changes the fork-join-executor
            # to use a stack discipline for task scheduling. This usually
            # improves throughput at the cost of possibly increasing
            # latency and risking task starvation (which should be rare).
            task-peeking-mode = LIFO
          }
        }
      #default-config """
      val parsed = ConfigFactory.parseString(config)
      val actorSystem = ActorSystem("test", parsed.getConfig("akka"))
      actorSystem.terminate()
      success
    }

    "use akka default thread pool configuration" in {
      val config = """#akka-default-config
        akka {
          fork-join-executor {
            # The parallelism factor is used to determine thread pool size using the
            # following formula: ceil(available processors * factor). Resulting size
            # is then bounded by the parallelism-min and parallelism-max values.
            parallelism-factor = 3.0

            # Min number of threads to cap factor-based parallelism number to
            parallelism-min = 8

            # Max number of threads to cap factor-based parallelism number to
            parallelism-max = 64
          }
        }
      #akka-default-config """
      val parsed = ConfigFactory.parseString(config)
      val actorSystem = ActorSystem("test", parsed.getConfig("akka"))
      actorSystem.terminate()
      success
    }

    "allow configuring a custom thread pool" in runningWithConfig(
      """#my-context-config
        my-context {
          fork-join-executor {
            parallelism-factor = 20.0
            parallelism-max = 200
          }
        }
      #my-context-config """
    ) { implicit app =>
      //#my-context-usage
      object Contexts {
        implicit val myExecutionContext: ExecutionContext = Akka.system.dispatchers.lookup("my-context")
      }
      //#my-context-usage
      await(Future(Thread.currentThread().getName)(Contexts.myExecutionContext)) must startWith("application-my-context")

      //#my-context-explicit
      Future {
        // Some blocking or expensive code here
      }(Contexts.myExecutionContext)
      //#my-context-explicit

      {
        //#my-context-implicit
        import Contexts.myExecutionContext

        Future {
          // Some blocking or expensive code here
        }
        //#my-context-implicit
      }
      success
    }

    "allow access to the application classloader" in new WithApplication() {
      val myClassName = "java.lang.String"
      //#using-app-classloader
      val myClass = Play.current.classloader.loadClass(myClassName)
      //#using-app-classloader
    }

    "allow changing the default thread pool" in {
      val config = ConfigFactory.parseString("""#highly-synchronous
      akka {
        akka.loggers = ["akka.event.slf4j.Slf4jLogger"]
        loglevel = WARNING
        actor {
          default-dispatcher = {
            fork-join-executor {
              parallelism-min = 300
              parallelism-max = 300
            }
          }
        }
      }
      #highly-synchronous """)

      val actorSystem = ActorSystem("test", config.getConfig("akka"))
      actorSystem.terminate()
      success
    }

    "allow configuring many custom thread pools" in runningWithConfig(
    """ #many-specific-config
      contexts {
        simple-db-lookups {
          fork-join-executor {
            parallelism-factor = 10.0
          }
        }
        expensive-db-lookups {
          fork-join-executor {
            parallelism-max = 4
          }
        }
        db-write-operations {
          fork-join-executor {
            parallelism-factor = 2.0
          }
        }
        expensive-cpu-operations {
          fork-join-executor {
            parallelism-max = 2
          }
        }
      }
    #many-specific-config """
    ) { implicit app =>
      //#many-specific-contexts
      object Contexts {
        implicit val simpleDbLookups: ExecutionContext = Akka.system.dispatchers.lookup("contexts.simple-db-lookups")
        implicit val expensiveDbLookups: ExecutionContext = Akka.system.dispatchers.lookup("contexts.expensive-db-lookups")
        implicit val dbWriteOperations: ExecutionContext = Akka.system.dispatchers.lookup("contexts.db-write-operations")
        implicit val expensiveCpuOperations: ExecutionContext = Akka.system.dispatchers.lookup("contexts.expensive-cpu-operations")
      }
      //#many-specific-contexts
      def test(context: ExecutionContext, name: String) = {
        await(Future(Thread.currentThread().getName)(context)) must startWith("application-contexts." + name)
      }
      test(Contexts.simpleDbLookups, "simple-db-lookups")
      test(Contexts.expensiveDbLookups, "expensive-db-lookups")
      test(Contexts.dbWriteOperations, "db-write-operations")
      test(Contexts.expensiveCpuOperations, "expensive-cpu-operations")
    }

  }

  def runningWithConfig[T: AsResult](config: String )(block: Application => T) = {
    val parsed: java.util.Map[String,Object] = ConfigFactory.parseString(config).root.unwrapped
    running(_.configure(Configuration(ConfigFactory.parseString(config))))(block)
  }
}

// since specs provides defaultContext, implicitly importing it doesn't work
object Samples {

  //#global-thread-pool
  import play.api.libs.concurrent.Execution.Implicits._

  def someAsyncAction = Action.async {
    import play.api.Play.current
    WS.url("http://www.playframework.com").get().map { response =>
      // This code block is executed in the imported default execution context
      // which happens to be the same thread pool in which the outer block of
      // code in this action will be executed.
      Results.Ok("The response code was " + response.status)
    }
  }
  //#global-thread-pool

}
