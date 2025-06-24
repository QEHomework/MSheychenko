import time
import random
import requests
from datetime import datetime

AGGREGATOR_URL = "http://localhost:8085/metrics"
HEADERS = {"Content-Type": "text/plain"}

def generate_line_protocol():
    timestamp = int(datetime.utcnow().timestamp())
    metrics = [
        f"app_cpu_usage,env=dev value={random.uniform(0, 100):.2f} {timestamp}",
        f"app_ram_usage_mb,env=dev value={random.randint(500, 4000)} {timestamp}",
        f"app_active_users,env=dev value={random.randint(10, 100)} {timestamp}",
        f"app_requests_per_second,env=dev value={random.uniform(10, 200):.2f} {timestamp}",
        f"app_errors_minute,env=dev value={random.randint(0, 20)} {timestamp}",
        f"app_queue_size,env=dev value={random.randint(0, 100)} {timestamp}"
    ]
    return "\n".join(metrics)

def send_metrics():
    while True:
        payload = generate_line_protocol()
        try:
            response = requests.post(AGGREGATOR_URL, headers=HEADERS, data=payload, timeout=5)
            print(f"[{datetime.now()}] Sent metrics. Status: {response.status_code}")
        except Exception as e:
            print(f"[{datetime.now()}] Failed to send metrics: {e}")
        time.sleep(10)

if __name__ == "__main__":
    send_metrics()