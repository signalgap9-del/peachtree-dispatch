import { useI18n, type Language } from "./i18n";

export function LanguageToggle() {
  const { language, setLanguage, t } = useI18n();
  return (
    <div className="language-toggle" role="group" aria-label={t("language.label")}>
      {(["en", "ko"] as Language[]).map((option) => (
        <button key={option} className={language === option ? "active" : ""} onClick={() => setLanguage(option)} type="button">
          {t(option === "en" ? "language.en" : "language.ko")}
        </button>
      ))}
    </div>
  );
}
