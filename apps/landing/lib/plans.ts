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

type PlanCopy = {
  description: string;
  cta: string;
  features: readonly string[];
  footnotes?: readonly string[];
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
  const signup = localePath(locale, "/signup");
  const definitions: Array<{ id: string; name: string; amount: string; copy: PlanCopy }> = [
    { id: "spark", name: "Spark", amount: "0", copy: copy.spark },
    { id: "glow", name: "Glow", amount: "9,900", copy: copy.glow },
    { id: "beam", name: "Beam", amount: "19,900", copy: copy.beam },
    { id: "plasma", name: "Plasma", amount: "39,000", copy: copy.plasma },
  ];
  return definitions.map((definition): Plan => ({
    id: definition.id,
    name: definition.name,
    nameTone: definition.id === "beam" ? "streamer" : "plain",
    description: definition.copy.description,
    originalPrice: definition.id === "spark" ? undefined : { amount: definition.amount, period: month },
    price: { amount: "0", period: month, emphasize: definition.id !== "spark" },
    cta: definition.id === "spark"
      ? { label: definition.copy.cta, current: true }
      : { label: definition.copy.cta, href: signup },
    features: featureList([], definition.copy.features),
    ribbon: definition.id === "spark" ? undefined : messages.plans.ribbon,
    footnotes: definition.copy.footnotes?.length ? [...definition.copy.footnotes] : undefined,
    helpLabel: definition.id === "glow" ? copy.glow.helpLabel : undefined,
  }));
}

export function getBusinessPlans(messages: Messages, locale: Locale): Plan[] {
  const copy: Record<"crew" | "business", PlanCopy & { amount: string }> = messages.plans.business;
  const support = localePath(locale, "/support");
  return [
    {
      id: "crew",
      name: "Crew",
      nameTone: "plain",
      description: copy.crew.description,
      price: { amount: copy.crew.amount, period: "" },
      cta: { label: copy.crew.cta, href: support },
      features: featureList(["check", "sparkles"], copy.crew.features),
      footnotes: copy.crew.footnotes?.length ? [...copy.crew.footnotes] : undefined,
    },
    {
      id: "business",
      name: "Business",
      nameTone: "plain",
      description: copy.business.description,
      price: { amount: copy.business.amount, period: "" },
      cta: { label: copy.business.cta, href: support },
      features: featureList(["check", "check"], copy.business.features),
      footnotes: copy.business.footnotes?.length ? [...copy.business.footnotes] : undefined,
    },
  ];
}
