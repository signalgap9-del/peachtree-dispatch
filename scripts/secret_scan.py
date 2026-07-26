import os, re, subprocess, sys

sys.stdout.reconfigure(encoding='utf-8')

# Patterns: (name, regex). Values will be redacted in output.
PATTERNS = [
    ("AWS Access Key ID", r"AKIA[0-9A-Z]{16}"),
    ("AWS Secret Access Key", r"(?i)aws_secret_access_key\s*[=:]\s*['\"]?[A-Za-z0-9/+=]{40}"),
    ("AWS Session Token", r"(?i)aws_session_token\s*[=:]\s*['\"]?[A-Za-z0-9/+=]{40,}"),
    ("Generic API Key assignment", r"(?i)(api[_-]?key|apikey)\s*[=:]\s*['\"][A-Za-z0-9_\-]{16,}['\"]"),
    ("Generic Secret assignment", r"(?i)(secret|secret[_-]?key|client[_-]?secret)\s*[=:]\s*['\"][A-Za-z0-9_\-]{12,}['\"]"),
    ("Generic Token assignment", r"(?i)(token|access[_-]?token|auth[_-]?token)\s*[=:]\s*['\"][A-Za-z0-9_\-\.]{16,}['\"]"),
    ("Bearer token", r"(?i)bearer\s+[A-Za-z0-9_\-\.]{20,}"),
    ("Private Key block", r"-----BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"),
    ("Lemon Squeezy key", r"(?i)lemonsqueezy[_-]?(api[_-]?key|secret)\s*[=:]\s*['\"]?[A-Za-z0-9_\-]{16,}"),
    ("Stripe key", r"sk_(live|test)_[A-Za-z0-9]{16,}"),
    ("GitHub token", r"gh[pousr]_[A-Za-z0-9]{36,}"),
    ("Slack token", r"xox[baprs]-[A-Za-z0-9-]{10,}"),
    ("Password assignment", r"(?i)(password|passwd|pwd)\s*[=:]\s*['\"][^'\"]{6,}['\"]"),
    ("Cloudflare token", r"(?i)(cfut_|cfk_)[A-Za-z0-9]{20,}"),
    ("JWT", r"eyJ[A-Za-z0-9_\-]{10,}\.eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"),
]

EXCLUDE_DIRS = {".git", "node_modules", "dist", "build", ".tools", ".pytest_cache",
                "__pycache__", ".understand-anything", "target", ".terraform"}
EXCLUDE_EXTS = {".png", ".jpg", ".jpeg", ".gif", ".ico", ".pdf", ".woff", ".woff2",
                ".zip", ".jar", ".class", ".exe", ".dll", ".so", ".lock"}
# Files that legitimately contain example/placeholder secrets
ALLOWLIST_FILES = {"secret_scan.py", ".env.example"}

def redact(value):
    value = value.strip()
    if len(value) <= 8:
        return "[REDACTED]"
    return value[:4] + "..." + value[-2:] + " (len " + str(len(value)) + ")"

findings = []
scanned = 0
for root, dirs, files in os.walk("."):
    dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
    for fn in files:
        if os.path.splitext(fn)[1].lower() in EXCLUDE_EXTS:
            continue
        if fn in ALLOWLIST_FILES:
            continue
        path = os.path.join(root, fn)
        try:
            with open(path, encoding="utf-8", errors="ignore") as f:
                lines = f.readlines()
        except Exception:
            continue
        scanned += 1
        for i, line in enumerate(lines, 1):
            for name, pattern in PATTERNS:
                for m in re.finditer(pattern, line):
                    findings.append((path, i, name, redact(m.group(0))))

print(f"Scanned {scanned} files in working tree")
print(f"Findings: {len(findings)}")
print("=" * 70)
for path, line, name, val in findings:
    print(f"{path}:{line}  [{name}]  {val}")
