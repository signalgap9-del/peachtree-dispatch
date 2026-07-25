import { Sparkles, X } from "lucide-react";
import { useEffect, useRef, useState, type ReactElement } from "react";

import { useI18n, type CopyKey } from "./i18n";

const ONBOARDING_KEY = "atmospath:onboarding-complete";
const ONBOARDING_SESSION_KEY = "atmospath:onboarding-session";

export function shouldShowOnboarding(): boolean {
  try {
    return !window.localStorage.getItem(ONBOARDING_KEY) && !window.sessionStorage.getItem(ONBOARDING_SESSION_KEY);
  } catch {
    return false;
  }
}

type OnboardingStep = {
  titleKey: CopyKey;
  bodyKey: CopyKey;
  art: () => ReactElement;
};

const STEPS: OnboardingStep[] = [
  { titleKey: "onboarding.step1.title", bodyKey: "onboarding.step1.body", art: RouteArt },
  { titleKey: "onboarding.step2.title", bodyKey: "onboarding.step2.body", art: CompareArt },
  { titleKey: "onboarding.step3.title", bodyKey: "onboarding.step3.body", art: MonitorArt },
];

/** First-visit welcome tour. Skips persist forever; the X close only hides it for the session unless "don't show again" is checked. */
export function Onboarding({ onDone }: { onDone: () => void }) {
  const { t } = useI18n();
  const [step, setStep] = useState(0);
  const [dontShowAgain, setDontShowAgain] = useState(true);
  const dialogRef = useRef<HTMLDivElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  const dontShowRef = useRef(dontShowAgain);
  dontShowRef.current = dontShowAgain;

  function finish(persist: boolean) {
    try {
      if (persist || dontShowRef.current) window.localStorage.setItem(ONBOARDING_KEY, "1");
      window.sessionStorage.setItem(ONBOARDING_SESSION_KEY, "1");
    } catch {
      // Storage may be unavailable; the tour simply reappears next visit.
    }
    onDone();
  }

  useEffect(() => {
    restoreFocusRef.current = document.activeElement as HTMLElement | null;
    dialogRef.current?.querySelector<HTMLElement>(".onboarding-next")?.focus();
    return () => {
      restoreFocusRef.current?.focus?.();
    };
  }, []);

  // Esc dismisses; Tab cycles inside the dialog only.
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        finish(false);
        return;
      }
      if (event.key !== "Tab") return;
      const dialog = dialogRef.current;
      if (!dialog) return;
      const focusables = Array.from(
        dialog.querySelectorAll<HTMLElement>("button:not([disabled]), input, [href]"),
      );
      if (!focusables.length) return;
      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    window.addEventListener("keydown", onKeyDown, true);
    return () => window.removeEventListener("keydown", onKeyDown, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const active = STEPS[step];
  const Art = active.art;
  const isLast = step === STEPS.length - 1;

  return (
    <div className="onboarding-overlay" onMouseDown={(event) => { if (event.target === event.currentTarget) finish(false); }}>
      <div className="onboarding-dialog" role="dialog" aria-modal="true" aria-label={t("onboarding.welcome")} ref={dialogRef}>
        <div className="onboarding-top">
          <span className="onboarding-brand"><Sparkles size={15} /> AtmosPath</span>
          <button type="button" className="onboarding-close" onClick={() => finish(false)} aria-label={t("onboarding.close")}><X size={17} /></button>
        </div>
        <div className="onboarding-art"><Art /></div>
        <div className="onboarding-body">
          <span className="onboarding-step-label">{step + 1} / {STEPS.length}</span>
          <h2>{t(active.titleKey)}</h2>
          <p>{t(active.bodyKey)}</p>
        </div>
        <div className="onboarding-dots" role="group" aria-label={t("onboarding.progressLabel")}>
          {STEPS.map((item, index) => (
            <button
              key={item.titleKey}
              type="button"
              aria-current={index === step}
              aria-label={`${index + 1} / ${STEPS.length}`}
              onClick={() => setStep(index)}
            />
          ))}
        </div>
        <div className="onboarding-footer">
          <label className="onboarding-remember">
            <input type="checkbox" checked={dontShowAgain} onChange={(event) => setDontShowAgain(event.target.checked)} />
            {t("onboarding.dontShow")}
          </label>
          <div className="onboarding-actions">
            {!isLast && <button type="button" className="onboarding-skip" onClick={() => finish(true)}>{t("onboarding.skip")}</button>}
            {step > 0 && <button type="button" className="button secondary" onClick={() => setStep(step - 1)}>{t("onboarding.back")}</button>}
            <button type="button" className="button primary onboarding-next" onClick={() => (isLast ? finish(true) : setStep(step + 1))}>
              {isLast ? t("onboarding.start") : t("onboarding.next")}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function RouteArt() {
  return (
    <svg viewBox="0 0 280 150" aria-hidden="true">
      <rect x="1" y="1" width="278" height="148" rx="10" fill="#f4f8ff" />
      <g stroke="#dbe7f8" strokeWidth="1">
        <path d="M0 38h280M0 76h280M0 114h280" />
        <path d="M56 0v150M112 0v150M168 0v150M224 0v150" />
      </g>
      <path d="M30 122 C 80 118, 92 74, 140 70 S 226 44, 250 34" fill="none" stroke="#c4d4ec" strokeWidth="4" strokeLinecap="round" strokeDasharray="1 8" />
      <path d="M30 122 C 70 96, 108 96, 140 84 S 216 58, 250 34" fill="none" stroke="#0b57d0" strokeWidth="5" strokeLinecap="round" />
      <circle cx="30" cy="122" r="7" fill="#fff" stroke="#0b57d0" strokeWidth="3.5" />
      <g transform="translate(250 34)">
        <path d="M0 8 C -8 -2 -7 -14 0 -14 C 7 -14 8 -2 0 8 Z" fill="#d93025" />
        <circle cx="0" cy="-6" r="3.4" fill="#fff" />
      </g>
      <g transform="translate(16 14)">
        <rect width="120" height="26" rx="13" fill="#fff" stroke="#dadce0" />
        <circle cx="17" cy="13" r="5" fill="none" stroke="#5f6368" strokeWidth="2" />
        <path d="M21 17l4 4" stroke="#5f6368" strokeWidth="2" strokeLinecap="round" />
        <rect x="32" y="10" width="66" height="6" rx="3" fill="#e8eaed" />
      </g>
    </svg>
  );
}

function CompareArt() {
  return (
    <svg viewBox="0 0 280 150" aria-hidden="true">
      <rect x="1" y="1" width="278" height="148" rx="10" fill="#f4f8ff" />
      <circle cx="26" cy="75" r="7" fill="#fff" stroke="#202124" strokeWidth="3.5" />
      <g transform="translate(254 75)">
        <path d="M0 9 C -9 -2 -8 -15 0 -15 C 8 -15 9 -2 0 9 Z" fill="#202124" />
        <circle cx="0" cy="-7" r="3.4" fill="#fff" />
      </g>
      <path d="M34 68 C 90 26, 190 26, 246 66" fill="none" stroke="#f9ab00" strokeWidth="4.5" strokeLinecap="round" />
      <path d="M34 75 H 246" fill="none" stroke="#d93025" strokeWidth="4.5" strokeLinecap="round" strokeDasharray="10 7" />
      <path d="M34 82 C 90 124, 190 124, 246 84" fill="none" stroke="#188038" strokeWidth="4.5" strokeLinecap="round" />
      <g fontFamily="DM Mono, monospace" fontSize="11" fontWeight="700" textAnchor="middle">
        <g transform="translate(140 22)"><rect x="-24" y="-13" width="48" height="20" rx="10" fill="#fff" stroke="#f9ab00" /><text y="3" fill="#b06000">42</text></g>
        <g transform="translate(140 62)"><rect x="-24" y="-13" width="48" height="20" rx="10" fill="#fff" stroke="#d93025" /><text y="3" fill="#b3261e">71</text></g>
        <g transform="translate(140 132)"><rect x="-24" y="-13" width="48" height="20" rx="10" fill="#fff" stroke="#188038" /><text y="3" fill="#188038">18</text></g>
      </g>
    </svg>
  );
}

function MonitorArt() {
  return (
    <svg viewBox="0 0 280 150" aria-hidden="true">
      <rect x="1" y="1" width="278" height="148" rx="10" fill="#f4f8ff" />
      <rect x="26" y="22" width="228" height="106" rx="12" fill="#fff" stroke="#e3e8ef" />
      <path d="M46 60 C 86 42, 120 78, 156 62 S 214 48, 232 56" fill="none" stroke="#0b57d0" strokeWidth="4" strokeLinecap="round" />
      <circle cx="46" cy="60" r="5" fill="#fff" stroke="#0b57d0" strokeWidth="3" />
      <g transform="translate(232 56)">
        <path d="M0 6 C -6 -1 -5 -10 0 -10 C 5 -10 6 -1 0 6 Z" fill="#d93025" />
        <circle cx="0" cy="-4" r="2.4" fill="#fff" />
      </g>
      <g transform="translate(46 96)">
        <rect width="186" height="10" rx="5" fill="#e8eaed" />
        <rect width="70" height="10" rx="5" fill="#188038" />
        <rect x="70" width="58" height="10" fill="#f9ab00" opacity=".85" />
        <rect x="128" width="58" height="10" rx="0" fill="#d93025" opacity=".8" />
        <rect x="118" y="-5" width="3" height="20" rx="1.5" fill="#202124" />
      </g>
      <g transform="translate(216 20)">
        <path d="M12 2 C 6 2 4 6.5 4 11 L 4 17 L 2 20 H 22 L 20 17 V 11 C 20 6.5 18 2 12 2 Z" fill="#f9ab00" />
        <path d="M9 22 a3 3 0 0 0 6 0 Z" fill="#f9ab00" />
        <circle cx="20" cy="4" r="5" fill="#d93025" stroke="#fff" strokeWidth="2" />
      </g>
      <g transform="translate(40 20)">
        <path d="M4 2 h9 v16 l-4.5 -3.4 L4 18 Z" fill="#0b57d0" />
      </g>
    </svg>
  );
}
