import { localePath, type Locale } from "@/lib/locales";
import type { Messages } from "@/lib/messages";

export type PlanFeature = {
  icon: "check" | "sparkles";
  text: string;
};

export type Plan = {
  id: string;
  name: string;
  nameTone: "plain" | "streamer";
  description: string;
  originalPrice?: {
    amount: string;
    period: string;
  };
  price: {
    amount: string;
    period: string;
    emphasize?: boolean;
  };
  cta: {
    label: string;
    href?: string;
    current?: boolean;
  };
  features: PlanFeature[];
  ribbon?: string;
  footnotes?: string[];
  helpLabel?: string;
};

function featureList(
  icons: Array<PlanFeature["icon"]>,
  texts: readonly string[],
): PlanFeature[] {
  return texts.map((text, index) => ({
    icon: icons[index] ?? "check",
    text,
  }));
}

export function getPersonalPlans(messages: Messages, locale: Locale): Plan[] {
  const copy = messages.plans.personal;
  const month = messages.plans.periodMonth;
  const once = messages.plans.periodOnce;
  const signup = localePath(locale, "/signup");
  return [
    {
      id: "free",
      name: "Free",
      nameTone: "plain",
      description: copy.free.description,
      price: { amount: "0", period: month },
      cta: { label: copy.free.cta, current: true },
      features: featureList(
        ["check", "check", "check", "check", "check"],
        copy.free.features,
      ),
    },
    {
      id: "streamer",
      name: "Streamer",
      nameTone: "streamer",
      description: copy.streamer.description,
      originalPrice: { amount: "18,000", period: month },
      price: { amount: "0", period: month, emphasize: true },
      cta: { label: copy.streamer.cta, href: signup },
      features: featureList(
        ["sparkles", "check", "check", "check", "check"],
        copy.streamer.features,
      ),
      ribbon: messages.plans.ribbon,
    },
    {
      id: "pro",
      name: "Pro",
      nameTone: "plain",
      description: copy.pro.description,
      originalPrice: { amount: "34,000", period: month },
      price: { amount: "0", period: month, emphasize: true },
      cta: { label: copy.pro.cta, href: signup },
      features: featureList(
        ["sparkles", "check", "check", "check"],
        copy.pro.features,
      ),
      ribbon: messages.plans.ribbon,
    },
    {
      id: "on-device",
      name: "On Device",
      nameTone: "plain",
      description: copy.onDevice.description,
      originalPrice: { amount: "120,000", period: once },
      price: { amount: "0", period: once, emphasize: true },
      cta: { label: copy.onDevice.cta, href: signup },
      features: featureList(
        ["check", "check", "check", "check", "check", "check"],
        copy.onDevice.features,
      ),
      ribbon: messages.plans.ribbon,
      footnotes: [...copy.onDevice.footnotes],
      helpLabel: copy.onDevice.helpLabel,
    },
  ];
}

export function getBusinessPlans(messages: Messages, locale: Locale): Plan[] {
  const copy = messages.plans.business;
  const month = messages.plans.periodMonth;
  const signup = localePath(locale, "/signup");
  return [
    {
      id: "crew",
      name: "Crew",
      nameTone: "plain",
      description: copy.crew.description,
      price: { amount: copy.crew.amount, period: month },
      cta: { label: copy.crew.cta, href: signup },
      features: featureList(["check", "sparkles"], copy.crew.features),
    },
    {
      id: "business",
      name: "Business",
      nameTone: "plain",
      description: copy.business.description,
      price: { amount: copy.business.amount, period: month },
      cta: { label: copy.business.cta, href: signup },
      features: featureList(["check", "check"], copy.business.features),
    },
  ];
}
