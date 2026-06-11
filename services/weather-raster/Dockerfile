FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends libeccodes0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY pipeline.py .

ENTRYPOINT ["python", "pipeline.py"]
