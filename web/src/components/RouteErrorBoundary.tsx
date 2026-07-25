import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, Home, RotateCcw } from "lucide-react";

import { useI18n } from "../i18n";
import { createErrorId, reportClientIssue } from "../telemetry";

type Props = {
  children: ReactNode;
  /** Stable scope tag recorded in telemetry, e.g. "map", "chat", "pages". */
  scope: string;
};

type State = {
  error: Error | null;
  errorId: string | null;
};

/**
 * Section-level boundary so a crash inside one route (map, assistant,
 * page cluster) degrades that section instead of blanking the whole app.
 */
export class RouteErrorBoundary extends Component<Props, State> {
  override state: State = { error: null, errorId: null };

  static getDerivedStateFromError(error: Error): State {
    return { error, errorId: null };
  }

  override componentDidCatch(error: Error, info: ErrorInfo) {
    const errorId = createErrorId();
    this.setState({ errorId });
    reportClientIssue({
      kind: "render_error",
      message: error.message,
      details: {
        scope: this.props.scope,
        errorId,
        componentStack: info.componentStack?.slice(0, 1800) ?? null,
      },
    });
  }

  private reset = () => this.setState({ error: null, errorId: null });

  override render() {
    if (!this.state.error) return this.props.children;
    return <RouteFallback errorId={this.state.errorId} onRetry={this.reset} />;
  }
}

function RouteFallback({ errorId, onRetry }: { errorId: string | null; onRetry: () => void }) {
  const { t } = useI18n();
  return (
    <div className="route-fallback" role="alert">
      <span className="route-fallback-icon" aria-hidden="true"><AlertTriangle size={19} /></span>
      <div className="route-fallback-text">
        <strong>{t("error.route.title")}</strong>
        <small>{t("error.route.detail")}</small>
        {errorId && <code>{t("error.id")}: {errorId}</code>}
      </div>
      <div className="route-fallback-actions">
        <button type="button" className="button secondary" onClick={onRetry}><RotateCcw size={14} /> {t("error.route.retry")}</button>
        <button type="button" className="button secondary" onClick={() => window.location.assign("/")}><Home size={14} /> {t("error.route.home")}</button>
      </div>
    </div>
  );
}
