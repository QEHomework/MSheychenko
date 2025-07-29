# Полная настройка (ручной запуск)

Этот репозиторий содержит PoC системы мониторинга с ручным поднятием всех компонентов (кроме Pushgateway в Docker).

**Компоненты**:

* **Системные метрики** (CPU, RAM, Disk) → Bash скрипт → Pushgateway (Docker)
* **Java Stub Service** на Spring Boot + Micrometer → `/actuator/metrics/prometheus`
* **Kotlin Aggregator** на Ktor → HTTP POST → InfluxDB
* **InfluxDB** (локальная установка) → хранение метрик приложения и агрегатора
* **Prometheus** (локальная установка) → скрейп Pushgateway
* **Grafana** (локальная установка) → визуализация метрик
* **Load Runner** (Python) → генерация трафика в агрегатор

---

## Структура репозитория

```
MSheychenko/
├── grafana/
│   └── dashboards/
│       ├── full_observability_dashboard.json
│       └── aggregator_dashboard.json
├── scripts/
│   ├── push_sys_metrics.sh      # Bash → Pushgateway
│   └── load_runner.py           # Python → Aggregator
├── aggregator/                  # Kotlin-агрегатор
│   ├── src/main/kotlin/...
│   └── build.gradle.kts
├── stub-service/                # Java Spring Boot заглушка
│   └── TheNewStubService-main/
├── prometheus.yml               # Конфиг Prometheus
└── README.md                    # Этот файл
```

---

## Предварительные установки

1. **InfluxDB** (v2.x)

   ```bash
   brew install influxdb
   influxd &
   # В другом терминале
   influx setup --bucket metrics --org Ini --username admin --password admin --token <TOKEN>
   ```

2. **Prometheus**

   ```bash
   brew install prometheus
   # разместить prometheus.yml рядом с binary
   prometheus --config.file=prometheus.yml &
   ```

3. **Grafana**

   ```bash
   brew install grafana
   brew services start grafana
   ```

4. **Pushgateway** (Docker)

   ```bash
   docker run -d --name pushgateway -p 9091:9091 prom/pushgateway
   ```

5. **Python-зависимости**

   ```bash
   pip install requests
   ```
6. **Config Prometheus**

   ```prometheus.yml
   global:
   scrape_interval: 15s
   evaluation_interval: 15s

   external_labels:
     monitor: "codelab-monitor"
   scrape_configs:
     - job_name: prometheus
     static_configs:
       - targets: ["localhost:9090"]

   - job_name: cadvisor
     static_configs:
       - targets: ["localhost:8081"]

   - job_name: stub-service
     metrics_path: /actuator/metrics/prometheus
     static_configs:
      - targets: ["localhost:8080"]
   - job_name: 'aggregator'
     metrics_path: /metrics
     static_configs:
      - targets: ['localhost:8085']
   - job_name: 'bash_host'
     honor_labels: true
     static_configs:
      - targets: ['localhost:9091'] 
  ```



---

## 1. Системные метрики → Pushgateway

Запускаем `/push_sys_metrics.sh`:

```bash
chmod +x scripts/push_sys_metrics.sh
./push_sys_metrics.sh
```

Проверяем:

```bash
curl -s http://localhost:9091/metrics | grep system_cpu_usage_percent
```

---

## 2. Java Stub Service

1. Перейдите в `stub-service/TheNewStubService-main`.
2. В `application.properties` добавьте:

   ```properties
   management.endpoints.web.exposure.include=*
   management.endpoint.prometheus.enabled=true
   management.endpoints.web.base-path=/actuator
   management.endpoints.web.path-mapping.prometheus=metrics/prometheus
   ```
3. Запустите:

   ```bash
   ./mvnw spring-boot:run
   ```
4. Откройте: `http://localhost:8080/actuator/metrics/prometheus`

---

## 3. Kotlin Aggregator → InfluxDB

1. Перейдите в `aggregator/`.
2. Укажите токен и URL InfluxDB в `Main.kt`.
3. Соберите и запустите:

   ```bash
   ./gradlew run
   ```
4. Принимайте POST на `http://localhost:8085/metrics`.

---

## 4. Python Load Runner

```bash
main.py
```

Симулирует метрики и отправляет в агрегатор.

---

## 5. Настройка Grafana

1. Перейдите в Grafana: [http://localhost:3000](http://localhost:3000) (admin/admin)
2. **Dashboards → Import**
3. Загрузите `grafana/dashboards/dashboard_task_3.json`
4. Выберите источник данных **Prometheus** → Import


---

## 6. Проверка работы

* **Prometheus → /targets**: job `bash_host` UP, `aggregator` UP
* **Prometheus UI (Graph)**: запрос `system_cpu_usage_percent` и др.
* **InfluxDB Data Explorer**: проверить метрики агрегатора
* **Grafana**: убедиться в работе дашбордов

---

## Полезные команды и отладка

* Сбросить метрики Pushgateway:

  ```bash
  curl -X DELETE http://localhost:9091/metrics/job/bash_host
  ```
* Просмотр лога сервиса:

  ```bash
  ps aux | grep push_sys_metrics.sh
  ```

---

