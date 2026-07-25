import { AlertTriangle, ArrowRightLeft, X } from "lucide-react";
import { useEffect } from "react";

import { dismissSuggestion } from "./llmApi";
import type { RiskSuggestion } from "./types";

const AUTO_DISMISS_MS = 30000;

type Props = {
  suggestion: RiskSuggestion | null;
  /** "전환" - opens the chat with a pre-filled switch request. */
  onSwitch: (suggestion: RiskSuggestion) => void;
  /** Banner asked to disappear (dismiss button, auto-dismiss, or switch). */
  onDismissed: () => void;
};

/**
 * Amber warning banner for proactive risk suggestions arriving over the
 * alert SSE stream. Auto-dismisses after 30 seconds without interaction.
 */
export function ProactiveSuggestionBanner({ suggestion, onSwitch, onDismissed }: Props) {
  useEffect(() => {
    if (!suggestion) return;
    const timer = window.setTimeout(onDismissed, AUTO_DISMISS_MS);
    return () => window.clearTimeout(timer);
  }, [suggestion, onDismissed]);

  if (!suggestion) return null;

  const severe = ["HIGH", "SEVERE", "EXTREME"].includes(suggestion.severity.toUpperCase());
  // The suggestion text is LLM-authored and may already name the alternative;
  // only add the structured chip when it does not.
  const alternative = suggestion.alternativeRoute
    && suggestion.alternativeRisk !== undefined
    && !suggestion.suggestionText.includes(suggestion.alternativeRoute)
    ? `${suggestion.alternativeRoute} 대안: 위험 ${suggestion.currentRisk}→${suggestion.alternativeRisk}`
    : null;

  function handleSwitch() {
    if (!suggestion) return;
    onSwitch(suggestion);
  }

  function handleDismiss() {
    if (!suggestion) return;
    void dismissSuggestion(suggestion.id);
    onDismissed();
  }

  return (
    <div
      key={suggestion.id}
      className={`suggestion-banner${severe ? " severe" : ""}`}
      role="status"
      aria-label="경로 위험 제안"
    >
      <span className="suggestion-banner-icon"><AlertTriangle size={18} /></span>
      <div className="suggestion-banner-body">
        <strong>저장하신 {suggestion.routeName} 경로 위험 상승</strong>
        <small>
          {suggestion.suggestionText}
          {alternative && <em>{alternative}</em>}
        </small>
      </div>
      <div className="suggestion-banner-actions">
        <button type="button" className="suggestion-switch" onClick={handleSwitch}>
          <ArrowRightLeft size={14} /> 전환
        </button>
        <button type="button" className="suggestion-dismiss" onClick={handleDismiss} aria-label="닫기">
          <X size={15} />
        </button>
      </div>
      <i className="suggestion-banner-countdown" aria-hidden="true" />
    </div>
  );
}
