# Deployment Rollback Runbook

## Trigger Conditions

Roll back when **any** of these occur post-deployment:

- Smoke test failure: `/api/health`, `/api/risk/weather-raster`, or directions
  check in `deploy-dev` returns non-2xx.
- Lambda errors: `peachtree-dispatch-dev-api-errors` or
  `peachtree-dispatch-dev-optimizer-errors` alarm enters `ALARM` (> 0 in 5 min).
- API contract break: documented endpoint returns unexpected schema/status.

## Rollback vs. Fix Forward

| Roll back | Fix forward |
|-----------|-------------|
| User-facing errors or data-loss risk | Cosmetic or non-blocking regression |
| Root cause unclear, fix > 30 min | One-line fix identified and tested |
| Multiple alarms firing | Single non-critical alarm, known cause |

## Procedure

1. **Freeze deploys.** Disable the GitHub environment (`dev`/`production`).

2. **Find last good images:**
   ```powershell
   ./scripts/aws.ps1 ecr describe-images `
     --repository-name peachtree-dispatch-dev-platform-api `
     --query 'imageDetails | sort_by(@, &imagePushedAt) | [-3:].[imageTags[0],imagePushedAt]' `
     --output table
   ```
   Repeat for `peachtree-dispatch-dev-api`. Pick the last CI-passing tag.

3. **Trigger rollback.** GitHub Actions > **Roll Back Environment** with:
   - `environment`: `dev` (or `production`)
   - `platform_api_image_uri` / `risk_engine_image_uri`: URIs from step 2

4. **Verify recovery:**
   ```powershell
   curl --fail https://<cloudfront-domain>/api/health
   # Expected: 200 {"status":"UP"}
   ./scripts/aws.ps1 cloudwatch get-metric-statistics `
     --namespace AWS/Lambda --metric-name Errors `
     --dimensions Name=FunctionName,Value=peachtree-dispatch-dev-api `
     --start-time (Get-Date).AddMinutes(-10).ToString("o") `
     --end-time (Get-Date).ToString("o") `
     --period 300 --statistics Sum --query 'Datapoints[0].Sum'
   # Expected: 0 or null
   ```

5. **Confirm alarms clear.** `*-api-errors` and `*-optimizer-errors` back to `OK` within one 5-min evaluation period.

## Post-Rollback Checklist

- [ ] Re-enable the GitHub environment.
- [ ] Open a GitHub issue tagged `incident`: timeline, impact, root cause.
- [ ] Revert or fix the offending commit before next deploy.
- [ ] Confirm DynamoDB data integrity (no partial writes from bad revision).
- [ ] Write postmortem within 48 hours.
