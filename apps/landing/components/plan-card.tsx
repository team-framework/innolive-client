"use client";

import Image from "next/image";
import { Button } from "@/components/button";
import { useLocale } from "@/components/locale-provider";
import { OnDeviceHelp } from "@/components/on-device-help";
import { cn } from "@/lib/cn";
import type { Plan } from "@/lib/plans";

const featureIcons = {
  check: { src: "/icons/circle-check.svg", width: 24, height: 24 },
  sparkles: { src: "/icons/sparkles.svg", width: 24, height: 24 },
} as const;

function PromoRibbon({ label }: { label: string }) {
  return (
    <div
      className="pointer-events-none absolute -top-6 right-[-2.35rem] flex h-[10rem] w-[10.4rem] items-center justify-center"
      aria-hidden="true"
    >
      <Image
        src="/icons/ribbon.svg"
        alt=""
        width={166}
        height={160}
        unoptimized
        className="absolute inset-0 size-full"
      />
      <div className="relative flex-none rotate-[44.24deg] skew-x-[-1.52deg]">
        <p className="flex h-[2.42rem] w-[9.41rem] items-center justify-center text-center text-lg font-medium leading-[1.15] whitespace-nowrap text-text-reversed">
          {label}
        </p>
      </div>
    </div>
  );
}

function PriceLine({
  amount,
  period,
  emphasize,
  original,
}: {
  amount: string;
  period: string;
  emphasize?: boolean;
  original?: boolean;
}) {
  return (
    <div className="flex flex-wrap items-center gap-1">
      <p
        className={cn(
          "min-w-0 break-keep leading-[1.15] text-text-primary",
          original
            ? "text-2xl line-through [text-underline-position:from-font]"
            : emphasize
              ? "text-[2rem] font-medium"
              : "text-[2rem]",
        )}
      >
        ₩{amount}
      </p>
      <p
        className={cn(
          "flex items-center gap-1 leading-[1.15] text-text-secondary",
          original ? "text-base" : emphasize ? "text-[1.375rem]" : "text-xl",
        )}
      >
        <span>/</span>
        <span>{period}</span>
      </p>
    </div>
  );
}

export function PlanCard({
  plan,
  isLogined,
  isLoading,
}: {
  plan: Plan;
  isLogined: boolean;
  isLoading: boolean;
}) {
  const { href, messages } = useLocale();
  const footnotesId = plan.footnotes ? `${plan.id}-notes` : undefined;

  return (
    <article
      className={cn(
        "relative flex h-auto w-full flex-col gap-8 rounded-[20px] bg-gradient-to-b from-white to-[#efefefef] px-8 pb-7 pt-9 shadow-[2px_2px_12px_0_#0000000d] min-[64rem]:h-[46.25rem]",
        plan.ribbon ? "overflow-visible" : "overflow-clip",
      )}
    >
      {plan.ribbon ? <PromoRibbon label={plan.ribbon} /> : null}
      <div className="flex min-h-0 flex-1 flex-col justify-between gap-8">
        <div className="flex flex-col gap-8">
          <div className="flex flex-col gap-2.5">
            <div className="flex items-center gap-3">
              <h3
                className={cn(
                  "text-[clamp(1.5rem,1.15rem+1.4vw,2.25rem)] font-bold leading-[1.15]",
                  plan.nameTone === "streamer"
                    ? "bg-gradient-to-r from-[#00da7c] to-[#0048ff] bg-clip-text text-transparent"
                    : "text-text-primary",
                )}
              >
                {plan.name}
              </h3>
              {plan.helpLabel ? <OnDeviceHelp label={plan.helpLabel} /> : null}
            </div>
            <p className="break-keep text-[clamp(1.125rem,0.95rem+0.8vw,1.625rem)] font-normal leading-[1.15] text-text-primary">
              {plan.description}
            </p>
          </div>

          <div className="flex flex-col">
            {plan.originalPrice ? (
              <PriceLine
                amount={plan.originalPrice.amount}
                period={plan.originalPrice.period}
                original
              />
            ) : null}
            <PriceLine
              amount={plan.price.amount}
              period={plan.price.period}
              emphasize={plan.price.emphasize}
            />
          </div>

          {plan.cta.current ? (
            isLogined || isLoading ? (
              <Button
                variant="primary"
                disabled
                showChevron={false}
                className="w-full max-w-none border border-button-secondary"
              >
                {isLoading ? messages.common.loading : plan.cta.label}
              </Button>
            ) : (
              <Button
                variant="secondary"
                href={href("/login")}
                showChevron={false}
                className="w-full max-w-none"
              >
                {messages.pricing.loginCta}
              </Button>
            )
          ) : (
            <Button
              variant="secondary"
              href={plan.cta.href}
              showChevron={false}
              className="w-full max-w-none"
            >
              {plan.cta.label}
            </Button>
          )}

          <ul className="flex flex-col gap-6 overflow-clip p-3">
            {plan.features.map((feature) => {
              const icon = featureIcons[feature.icon];
              return (
                <li
                  key={feature.text}
                  className="flex min-h-6 w-full items-center gap-2"
                >
                  <Image
                    src={icon.src}
                    alt=""
                    width={icon.width}
                    height={icon.height}
                    unoptimized
                    className="size-6 shrink-0"
                  />
                  <span className="min-w-0 flex-1 break-keep text-xl leading-none text-text-primary">
                    {feature.text}
                  </span>
                </li>
              );
            })}
          </ul>
        </div>

        {plan.footnotes ? (
          <div
            id={footnotesId}
            tabIndex={-1}
            className="w-full text-base leading-[1.6] text-text-secondary underline [text-underline-position:from-font]"
          >
            {plan.footnotes.map((note) => (
              <p key={note}>{note}</p>
            ))}
          </div>
        ) : null}
      </div>
    </article>
  );
}
