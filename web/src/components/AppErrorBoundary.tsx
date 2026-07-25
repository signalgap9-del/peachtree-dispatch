import { Component, type ErrorInfo, type ReactNode } from "react";
import { AlertTriangle, Home, RefreshCcw } from "lucide-react";

import { createErrorId, reportClientIssue } from "../telemetry";

type Props = {
  children: ReactNode;
};

type State = {
  error: Error | null;
  errorId: string | null;
};

export class AppErrorBoundary extends Component<Props, State> {
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
        errorId,
        componentStack: info.componentStack?.slice(0, 1800) ?? null,
      },
    });
  }

  override render() {
    if (!this.state.error) return this.props.children;
    return (
      <main className="page-shell empty-page app-fallback" role="alert">
        <AlertTriangle size={38} />
        <h1>FreightScaler hit a client-side issue</h1>
        <p>The app recovered into a safe fallback instead of leaving a blank screen. The issue is recorded in this session's operational status view.</p>
        {this.state.errorId && <p className="app-fallback-id">Error ID: <code>{this.state.errorId}</code></p>}
        <div>
          <button className="button primary" onClick={() => window.location.reload()}><RefreshCcw size={16} /> Reload</button>
          <button className="button secondary" onClick={() => window.location.assign("/")}><Home size={16} /> Go home</button>
        </div>
      </main>
    );
  }
}
