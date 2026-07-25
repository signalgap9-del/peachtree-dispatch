import { ChevronDown, Send, Sparkles, Square, Trash2, X } from "lucide-react";
import { useEffect, useRef, useState, type ChangeEvent, type KeyboardEvent } from "react";

import { useI18n, type CopyKey } from "./i18n";
import { useLlmChat, type ChatMessage, type ChatProgressStage } from "./useLlmChat";

export type ChatDraft = { text: string; nonce: number };

type Props = {
  open: boolean;
  onClose: () => void;
  /** Pre-filled input pushed by other surfaces (e.g. the proactive suggestion banner). */
  draft?: ChatDraft | null;
};

const PROGRESS_KEYS: Record<ChatProgressStage, CopyKey> = {
  intent: "chat.progress.intent",
  extracting: "chat.progress.extracting",
  solving: "chat.progress.solving",
  interpreting: "chat.progress.interpreting",
};

/**
 * Slide-in AI assistant drawer. Stays mounted so the open/close
 * transition runs and conversation state survives closing; visibility
 * is driven by the `.open` class.
 */
export function ChatPanel({ open, onClose, draft }: Props) {
  const { t, language } = useI18n();
  const chat = useLlmChat();
  const [input, setInput] = useState("");
  const [citationsOpen, setCitationsOpen] = useState<Record<string, boolean>>({});
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  useEffect(() => {
    if (!draft) return;
    setInput(draft.text);
    if (open) inputRef.current?.focus();
  }, [draft, open]);

  useEffect(() => {
    const scroller = scrollRef.current;
    if (scroller) scroller.scrollTop = scroller.scrollHeight;
  }, [chat.messages]);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [open, onClose]);

  function submit() {
    const text = input.trim();
    if (!text || chat.isStreaming) return;
    chat.sendMessage(text, language);
    setInput("");
    const area = inputRef.current;
    if (area) area.style.height = "auto";
  }

  function handleInput(event: ChangeEvent<HTMLTextAreaElement>) {
    setInput(event.target.value);
    const area = event.target;
    area.style.height = "auto";
    area.style.height = `${Math.min(area.scrollHeight, 120)}px`;
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      submit();
    }
  }

  function toggleCitations(messageId: string) {
    setCitationsOpen((previous) => ({ ...previous, [messageId]: !previous[messageId] }));
  }

  // Screen-reader mirror of the streaming assistant turn: announces stage
  // changes while streaming and the full answer once it completes, without
  // reading every token aloud.
  const lastMessage = chat.messages.at(-1);
  const liveAnnouncement = lastMessage?.role !== "assistant"
    ? ""
    : lastMessage.status === "streaming"
      ? lastMessage.progress ? t(PROGRESS_KEYS[lastMessage.progress]) : ""
      : lastMessage.status === "complete"
        ? lastMessage.content
        : chat.error ?? t("chat.error");

  return (
    <section className={`chat-panel${open ? " open" : ""}`} role="dialog" aria-label={t("chat.title")} aria-hidden={!open}>
      <header className="chat-panel-head">
        <span className="chat-head-mark"><Sparkles size={16} /></span>
        <div className="chat-head-text">
          <strong>{t("chat.title")}</strong>
          <small className="chat-live-dot"><i aria-hidden="true" />{chat.isStreaming && chat.messages.at(-1)?.progress ? t(PROGRESS_KEYS[chat.messages.at(-1)!.progress!]) : "AtmosPath"}</small>
        </div>
        <button type="button" className="chat-head-action" onClick={chat.clearChat} disabled={chat.messages.length === 0} aria-label={t("chat.clear")}><Trash2 size={15} /></button>
        <button type="button" className="chat-head-action" onClick={onClose} aria-label={t("chat.close")}><X size={16} /></button>
      </header>
      <div className="chat-scroll" ref={scrollRef}>
        {chat.messages.length === 0 ? (
          <div className="chat-empty">
            <span className="chat-empty-mark"><Sparkles size={20} /></span>
            <strong>{t("chat.empty.title")}</strong>
            <p>{t("chat.empty.detail")}</p>
            <div className="chat-quick-actions">
              <button type="button" onClick={() => chat.sendMessage(t("chat.quick.planPrompt"), language)}>{t("chat.quick.seattle")}</button>
              <button type="button" onClick={() => chat.sendMessage(t("chat.quick.nationalPrompt"), language)}>{t("chat.quick.national")}</button>
              <button type="button" onClick={() => chat.sendMessage(t("chat.quick.riskPrompt"), language)}>{t("chat.quick.risk")}</button>
              <button type="button" onClick={() => chat.sendMessage(t("chat.quick.alertsPrompt"), language)}>{t("chat.quick.alerts")}</button>
            </div>
            <span className="chat-empty-stack">ATMOSPATH · LLM ASSISTANT</span>
          </div>
        ) : (
          chat.messages.map((message) => (message.role === "user"
            ? (
              <div key={message.id} className="chat-row user">
                <div className="chat-message user">
                  <p className="chat-message-text">{message.content}</p>
                  <time className="chat-message-time" dateTime={message.timestamp}>{formatClock(message.timestamp)}</time>
                </div>
              </div>
            )
            : (
              <AssistantMessage
                key={message.id}
                message={message}
                errorMessage={chat.error}
                citationsExpanded={Boolean(citationsOpen[message.id])}
                onToggleCitations={() => toggleCitations(message.id)}
                onRetry={chat.retry}
              />
            )))
        )}
      </div>
      <div className="chat-input">
        <textarea
          ref={inputRef}
          rows={1}
          value={input}
          onChange={handleInput}
          onKeyDown={handleKeyDown}
          placeholder={t("chat.placeholder")}
          aria-label={t("chat.placeholder")}
        />
        {chat.isStreaming ? (
          <button type="button" className="chat-send stop" onClick={chat.stop} aria-label={t("chat.stop")}><Square size={13} /></button>
        ) : (
          <button type="button" className="chat-send" onClick={submit} disabled={!input.trim()} aria-label={t("chat.send")}><Send size={15} /></button>
        )}
      </div>
      <p className="chat-disclaimer">{t("chat.disclaimer")}</p>
      <span className="sr-only" role="status" aria-live="polite">{liveAnnouncement}</span>
    </section>
  );
}

function AssistantMessage({ message, errorMessage, citationsExpanded, onToggleCitations, onRetry }: {
  message: ChatMessage;
  errorMessage: string | null;
  citationsExpanded: boolean;
  onToggleCitations: () => void;
  onRetry: () => void;
}) {
  const { t } = useI18n();
  const showProgress = message.status === "streaming" && !message.content;
  const citations = message.citations ?? [];
  return (
    <div className="chat-row">
      <span className="chat-avatar"><Sparkles size={13} /></span>
      <div className={`chat-message assistant${message.status === "error" ? " error" : ""}`}>
        {showProgress && message.progress ? (
          <span className="chat-progress">
            <span className="chat-progress-dots" aria-hidden="true"><i /><i /><i /></span>
            {t(PROGRESS_KEYS[message.progress])}
          </span>
        ) : message.content ? (
          <p className="chat-message-text">
            {message.content}
            {message.status === "streaming" && <span className="chat-caret" aria-hidden="true" />}
          </p>
        ) : message.status === "streaming" ? (
          <p className="chat-message-text"><span className="chat-caret" aria-hidden="true" /></p>
        ) : null}
        {message.status === "error" && (
          <span className="chat-message-error">
            {errorMessage ?? t("chat.error")}
            <button type="button" onClick={onRetry}>{t("chat.retry")}</button>
          </span>
        )}
        {citations.length > 0 && (
          <div className="chat-citations">
            <button type="button" onClick={onToggleCitations} aria-expanded={citationsExpanded}>
              {t("chat.citations")} <em>{citations.length}</em>
              <ChevronDown size={12} className={citationsExpanded ? "expanded" : ""} />
            </button>
            {citationsExpanded && <ul>{citations.map((citation) => <li key={citation}>{citation}</li>)}</ul>}
          </div>
        )}
        <time className="chat-message-time" dateTime={message.timestamp}>{formatClock(message.timestamp)}</time>
      </div>
    </div>
  );
}

function formatClock(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat(undefined, { hour: "2-digit", minute: "2-digit" }).format(date);
}
