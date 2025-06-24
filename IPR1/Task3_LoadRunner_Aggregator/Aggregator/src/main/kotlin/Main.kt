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

позваляет превращать джесон в этот класс 
@Serializable
data class Metric(
    val name: String,
    val tags: Map<String, String> = emptyMap(),
    val fields: Map<String, JsonElement>,
    val timestamp: Long
)

потокобезопасная очередь
private val queue = CopyOnWriteArrayList<Metric>()

// ───── Micrometer / Prometheus registry ─────
создает регистратор метрик привязанный к прометеус - настройки по умолчанию 
private val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
показывает текущее количество метрик в очереди - переводим размер очереди в дабл тк онли дабл принимает gauge - !! а эт типо не может быть нулл 
private val queueGauge        = prometheusRegistry.gauge("agg_queue_size", queue) { it.size.toDouble() }!!
считает общее число элементов, которые успешно разобрал и положил в очередь ваш POST /metrics.
private val acceptedCounter   = Counter.builder("agg_metrics_accepted_total").register(prometheusRegistry)
 считает, сколько пришедших JSON-элементов отбросилось (не прошли десериализацию).
private val rejectedCounter   = Counter.builder("agg_metrics_rejected_total").register(prometheusRegistry)
увеличивается при каждом успешном пакете метрик, отправленном в InfluxDB.
private val flushOkCounter    = Counter.builder("agg_batch_flush_total").register(prometheusRegistry)
увеличивается, когда пакетная отправка не удалась (HTTP-код ≠ 204 или исключение).
private val flushFailCounter  = Counter.builder("agg_batch_fail_total").register(prometheusRegistry)

метрика для измерения длительности каких-то операций и подсчёта их количества.
 создаёт «таймер» с именем agg_flush_duration_ms. (выгрузка в инфлакс)
и регистрирует его шоб можно было скрейпить
а


fun main() {
    // ───── flush timer (schedule first) ─────
Создаёт таймер, который запускает переданный лямбда-блок по жёсткому расписанию, основываясь на period, независимо от того, сколько заняло прошлое выполнение.
flush имя таймера 
deamon поток-демон - не блокирует завершение прилодения
    fixedRateTimer(name = "flush", daemon = true, initialDelay = 5_000L, period = 15_000L) {
Если в очереди нет метрик — пропускаем итерацию - return@fixedRateTimer означает что пропускаем именно шаг с fixed rate timer
        if (queue.isEmpty()) return@fixedRateTimer
.toList создает лист со всеми элементами очереди
выполняет действие и возвращает сам объект к которому ее применили 
        val batch = queue.toList().also { queue.clear() }
выводит текущее время + batch — это List<Metric> со всеми метриками, которые нужно сейчас отправить в InfluxDB.
        println("[${Instant.now()}] Flushing ${batch.size} metrics → InfluxDB")
        writeToInflux(batch)
    }

    // ───── Ktor HTTP server ─────
запускаем ктор-сервер на движке netty (асинхронный веб фреймворк который позволяет поднимать серверы и клиенты 
    embeddedServer(Netty, port = 8085) {
регистрирует хуйню ContentNegotiation который умеет разбирать тело запроса и формировать тело ответа джесон хмл и тд
С ignoreUnknownKeys = true любой лишний ключ в JSON будет просто пропущен, без ошибки.
explicitNulls - kotlinx.serialization включает в выход все поля, даже если у них значение null: - выключаем это 
json указывает что включить поддержку джесон формата? а Json это уже наш сериализатор 
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }

        routing {
проверка что сервер жив - стучусь на пинг и в ответ получает текст - понг 
            get("/ping") { call.respondText("pong") }
            // Prometheus scrape endpoint
когда наш лоад раннер дергает /mеtrics у нас автоматически полтягиваются актуальные метрики агрегатора 
            get("/metrics") { call.respondText(prometheusRegistry.scrape(), ContentType.Text.Plain) }
Читает из тела POST весь JSON как строку.
            post("/metrics") {
                val raw = call.receiveText()
                println("RAW JSON: $raw")
Пробуем распарсить в древовидное представление джесона? если нет то нул. runCatching типо как try except 
                val arrElem = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
на всякий случай еще проверка на джесон 
                if (arrElem !is JsonArray) {
                    call.respond(HttpStatusCode.BadRequest, "Payload must be JSON array")
завершаем тока метод пост 
                    return@post
                }

                var ok = 0; var bad = 0
Проходим по всем элементам JSON-массива.
                arrElem.forEach { el ->
хуйня которая десериализует каждый джесон объект в Метрик объект 
                    runCatching { Json.decodeFromJsonElement<Metric>(el) }
ес все успешно то добавляет элемент в очередь для инфлакса ну и параметры инкрементируем
                        .onSuccess { m -> queue += m; acceptedCounter.increment(); ok++ }
ес хуйня - ошибку выводим и параметры бэда инкрементируем 
                        .onFailure { e ->
                            rejectedCounter.increment(); bad++
                            println("⚠️ skipping bad element: $el — ${e.message}")
                        }
                }
                println("✔ queued size = ${queue.size}")
Означает статус 202, то есть «запрос принят, но ещё не обработан окончательно». создает котлин мап с двумя парами - скок получено и скок проебано 
                call.respond(HttpStatusCode.Accepted, mapOf("received" to ok, "skipped" to bad))
            }
        }
бесконечный цикл выполнения ктор 
    }.start(wait = true)
}

// ───── write batch to InfluxDB ─────
Инфлакс ждет данные в формате lineProtocol где одна метрика = одна строка - ну и короче там есть тег (ну типо инстанс или дев/енв) и field это уже наша метрика типо цпу=3.66
private fun writeToInflux(metrics: List<Metric>) {
пробегаемся по списку метрик 
    val lineProtocol = metrics.joinToString("\n") { m ->
метод entries возвращает пары ключ-значение из нашего мапа 
        val tagStr   = m.tags.entries.joinToString(",") { "${it.key}=${it.value}" }
        val fieldStr = m.fields.entries.joinToString(",") { (k, v) ->
            val value = when {
проверяем что v точно явл строковым примитивом 
для line-protocol нужны кавычки поэтому пишем "\"${v.content}\""
                v is JsonPrimitive && v.isString -> "\"${v.content}\""
но ес числовое или булевое поле - то не нужны кавычки
                else -> v.toString()
            }
пишет типо цпу=3.14
            "$k=$value"
        }
cpu_usage,env=dev,host=app1 usage=42.5 1623679200
app_error_rate error=0.0 1623679200
        if (tagStr.isNotEmpty()) "${m.name},$tagStr $fieldStr ${m.timestamp}" else "${m.name} $fieldStr ${m.timestamp}"
    }
формирует запрос хттп
    val request = HttpRequest.newBuilder()
bucket - имя бд в инфлакс
        .uri(URI.create("http://localhost:8086/api/v2/write?bucket=Ini&org=Ini&precision=s"))
токен на сайте выдавали 
        .header("Authorization", "Token wa2tworoSNRyl1gOnK0hqwFKOXDL383gKDLF4vB-vjK8RPcm6BOKZSbpECbvOrrN9kXEsJc6kD5mEJtg-oq09w==")
заголовок формата
        .header("Content-Type", "text/plain")
класс-фабрика который берет объект и возвращает его же нужного нам типа ofString - статический метод который создает из нашей строки line-protocol объект, реализующий интерфейс BodyPublishers
        .POST(HttpRequest.BodyPublishers.ofString(lineProtocol))
        .build()
клиент для выполнения http запроса 
    val client = HttpClient.newHttpClient()
оборачивается в runnable для запуска таймера
    flushTimer.record(Runnable {
это функция из библиотеки stdlib, которая принимает лямбду и возвращает объект Result<T>, где T — тип возвращаемого значения блока.
возвращает ответ в формате строки 
        runCatching { client.send(request, HttpResponse.BodyHandlers.ofString()) }
resp - объект возвращается методом client.send(request, HttpResponse.BodyHandlers.ofString())
            .onSuccess { resp ->
ес все норм - пишет код ответа и тело ответа 
                println("InfluxDB ← ${resp.statusCode()} ${resp.body()}")
ес статус 204 - то все ок инкрементируем - ес не ок то инкрементируем другое 
204 означает, что запрос успешно обработан, но в теле ответа нет никакой дополнительной информации (контент отсутствует).
                if (resp.statusCode() == 204) flushOkCounter.increment() else flushFailCounter.increment()
            }
e - exception 
            .onFailure { e ->
                flushFailCounter.increment()
                println("Failed to write: ${e.message}")
            }
    })
}