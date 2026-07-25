import { Activity, Bell, ChevronRight, Database, Info, Trash2 } from "lucide-react";
import { useState } from "react";

import type { Navigate } from "./App";
import { useI18n, languageKey } from "./i18n";
import { notify } from "./ui";

const APP_VERSION = "0.1.0";
export const NOTIFY_PREFS_KEY = "freightscaler:notify-prefs";

export type NotifyPrefs = {
  severe: boolean;
  routeThreshold: boolean;
  digest: boolean;
  threshold: number;
};

const DEFAULT_PREFS: NotifyPrefs = { severe: true, routeThreshold: true, digest: false, threshold: 55 };

export function loadNotifyPrefs(): NotifyPrefs {
  try {
    const raw = window.localStorage.getItem(NOTIFY_PREFS_KEY);
    if (!raw) return DEFAULT_PREFS;
    const parsed = JSON.parse(raw) as Partial<NotifyPrefs>;
    return { ...DEFAULT_PREFS, ...parsed };
  } catch {
    return DEFAULT_PREFS;
  }
}

export function SettingsPage({ navigate }: { navigate: Navigate }) {
  const { t } = useI18n();
  const [prefs, setPrefs] = useState<NotifyPrefs>(() => loadNotifyPrefs());
  const [confirming, setConfirming] = useState(false);

  function updatePrefs(patch: Partial<NotifyPrefs>) {
    setPrefs((current) => {
      const next = { ...current, ...patch };
      try {
        window.localStorage.setItem(NOTIFY_PREFS_KEY, JSON.stringify(next));
      } catch {
        // Storage unavailable; preference applies for this session only.
      }
      return next;
    });
    notify(t("toast.preferencesSaved"), "success");
  }

  function clearLocalData() {
    try {
      const doomed: string[] = [];
      for (let i = 0; i < window.localStorage.length; i += 1) {
        const key = window.localStorage.key(i);
        if (key && key.startsWith("freightscaler:") && key !== languageKey) doomed.push(key);
      }
      doomed.forEach((key) => window.localStorage.removeItem(key));
      window.sessionStorage.clear();
    } catch {
      // Ignore storage failures; nothing else to clean up.
    }
    setPrefs(DEFAULT_PREFS);
    setConfirming(false);
    notify(t("settings.cleared"), "success");
  }

  const aboutLinks = [
    { label: t("footer.terms"), path: "/legal/terms" },
    { label: t("footer.privacy"), path: "/legal/privacy" },
    { label: t("footer.status"), path: "/status" },
  ];

  return (
    <main className="page-shell settings-page">
      <header className="page-title">
        <div><h1>{t("settings.title")}</h1><p>{t("settings.subtitle")}</p></div>
      </header>

      <section className="surface settings-section">
        <div className="settings-section-head">
          <i><Bell size={18} /></i>
          <div><strong>{t("settings.notifications")}</strong><small>{t("settings.notificationsDetail")}</small></div>
        </div>
        <ToggleRow
          label={t("settings.notifySevere")}
          detail={t("settings.notifySevereDetail")}
          checked={prefs.severe}
          onChange={(value) => updatePrefs({ severe: value })}
        />
        <ToggleRow
          label={t("settings.notifyRoute")}
          detail={t("settings.notifyRouteDetail")}
          checked={prefs.routeThreshold}
          onChange={(value) => updatePrefs({ routeThreshold: value })}
        />
        <ToggleRow
          label={t("settings.notifyDigest")}
          detail={t("settings.notifyDigestDetail")}
          checked={prefs.digest}
          onChange={(value) => updatePrefs({ digest: value })}
        />
        <div className="settings-row">
          <div className="settings-row-text"><strong>{t("settings.threshold")}</strong><small>{t("settings.thresholdDetail")}</small></div>
          <div className="threshold-control">
            <input
              type="range"
              min={1}
              max={100}
              value={prefs.threshold}
              aria-label={t("settings.threshold")}
              onChange={(event) => updatePrefs({ threshold: Number(event.target.value) })}
            />
            <output>{prefs.threshold}</output>
          </div>
        </div>
      </section>

      <section className="surface settings-section">
        <div className="settings-section-head">
          <i><Database size={18} /></i>
          <div><strong>{t("settings.data")}</strong><small>{t("settings.dataDetail")}</small></div>
        </div>
        <div className="settings-row">
          <div className="settings-row-text"><strong>{t("settings.clearData")}</strong><small>{t("settings.clearDataDetail")}</small></div>
          <button type="button" className="button secondary danger-outline" onClick={() => setConfirming(true)}><Trash2 size={15} /> {t("settings.clearData")}</button>
        </div>
        {confirming && (
          <div className="settings-confirm" role="alertdialog" aria-label={t("settings.clearConfirmTitle")}>
            <strong>{t("settings.clearConfirmTitle")}</strong>
            <p>{t("settings.clearConfirmDetail")}</p>
            <div className="settings-confirm-actions">
              <button type="button" className="button secondary" onClick={() => setConfirming(false)}>{t("settings.cancel")}</button>
              <button type="button" className="button danger" onClick={clearLocalData}><Trash2 size={15} /> {t("settings.clear")}</button>
            </div>
          </div>
        )}
      </section>

      <section className="surface settings-section">
        <div className="settings-section-head">
          <i><Info size={18} /></i>
          <div><strong>{t("settings.about")}</strong><small>FreightScaler · {t("brand.tagline")}</small></div>
        </div>
        <div className="settings-row">
          <div className="settings-row-text"><strong>{t("settings.version")}</strong></div>
          <span className="version-chip">v{APP_VERSION}</span>
        </div>
        <div className="about-links">
          {aboutLinks.map((link) => (
            <button key={link.path} type="button" onClick={() => navigate(link.path)}>
              <span><Activity size={15} className="about-link-icon" /> {link.label}</span>
              <ChevronRight size={15} />
            </button>
          ))}
        </div>
      </section>
    </main>
  );
}

function ToggleRow({ label, detail, checked, onChange }: { label: string; detail: string; checked: boolean; onChange: (value: boolean) => void }) {
  return (
    <div className="settings-row">
      <div className="settings-row-text"><strong>{label}</strong><small>{detail}</small></div>
      <button type="button" role="switch" aria-checked={checked} aria-label={label} className="switch" onClick={() => onChange(!checked)}>
        <i aria-hidden="true" />
      </button>
    </div>
  );
}
