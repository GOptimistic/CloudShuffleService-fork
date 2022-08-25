/*
 * Copyright 2022 Bytedance Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.shuffle.css

import java.util

import com.bytedance.css.api.CssShuffleContext
import com.bytedance.css.client.ShuffleClient
import com.bytedance.css.client.metrics.ClientSource
import com.bytedance.css.common.CssConf
import com.bytedance.css.common.internal.Logging
import com.bytedance.css.common.metrics.MetricsSystem
import com.bytedance.css.common.util.Utils

import org.apache.spark.{ShuffleDependency, SparkConf, SparkContext, SparkEnv, TaskContext}
import org.apache.spark.internal.config._
import org.apache.spark.shuffle.{BaseShuffleHandle, ShuffleBlockResolver, ShuffleHandle}
import org.apache.spark.shuffle.{ShuffleManager, ShuffleReader, ShuffleWriter}
import org.apache.spark.shuffle.css.CssShuffleManager.getAppId

class CssShuffleManager(conf: SparkConf) extends ShuffleManager with Logging {

  private lazy val cssConf = CssShuffleManager.fromSparkConf(conf)

  private lazy val cssShuffleClient: ShuffleClient = ShuffleClient.get(cssConf)
  private lazy val maxPartitionsPerGroup = CssConf.maxPartitionsPerGroup(cssConf)
  private lazy val cssClusterName = CssConf.clusterName(cssConf)
  private var appId: Option[String] = None
  @volatile private var metricsInitialized = false

  if (!CssConf.workerRegistryType(cssConf).equals("standalone")) {
    if (conf.getOption("spark.executor.id").exists(_.equals(SparkContext.DRIVER_IDENTIFIER)) &&
      !conf.contains("spark.css.master.identifier")) {
      val cssParams = new util.HashMap[String, String]()
      cssConf.getAll.foreach(kv => cssParams.put(kv._1, kv._2))
      val host = conf.get(DRIVER_HOST_ADDRESS)
      logInfo("Spark Driver try to start css master if needed.")
      CssShuffleContext.get.startMaster(host, 0, cssParams)

      // Set master host & port for CssShuffleManager
      val cssMasterAddr = s"css://${host}:${CssShuffleContext.get().getMasterPort.toString}"
      cssConf.set("css.master.address", cssMasterAddr)
      conf.set("spark.css.master.address", cssMasterAddr)
      conf.set("spark.css.master.identifier", "driver-master")

      // initialize shuffle workers num
      val estimatedWorkers: Int = if (conf.getBoolean("spark.dynamicAllocation.enabled", false)) {
        (conf.get(DYN_ALLOCATION_MAX_EXECUTORS) / 50 + conf.get(DYN_ALLOCATION_MIN_EXECUTORS)) / 10
      } else {
        conf.get(EXECUTOR_INSTANCES).getOrElse(0) / 20
      }

      val initWorkers = Math.max(2, estimatedWorkers)
      require(initWorkers != 0, "initWorkers could not be zero.")
      CssShuffleContext.get().allocateWorkerIfNeeded(initWorkers)
    }
  }

  override def registerShuffle[K, V, C](
      shuffleId: Int,
      numMaps: Int,
      dependency: ShuffleDependency[K, V, C]): ShuffleHandle = {

    // enable heartbeat
    appId = Some(getAppId(dependency.rdd.context))
    cssShuffleClient.registerApplication(appId.get)

    logInfo(s"Css RegisterShuffle in Spark Driver with shuffleId: $shuffleId")
    new CssShuffleHandle(
      appId.get,
      shuffleId,
      numMaps,
      cssShuffleClient.registerPartitionGroup(
        appId.get, shuffleId,
        dependency.rdd.getNumPartitions, dependency.partitioner.numPartitions, maxPartitionsPerGroup),
      dependency.asInstanceOf[ShuffleDependency[K, V, V]])
  }

  override def getWriter[K, V](
      handle: ShuffleHandle,
      mapId: Int,
      context: TaskContext): ShuffleWriter[K, V] = {
    val cssShuffleHandle = handle.asInstanceOf[CssShuffleHandle[K, V]]
    initializeMetrics(cssShuffleHandle.appId)
    cssShuffleClient.applyShufflePartitionGroup(cssShuffleHandle.shuffleId, cssShuffleHandle.partitionGroups)
    handle match {
      case h: BaseShuffleHandle[K@unchecked, V@unchecked, _] =>
        new CssShuffleWriter(h, mapId, context, conf, cssConf, cssShuffleClient)
    }
  }

  override def getReader[K, C](
      handle: ShuffleHandle,
      startPartition: Int,
      endPartition: Int,
      context: TaskContext): ShuffleReader[K, C] = {
    val cssShuffleHandle = handle.asInstanceOf[CssShuffleHandle[K, C]]
    initializeMetrics(cssShuffleHandle.appId)
    new CssShuffleReader[K, C](cssShuffleHandle, 0, cssShuffleHandle.numMaps,
      startPartition, endPartition, context, cssShuffleClient)
  }

  override def unregisterShuffle(shuffleId: Int): Boolean = {
    // In community spark
    // unregisterShuffle is called by all nodes include driver & executor to unregister with external
    // shuffle service.
    // But in CSS, we should only call unregisterShuffle once will be good.
    appId match {
      case Some(id) =>
        cssShuffleClient.unregisterShuffle(id, shuffleId,
          SparkEnv.get.executorId == SparkContext.DRIVER_IDENTIFIER)
      case None =>
    }
    true
  }

  // No need for CSS, because we deal with partition Resolver in ShuffleClient
  override def shuffleBlockResolver: ShuffleBlockResolver = null

  override def stop(): Unit = {
    if (SparkEnv.get.executorId != SparkContext.DRIVER_IDENTIFIER) {
      Utils.tryLogNonFatalError(cssShuffleClient.shutDown())
    } else {
      logInfo("Spark Driver try to stop css master")
      Utils.tryLogNonFatalError(cssShuffleClient.shutDown())
      Utils.tryLogNonFatalError(CssShuffleContext.get.stopMaster())
    }
  }

  def initializeMetrics(appId: String): Unit = {
    if (!metricsInitialized) {
      synchronized {
        if (!metricsInitialized) {
          cssConf.set("css.metrics.conf.*.sink.bytedance.prefix", "inf.spark")
          val metricsSystem = MetricsSystem.createMetricsSystem(MetricsSystem.CLIENT, cssConf)
          metricsSystem.registerSource(ClientSource.instance(cssClusterName, appId))
          metricsSystem.start()
          metricsInitialized = true
        }
      }
    }
  }

}

object CssShuffleManager {

  def getAppId(context: SparkContext): String = {
    context.applicationAttemptId match {
      case Some(id) => s"${context.applicationId}_$id"
      case None => s"${context.applicationId}"
    }
  }

  // parsing spark.css.* into css.* to construct CssConf
  def fromSparkConf(conf: SparkConf): CssConf = {
    val tmpConf = new CssConf()
    for ((key, value) <- conf.getAll) {
      if (key.startsWith("spark.css.")) {
        tmpConf.set(key.substring("spark.".length), value)
        // if zk mode enable.
        if (key.equals("spark.css.zookeeper.address")) {
          tmpConf.set("css.worker.registry.type", "zookeeper")
        }
      }
    }

    tmpConf
  }

}
