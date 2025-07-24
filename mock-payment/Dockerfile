FROM python:3.11-slim

WORKDIR /main

COPY main.py .

RUN pip install flask prometheus_client

ENV PORT=1337

CMD ["python", "main.py"]

