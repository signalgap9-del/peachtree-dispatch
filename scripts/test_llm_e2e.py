"""AtmosPath LLM E2E Test - tests API directly, no Docker needed."""
import json, os, sys, time, urllib.request, urllib.error

CONFIG_PATH = os.path.expanduser("~/.opencodex/config.json")
BASE_URL = "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1"
MODEL = "qwen3.8-max-preview"

def load_api_key():
    if os.environ.get("LLM_API_KEY"):
        return os.environ["LLM_API_KEY"]
    with open(CONFIG_PATH, "r", encoding="utf-8") as f:
        return json.load(f)["providers"]["alibaba-token-plan-intl"]["apiKey"]

def api_call(endpoint, payload, api_key):
    url = f"{BASE_URL}/{endpoint}"
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    })
    start = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            result = json.loads(resp.read().decode("utf-8"))
            return result, round((time.time() - start) * 1000)
    except urllib.error.HTTPError as e:
        print(f"  HTTP {e.code}: {e.read().decode('utf-8', errors='replace')[:200]}")
        return None, 0
    except (TimeoutError, OSError) as e:
        print(f"  Timeout/Network error: {e}")
        return None, 0

def test_chat(api_key):
    print("\n=== Test 1: Chat Completion ===")
    r, ms = api_call("chat/completions", {"model": MODEL, "messages": [
        {"role": "system", "content": "You are a driving safety analyst. Answer concisely."},
        {"role": "user", "content": "What is the main risk of driving through a flood zone?"}
    ], "max_tokens": 100, "temperature": 0.3}, api_key)
    if r:
        c = r["choices"][0]["message"]["content"]
        u = r.get("usage", {})
        print(f"  ({ms}ms) {c[:150]}")
        print(f"  Tokens: in={u.get('prompt_tokens')}, out={u.get('completion_tokens')}")
        print("  PASS"); return True
    print("  FAIL"); return False

def test_korean(api_key):
    print("\n=== Test 2: Korean Response ===")
    r, ms = api_call("chat/completions", {"model": MODEL, "messages": [
        {"role": "system", "content": "Respond in Korean. Driving safety analyst."},
        {"role": "user", "content": "시애틀에서 마이애미까지 트럭으로 가는데 폭풍이 오고 있어. 어떤 경로를 추천해?"}
    ], "max_tokens": 100, "temperature": 0.3}, api_key)
    if r:
        c = r["choices"][0]["message"]["content"]
        kr = any("\uac00" <= ch <= "\ud7a3" for ch in c)
        print(f"  ({ms}ms) {c[:200]}")
        print(f"  Korean: {kr}")
        print("  PASS" if kr else "  FAIL"); return kr
    print("  FAIL"); return False

def test_nl2opt(api_key):
    print("\n=== Test 3: NL2Opt Extraction ===")
    schema = json.dumps({"stops": [{"name": "str", "type": "origin|dest"}],
        "vehicle": {"type": "str", "hazmat": "bool"},
        "departure": {"earliest": "HH:MM"},
        "softConstraints": [{"type": "str", "target": "str", "weight": "0-1"}],
        "objective": "min_risk|min_time|balanced"})
    r, ms = api_call("chat/completions", {"model": MODEL, "messages": [
        {"role": "system", "content": f"Extract VRP constraints as JSON only. Schema: {schema}"},
        {"role": "user", "content": "시애틀에서 8시 출발, 트럭 유해물질, 폭풍 전 도착, 고속도로 회피"}
    ], "max_tokens": 500, "temperature": 0.1}, api_key)
    if r:
        raw = r["choices"][0]["message"]["content"].strip()
        if raw.startswith("```"):
            lines = raw.split("\n")
            raw = "\n".join(lines[1:-1] if lines[-1].strip() == "```" else lines[1:])
        try:
            c = json.loads(raw.strip())
            hazmat = c.get("vehicle", {}).get("hazmat", False)
            dep = "earliest" in str(c.get("departure", {}))
            avoid = any(x.get("type") == "avoid" for x in c.get("softConstraints", []))
            obj = c.get("objective", "N/A")
            print(f"  ({ms}ms) hazmat={hazmat}, departure={dep}, avoid_highway={avoid}, objective={obj}")
            ok = hazmat and dep
            print(f"  {'PASS' if ok else 'PARTIAL'}"); return ok
        except json.JSONDecodeError as e:
            print(f"  JSON error: {e}")
            print(f"  Raw: {raw[:300]}")
            print("  FAIL"); return False
    print("  FAIL"); return False

def test_embedding(api_key):
    print("\n=== Test 4: Embedding ===")
    for model in ["text-embedding-v3", "text-embedding-v2", "text-embedding-v1"]:
        r, ms = api_call("embeddings", {"model": model,
            "input": ["Route I-95 South, risk 85, flood warning"]}, api_key)
        if r:
            dims = len(r["data"][0]["embedding"])
            print(f"  {model}: {dims} dims ({ms}ms)")
            print("  PASS"); return True
    print("  FAIL (no embedding model)"); return False

def test_intent(api_key):
    print("\n=== Test 5: Intent Classification ===")
    cases = [
        ("시애틀에서 마이애미까지 경로 알려줘", "route_plan"),
        ("2시간 뒤에 출발하면?", "modify"),
        ("어떤 경로가 더 안전해?", "compare"),
        ("배송 5건 최적 순서 짜줘", "fleet_optimize"),
        ("지금 전국 기상 위험 어때?", "explain"),
    ]
    passed = 0
    for msg, expected in cases:
        r, ms = api_call("chat/completions", {"model": MODEL, "messages": [
            {"role": "system", "content": "Classify: route_plan, modify, compare, fleet_optimize, explain. Reply ONLY the intent."},
            {"role": "user", "content": msg}
        ], "max_tokens": 20, "temperature": 0}, api_key)
        if r:
            got = r["choices"][0]["message"]["content"].strip().lower()
            ok = expected in got
            print(f"  [{'OK' if ok else 'MISS'}] \"{msg[:25]}\" -> {got} ({ms}ms)")
            if ok: passed += 1
    print(f"  {passed}/{len(cases)} correct")
    return passed >= 3

def main():
    print("=" * 60)
    print("AtmosPath LLM E2E Test")
    print("=" * 60)
    try:
        api_key = load_api_key()
        print(f"API Key: loaded ({len(api_key)} chars)")
        print(f"Model: {MODEL}")
    except Exception as e:
        print(f"API key load failed: {e}"); sys.exit(1)
    results = {}
    results["chat"] = test_chat(api_key)
    results["korean"] = test_korean(api_key)
    results["nl2opt"] = test_nl2opt(api_key)
    results["embedding"] = test_embedding(api_key)
    results["intent"] = test_intent(api_key)
    print("\n" + "=" * 60)
    print("RESULTS")
    for name, ok in results.items():
        print(f"  {'PASS' if ok else 'FAIL'} - {name}")
    p = sum(1 for v in results.values() if v)
    print(f"\n  {p}/{len(results)} passed")
    print("=" * 60)
    sys.exit(0 if p == len(results) else 1)

if __name__ == "__main__":
    main()
