import { AlertTriangle, CheckCircle2, Info, X } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import { useI18n } from "./i18n";
import type { NotifyKind } from "./ui";

type ToastItem = { id: number; kind: NotifyKind; message: string };

let nextToastId = 0;

const TOAST_ICONS: Record<NotifyKind, typeof Info> = {
  success: CheckCircle2,
  error: AlertTriangle,
  info: Info,
};

/**
 * Global toast stack. Listens on the `freightscaler:toast` event bus so any
 * module can call `notify(message, kind)` from `./ui` without prop drilling.
 */
export function ToastHost() {
  const { t } = useI18n();
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const dismiss = useCallback((id: number) => {
    setToasts((items) => items.filter((item) => item.id !== id));
  }, []);

  useEffect(() => {
    const onToast = (event: Event) => {
      const detail = (event as CustomEvent<string | { message: string; kind?: NotifyKind }>).detail;
      const message = typeof detail === "string" ? detail : detail.message;
      const kind: NotifyKind = typeof detail === "string" ? "info" : detail.kind ?? "info";
      if (!message) return;
      const id = ++nextToastId;
      // Keep at most three toasts on screen; oldest drops first.
      setToasts((items) => [...items.slice(-2), { id, kind, message }]);
      window.setTimeout(() => dismiss(id), 4500);
    };
    window.addEventListener("freightscaler:toast", onToast);
    return () => window.removeEventListener("freightscaler:toast", onToast);
  }, [dismiss]);

  if (!toasts.length) return null;
  return (
    <div className="toast-stack" role="status" aria-live="polite">
      {toasts.map((toast) => {
        const Icon = TOAST_ICONS[toast.kind];
        return (
          <div key={toast.id} className={`toast toast-${toast.kind}`}>
            <Icon size={16} aria-hidden="true" />
            <span>{toast.message}</span>
            <button type="button" onClick={() => dismiss(toast.id)} aria-label={t("toast.dismiss")}><X size={14} /></button>
          </div>
        );
      })}
    </div>
  );
}
