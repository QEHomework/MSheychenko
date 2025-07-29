import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
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

private val queue = CopyOnWriteArrayList<String>()
private val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
private val queueGauge        = prometheusRegistry.gauge("agg_queue_size", queue) { it.size.toDouble() }!!
private val acceptedCounter   = Counter.builder("agg_metrics_accepted_total").register(prometheusRegistry)
private val rejectedCounter   = Counter.builder("agg_metrics_rejected_total").register(prometheusRegistry)
private val flushOkCounter    = Counter.builder("agg_batch_flush_total").register(prometheusRegistry)
private val flushFailCounter  = Counter.builder("agg_batch_fail_total").register(prometheusRegistry)
private val flushTimer: Timer = Timer.builder("agg_flush_duration_ms").register(prometheusRegistry)

fun main() {
    fixedRateTimer(name = "flush", daemon = true, initialDelay = 5_000L, period = 15_000L) {
        if (queue.isEmpty()) return@fixedRateTimer
        val batch = queue.toList().also { queue.clear() }
        println("[${Instant.now()}] Flushing ${batch.size} metrics → InfluxDB")
        writeToInflux(batch)
    }

    embeddedServer(Netty, port = 8085) {

        routing {
            get("/ping") { call.respondText("pong") }
            get("/metrics") { call.respondText(prometheusRegistry.scrape(), ContentType.Text.Plain) }

            post("/metrics") {
                val body = call.receiveText()
                val lines = body.trim().lines()
                var ok = 0; var bad = 0
                lines.forEach { line ->
                    if (line.matches(Regex("^[^,\\s]+(,[^=]+=[^,\\s]+)* [^=]+=[^\\s]+ \\d+$"))) {
                        queue += line
                        acceptedCounter.increment()
                        ok++
                    } else {
                        rejectedCounter.increment()
                        bad++
                        println("bad line: $line")
                    }
                }
                call.respondText("Accepted: $ok, Rejected: $bad", status = HttpStatusCode.Accepted)
            }
        }
    }.start(wait = true)
}

private fun writeToInflux(metrics: List<String>) {
    val body = metrics.joinToString("\n")
    val request = HttpRequest.newBuilder()
        .uri(URI.create("http://localhost:8086/api/v2/write?org=admin&bucket=Ini&precision=s"))
        .header("Authorization", "Token W80d1pH6_J-EtSR6b5RRuInh04WC6QMlHp_MIYdnE-C1jWJRPYgRIz__9yojR_BKCAJOUNDAif2nvFh3NmwtvQ==")
        .header("Content-Type", "text/plain")
        .POST(HttpRequest.BodyPublishers.ofString(body))
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
