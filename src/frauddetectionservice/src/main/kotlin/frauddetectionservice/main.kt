/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package frauddetectionservice

import org.apache.kafka.clients.consumer.ConsumerConfig.*
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import oteldemo.Demo.*
import java.time.Duration.ofMillis
import java.util.*
import kotlin.system.exitProcess
import kotlin.concurrent.thread

import javax.jms.Message
import javax.jms.MessageListener
import javax.jms.BytesMessage

import com.solacesystems.jms.SolConnectionFactory
import com.solacesystems.jms.SolJmsUtility
import com.solacesystems.jms.SupportedProperty

const val topic = "orders"
const val groupID = "frauddetectionservice"

private val logger: Logger = LogManager.getLogger(groupID)

fun main() {
    val props = Properties()
    props[KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java.name
    props[VALUE_DESERIALIZER_CLASS_CONFIG] = ByteArrayDeserializer::class.java.name
    props[GROUP_ID_CONFIG] = groupID
    val bootstrapServers = System.getenv("KAFKA_SERVICE_ADDR")
    if (bootstrapServers == null) {
        println("KAFKA_SERVICE_ADDR is not supplied")
        exitProcess(1)
    }
    val solaceServers = System.getenv("SOLACE_SERVICE_ADDR")
    if (solaceServers == null) {
        println("SOLACE_SERVICE_ADDR is not supplied")
        exitProcess(1)
    }
    props[BOOTSTRAP_SERVERS_CONFIG] = bootstrapServers
    val consumer = KafkaConsumer<String, ByteArray>(props).apply {
        subscribe(listOf(topic))
    }

    thread {
        val connectionFactory = SolJmsUtility.createConnectionFactory()
        connectionFactory.setHost(solaceServers)
        connectionFactory.setVPN("default")
        connectionFactory.setUsername("default")
        connectionFactory.setPassword("default")

        val connection = connectionFactory.createConnection()
        val session = connection.createSession(false, SupportedProperty.SOL_CLIENT_ACKNOWLEDGE)

        val queue = session.createQueue("fraud-detection-orders")
        val cons = session.createConsumer(queue)

        val listener = object : MessageListener {
            override fun onMessage(msg: Message) {
                if (msg is BytesMessage) {
                    val body = ByteArray(msg.getBodyLength().toInt())
                    msg.readBytes(body)
                    val orders = OrderResult.parseFrom(body)
                    println("Consumed record with Solace with orderId: ${orders.orderId}")
                }
                msg.acknowledge()
            }
        }

        cons.setMessageListener(listener)
        connection.start()
    }

    var totalCount = 0L

    consumer.use {
        while (true) {
            totalCount = consumer
                .poll(ofMillis(100))
                .fold(totalCount) { accumulator, record ->
                    val newCount = accumulator + 1
                    val orders = OrderResult.parseFrom(record.value())
                    logger.info("Consumed record with orderId: ${orders.orderId}, and updated total count to: $newCount")
                    newCount
                }
        }
    }
}
