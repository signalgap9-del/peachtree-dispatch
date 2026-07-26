import { Gauge, X } from "lucide-react";

import type { Navigate } from "../App";
import { useI18n } from "../i18n";

/**
 * Single tasteful nudge shown when a workspace hits a free-tier limit.
 * Links to the pricing page; render at most one per surface and always
 * offer dismissal so it never blocks the task at hand.
 */
export function UpgradePrompt({ navigate, onDismissed }: { navigate: Navigate; onDismissed?: () => void }) {
  const { t } = useI18n();
  return (
    <div className="upgrade-prompt">
      <i aria-hidden="true"><Gauge size={18} /></i>
      <div>
        <strong>{t("upgrade.limit.title")}</strong>
        <span>{t("upgrade.limit.detail")}</span>
      </div>
      <button type="button" className="button primary" onClick={() => navigate("/pricing")}>{t("upgrade.limit.cta")}</button>
      {onDismissed && (
        <button type="button" className="upgrade-prompt-dismiss" aria-label={t("upgrade.limit.dismiss")} onClick={onDismissed}>
          <X size={15} />
        </button>
      )}
    </div>
  );
}
