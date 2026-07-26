# Disaster Recovery Game Day

Structured failure-simulation exercises for the FreightScaler platform.
Game days validate that recovery procedures actually work under realistic
pressure, not just on paper. Run quarterly or after significant
infrastructure changes.

**Status: template — no game day has been conducted yet.**

---

## Scope

| Component | Recovery mechanism | Target RTO | Target RPO |
|-----------|-------------------|------------|------------|
| DynamoDB (operational) | PITR restore to new table + cutover | < 60 min | < 5 min |
| S3 (web assets) | Versioning + CloudFront invalidation | < 15 min | 0 (versioned) |
| S3 (weather snapshots) | Hourly collector re-run | < 60 min | < 60 min |
| Aurora PostgreSQL (SaaS) | Automated snapshot restore | < 30 min | < 5 min |
| CloudFront distribution | Terraform re-apply from state | < 30 min | IaC-managed |
| Lambda functions | Redeploy last known-good image | < 15 min | Immutable images |

---

## Failure Scenarios

### Scenario 1: Accidental DynamoDB Data Deletion

**Inject:** Delete a batch of items from the operational table (use a test
PK prefix, never production tenant data).

**Steps:**
1. Record the current timestamp (this is the "incident time").
2. Delete 10-50 test items with a known PK prefix (`DRILL#`).
3. Follow [dynamodb-recovery.md](runbooks/dynamodb-recovery.md) or run:
   ```powershell
   ./scripts/dr_restore_test.ps1 -RestoreDateTime "<incident-time>"
   ```
4. Verify the deleted items exist in the restored table.
5. Measure time from "incident detected" to "data verified in restored table".

**Success criteria:**
- Restored table contains all deleted items.
- RTO < 60 minutes.
- Contract tests pass against the restored table.

### Scenario 2: Bad Deployment (Lambda Regression)

**Inject:** Deploy a Lambda image with a known-breaking change (e.g., a
missing environment variable or a handler that returns 500).

**Steps:**
1. Deploy the broken image to dev via the standard workflow.
2. Confirm `/api/health` returns errors.
3. Execute the rollback procedure from
   [deployment-rollback.md](runbooks/deployment-rollback.md).
4. Verify health returns to normal.

**Success criteria:**
- Rollback completes in < 15 minutes.
- No data loss or corruption.
- Alarms return to OK state within 5 minutes of rollback.

### Scenario 3: S3 Web Bucket Corruption

**Inject:** Overwrite `index.html` in the web bucket with garbage content.

**Steps:**
1. Upload a broken `index.html` to the S3 web bucket.
2. Invalidate CloudFront (`/*`) to force edge refresh.
3. Confirm the site serves broken content.
4. Restore the previous version using S3 versioning:
   ```powershell
   ./scripts/aws.ps1 s3api list-object-versions --bucket <web-bucket> --prefix index.html
   ./scripts/aws.ps1 s3api copy-object --bucket <web-bucket> --key index.html `
     --copy-source "<web-bucket>/index.html?versionId=<good-version>"
   ```
5. Invalidate CloudFront again and verify.

**Success criteria:**
- Site restored in < 15 minutes.
- Correct content served at all edge locations.

### Scenario 4: Full Region Failure (Tabletop)

**Inject:** None (discussion-only). Simulate `us-east-1` being unavailable.

**Steps:**
1. Walk through what would happen: CloudFront serves cached content, API
   returns errors, Lambda invocations fail.
2. Identify what would need to change for multi-region: Route 53 health
   checks, DynamoDB global tables, S3 cross-region replication.
3. Document gaps and cost estimates for multi-region readiness.

**Success criteria:**
- Gaps documented with severity ratings.
- Cost estimate for multi-region recorded in an ADR.

---

## Game Day Procedure

1. **Schedule** a 2-hour window. Notify anyone who might see staging alerts.
2. **Pre-check:** confirm monitoring dashboards and alarms are healthy.
3. **Run scenarios** in order (1-3 hands-on, 4 tabletop).
4. **Record** each scenario's actual RTO, issues encountered, and surprises.
5. **Debrief:** what worked, what didn't, what needs automation.
6. **Update** the runbooks with lessons learned.
7. **Log results** in the table below and in `docs/dr-drill-results.md`.

---

## Game Day History

| Date | Scenarios run | RTO (DynamoDB) | Issues found | Follow-ups |
|------|--------------|----------------|--------------|------------|
| _(none yet)_ | — | — | — | — |

---

## References

- [DynamoDB Recovery Runbook](runbooks/dynamodb-recovery.md)
- [Deployment Rollback Runbook](runbooks/deployment-rollback.md)
- [Data Retention & Backup Policy](data-retention.md)
- [DR Restore Test Script](../scripts/dr_restore_test.ps1)
