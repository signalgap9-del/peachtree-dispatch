export function notify(message: string) {
  window.dispatchEvent(new CustomEvent("atmospath:toast", { detail: message }));
}
