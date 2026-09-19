"use client";

import Link from "next/link";
import { Button } from "@/components/button";
import { PlanCard } from "@/components/plan-card";
import {getBusinessPlans, getPersonalPlans, type Plan} from "@/lib/plans";
import {SequentialHeroHeading} from "@/components/sequential-hero-heading";
import {useLocale} from "@/components/locale-provider";
import {useIsLogined} from "@/hooks/use-is-logined";
import {useRef} from "react";
import Image from "next/image";

const Arrow = ({
  direction,
  onClick,
  label,
}: {
  direction: "left" | "right";
  onClick: () => void;
  label: string;
}) => {
  return (
    <div
      className={`group sticky my-auto ${direction === "left" ? "left-0" : "right-0"} z-10 shrink-0 rounded-full p-[20px] transition-all duration-200 ease-linear hover:bg-[#00000080]`}
      onClick={onClick}
    >
      <Image
        width={28}
        height={28}
        src={`/icons/arrow-${direction}.svg`}
        alt={label}
        className="transition-[filter] group-hover:invert"
      />
    </div>
  );
};

function PlanScroller({
  label,
  plans,
  isLogined,
  isLoading,
  className,
  scrollLeft,
  scrollRight,
}: {
  label: string;
  plans: Plan[];
  isLogined: boolean;
  isLoading: boolean;
  className?: string;
  scrollLeft: string;
  scrollRight: string;
}) {
  const plansRef = useRef<HTMLDivElement>(null);

  const handleScroll = (direction: "l" | "r") => {
    if (!plansRef.current) {
      return;
    }

    plansRef.current.scrollBy({
      left: direction === "l" ? -400 : 400,
      behavior: "smooth",
    });
  }

  return (
    <div className="relative flex w-full">
      {plans.length > 3 && (
        <Arrow
          direction="left"
          label={scrollLeft}
          onClick={() => handleScroll("l")}
        />
      )}

      <div
        role="region"
        aria-label={label}
        tabIndex={0}
        className={`flex w-full snap-x gap-7 overflow-x-auto overflow-y-clip overscroll-x-contain pb-4 pt-10 ${className ?? ""}`}
        ref={plansRef}
      >
        {plans.map((plan: Plan) => (
          <div
            key={plan.id}
            className="w-[min(100%,30rem)] shrink-0 snap-start"
          >
            <PlanCard
              plan={plan}
              isLogined={isLogined}
              isLoading={isLoading}
            />
          </div>
        ))}
      </div>

      {plans.length > 3 && (
        <Arrow
          direction="right"
          label={scrollRight}
          onClick={() => handleScroll("r")}
        />
      )}
    </div>
  );
}

function TermsNote() {
  const { href, messages } = useLocale();
  return (
    <p className="flex flex-wrap items-center justify-center gap-1 text-xl leading-none">
      <span className="text-text-primary">{messages.pricing.termsNote}</span>
      <Link
        href={href("/terms")}
        className="text-text-secondary underline [text-underline-position:from-font]"
      >
        {messages.pricing.termsLink}
      </Link>
    </p>
  );
}

export default function PricingPage() {
  const { href, locale, messages } = useLocale();
  const { isLogined, isLoading } = useIsLogined();
  const personalPlans = getPersonalPlans(messages, locale);
  const businessPlans = getBusinessPlans(messages, locale);
  const segments = [
    { text: messages.pricing.headingBrand, className: "font-normal tracking-[-0.05em]" },
    { text: messages.pricing.headingMid, className: "" },
    { text: messages.pricing.headingAccent, className: "", gradient: true },
  ];

  return (
    <main id="main" data-page="pricing" className="bg-background-primary">
      <section
        className="flex w-full flex-col items-center gap-12 overflow-x-clip px-[var(--page-gutter)] pb-[5.25rem] pt-16 lg:pt-24 min-[106.5rem]:gap-[7.5rem] min-[106.5rem]:pt-[9.5rem]"
        aria-labelledby="pricing-heading"
      >
        <div className="flex w-full max-w-[43.75rem] flex-col items-center gap-8 text-center">
          <SequentialHeroHeading segments={segments} ariaLabel={messages.pricing.ariaLabel} />
          <div className="pricing-support-reveal w-full max-w-[37.0625rem] break-keep text-body-lg font-normal text-text-primary">
            <p>{messages.pricing.body1}</p>
            <p>{messages.pricing.body2}</p>
          </div>
          <Button
            href={href("/login")}
            showChevron={false}
            className="pricing-support-reveal pricing-support-actions min-w-[15.8125rem] w-max max-w-none whitespace-nowrap"
          >
            <span className="font-normal">{messages.pricing.subscribeBrand}</span>{" "}
            <span className="font-semibold">{messages.pricing.subscribe}</span>
          </Button>
        </div>

        <div className="flex w-full max-w-[102.5rem] flex-col items-center gap-[2.625rem]">
          <PlanScroller
            label={messages.pricing.personalLabel}
            plans={personalPlans}
            isLogined={isLogined}
            isLoading={isLoading}
            scrollLeft={messages.pricing.scrollLeft}
            scrollRight={messages.pricing.scrollRight}
          />
          <TermsNote />
        </div>
      </section>

      <section
        className="flex w-full flex-col items-center gap-12 bg-background-secondary px-[var(--page-gutter)] py-[5.25rem] min-[106.5rem]:gap-[7.5rem]"
        aria-labelledby="business-plans-heading"
      >
        <div className="flex w-full max-w-[41.25rem] flex-col items-center gap-8 text-center text-text-primary">
          <h2
            id="business-plans-heading"
            className="w-full break-keep text-[clamp(1.75rem,1.2rem+2vw,3rem)] font-bold leading-none"
          >
            {messages.pricing.businessTitle}
          </h2>
          <p className="w-full break-keep text-body-lg font-normal">
            {messages.pricing.businessBody}
          </p>
        </div>

        <div className="flex w-full max-w-[65.25rem] flex-col items-center gap-[2.625rem]">
          <PlanScroller
            label={messages.pricing.businessLabel}
            plans={businessPlans}
            isLogined={isLogined}
            isLoading={isLoading}
            className="min-[68rem]:justify-center"
            scrollLeft={messages.pricing.scrollLeft}
            scrollRight={messages.pricing.scrollRight}
          />
          <TermsNote />
        </div>
      </section>
    </main>
  );
}
