#!/usr/bin/env python3
import json
import time
import random
import requests
from datetime import datetime

AGGREGATOR_URL = "http://localhost:8085/metrics"

def generate_metric(name: str, value: float, tags=None):
    if tags is None:
        tags = {}
    return {
        "name": name,
        "tags": tags,
        "fields": {
            "value": value
        },
        "timestamp": int(datetime.utcnow().timestamp())
    }

def send_metrics():
    headers = {
        "Content-Type": "application/json"
    }

    while True:
        metrics = [
            generate_metric("app_requests_total", random.randint(100, 200), {"env": "dev"}),
            generate_metric("app_requests_successful", random.randint(80, 100), {"env": "dev"}),
            generate_metric("app_requests_failed", random.randint(0, 20), {"env": "dev"}),
            generate_metric("app_rate_limit", 2_147_483_647, {"env": "dev"}),
            generate_metric("app_error_rate", random.uniform(0, 10), {"env": "dev"}),
        ]
        try:
            response = requests.post(AGGREGATOR_URL, headers=headers, json=metrics, timeout=5)
            print(f"[{datetime.now()}] Sent {len(metrics)} metrics. Status: {response.status_code}")
        except Exception as e:
            print(f"[{datetime.now()}] Failed to send metrics: {e}")

        time.sleep(10)

if __name__ == '__main__':
    send_metrics()