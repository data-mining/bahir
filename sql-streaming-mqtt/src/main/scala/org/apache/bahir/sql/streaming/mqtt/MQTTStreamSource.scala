/*
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

package org.apache.bahir.sql.streaming.mqtt

import java.nio.charset.Charset
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.concurrent.CountDownLatch

import scala.collection.concurrent.TrieMap
import scala.collection.mutable.ArrayBuffer
import scala.util.{Failure, Success, Try}

import org.apache.bahir.utils.Logging
import org.eclipse.paho.client.mqttv3._
import org.eclipse.paho.client.mqttv3.persist.{MemoryPersistence, MqttDefaultFilePersistence}

import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.sql.execution.streaming.{LongOffset, Offset, Source}
import org.apache.spark.sql.sources.{DataSourceRegister, StreamSourceProvider}
import org.apache.spark.sql.types.{StringType, StructField, StructType, TimestampType}


object MQTTStreamConstants {

  val DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  val SCHEMA_DEFAULT = StructType(StructField("value", StringType)
    :: StructField("timestamp", TimestampType) :: Nil)
}

/**
 * A Text based mqtt stream source, it interprets the payload of each incoming message by converting
 * the bytes to String using Charset.defaultCharset as charset. Each value is associated with a
 * timestamp of arrival of the message on the source. It can be used to operate a window on the
 * incoming stream.
 *
 * @param brokerUrl url MqttClient connects to.
 * @param persistence an instance of MqttClientPersistence. By default it is used for storing
 *                    incoming messages on disk. If memory is provided as option, then recovery on
 *                    restart is not supported.
 * @param topic topic MqttClient subscribes to.
 * @param clientId clientId, this client is assoicated with. Provide the same value to recover
 *                 a stopped client.
 * @param messageParser parsing logic for processing incoming messages from Mqtt Server.
 * @param sqlContext Spark provided, SqlContext.
 * @param mqttConnectOptions an instance of MqttConnectOptions for this Source.
 * @param qos the maximum quality of service to subscribe each topic at.Messages published at
 *            a lower quality of service will be received at the published QoS. Messages
 *            published at a higher quality of service will be received using the QoS specified
 *            on the subscribe.
 */
class MQTTTextStreamSource(brokerUrl: String, persistence: MqttClientPersistence,
    topic: String, clientId: String, messageParser: Array[Byte] => (String, Timestamp),
    sqlContext: SQLContext, mqttConnectOptions: MqttConnectOptions, qos: Int)
  extends Source with Logging {

  override def schema: StructType = MQTTStreamConstants.SCHEMA_DEFAULT

  private val store = new LocalMessageStore(persistence, sqlContext.sparkContext.getConf)

  private val messages = new TrieMap[Int, (String, Timestamp)]

  private val initLock = new CountDownLatch(1)

  private var offset = 0

  private var client: MqttClient = _

  private def fetchLastProcessedOffset(): Int = {
    Try(store.maxProcessedOffset) match {
      case Success(x) =>
        log.info(s"Recovering from last stored offset $x")
        x
      case Failure(e) => 0
    }
  }

  initialize()
  private def initialize(): Unit = {

    client = new MqttClient(brokerUrl, clientId, persistence)

    val callback = new MqttCallbackExtended() {

      override def messageArrived(topic_ : String, message: MqttMessage): Unit = synchronized {
        initLock.await() // Wait for initialization to complete.
        val temp = offset + 1
        messages.put(temp, messageParser(message.getPayload))
        offset = temp
        log.trace(s"Message arrived, $topic_ $message")
      }

      override def deliveryComplete(token: IMqttDeliveryToken): Unit = {
      }

      override def connectionLost(cause: Throwable): Unit = {
        log.warn("Connection to mqtt server lost.", cause)
      }

      override def connectComplete(reconnect: Boolean, serverURI: String): Unit = {
        log.info(s"Connect complete $serverURI. Is it a reconnect?: $reconnect")
      }
    }
    client.setCallback(callback)
    client.connect(mqttConnectOptions)
    client.subscribe(topic, qos)
    // It is not possible to initialize offset without `client.connect`
    offset = fetchLastProcessedOffset()
    initLock.countDown() // Release.
  }

  /** Stop this source and free any resources it has allocated. */
  override def stop(): Unit = {
    client.disconnect()
    persistence.close()
    client.close()
  }

  /** Returns the maximum available offset for this source. */
  override def getOffset: Option[Offset] = {
    if (offset == 0) {
      None
    } else {
      Some(LongOffset(offset))
    }
  }

  /**
   * Returns the data that is between the offsets (`start`, `end`]. When `start` is `None` then
   * the batch should begin with the first available record. This method must always return the
   * same data for a particular `start` and `end` pair.
   */
  override def getBatch(start: Option[Offset], end: Offset): DataFrame = synchronized {
    val startIndex = start.getOrElse(LongOffset(0L)).asInstanceOf[LongOffset].offset.toInt
    val endIndex = end.asInstanceOf[LongOffset].offset.toInt
    val data: ArrayBuffer[(String, Timestamp)] = ArrayBuffer.empty
    // Move consumed messages to persistent store.
    (startIndex + 1 to endIndex).foreach { id =>
      val element: (String, Timestamp) = messages.getOrElse(id, store.retrieve(id))
      data += element
      store.store(id, element)
      messages.remove(id, element)
    }
    log.trace(s"Get Batch invoked, ${data.mkString}")
    import sqlContext.implicits._
    data.toDF("value", "timestamp")
  }

}

class MQTTStreamSourceProvider extends StreamSourceProvider with DataSourceRegister with Logging {

  override def sourceSchema(sqlContext: SQLContext, schema: Option[StructType],
      providerName: String, parameters: Map[String, String]): (String, StructType) = {
    ("mqtt", MQTTStreamConstants.SCHEMA_DEFAULT)
  }

  override def createSource(sqlContext: SQLContext, metadataPath: String,
      schema: Option[StructType], providerName: String, parameters: Map[String, String]): Source = {

    def e(s: String) = new IllegalArgumentException(s)

    val brokerUrl: String = parameters.getOrElse("brokerUrl", parameters.getOrElse("path",
      throw e("Please provide a `brokerUrl` by specifying path or .options(\"brokerUrl\",...)")))


    val persistence: MqttClientPersistence = parameters.get("persistence") match {
      case Some("memory") => new MemoryPersistence()
      case _ => val localStorage: Option[String] = parameters.get("localStorage")
        localStorage match {
          case Some(x) => new MqttDefaultFilePersistence(x)
          case None => new MqttDefaultFilePersistence()
        }
    }

    val messageParserWithTimeStamp = (x: Array[Byte]) =>
      (new String(x, Charset.defaultCharset()), Timestamp.valueOf(
      MQTTStreamConstants.DATE_FORMAT.format(Calendar.getInstance().getTime)))

    // if default is subscribe everything, it leads to getting lot unwanted system messages.
    val topic: String = parameters.getOrElse("topic",
      throw e("Please specify a topic, by .options(\"topic\",...)"))

    val clientId: String = parameters.getOrElse("clientId", {
      log.warn("If `clientId` is not set, a random value is picked up." +
        "\nRecovering from failure is not supported in such a case.")
      MqttClient.generateClientId()})

    val username: Option[String] = parameters.get("username")
    val password: Option[String] = parameters.get("password")
    val connectionTimeout: Int = parameters.getOrElse("connectionTimeout",
      MqttConnectOptions.CONNECTION_TIMEOUT_DEFAULT.toString).toInt
    val keepAlive: Int = parameters.getOrElse("keepAlive", MqttConnectOptions
      .KEEP_ALIVE_INTERVAL_DEFAULT.toString).toInt
    val mqttVersion: Int = parameters.getOrElse("mqttVersion", MqttConnectOptions
      .MQTT_VERSION_DEFAULT.toString).toInt
    val cleanSession: Boolean = parameters.getOrElse("cleanSession", "false").toBoolean
    val qos: Int = parameters.getOrElse("QoS", "1").toInt

    val mqttConnectOptions: MqttConnectOptions = new MqttConnectOptions()
    mqttConnectOptions.setAutomaticReconnect(true)
    mqttConnectOptions.setCleanSession(cleanSession)
    mqttConnectOptions.setConnectionTimeout(connectionTimeout)
    mqttConnectOptions.setKeepAliveInterval(keepAlive)
    mqttConnectOptions.setMqttVersion(mqttVersion)
    (username, password) match {
      case (Some(u: String), Some(p: String)) =>
        mqttConnectOptions.setUserName(u)
        mqttConnectOptions.setPassword(p.toCharArray)
      case _ =>
    }

    new MQTTTextStreamSource(brokerUrl, persistence, topic, clientId,
      messageParserWithTimeStamp, sqlContext, mqttConnectOptions, qos)
  }

  override def shortName(): String = "mqtt"
}
