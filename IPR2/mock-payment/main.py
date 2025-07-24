from flask import Flask, request, jsonify
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
import random, time

app = Flask(__name__)

# --- Prometheus metrics ---
REQ_TOTAL      = Counter('mock_payment_requests_total',   'All payment requests')
REQ_FAILURES   = Counter('mock_payment_failures_total',   'Failed (402) payments')
REQ_DURATION   = Histogram('mock_payment_duration_seconds','Payment handler duration',
                           buckets=(0.01,0.05,0.1,0.2,0.5,1,2,5))

ERROR_PROB = float(app.config.get('ERROR_PROB', 0.01))

@app.post("/mock/payment")
def pay():
    start = time.time()
    REQ_TOTAL.inc()

    data = request.get_json(silent=True) or {}
    user   = data.get("user", "unknown")
    amount = data.get("amount", "0")

    if random.random() < ERROR_PROB:
        REQ_FAILURES.inc()
        REQ_DURATION.observe(time.time() - start)
        return jsonify({"status": "fail", "reason": "Payment Required"}), 402

    REQ_DURATION.observe(time.time() - start)
    return jsonify({"status": "success", "user": user, "amount": amount}), 200

@app.get("/metrics")
def metrics():
    return generate_latest(), 200, {'Content-Type': CONTENT_TYPE_LATEST}

if __name__ == "__main__":
    import os
    port = int(os.environ.get("PORT", 1337))
    app.run(host="0.0.0.0", port=port)