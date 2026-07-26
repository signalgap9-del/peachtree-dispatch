import os

EXCLUDE_DIRS = {".git", "node_modules", "dist", "build", "target", ".tools",
                ".pytest_cache", "__pycache__", ".understand-anything", ".terraform",
                ".idea", ".vscode", "tmp"}
CODE_EXT = {".java", ".py", ".ts", ".tsx", ".sql", ".css", ".html"}

def classify(path):
    p = path.replace("\\", "/").lower()
    if "/test/" in p or "/tests/" in p or p.endswith("test.py") or "test_" in os.path.basename(p).lower() or p.endswith("tests.java") or "test.java" in p:
        return "test"
    return "prod"

stats = {}  # (ext, kind) -> [files, lines]
for root, dirs, files in os.walk("."):
    dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
    # skip docs and generated assets
    rel = root.replace("\\", "/").lower()
    if "/docs/" in rel or rel.startswith("./docs") or "/public/" in rel:
        continue
    for fn in files:
        ext = os.path.splitext(fn)[1].lower()
        if ext not in CODE_EXT:
            continue
        path = os.path.join(root, fn)
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                n = sum(1 for _ in f)
        except Exception:
            continue
        kind = classify(path)
        key = (ext, kind)
        if key not in stats:
            stats[key] = [0, 0]
        stats[key][0] += 1
        stats[key][1] += n

prod_total = sum(v[1] for k, v in stats.items() if k[1] == "prod")
test_total = sum(v[1] for k, v in stats.items() if k[1] == "test")

print(f"{'ext':<8}{'kind':<8}{'files':>8}{'lines':>10}")
for (ext, kind) in sorted(stats.keys()):
    f, l = stats[(ext, kind)]
    print(f"{ext:<8}{kind:<8}{f:>8}{l:>10}")
print("-" * 34)
print(f"{'PRODUCTION':<16}{'':>8}{prod_total:>10}")
print(f"{'TESTS':<16}{'':>8}{test_total:>10}")
print(f"{'TOTAL':<16}{'':>8}{prod_total + test_total:>10}")
