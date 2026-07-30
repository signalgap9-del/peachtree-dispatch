// Settlement saga status screen (Phase 3 settlement service).
//
// Each settlement runs a five-step saga (Delivery → Inspection → Amount →
// Payment → Invoice). `currentStep` is the last COMPLETED step number
// (0 = nothing completed, 5 = fully settled), so the stepper derives every
// visual state from (status, currentStep) alone.

import { AlertTriangle, CheckCircle2, Clock, RefreshCw, RotateCw, XCircle } from "lucide-react";
import { Fragment, useCallback, useEffect, useState } from "react";

import { freightApi } from "../freightApi";
import type { Settlement, SettlementStatus } from "../freightTypes";
import { useI18n } from "../i18n";
import { notify } from "../ui";

const SAGA_STEPS = [
  { step: 1, labelKey: "freight.step.delivery" },
  { step: 2, labelKey: "freight.step.inspection" },
  { step: 3, labelKey: "freight.step.amount" },
  { step: 4, labelKey: "freight.step.payment" },
  { step: 5, labelKey: "freight.step.invoice" },
] as const;

const STATUS_OPTIONS: readonly SettlementStatus[] = [
  "PENDING",
  "IN_PROGRESS",
  "COMPLETED",
  "FAILED",
  "DISPUTED",
];

type StatusFilter = "ALL" | SettlementStatus;
type StepState = "done" | "failed" | "active" | "pending";

function stepState(step: number, settlement: Settlement): StepState {
  const halted = settlement.status === "FAILED" || settlement.status === "DISPUTED";
  if (halted && step === settlement.currentStep + 1) return "failed";
  if (step <= settlement.currentStep) return "done";
  if (step === settlement.currentStep + 1 && settlement.status === "IN_PROGRESS") return "active";
  return "pending";
}

function statusChipClass(status: SettlementStatus): string {
  switch (status) {
    case "COMPLETED": return "chip--green";
    case "PENDING":
    case "IN_PROGRESS": return "chip--blue";
    case "FAILED": return "chip--red";
    case "DISPUTED": return "chip--amber";
  }
}

function StatusIcon({ status, size = 12 }: { status: SettlementStatus; size?: number }) {
  switch (status) {
    case "COMPLETED": return <CheckCircle2 size={size} aria-hidden="true" />;
    case "PENDING": return <Clock size={size} aria-hidden="true" />;
    case "IN_PROGRESS": return <RefreshCw size={size} aria-hidden="true" />;
    case "FAILED": return <XCircle size={size} aria-hidden="true" />;
    case "DISPUTED": return <AlertTriangle size={size} aria-hidden="true" />;
  }
}

const usd = (cents: number): string =>
  `$${(cents / 100).toLocaleString(undefined, { minimumFractionDigits: 2 })}`;

const signedUsd = (cents: number): string => (cents >= 0 ? "+" : "−") + usd(Math.abs(cents));

const shortId = (id: string): string => (id.length > 12 ? `${id.slice(0, 10)}…` : id);

// ── Saga log ────────────────────────────────────────────────────────────
// sagaLog is a free-form JSON array of step records written by the saga
// orchestrator. Parse defensively: a malformed payload must never break the
// card, so unknown shapes simply hide the detail line.

interface SagaLogEntry {
  step?: number;
  status?: string;
  message?: string;
  at?: string;
}

function latestSagaEntry(sagaLog: string): SagaLogEntry | null {
  if (!sagaLog) return null;
  try {
    const parsed: unknown = JSON.parse(sagaLog);
    if (!Array.isArray(parsed) || parsed.length === 0) return null;
    const last: unknown = parsed[parsed.length - 1];
    if (typeof last !== "object" || last === null) return null;
    const fields = last as Record<string, unknown>;
    const entry: SagaLogEntry = {
      step: typeof fields.step === "number" ? fields.step : undefined,
      status: typeof fields.status === "string" ? fields.status : undefined,
      message: typeof fields.message === "string" ? fields.message : undefined,
      at: typeof fields.at === "string" ? fields.at : undefined,
    };
    return entry.step === undefined && !entry.status && !entry.message && !entry.at ? null : entry;
  } catch {
    return null;
  }
}

function formatSagaEntry(entry: SagaLogEntry): string {
  const parts: string[] = [];
  if (entry.step !== undefined) parts.push(`#${entry.step}`);
  if (entry.status) parts.push(entry.status);
  if (entry.message) parts.push(entry.message);
  if (entry.at) {
    const date = new Date(entry.at);
    parts.push(Number.isNaN(date.getTime()) ? entry.at : date.toLocaleString());
  }
  return parts.join(" · ");
}

// ── Page ────────────────────────────────────────────────────────────────

export function SettlementsPage({ navigate }: { navigate: (path: string) => void }) {
  const { t } = useI18n();
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [settlements, setSettlements] = useState<Settlement[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(true);
  const [retryingId, setRetryingId] = useState<number | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    freightApi
      .listSettlements(statusFilter === "ALL" ? {} : { status: statusFilter })
      .then((rows) => {
        if (!cancelled) {
          setSettlements(rows);
          setFailed(false);
        }
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [statusFilter, reloadNonce]);

  const refresh = useCallback(() => setReloadNonce((nonce) => nonce + 1), []);

  const retry = (settlement: Settlement) => {
    setRetryingId(settlement.id);
    freightApi
      .retrySettlement(settlement.id)
      .then(refresh)
      .catch(() => notify(t("freight.error"), "error"))
      .finally(() => setRetryingId(null));
  };

  return (
    <main className="freight-page">
      <header className="freight-head">
        <div>
          <h1>{t("freight.settlements.title")}</h1>
          <p>{t("freight.settlements.subtitle")}</p>
        </div>
        {settlements && !failed && (
          <span className="chip chip--muted" title={t("freight.settlements.title")}>
            <span className="mono">{settlements.length}</span>
          </span>
        )}
        <button className="fbtn fbtn--secondary" onClick={() => navigate("/loads")}>
          {t("freight.viewLoads")}
        </button>
      </header>

      <div className="freight-toolbar">
        <select
          className="freight-select"
          value={statusFilter}
          onChange={(event) => setStatusFilter(event.target.value as StatusFilter)}
          aria-label={t("freight.settlements.title")}
        >
          <option value="ALL">{t("freight.settlements.all")}</option>
          {STATUS_OPTIONS.map((status) => (
            <option key={status} value={status}>{status}</option>
          ))}
        </select>
      </div>

      {failed ? (
        <div className="freight-empty">{t("freight.error")}</div>
      ) : loading && settlements === null ? (
        <div className="freight-loading" role="status">
          <div className="freight-spinner" aria-hidden="true" />
          {t("freight.loading")}
        </div>
      ) : settlements !== null && settlements.length === 0 ? (
        <div className="freight-empty">{t("freight.settlements.empty")}</div>
      ) : (
        // Settlement cards stack full-width so the saga stepper always has
        // room to read left-to-right, even with five labeled steps.
        <div className="freight-cards" style={{ gridTemplateColumns: "1fr" }}>
          {(settlements ?? []).map((settlement) => (
            <SettlementCard
              key={settlement.id}
              settlement={settlement}
              retrying={retryingId === settlement.id}
              onRetry={() => retry(settlement)}
            />
          ))}
        </div>
      )}
    </main>
  );
}

function SettlementCard({
  settlement,
  retrying,
  onRetry,
}: {
  settlement: Settlement;
  retrying: boolean;
  onRetry: () => void;
}) {
  const { t } = useI18n();
  const halted = settlement.status === "FAILED" || settlement.status === "DISPUTED";
  const sagaEntry = latestSagaEntry(settlement.sagaLog);
  const labelFor = (step: number): string => t(SAGA_STEPS[step - 1].labelKey);
  const total = SAGA_STEPS.length;
  const focusStep = Math.min(settlement.currentStep + 1, total);
  const stepperLabel =
    settlement.status === "COMPLETED"
      ? `${labelFor(total)} · ${total}/${total}`
      : `${labelFor(focusStep)} · ${settlement.currentStep}/${total}`;

  return (
    <article className="freight-card">
      <div className="freight-card-route">
        <span>Settlement <span className="mono">#{settlement.id}</span></span>
        <span className={`chip ${statusChipClass(settlement.status)}`} style={{ marginLeft: "auto" }}>
          <StatusIcon status={settlement.status} />
          {settlement.status}
        </span>
      </div>

      <div className="freight-card-meta">
        <span>{t("freight.settlements.load")} <span className="mono">#{settlement.loadId}</span></span>
        <span>{t("freight.settlements.carrier")} <span className="mono">{shortId(settlement.carrierId)}</span></span>
      </div>

      <div className="saga-stepper" role="img" aria-label={stepperLabel}>
        {SAGA_STEPS.map(({ step, labelKey }, index) => {
          const state = stepState(step, settlement);
          const stepClass = state === "pending" ? "saga-step" : `saga-step saga-step--${state}`;
          return (
            <Fragment key={step}>
              {index > 0 && <span className="saga-connector" aria-hidden="true" />}
              <span className={stepClass}>
                <span className="dot" aria-hidden="true">
                  {state === "done" ? "✓" : state === "failed" ? "✕" : step}
                </span>
                {t(labelKey)}
              </span>
            </Fragment>
          );
        })}
      </div>

      <div className="freight-card-meta">
        <span>{t("freight.settlements.base")} <span className="mono">{usd(settlement.baseRateCents)}</span></span>
        <span>{t("freight.settlements.adjustment")} <span className="mono">{signedUsd(settlement.adjustmentCents)}</span></span>
        <span>
          {t("freight.settlements.final")}{" "}
          <span className="mono">
            {settlement.finalAmountCents === null ? "—" : usd(settlement.finalAmountCents)}
          </span>
        </span>
      </div>

      {sagaEntry && (
        <div className="freight-card-meta">
          <span>{formatSagaEntry(sagaEntry)}</span>
        </div>
      )}

      {halted && (
        <div className="freight-card-actions">
          <button className="fbtn fbtn--secondary" onClick={onRetry} disabled={retrying}>
            <RotateCw size={14} />
            {t("freight.settlements.retry")}
          </button>
        </div>
      )}
    </article>
  );
}
