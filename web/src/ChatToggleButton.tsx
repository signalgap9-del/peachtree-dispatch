import { MessageSquare } from "lucide-react";

import { useI18n } from "./i18n";

export function ChatToggleButton({
  open,
  unread,
  attention,
  onClick,
}: {
  open: boolean;
  unread: number;
  attention: boolean;
  onClick: () => void;
}) {
  const { t } = useI18n();
  const label = open ? t("chat.close") : t("chat.open");
  return (
    <button
      type="button"
      className={`chat-toggle${attention && !open ? " attention" : ""}`}
      onClick={onClick}
      aria-label={label}
      title={label}
      aria-expanded={open}
    >
      <MessageSquare size={21} />
      {unread > 0 && !open && (
        <em className="chat-toggle-badge" aria-label={`${unread} ${t("chat.unread")}`}>
          {unread > 9 ? "9+" : unread}
        </em>
      )}
    </button>
  );
}
