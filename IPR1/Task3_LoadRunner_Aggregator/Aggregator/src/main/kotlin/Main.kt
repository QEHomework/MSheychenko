import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Timer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.fixedRateTimer

@Serializable
data class Metric(
    val name: String,
    val tags: Map<String, String> = emptyMap(),
    val fields: Map<String, JsonElement>,
    val timestamp: Long
)

private val queue = CopyOnWriteArrayList<Metric>()

// ───── Micrometer / Prometheus registry ─────
private val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
private val queueGauge        = prometheusRegistry.gauge("agg_queue_size", queue) { it.size.toDouble() }!!
private val acceptedCounter   = Counter.builder("agg_metrics_accepted_total").register(prometheusRegistry)
private val rejectedCounter   = Counter.builder("agg_metrics_rejected_total").register(prometheusRegistry)
private val flushOkCounter    = Counter.builder("agg_batch_flush_total").register(prometheusRegistry)
private val flushFailCounter  = Counter.builder("agg_batch_fail_total").register(prometheusRegistry)
private val flushTimer: Timer = Timer.builder("agg_flush_duration_ms").register(prometheusRegistry)

fun main() {
    // ───── flush timer (schedule first) ─────
    fixedRateTimer(name = "flush", daemon = true, initialDelay = 5_000L, period = 15_000L) {
        if (queue.isEmpty()) return@fixedRateTimer
        val batch = queue.toList().also { queue.clear() }
        println("[${Instant.now()}] Flushing ${batch.size} metrics → InfluxDB")
        writeToInflux(batch)
    }

    // ───── Ktor HTTP server ─────
    embeddedServer(Netty, port = 8085) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }

        routing {
            get("/ping") { call.respondText("pong") }
            // Prometheus scrape endpoint
            get("/metrics") { call.respondText(prometheusRegistry.scrape(), ContentType.Text.Plain) }

            post("/metrics") {
                val raw = call.receiveText()
                println("RAW JSON: $raw")

                val arrElem = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                if (arrElem !is JsonArray) {
                    call.respond(HttpStatusCode.BadRequest, "Payload must be JSON array")
                    return@post
                }

                var ok = 0; var bad = 0
                arrElem.forEach { el ->
                    runCatching { Json.decodeFromJsonElement<Metric>(el) }
                        .onSuccess { m -> queue += m; acceptedCounter.increment(); ok++ }
                        .onFailure { e ->
                            rejectedCounter.increment(); bad++
                            println("⚠️ skipping bad element: $el — ${e.message}")
                        }
                }
                println("✔ queued size = ${queue.size}")
                call.respond(HttpStatusCode.Accepted, mapOf("received" to ok, "skipped" to bad))
            }
        }
    }.start(wait = true)
}

// ───── write batch to InfluxDB ─────
private fun writeToInflux(metrics: List<Metric>) {
    val lineProtocol = metrics.joinToString("\n") { m ->
        val tagStr   = m.tags.entries.joinToString(",") { "${it.key}=${it.value}" }
        val fieldStr = m.fields.entries.joinToString(",") { (k, v) ->
            val value = when {
                v is JsonPrimitive && v.isString -> "\"${v.content}\""
                else -> v.toString()
            }
            "$k=$value"
        }
        if (tagStr.isNotEmpty()) "${m.name},$tagStr $fieldStr ${m.timestamp}" else "${m.name} $fieldStr ${m.timestamp}"
    }

    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:8086/api/v2/write?bucket=Ini&org=Ini&precision=s"))
        .header("Authorization", "Token wa2tworoSNRyl1gOnK0hqwFKOXDL383gKDLF4vB-vjK8RPcm6BOKZSbpECbvOrrN9kXEsJc6kD5mEJtg-oq09w==")
        .header("Content-Type", "text/plain")
        .POST(HttpRequest.BodyPublishers.ofString(lineProtocol))
        .build()

    val client = HttpClient.newHttpClient()
    flushTimer.record(Runnable {
        runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }
            .onSuccess { resp ->
                println("InfluxDB ← ${resp.statusCode()} ${resp.body()}")
                if (resp.statusCode() == 204) flushOkCounter.increment() else flushFailCounter.increment()
            }
            .onFailure { e ->
                flushFailCounter.increment()
                println("Failed to write: ${e.message}")
            }
    })
}