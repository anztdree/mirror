package com.screenmirror.receiver.signaling

import android.util.Log
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscribe
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/**
 * MQTT signaling client for the RECEIVER side with MULTI-BROKER fallback.
 *
 * Each broker is tried with a 15-second timeout. Pre-flight DNS check
 * fails fast if DNS resolution fails (instead of waiting indefinitely).
 */
class MqttSignalingClient(
    private val pairingCode: String,
    private val myPeerId: String = UUID.randomUUID().toString()
) {
    companion object {
        private const val TAG = "MqttSignaling"
        private const val CONNECT_TIMEOUT_SEC = 15L

        private val BROKERS = listOf(
            BrokerConfig("broker.emqx.io", 8084),
            BrokerConfig("broker.hivemq.com", 8884)
        )

        const val MSG_TYPE_RECEIVER_HELLO = "receiver_hello"
        const val MSG_TYPE_SENDER_READY = "sender_ready"
        const val MSG_TYPE_SDP_OFFER = "sdp_offer"
        const val MSG_TYPE_SDP_ANSWER = "sdp_answer"
        const val MSG_TYPE_ICE_CANDIDATE = "ice_candidate"
        const val MSG_TYPE_SWITCH_SOURCE = "switch_source"
        const val MSG_TYPE_MUTE_AUDIO = "mute_audio"
        const val MSG_TYPE_DISCONNECT = "disconnect"

        const val SOURCE_SCREEN = "screen"
        const val SOURCE_FRONT_CAMERA = "front_camera"
    }

    data class BrokerConfig(val host: String, val port: Int)

    private var client: Mqtt5AsyncClient? = null
    private var connectedBroker: String? = null

    private val _incomingMessages = MutableSharedFlow<SignalingMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<SignalingMessage> = _incomingMessages.asSharedFlow()

    val myId: String get() = myPeerId
    val connectedBrokerHost: String? get() = connectedBroker

    private var statusCallback: ((String) -> Unit)? = null
    fun setStatusCallback(cb: (String) -> Unit) { statusCallback = cb }

    private fun lobbyTopic(): String = "screenmirror/$pairingCode/lobby"
    private fun directTopic(peerId: String): String = "screenmirror/$pairingCode/to/$peerId"

    private val messageCallback = Consumer<Mqtt5Publish> { publish ->
        try {
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            onMessageReceived(publish.topic.toString(), payload)
        } catch (e: Exception) {
            Log.e(TAG, "Callback parse error", e)
        }
    }

    suspend fun connect() = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null

        for (broker in BROKERS) {
            try {
                statusCallback?.invoke("Mencoba broker: ${broker.host}...")
                Log.i(TAG, "Trying broker: ${broker.host}:${broker.port}")

                // Pre-flight DNS check - fails fast instead of waiting
                val addresses = try {
                    InetAddress.getAllByName(broker.host)
                } catch (e: UnknownHostException) {
                    Log.w(TAG, "DNS pre-flight failed for ${broker.host}: ${e.message}")
                    lastError = e
                    continue
                }
                Log.i(TAG, "DNS resolved ${broker.host} -> ${addresses.joinToString { it.hostAddress }}")

                statusCallback?.invoke("Connect ke ${broker.host}...")

                val c = Mqtt5Client.builder()
                    .identifier("screenmirror_receiver_${myPeerId.take(8)}_${broker.host.replace(".", "_")}")
                    .serverHost(broker.host)
                    .serverPort(broker.port)
                    .webSocketWithDefaultConfig()
                    .sslWithDefaultConfig()
                    .buildAsync()

                // Use timeout to avoid hanging forever
                c.connectWith()
                    .keepAlive(30)
                    .send()
                    .get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)

                statusCallback?.invoke("Subscribe topik di ${broker.host}...")

                val subDirect = Mqtt5Subscription.builder()
                    .topicFilter(directTopic(myPeerId))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .build()
                val subscribeDirect = Mqtt5Subscribe.builder()
                    .addSubscription(subDirect)
                    .build()
                c.subscribe(subscribeDirect, messageCallback).get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)

                client = c
                connectedBroker = "${broker.host}:${broker.port}"
                Log.i(TAG, "Connected to broker: $connectedBroker. peerId=$myPeerId, code=$pairingCode")
                statusCallback?.invoke("Tersambung ke $connectedBroker")
                return@withContext
            } catch (e: UnknownHostException) {
                Log.w(TAG, "DNS failed for ${broker.host}: ${e.message}")
                lastError = e
                continue
            } catch (e: java.util.concurrent.TimeoutException) {
                Log.w(TAG, "Connect timeout for ${broker.host}")
                lastError = e
                continue
            } catch (e: Throwable) {
                Log.w(TAG, "Connect failed for ${broker.host}: ${e.message}", e)
                lastError = e
                continue
            }
        }
        throw lastError ?: RuntimeException("All brokers failed")
    }

    private fun onMessageReceived(topic: String, payload: String) {
        try {
            val json = JSONObject(payload)
            val msg = SignalingMessage(
                type = json.getString("type"),
                from = json.optString("from", ""),
                to = json.optString("to", ""),
                sdp = json.optString("sdp", null),
                sdpType = json.optString("sdpType", null),
                candidate = json.optString("candidate", null),
                sdpMid = json.optString("sdpMid", null),
                sdpMLineIndex = json.optInt("sdpMLineIndex", -1),
                source = json.optString("source", null),
                muted = json.optBoolean("muted", false)
            )
            _incomingMessages.tryEmit(msg)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error on topic=$topic payload=$payload", e)
        }
    }

    suspend fun sendReceiverHello() = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("Not connected")
        val json = JSONObject().apply {
            put("type", MSG_TYPE_RECEIVER_HELLO)
            put("from", myPeerId)
        }
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)

        val publish = Mqtt5Publish.builder()
            .topic(lobbyTopic())
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload)
            .build()

        c.publish(publish).get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        Log.d(TAG, "Sent receiver_hello to lobby")
    }

    suspend fun sendTo(peerId: String, msg: SignalingMessage) = withContext(Dispatchers.IO) {
        val c = client ?: throw IllegalStateException("Not connected")
        val json = JSONObject().apply {
            put("type", msg.type)
            put("from", myPeerId)
            put("to", peerId)
            msg.sdp?.let { put("sdp", it) }
            msg.sdpType?.let { put("sdpType", it) }
            msg.candidate?.let { put("candidate", it) }
            msg.sdpMid?.let { put("sdpMid", it) }
            if (msg.sdpMLineIndex >= 0) put("sdpMLineIndex", msg.sdpMLineIndex)
            msg.source?.let { put("source", it) }
            put("muted", msg.muted)
        }
        val payload = json.toString().toByteArray(StandardCharsets.UTF_8)

        val publish = Mqtt5Publish.builder()
            .topic(directTopic(peerId))
            .qos(MqttQos.AT_LEAST_ONCE)
            .payload(payload)
            .build()

        c.publish(publish).get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        Log.d(TAG, "Sent ${msg.type} to $peerId")
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            client?.disconnect()?.get(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            Log.d(TAG, "Disconnected from broker")
        } catch (e: Exception) {
            Log.w(TAG, "Disconnect error", e)
        }
    }
}

data class SignalingMessage(
    val type: String,
    val from: String,
    val to: String,
    val sdp: String? = null,
    val sdpType: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = -1,
    val source: String? = null,
    val muted: Boolean = false
)
