// Carrier ranking leaderboard (Phase 3 ── ranking service).
//
// Scores come from a Redis Sorted Set behind the freight gateway and move as
// trips complete, so the active board refetches on a short interval instead
// of waiting for a manual reload. The Trend column is deliberately neutral:
// the API reports absolute scores only, so no deltas are fabricated.

import { useEffect, useState, type CSSProperties } from "react";

import { freightApi } from "../freightApi";
import { CORRIDORS, type CarrierScore, type Corridor } from "../freightTypes";
import { useI18n } from "../i18n";

const REFRESH_INTERVAL_MS = 10_000;
const RANK_LIMIT = 10;
const MEDALS = ["🥇", "🥈", "🥉"] as const;

type RankingTab = "overall" | Corridor;

const prefersReducedMotion =
  typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

// Pill tabs mirror the .freight-select shape; only the active fill differs.
const tabStyle: CSSProperties = {
  appearance: "none",
  border: "1px solid var(--line)",
  background: "var(--surface)",
  color: "var(--ink)",
  borderRadius: 9999,
  height: 36,
  padding: "0 14px",
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
  transition: "background 0.15s ease, border-color 0.15s ease, color 0.15s ease",
};

const activeTabStyle: CSSProperties = {
  ...tabStyle,
  background: "var(--blue)",
  borderColor: "var(--blue)",
  color: "#fff",
};

// Reuses the saga-pulse keyframes already defined in freight.css.
const liveDotStyle: CSSProperties = {
  width: 8,
  height: 8,
  borderRadius: "50%",
  background: "var(--blue)",
  marginLeft: "auto",
  animation: prefersReducedMotion ? undefined : "saga-pulse 1.6s ease-in-out infinite",
};

export function CarriersPage({ navigate }: { navigate: (path: string) => void }) {
  const { t } = useI18n();
  const [tab, setTab] = useState<RankingTab>("overall");
  const [rows, setRows] = useState<CarrierScore[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const fetchRankings = () => {
      const request =
        tab === "overall"
          ? freightApi.overallRankings(RANK_LIMIT)
          : freightApi.corridorRankings(tab, RANK_LIMIT);
      request
        .then((scores) => {
          if (cancelled) return;
          setRows(scores);
          setFailed(false);
        })
        .catch(() => {
          if (!cancelled) setFailed(true);
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    };

    setLoading(true);
    fetchRankings();
    // Background refreshes update rows in place; the spinner only shows on
    // the first fetch per tab so the board doesn't flicker every interval.
    const interval = window.setInterval(fetchRankings, REFRESH_INTERVAL_MS);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [tab]);

  const medalFor = (rank: number) =>
    rank >= 1 && rank <= MEDALS.length ? MEDALS[rank - 1] : null;

  return (
    <main className="freight-page">
      <header className="freight-head">
        <div>
          <h1>{t("freight.carriers.title")}</h1>
          <p>{t("freight.carriers.subtitle")}</p>
        </div>
        <button className="fbtn fbtn--secondary" onClick={() => navigate("/loads")}>
          {t("freight.viewLoads")}
        </button>
      </header>

      <div className="freight-toolbar" aria-label={t("freight.carriers.title")}>
        <button
          type="button"
          style={tab === "overall" ? activeTabStyle : tabStyle}
          aria-pressed={tab === "overall"}
          onClick={() => setTab("overall")}
        >
          {t("freight.carriers.overall")}
        </button>
        {CORRIDORS.map((corridor) => (
          <button
            key={corridor}
            type="button"
            style={tab === corridor ? activeTabStyle : tabStyle}
            aria-pressed={tab === corridor}
            onClick={() => setTab(corridor)}
          >
            {corridor}
          </button>
        ))}
        <span style={liveDotStyle} aria-hidden="true" />
      </div>

      {loading ? (
        <div className="freight-loading" role="status">
          <span className="freight-spinner" aria-hidden="true" />
          <p>{t("freight.loading")}</p>
        </div>
      ) : failed ? (
        <div className="freight-empty" role="alert">
          {t("freight.error")}
        </div>
      ) : rows.length === 0 ? (
        <div className="freight-empty">{t("freight.carriers.empty")}</div>
      ) : (
        <table className="freight-table">
          <thead>
            <tr>
              <th scope="col">{t("freight.carriers.rank")}</th>
              <th scope="col">{t("freight.carriers.carrier")}</th>
              <th scope="col" className="num">{t("freight.carriers.score")}</th>
              <th scope="col">{t("freight.carriers.trend")}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => {
              const medal = medalFor(row.rank);
              return (
                <tr key={row.carrierId}>
                  <td>{medal ? <span className="rank-medal">{medal}</span> : row.rank}</td>
                  <td title={row.carrierId}>Carrier {row.carrierId.slice(0, 8)}</td>
                  <td className="num">{Math.round(row.score).toLocaleString("en-US")}</td>
                  <td><span className="delta-flat">—</span></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </main>
  );
}
