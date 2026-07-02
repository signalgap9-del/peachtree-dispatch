import { useEffect, useMemo, useState, type ReactNode } from "react";

import { I18nContext, copy, initialLanguage, languageKey, type I18nValue, type Language } from "./i18n";

export function I18nProvider({ children }: { children: ReactNode }) {
  const [language, setLanguageState] = useState<Language>(() => initialLanguage());

  useEffect(() => {
    document.documentElement.lang = language;
  }, [language]);

  const value = useMemo<I18nValue>(() => ({
    language,
    setLanguage: (next) => {
      localStorage.setItem(languageKey, next);
      setLanguageState(next);
      document.documentElement.lang = next;
    },
    t: (key) => copy[language][key] ?? copy.en[key],
  }), [language]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
