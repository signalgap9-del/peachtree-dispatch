export type NotifyKind = "success" | "error" | "info";

export function notify(message: string, kind: NotifyKind = "info") {
  window.dispatchEvent(new CustomEvent("freightscaler:toast", { detail: { message, kind } }));
}
