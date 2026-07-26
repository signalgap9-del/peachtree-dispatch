import { AlertTriangle, ArrowRight, BadgeCheck, CalendarClock, ExternalLink, Sparkles } from "lucide-react";
import { useCallback, useEffect, useState } from "react";

import type { Navigate } from "./App";
import { api, isBillingDisabledError } from "./api";
import { currentUser, login } from "./auth";
import { useI18n } from "./i18n";
import { CallToAction, EmptyState, PageTitle, SectionHeader, UsageMeter } from "./ProductPages";
import type { AccountSummary, BillingSubscription } from "./types";

type LoadState = "loading" | "ready" | "unavailable" | "error";

const PLAN_LABEL_KEYS = {
  FREE: "account.plan.free",
  PRO: "account.plan.pro",
  TEAM: "account.plan.team",
} as const;

const PLAN_DETAIL_KEYS = {
  FREE: "account.plan.freeDetail",
  PRO: "account.plan.proDetail",
  TEAM: "account.plan.teamDetail",
} as const;

const STATUS_LABEL_KEYS = {
  ACTIVE: "billing.status.active",
  TRIALING: "billing.status.trialing",
  PAST_DUE: "billing.status.pastDue",
  SUSPENDED: "billing.status.suspended",
} as const;

function statusTone(status: string): "ok" | "warn" | "bad" {
  if (status === "ACTIVE" || status === "TRIALING") return "ok";
  if (status === "PAST_DUE") return "warn";
  return "bad";
}

function formatPeriodDate(value: string | null): string {
  if (!value) return "--";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "--";
  return date.toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric" });
}

export function AccountPage({ navigate }: { navigate: Navigate }) {
  const { t } = useI18n();
  const user = currentUser();
  // currentUser() returns a fresh object per call; depend on a stable key so
  // the load effect does not re-fire on every render.
  const userKey = user?.subject ?? user?.email ?? null;
  const [subscription, setSubscription] = useState<BillingSubscription | null>(null);
  const [account, setAccount] = useState<AccountSummary | null>(null);
  const [loadState, setLoadState] = useState<LoadState>("loading");
  const [portalState, setPortalState] = useState<"idle" | "starting" | "error">("idle");

  const load = useCallback(() => {
    if (!userKey) return;
    setLoadState("loading");
    void Promise.allSettled([api.billingSubscription({ quiet: true }), api.accountSummary()]).then(([subscriptionResult, accountResult]) => {
      if (accountResult.status === "fulfilled") setAccount(accountResult.value);
      if (subscriptionResult.status === "fulfilled") {
        setSubscription(subscriptionResult.value);
        setLoadState("ready");
      } else if (isBillingDisabledError(subscriptionResult.reason)) {
        // Billing service is not live yet: fall back to the workspace summary
        // so the page still carries its weight.
        setLoadState(accountResult.status === "fulfilled" ? "unavailable" : "error");
      } else {
        setLoadState("error");
      }
    });
  }, [userKey]);

  useEffect(() => { load(); }, [load]);

  const openPortal = async () => {
    if (subscription?.manageUrl) {
      window.location.assign(subscription.manageUrl);
      return;
    }
    setPortalState("starting");
    try {
      const { portalUrl } = await api.billingPortal();
      window.location.assign(portalUrl);
    } catch {
      setPortalState("error");
    }
  };

  if (!user) {
    return (
      <main className="page-shell account-page">
        <CallToAction
          title={t("account.signIn.title")}
          detail={t("account.signIn.detail")}
          action={t("account.signIn.action")}
          onClick={() => void login()}
          secondaryAction={t("account.comparePlans")}
          onSecondaryClick={() => navigate("/pricing")}
        />
      </main>
    );
  }

  const planCode = subscription?.plan ?? account?.plan.code ?? "FREE";
  const planLabelKey = PLAN_LABEL_KEYS[planCode as keyof typeof PLAN_LABEL_KEYS];
  const planLabel = planLabelKey ? t(planLabelKey) : planCode;
  const planDetailKey = PLAN_DETAIL_KEYS[planCode as keyof typeof PLAN_DETAIL_KEYS];
  const planDetail = planDetailKey ? t(planDetailKey) : "";
  const isPaid = planCode !== "FREE";
  const statusLabelKey = subscription ? STATUS_LABEL_KEYS[subscription.status as keyof typeof STATUS_LABEL_KEYS] : undefined;
  const statusLabel = subscription ? (statusLabelKey ? t(statusLabelKey) : subscription.status) : "";

  return (
    <main className="page-shell account-page">
      <PageTitle title={t("account.title")} subtitle={t("account.subtitle")}>
        <button className="button secondary" onClick={() => navigate("/usage")}>{t("account.viewUsage")}</button>
        <button className="button secondary" onClick={() => navigate("/pricing")}>{t("account.comparePlans")}</button>
      </PageTitle>

      {loadState === "loading" && <EmptyState title={t("account.loading")} detail={t("account.loadingDetail")} />}

      {loadState === "error" && (
        <div className="data-notice degraded" role="alert">
          <AlertTriangle size={17} />
          <span><strong>{t("account.error.title")}</strong><small>{t("account.error.detail")}</small></span>
          <button type="button" className="data-notice-retry" onClick={load}>{t("error.retry")}</button>
        </div>
      )}

      {(loadState === "ready" || loadState === "unavailable") && (
        <>
          <section className="surface account-plan" aria-labelledby="account-plan-heading">
            <div className="account-plan-head">
              <div>
                <span className="eyebrow">{t("account.plan.label")}</span>
                <h2 id="account-plan-heading">{planLabel}</h2>
                <p>{planDetail}</p>
              </div>
              <span className={`plan-chip ${planCode.toLowerCase()}`}><BadgeCheck size={15} /> {planLabel}</span>
            </div>

            {subscription ? (
              <>
                <dl className="account-facts">
                  <div>
                    <dt>{t("account.status")}</dt>
                    <dd><span className={`status-chip ${statusTone(subscription.status)}`}>{statusLabel}</span></dd>
                  </div>
                  <div>
                    <dt>{subscription.cancelAtPeriodEnd ? t("account.periodEnds") : t("account.renews")}</dt>
                    <dd className="mono">{formatPeriodDate(subscription.currentPeriodEnd)}</dd>
                  </div>
                </dl>
                {subscription.cancelAtPeriodEnd && (
                  <p className="account-cancel-note"><AlertTriangle size={16} /> {t("account.cancelNotice")}</p>
                )}
                <div className="account-plan-actions">
                  <button type="button" className="button primary" onClick={() => void openPortal()} disabled={portalState === "starting"}>
                    {portalState === "starting" ? t("account.manage.starting") : t("account.manage")} <ExternalLink size={15} />
                  </button>
                  {portalState === "error" && <span className="account-portal-error" role="alert">{t("billing.portal.error")}</span>}
                </div>
              </>
            ) : (
              <div className="account-soon" role="status">
                <CalendarClock size={18} aria-hidden="true" />
                <span><strong>{t("account.manage.soon")}</strong><small>{t("account.manage.soonDetail")}</small></span>
              </div>
            )}
          </section>

          {!isPaid && (
            <section className="account-upgrade" aria-labelledby="account-upgrade-heading">
              <Sparkles size={22} aria-hidden="true" />
              <div>
                <h3 id="account-upgrade-heading">{t("account.upgrade.title")}</h3>
                <p>{t("account.upgrade.detail")}</p>
              </div>
              <button type="button" className="button primary" onClick={() => navigate("/pricing")}>
                {t("account.upgrade.cta")} <ArrowRight size={15} />
              </button>
            </section>
          )}

          {account && (
            <section className="surface account-usage">
              <SectionHeader title={t("account.usage.title")} action={t("account.usage.viewAll")} onAction={() => navigate("/usage")} />
              <div className="usage-meter-list">
                {account.dailyUsage.map((usage) => <UsageMeter key={usage.feature} usage={usage} />)}
              </div>
            </section>
          )}
        </>
      )}
    </main>
  );
}
