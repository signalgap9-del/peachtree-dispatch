// Load board: filterable card list with keyset pagination, inline bidding,
// and load creation. Part of the freight platform UI (Phase 2-4) — see
// web/src/freightApi.ts for the gateway client and freight.css for styles.

import { ChevronDown, Plus, Truck } from "lucide-react";
import { useCallback, useEffect, useRef, useState, type CSSProperties, type FormEvent } from "react";

import { freightApi } from "../freightApi";
import {
  CORRIDORS,
  type Bid,
  type BidStatus,
  type CargoType,
  type CreateLoadRequest,
  type FreightLoad,
  type LoadStatus,
} from "../freightTypes";
import { useI18n } from "../i18n";

// Demo identities until auth is wired into the freight services.
const DEMO_CARRIER_ID = "00000000-0000-0000-0000-000000000099";
const DEMO_TENANT_ID = "00000000-0000-0000-0000-000000000001";

const CARGO_TYPES: CargoType[] = ["GENERAL", "REFRIGERATED", "HAZMAT", "OVERSIZE"];
const BOARD_STATUSES: LoadStatus[] = ["OPEN", "MATCHED", "DISPATCHED", "IN_TRANSIT", "DELIVERED"];

const BID_STATUS_CHIP: Record<BidStatus, string> = {
  SUBMITTED: "chip--blue",
  ACCEPTED: "chip--green",
  REJECTED: "chip--red",
  WITHDRAWN: "chip--muted",
};

const usd = (cents: number): string =>
  `$${(cents / 100).toLocaleString(undefined, { minimumFractionDigits: 2 })}`;
const formatDate = (iso: string): string => new Date(iso).toLocaleDateString();
const riskChipClass = (score: number): string =>
  score > 70 ? "chip--red" : score >= 40 ? "chip--amber" : "chip--green";
const shortId = (id: string): string => (id.length > 8 ? `…${id.slice(-6)}` : id);

// freight.css intentionally carries no form-field styles; these minimal
// inline styles are shared by the bid and create forms so both stay
// visually consistent with the surrounding cards.
const fieldStyle: CSSProperties = {
  width: "100%",
  height: 38,
  padding: "0 12px",
  border: "1px solid var(--line)",
  borderRadius: 10,
  background: "var(--surface)",
  color: "var(--ink)",
  fontSize: 14,
};
const labelStyle: CSSProperties = {
  display: "grid",
  gap: 6,
  fontSize: 12,
  fontWeight: 700,
  color: "var(--muted)",
};
const formGridStyle: CSSProperties = {
  display: "grid",
  gridTemplateColumns: "repeat(auto-fit, minmax(160px, 1fr))",
  gap: 12,
};
const chipRowStyle: CSSProperties = { display: "flex", flexWrap: "wrap", gap: 6 };
const detailStyle: CSSProperties = {
  display: "grid",
  gap: 12,
  borderTop: "1px solid var(--line)",
  paddingTop: 12,
};
const bidListStyle: CSSProperties = {
  listStyle: "none",
  margin: 0,
  padding: 0,
  display: "grid",
  gap: 6,
};
const bidRowStyle: CSSProperties = {
  display: "flex",
  alignItems: "center",
  flexWrap: "wrap",
  gap: 8,
  fontSize: 13,
};
const buttonRowStyle: CSSProperties = {
  display: "flex",
  alignItems: "center",
  flexWrap: "wrap",
  gap: 8,
};
const mutedStyle: CSSProperties = { color: "var(--muted)" };
const endAlignStyle: CSSProperties = { marginLeft: "auto" };

export function LoadsPage({ navigate }: { navigate: (path: string) => void }) {
  const { t } = useI18n();
  const [corridor, setCorridor] = useState("");
  const [cargoType, setCargoType] = useState("");
  const [status, setStatus] = useState("");

  // Keyset-paginated list state: pages append via nextCursor.
  const [loads, setLoads] = useState<FreightLoad[]>([]);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [failed, setFailed] = useState(false);

  const [createOpen, setCreateOpen] = useState(false);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [bidFormId, setBidFormId] = useState<number | null>(null);
  const [bidsById, setBidsById] = useState<Record<number, Bid[]>>({});

  // Monotonic guard so a slow response can never overwrite a newer request
  // (e.g. the user changes filters while the previous page is in flight).
  const requestSeq = useRef(0);

  const fetchPage = useCallback(
    async (filters: { corridor: string; cargoType: string; status: string }, cursor?: number) => {
      const seq = ++requestSeq.current;
      const append = cursor !== undefined;
      if (append) {
        setLoadingMore(true);
      } else {
        setLoading(true);
        setFailed(false);
      }
      try {
        const page = await freightApi.listLoads({
          corridor: filters.corridor || undefined,
          cargoType: filters.cargoType || undefined,
          status: filters.status || undefined,
          cursor,
        });
        if (seq !== requestSeq.current) return;
        setLoads((prev) => (append ? [...prev, ...page.loads] : page.loads));
        setNextCursor(page.nextCursor);
        setHasMore(page.hasMore);
      } catch {
        if (seq !== requestSeq.current) return;
        // Keep already-fetched pages on a failed "load more".
        if (!append) setFailed(true);
      } finally {
        if (seq === requestSeq.current) {
          setLoading(false);
          setLoadingMore(false);
        }
      }
    },
    [],
  );

  useEffect(() => {
    setLoads([]);
    setExpandedId(null);
    setBidFormId(null);
    void fetchPage({ corridor, cargoType, status });
  }, [corridor, cargoType, status, fetchPage]);

  const refreshBids = useCallback(async (loadId: number) => {
    try {
      const bids = await freightApi.bidsForLoad(loadId);
      setBidsById((prev) => ({ ...prev, [loadId]: bids }));
    } catch {
      // An empty list keeps the expanded card usable even if bids fail.
      setBidsById((prev) => ({ ...prev, [loadId]: prev[loadId] ?? [] }));
    }
  }, []);

  const toggleDetail = (loadId: number) => {
    const next = expandedId === loadId ? null : loadId;
    setExpandedId(next);
    if (next === null) setBidFormId(null);
    if (next !== null && bidsById[next] === undefined) void refreshBids(next);
  };

  const openBidForm = (loadId: number) => {
    setExpandedId(loadId);
    setBidFormId(loadId);
    if (bidsById[loadId] === undefined) void refreshBids(loadId);
  };

  return (
    <main className="freight-page">
      <div className="freight-head">
        <div>
          <h1>{t("freight.loads.title")}</h1>
          <p>{t("freight.loads.subtitle")}</p>
        </div>
        <button className="fbtn fbtn--primary" onClick={() => setCreateOpen((open) => !open)}>
          <Plus size={15} /> {t("freight.loads.create")}
        </button>
      </div>

      {createOpen && (
        <CreateLoadForm
          onCancel={() => setCreateOpen(false)}
          onCreated={(load) => {
            setLoads((prev) => [load, ...prev]);
            setCreateOpen(false);
          }}
        />
      )}

      <div className="freight-toolbar" role="group" aria-label={t("freight.loads.title")}>
        <select
          className="freight-select"
          value={corridor}
          aria-label={t("freight.loads.allCorridors")}
          onChange={(event) => setCorridor(event.target.value)}
        >
          <option value="">{t("freight.loads.allCorridors")}</option>
          {CORRIDORS.map((corridorOption) => (
            <option key={corridorOption} value={corridorOption}>{corridorOption}</option>
          ))}
        </select>
        <select
          className="freight-select"
          value={cargoType}
          aria-label={t("freight.loads.allCargo")}
          onChange={(event) => setCargoType(event.target.value)}
        >
          <option value="">{t("freight.loads.allCargo")}</option>
          {CARGO_TYPES.map((cargoOption) => (
            <option key={cargoOption} value={cargoOption}>{cargoOption}</option>
          ))}
        </select>
        <select
          className="freight-select"
          value={status}
          aria-label={t("freight.loads.allStatus")}
          onChange={(event) => setStatus(event.target.value)}
        >
          <option value="">{t("freight.loads.allStatus")}</option>
          {BOARD_STATUSES.map((statusOption) => (
            <option key={statusOption} value={statusOption}>{statusOption}</option>
          ))}
        </select>
      </div>

      {loading ? (
        <div className="freight-loading" role="status">
          <div className="freight-spinner" aria-hidden="true" />
          {t("freight.loading")}
        </div>
      ) : failed ? (
        <div className="freight-empty" role="alert">{t("freight.error")}</div>
      ) : loads.length === 0 ? (
        <div className="freight-empty">{t("freight.loads.empty")}</div>
      ) : (
        <>
          <div className="freight-cards">
            {loads.map((load) => (
              <LoadCard
                key={load.id}
                load={load}
                navigate={navigate}
                expanded={expandedId === load.id}
                bids={bidsById[load.id]}
                bidFormOpen={bidFormId === load.id}
                onToggleDetail={toggleDetail}
                onOpenBidForm={openBidForm}
                onCloseBidForm={() => setBidFormId(null)}
                onBidsChanged={refreshBids}
              />
            ))}
          </div>
          {hasMore && (
            <div className="freight-load-more">
              <button
                className="fbtn fbtn--secondary"
                disabled={loadingMore}
                onClick={() => {
                  if (nextCursor !== null) void fetchPage({ corridor, cargoType, status }, nextCursor);
                }}
              >
                {loadingMore ? t("freight.loading") : t("freight.loads.loadMore")}
                {!loadingMore && <ChevronDown size={15} />}
              </button>
            </div>
          )}
        </>
      )}
    </main>
  );
}

interface LoadCardProps {
  load: FreightLoad;
  navigate: (path: string) => void;
  expanded: boolean;
  bids: Bid[] | undefined;
  bidFormOpen: boolean;
  onToggleDetail: (loadId: number) => void;
  onOpenBidForm: (loadId: number) => void;
  onCloseBidForm: () => void;
  onBidsChanged: (loadId: number) => void;
}

function LoadCard({
  load,
  navigate,
  expanded,
  bids,
  bidFormOpen,
  onToggleDetail,
  onOpenBidForm,
  onCloseBidForm,
  onBidsChanged,
}: LoadCardProps) {
  const { t } = useI18n();
  const detailId = `load-detail-${load.id}`;
  const trackable = load.status === "DISPATCHED" || load.status === "IN_TRANSIT";
  const topBidCents = bids && bids.length > 0 ? Math.min(...bids.map((bid) => bid.rateCents)) : null;

  return (
    <article className="freight-card" aria-labelledby={`load-route-${load.id}`}>
      <div className="freight-card-route" id={`load-route-${load.id}`}>
        <span>{load.origin}</span>
        <span className="arrow" aria-hidden="true">→</span>
        <span>{load.destination}</span>
      </div>

      <div style={chipRowStyle}>
        <span className="chip chip--blue">{load.cargoType}</span>
        {load.corridorId && <span className="chip chip--muted">{load.corridorId}</span>}
        {load.corridorRisk !== null && (
          <span className={`chip ${riskChipClass(load.corridorRisk)}`}>
            <span className="mono">{load.corridorRisk}</span> risk
          </span>
        )}
      </div>

      <div className="freight-card-meta">
        {load.deliveryDeadline && (
          <span>
            {t("freight.loads.deadline")} <span className="mono">{formatDate(load.deliveryDeadline)}</span>
          </span>
        )}
        {load.weightKg !== null && (
          <span>
            {t("freight.loads.weight")} <span className="mono">{load.weightKg.toLocaleString()} kg</span>
          </span>
        )}
        {load.maxRateCents !== null && (
          <span>
            Max rate <span className="mono">{usd(load.maxRateCents)}</span>
          </span>
        )}
        <span className="chip chip--muted">{load.status}</span>
      </div>

      <div className="freight-card-actions">
        {load.status === "OPEN" && (
          <button className="fbtn fbtn--primary" onClick={() => onOpenBidForm(load.id)}>
            {t("freight.loads.bid")}
          </button>
        )}
        <button
          className="fbtn fbtn--secondary"
          aria-expanded={expanded}
          aria-controls={detailId}
          onClick={() => onToggleDetail(load.id)}
        >
          {t("freight.loads.detail")}
          <ChevronDown
            size={15}
            style={{ transform: expanded ? "rotate(180deg)" : undefined, transition: "transform 0.15s ease" }}
          />
        </button>
      </div>

      {expanded && (
        <div id={detailId} style={detailStyle}>
          <div className="freight-card-meta">
            <span className="mono" style={mutedStyle}>#{load.id}</span>
            <span>{bids ? `${bids.length} ${t("freight.loads.bids")}` : t("freight.loading")}</span>
            {topBidCents !== null && (
              <span>
                {t("freight.loads.topBid")} <span className="mono">{usd(topBidCents)}</span>
              </span>
            )}
          </div>

          {bids && bids.length > 0 && (
            <ul style={bidListStyle}>
              {bids.map((bid) => (
                <li key={bid.id} style={bidRowStyle}>
                  <span className="mono" style={mutedStyle}>{shortId(bid.carrierId)}</span>
                  <span className="mono">{usd(bid.rateCents)}</span>
                  {bid.estimatedHours !== null && <span style={mutedStyle}>{bid.estimatedHours} h</span>}
                  <span className={`chip ${BID_STATUS_CHIP[bid.status]}`} style={endAlignStyle}>
                    {bid.status}
                  </span>
                </li>
              ))}
            </ul>
          )}

          {bidFormOpen && load.status === "OPEN" && (
            <BidForm loadId={load.id} onCancel={onCloseBidForm} onSubmitted={() => onBidsChanged(load.id)} />
          )}

          {trackable && (
            <div>
              <button className="fbtn fbtn--secondary" onClick={() => navigate("/fleet")}>
                <Truck size={15} /> Track on fleet map
              </button>
            </div>
          )}
        </div>
      )}
    </article>
  );
}

function BidForm({
  loadId,
  onSubmitted,
  onCancel,
}: {
  loadId: number;
  onSubmitted: () => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const [amountUsd, setAmountUsd] = useState("");
  const [hours, setHours] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [note, setNote] = useState<"accepted" | "failed" | null>(null);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const amount = Number(amountUsd);
    if (!Number.isFinite(amount) || amount <= 0) return;
    const estimatedHours = Number(hours);
    setSubmitting(true);
    setNote(null);
    try {
      await freightApi.submitBid({
        loadId,
        carrierId: DEMO_CARRIER_ID,
        rateCents: Math.round(amount * 100),
        ...(Number.isFinite(estimatedHours) && estimatedHours > 0
          ? { estimatedHours: Math.round(estimatedHours) }
          : {}),
      });
      // The bid service answers 202: the write is flattened and processed
      // asynchronously, so surface the accepted state instead of a new bid row.
      setNote("accepted");
      setAmountUsd("");
      setHours("");
      onSubmitted();
    } catch {
      setNote("failed");
    } finally {
      setSubmitting(false);
    }
  };

  const amountId = `bid-amount-${loadId}`;
  const hoursId = `bid-hours-${loadId}`;

  return (
    <form onSubmit={(event) => { void handleSubmit(event); }} style={{ display: "grid", gap: 10 }}>
      <strong style={{ fontSize: 13 }}>{t("freight.loads.bidTitle")}</strong>
      <div style={formGridStyle}>
        <label htmlFor={amountId} style={labelStyle}>
          {t("freight.loads.bidAmount")}
          <input
            id={amountId}
            type="number"
            inputMode="decimal"
            min="0.01"
            step="0.01"
            required
            placeholder="2400.00"
            value={amountUsd}
            onChange={(event) => setAmountUsd(event.target.value)}
            style={fieldStyle}
          />
        </label>
        <label htmlFor={hoursId} style={labelStyle}>
          {t("freight.loads.bidHours")}
          <input
            id={hoursId}
            type="number"
            inputMode="numeric"
            min="1"
            step="1"
            placeholder="24"
            value={hours}
            onChange={(event) => setHours(event.target.value)}
            style={fieldStyle}
          />
        </label>
      </div>
      <div style={buttonRowStyle}>
        <button type="submit" className="fbtn fbtn--primary" disabled={submitting}>
          {t("freight.loads.bidSubmit")}
        </button>
        <button type="button" className="fbtn fbtn--secondary" onClick={onCancel}>
          {t("freight.loads.bidCancel")}
        </button>
        {note === "accepted" && (
          <span className="chip chip--green" role="status">{t("freight.loads.bidAccepted")}</span>
        )}
        {note === "failed" && (
          <span className="chip chip--red" role="alert">{t("freight.loads.bidFailed")}</span>
        )}
      </div>
    </form>
  );
}

function CreateLoadForm({
  onCreated,
  onCancel,
}: {
  onCreated: (load: FreightLoad) => void;
  onCancel: () => void;
}) {
  const { t } = useI18n();
  const [origin, setOrigin] = useState("");
  const [destination, setDestination] = useState("");
  const [corridorId, setCorridorId] = useState<string>(CORRIDORS[0]);
  const [cargoType, setCargoType] = useState<CargoType>("GENERAL");
  const [maxRateUsd, setMaxRateUsd] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [failed, setFailed] = useState(false);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const trimmedOrigin = origin.trim();
    const trimmedDestination = destination.trim();
    if (!trimmedOrigin || !trimmedDestination) return;
    const maxRate = Number(maxRateUsd);
    const payload: CreateLoadRequest = {
      tenantId: DEMO_TENANT_ID,
      origin: trimmedOrigin,
      destination: trimmedDestination,
      cargoType,
      corridorId,
      corridorRisk: 50,
      ...(Number.isFinite(maxRate) && maxRate > 0 ? { maxRateCents: Math.round(maxRate * 100) } : {}),
    };
    setSubmitting(true);
    setFailed(false);
    try {
      const created = await freightApi.createLoad(payload);
      onCreated(created);
    } catch {
      setFailed(true);
      setSubmitting(false);
    }
  };

  return (
    <form className="freight-card" style={{ marginBottom: 16 }} onSubmit={(event) => { void handleSubmit(event); }}>
      <div style={formGridStyle}>
        <label htmlFor="create-origin" style={labelStyle}>
          Origin
          <input
            id="create-origin"
            type="text"
            required
            placeholder="Atlanta, GA"
            value={origin}
            onChange={(event) => setOrigin(event.target.value)}
            style={fieldStyle}
          />
        </label>
        <label htmlFor="create-destination" style={labelStyle}>
          Destination
          <input
            id="create-destination"
            type="text"
            required
            placeholder="Miami, FL"
            value={destination}
            onChange={(event) => setDestination(event.target.value)}
            style={fieldStyle}
          />
        </label>
        <label htmlFor="create-corridor" style={labelStyle}>
          Corridor
          <select
            id="create-corridor"
            className="freight-select"
            value={corridorId}
            onChange={(event) => setCorridorId(event.target.value)}
            style={{ width: "100%" }}
          >
            {CORRIDORS.map((corridorOption) => (
              <option key={corridorOption} value={corridorOption}>{corridorOption}</option>
            ))}
          </select>
        </label>
        <label htmlFor="create-cargo" style={labelStyle}>
          Cargo type
          <select
            id="create-cargo"
            className="freight-select"
            value={cargoType}
            onChange={(event) => setCargoType(event.target.value as CargoType)}
            style={{ width: "100%" }}
          >
            {CARGO_TYPES.map((cargoOption) => (
              <option key={cargoOption} value={cargoOption}>{cargoOption}</option>
            ))}
          </select>
        </label>
        <label htmlFor="create-rate" style={labelStyle}>
          Max rate (USD)
          <input
            id="create-rate"
            type="number"
            inputMode="decimal"
            min="0.01"
            step="0.01"
            placeholder="3500.00"
            value={maxRateUsd}
            onChange={(event) => setMaxRateUsd(event.target.value)}
            style={fieldStyle}
          />
        </label>
      </div>
      <div style={buttonRowStyle}>
        <button type="submit" className="fbtn fbtn--primary" disabled={submitting}>
          {t("freight.loads.create")}
        </button>
        <button type="button" className="fbtn fbtn--secondary" onClick={onCancel}>
          Cancel
        </button>
        {failed && <span className="chip chip--red" role="alert">{t("freight.error")}</span>}
      </div>
    </form>
  );
}
