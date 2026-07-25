#!/usr/bin/env python3
"""Seed existing route_risk_observation and alert_event rows with embeddings.

Usage:
    python scripts/seed_embeddings.py \
        --db-url postgresql://user:pass@localhost:5432/atmospath \
        --api-url http://localhost:4000

Requires: psycopg2, requests
"""

import argparse
import json
import sys

import psycopg2
import psycopg2.extras
import requests

EMBEDDING_MODEL = "text-embedding-v3"
BATCH_SIZE = 20


def call_embedding_api(api_url: str, texts: list[str], api_key: str = "") -> list[list[float]]:
    """Call the OpenAI-compatible /v1/embeddings endpoint."""
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    resp = requests.post(
        f"{api_url}/v1/embeddings",
        json={"model": EMBEDDING_MODEL, "input": texts},
        headers=headers,
        timeout=60,
    )
    resp.raise_for_status()
    data = resp.json()["data"]
    return [item["embedding"] for item in data]


def build_observation_text(row: dict) -> str:
    factors = row.get("factors") or "{}"
    if isinstance(factors, dict):
        factors = json.dumps(factors)
    return (
        f"Route {row['saved_route_id']}, "
        f"risk {row.get('risk_score', 0)}/100 ({row.get('risk_level', 'UNKNOWN')}), "
        f"factors: {factors}"
    )


def build_alert_text(row: dict) -> str:
    return (
        f"{row.get('severity', 'UNKNOWN')} alert: "
        f"risk score {row.get('risk_score', 0)}, "
        f"state {row.get('state', 'UNKNOWN')}, "
        f"triggered at {row.get('triggered_at', '')}"
    )


def to_vector_literal(embedding: list[float]) -> str:
    return "[" + ",".join(str(v) for v in embedding) + "]"


def seed_table(conn, api_url: str, api_key: str, table: str, text_builder, id_columns: list[str]):
    """Seed one table with embeddings. Returns (total, embedded, failed)."""
    cur = conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor)
    cur.execute(f"SELECT * FROM {table} WHERE embedding IS NULL")
    rows = cur.fetchall()
    total = len(rows)
    embedded = 0
    failed = 0

    for i in range(0, total, BATCH_SIZE):
        batch = rows[i : i + BATCH_SIZE]
        texts = [text_builder(row) for row in batch]
        try:
            embeddings = call_embedding_api(api_url, texts, api_key)
        except Exception as exc:
            print(f"  [ERROR] Batch {i // BATCH_SIZE + 1} failed: {exc}", file=sys.stderr)
            failed += len(batch)
            continue

        for row, emb in zip(batch, embeddings):
            record_id = ":".join(str(row[col]) for col in id_columns)
            where_clause = " AND ".join(f"{col} = %s" for col in id_columns)
            params = [to_vector_literal(emb)] + [row[col] for col in id_columns]
            cur.execute(
                f"UPDATE {table} SET embedding = %s::vector WHERE {where_clause}",
                params,
            )
            cur.execute(
                """INSERT INTO embedding_metadata (table_name, record_id, embedding_model)
                   VALUES (%s, %s, %s)
                   ON CONFLICT (table_name, record_id, embedding_model) DO UPDATE
                   SET embedding_version = embedding_metadata.embedding_version + 1,
                       created_at = now()""",
                (table, record_id, EMBEDDING_MODEL),
            )
            embedded += 1

        conn.commit()

    cur.close()
    return total, embedded, failed


def main():
    parser = argparse.ArgumentParser(description="Seed embeddings for existing data")
    parser.add_argument("--db-url", required=True, help="PostgreSQL connection URL")
    parser.add_argument("--api-url", default="http://localhost:4000", help="LiteLLM proxy URL")
    parser.add_argument("--api-key", default="", help="API key for the embedding endpoint")
    args = parser.parse_args()

    conn = psycopg2.connect(args.db_url)
    print(f"Connected to database. Seeding embeddings via {args.api_url}")

    print("\n--- route_risk_observation ---")
    total, embedded, failed = seed_table(
        conn, args.api_url, args.api_key,
        "route_risk_observation", build_observation_text,
        ["saved_route_id", "time"],
    )
    print(f"  Total: {total}, Embedded: {embedded}, Failed: {failed}")

    print("\n--- alert_event ---")
    total, embedded, failed = seed_table(
        conn, args.api_url, args.api_key,
        "alert_event", build_alert_text,
        ["id"],
    )
    print(f"  Total: {total}, Embedded: {embedded}, Failed: {failed}")

    conn.close()
    print("\nDone.")


if __name__ == "__main__":
    main()
