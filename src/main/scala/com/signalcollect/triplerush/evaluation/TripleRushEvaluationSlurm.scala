package com.signalcollect.triplerush.evaluation

import java.io.File
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.util.Date
import scala.Option.option2Iterable
import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.collectionAsScalaIterable
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.reflect.runtime.universe
import com.signalcollect.GraphBuilder
import com.signalcollect.deployment.TorqueDeployableAlgorithm
import com.signalcollect.triplerush.TripleRush
import com.signalcollect.triplerush.optimizers.Optimizer
import akka.actor.ActorRef
import com.signalcollect.triplerush.TriplePattern
import com.signalcollect.triplerush.evaluation.lubm.FileOperations._
import com.signalcollect.deployment.SlurmDeployableAlgorithm

class TripleRushEvaluationSlurm extends SlurmDeployableAlgorithm {
  import SlurmEvalHelpers._

  val evaluationDescriptionKey = "evaluationDescription"
  val warmupRunsKey = "jitRepetitions"
  val datasetKey = "dataset"
  val universitiesKey = "universities"
  val optimizerCreatorKey = "optimizerCreator"
  val spreadsheetUsernameKey = "spreadsheetUsername"
  val spreadsheetPasswordKey = "spreadsheetPassword"
  val spreadsheetNameKey = "spreadsheetName"
  val worksheetNameKey = "worksheetName"
  val dataSourceKey = "dataSource"
  val rdfTypePartitioningKey = "rdfType"

  def execute(parameters: Map[String, String], nodeActors: Array[ActorRef]) {
    println(s"Received parameters $parameters")
    val evaluationDescription = parameters(evaluationDescriptionKey)
    val warmupRuns = parameters(warmupRunsKey).toInt
    val dataset = parameters(datasetKey)
    val universities: Option[String] = parameters.get(universitiesKey)
    val optimizerCreatorName = parameters(optimizerCreatorKey)
    val optimizerCreator = getObject[Function1[TripleRush, Option[Optimizer]]](optimizerCreatorName)
    val spreadsheetUsername = parameters(spreadsheetUsernameKey)
    val spreadsheetPassword = parameters(spreadsheetPasswordKey)
    val spreadsheetName = parameters(spreadsheetNameKey)
    val worksheetName = parameters(worksheetNameKey)
    val dataSource = parameters(dataSourceKey)
    val rdfTypePartitioning = parameters(rdfTypePartitioningKey).toBoolean
    val graphBuilder = GraphBuilder.withPreallocatedNodes(nodeActors)
    val tr = new TripleRush(graphBuilder)
    println("TripleRush has been started.")
    var commonResults = parameters
    commonResults += "numberOfNodes" -> tr.graph.numberOfNodes.toString
    commonResults += "numberOfWorkers" -> tr.graph.numberOfWorkers.toString
    commonResults += "java.runtime.version" -> System.getProperty("java.runtime.version")

    val loadingTime = measureTime {
      if (dataSource == "ntriples") {
        loadLubmFromNTriples(universities.get.toInt, tr)
      } else {
        loadLubm(universities.get.toInt, tr, rdfTypePartitioning)
      }
      tr.prepareExecution
    }

    println(s"loading time: $loadingTime")

    val optimizerInitStart = System.nanoTime
    val optimizer = optimizerCreator(tr)
    val optimizerInitEnd = System.nanoTime
    val queries = if (rdfTypePartitioning) {
      LubmQueriesRdfType.fullQueries
    } else {
      LubmQueries.fullQueries
    }

    commonResults += ((s"optimizerInitialisationTime", roundToMillisecondFraction(optimizerInitEnd - optimizerInitStart).toString))
    commonResults += ((s"optimizerName", optimizer.toString))
    commonResults += (("loadingTime", loadingTime.toString))
    commonResults += s"loadNumber" -> universities.toString
    commonResults += s"dataSet" -> s"lubm$universities"

    println("Starting warm-up...")

    for (i <- 1 to warmupRuns) {
      println(s"Running warmup $i/$warmupRuns")
      for (query <- queries) {
        tr.executeAdvancedQuery(query, optimizer)
        tr.awaitIdle
      }
    }
    println(s"Finished warm-up.")
    JvmWarmup.sleepUntilGcInactiveForXSeconds(60)

    val resultReporter = new GoogleDocsResultHandler(spreadsheetUsername, spreadsheetPassword, spreadsheetName, worksheetName)
    for (queryId <- queries.size to 1 by -1) {
      //for (queryId <- 1 to queries.size) {
      val startTime = System.currentTimeMillis

      println(s"Running evaluation for query $queryId.")
      println(s"query: ${queries(queryId - 1)}.")
      val result = executeEvaluationRun(queries(queryId - 1), queryId.toString, optimizer, tr, commonResults)
      resultReporter(result)
      println(s"Done running evaluation for query $queryId. Awaiting idle")
      tr.awaitIdle
      println("Idle")

      val finishTime = System.currentTimeMillis
      println(s"query $queryId took ${finishTime - startTime}") 

      JvmWarmup.sleepUntilGcInactiveForXSeconds(10, 30)
    }
    tr.shutdown
  }

}

object SlurmEvalHelpers {

  def executeEvaluationRun(query: Seq[TriplePattern], queryDescription: String, optimizer: Option[Optimizer], tr: TripleRush, commonResults: Map[String, String]): Map[String, String] = {
    val gcs = ManagementFactory.getGarbageCollectorMXBeans.toList
    val compilations = ManagementFactory.getCompilationMXBean
    val javaVersion = ManagementFactory.getRuntimeMXBean.getVmVersion
    val jvmLibraryPath = ManagementFactory.getRuntimeMXBean.getLibraryPath
    val jvmArguments = ManagementFactory.getRuntimeMXBean.getInputArguments
    var runResult = commonResults
    runResult += (("javaVmVersion", javaVersion))
    runResult += (("jvmLibraryPath", jvmLibraryPath))
    runResult += (("jvmArguments", jvmArguments.mkString(" ")))
    val date: Date = new Date
    val gcTimeBefore = getGcCollectionTime(gcs)
    val gcCountBefore = getGcCollectionCount(gcs)
    val compileTimeBefore = compilations.getTotalCompilationTime
    runResult += ((s"totalMemoryBefore", bytesToGigabytes(Runtime.getRuntime.totalMemory).toString))
    runResult += ((s"freeMemoryBefore", bytesToGigabytes(Runtime.getRuntime.freeMemory).toString))
    runResult += ((s"usedMemoryBefore", bytesToGigabytes(Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory).toString))
    val startTime = System.nanoTime
    val (queryResultFuture, queryStatsFuture) = tr.executeAdvancedQuery(query, optimizer)
    val queryResult = Await.result(queryResultFuture, 7200.seconds)
    val finishTime = System.nanoTime
    val executionTime = roundToMillisecondFraction(finishTime - startTime)
    val gcTimeAfter = getGcCollectionTime(gcs)
    val gcCountAfter = getGcCollectionCount(gcs)
    val gcTimeDuringQuery = gcTimeAfter - gcTimeBefore
    val gcCountDuringQuery = gcCountAfter - gcCountBefore
    val compileTimeAfter = compilations.getTotalCompilationTime
    val compileTimeDuringQuery = compileTimeAfter - compileTimeBefore
    val queryStats = Await.result(queryStatsFuture, 10.seconds)
    val optimizingTime = roundToMillisecondFraction(queryStats("optimizingDuration").asInstanceOf[Long])
    runResult += ((s"queryId", queryDescription))
    runResult += ((s"optimizer", optimizer.toString))
    runResult += ((s"query", queryStats("optimizedQuery").toString))
    runResult += ((s"exception", queryStats("exception").toString))
    runResult += ((s"results", queryResult.size.toString))
    runResult += ((s"executionTime", executionTime.toString))
    runResult += ((s"optimizingTime", optimizingTime.toString))
    runResult += ((s"totalMemory", bytesToGigabytes(Runtime.getRuntime.totalMemory).toString))
    runResult += ((s"freeMemory", bytesToGigabytes(Runtime.getRuntime.freeMemory).toString))
    runResult += ((s"usedMemory", bytesToGigabytes(Runtime.getRuntime.totalMemory - Runtime.getRuntime.freeMemory).toString))
    runResult += ((s"executionHostname", java.net.InetAddress.getLocalHost.getHostName))
    runResult += (("gcTimeAfter", gcTimeAfter.toString))
    runResult += (("gcCountAfter", gcCountAfter.toString))
    runResult += (("gcTimeDuringQuery", gcTimeDuringQuery.toString))
    runResult += (("gcCountDuringQuery", gcCountDuringQuery.toString))
    runResult += (("compileTimeAfter", compileTimeAfter.toString))
    runResult += (("compileTimeDuringQuery", compileTimeDuringQuery.toString))
    runResult += s"date" -> date.toString
    runResult
  }

  def loadLubm(universities: Int, triplerush: TripleRush, rdfTypePartitioning: Boolean) {
    println(s"Loading LUBM $universities ...")
    val lubmFolderName =
      if (rdfTypePartitioning) {
        println(s"rdfTypePartitioning is true, directory is: lubm$universities-type-filtered-splits")
        s"lubm$universities-type-filtered-splits"
      } else {
        println(s"rdfTypePartitioning is false, directory is: lubm$universities-filtered-splits")
        s"lubm$universities-filtered-splits"
      }
    for (splitId <- 0 until 2880) {
      val splitFile = s"./$lubmFolderName/$splitId.filtered-split"
      triplerush.loadBinary(splitFile, Some(splitId))
      if (splitId % 288 == 279) {
        println(s"Dispatched up to split #$splitId/2880, awaiting idle.")
        triplerush.awaitIdle
        println(s"Continuing graph loading...")
      }
    }
  }

  def loadLubmFromNTriples(universities: Int, triplerush: TripleRush) {
    println(s"Loading LUBM $universities ... from NTriples")
    val lubmFolderName = s"lubm$universities-nt"

    val sourceFiles = filesIn(lubmFolderName).
      filter(_.getName.endsWith(".nt")).
      sorted

    for (src <- sourceFiles) {
      println(s"src: $src")
      val ntFile = s"$src"
      triplerush.loadNtriples(ntFile)
    }
  }

  def getObject[T](o: String): T = {
    val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
    val module = runtimeMirror.staticModule(o)
    val obj = runtimeMirror.reflectModule(module).instance
    obj.asInstanceOf[T]
  }

  def getRevision: String = {
    try {
      val gitLogPath = ".git/logs/HEAD"
      val gitLog = new File(gitLogPath)
      val lines = Source.fromFile(gitLogPath).getLines
      val lastLine = lines.toList.last
      val revision = lastLine.split(" ")(1)
      revision
    } catch {
      case t: Throwable => "Unknown revision."
    }
  }

  def bytesToGigabytes(bytes: Long): Double = ((bytes / 1073741824.0) * 10.0).round / 10.0

  def cleanGarbage {
    for (i <- 1 to 10) {
      System.runFinalization
      System.gc
      Thread.sleep(10000)
    }
    Thread.sleep(120000)
  }

  /**
   * Returns the time in milliseconds it takes to execute the code in 'codeBlock'.
   */
  def measureTime(codeBlock: => Unit): Long = {
    val startTime = System.currentTimeMillis
    codeBlock
    val finishTime = System.currentTimeMillis
    finishTime - startTime
  }

  def roundToMillisecondFraction(nanoseconds: Long): Double = {
    ((nanoseconds / 100000.0).round) / 10.0
  }

  /**
   * Go to JVM JIT steady state by executing the queries multiple times.
   */
  def jitSteadyState(queries: List[Seq[TriplePattern]], optimizer: Option[Optimizer], tr: TripleRush, repetitions: Int = 100) {
    for (i <- 1 to repetitions) {
      println(s"running warmup $i/$repetitions")
      for (query <- queries) {
        tr.executeQuery(query, optimizer)
        tr.awaitIdle
      }
    }
    println(s"warmup finished")
  }

  def getGcCollectionTime(gcs: List[GarbageCollectorMXBean]): Long = {
    gcs.map(_.getCollectionTime).sum
  }

  def lastGcId(gcs: List[GarbageCollectorMXBean]): Long = {
    val sunGcs = gcs map (_.asInstanceOf[com.sun.management.GarbageCollectorMXBean])
    val gcIds = sunGcs.
      map(_.getLastGcInfo).
      flatMap(info => if (info != null) Some(info.getId) else None)
    if (gcIds.isEmpty) 0 else gcIds.max
  }

  def freedDuringLastGc(gcs: List[GarbageCollectorMXBean]): Long = {
    val sunGcs = gcs map (_.asInstanceOf[com.sun.management.GarbageCollectorMXBean])
    val usedBeforeLastGc = sunGcs.
      map(_.getLastGcInfo).
      map(_.getMemoryUsageBeforeGc).
      flatMap(_.values).
      map(_.getCommitted).
      sum
    val usedAfterLastGc = sunGcs.
      map(_.getLastGcInfo).
      map(_.getMemoryUsageAfterGc).
      flatMap(_.values).
      map(_.getCommitted).
      sum
    val freedDuringLastGc = usedBeforeLastGc - usedAfterLastGc
    freedDuringLastGc
  }

  def getGcCollectionCount(gcs: List[GarbageCollectorMXBean]): Long = {
    gcs.map(_.getCollectionCount).sum
  }

}