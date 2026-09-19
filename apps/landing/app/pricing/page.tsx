"use client";

import Link from "next/link";
import { Button } from "@/components/button";
import { PlanCard } from "@/components/plan-card";
import {businessPlans, personalPlans, Plan} from "@/lib/plans";
import {SequentialHeroHeading} from "@/components/sequential-hero-heading";
import {useIsLogined} from "@/hooks/use-is-logined";
import {useRef} from "react";
import Image from "next/image";

const Arrow = ({direction, onClick}: { direction: "left" | "right"; onClick: () => void; }) => {
  return (
    <div
      className={`group sticky my-auto ${direction === "left" ? "left-0" : "right-0"} z-10 shrink-0 rounded-full p-[20px] transition-all duration-200 ease-linear hover:bg-[#00000080]`}
      onClick={onClick}
    >
      <Image
        width={28}
        height={28}
        src={`/icons/arrow-${direction}.svg`}
        alt={`scroll ${direction}`}
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
}: {
  label: string;
  plans: typeof personalPlans;
  isLogined: boolean;
  isLoading: boolean;
  className?: string;
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
          onClick={() => handleScroll("r")}
        />
      )}
    </div>
  );
}

function TermsNote() {
  return (
    <p className="flex flex-wrap items-center justify-center gap-1 text-xl leading-none">
      <span className="text-text-primary">관련 약관이 적용됩니다.</span>
      <Link
        href="/terms"
        className="text-text-secondary underline [text-underline-position:from-font]"
      >
        자세히 보기
      </Link>
    </p>
  );
}

export default function PricingPage() {
  const { isLogined, isLoading } = useIsLogined();
  const segments = [
    { text: "InnoLive", className: "font-normal tracking-[-0.05em]" },
    { text: "의 ", className: "" },
    { text: "잠재력 해제", className: "", gradient: true },
  ];

  return (
    <main id="main" data-page="pricing" className="bg-background-primary">
      <section
        className="flex w-full flex-col items-center gap-12 overflow-x-clip px-[var(--page-gutter)] pb-[5.25rem] pt-16 lg:pt-24 min-[106.5rem]:gap-[7.5rem] min-[106.5rem]:pt-[9.5rem]"
        aria-labelledby="pricing-heading"
      >
        <div className="flex w-full max-w-[43.75rem] flex-col items-center gap-8 text-center">
          <SequentialHeroHeading segments={segments} ariaLabel={"InnoLive의 잠재력 해제"} />
          <div className="pricing-support-reveal w-full max-w-[37.0625rem] break-keep text-body-lg font-normal text-text-primary">
            <p>합리적인 가격으로 InnoLive의 모든 기능을 잠금 해제하세요.</p>
            <p>실시간 얼굴 대체, 더 높은 사용량 등이 플랜에 포함됩니다.</p>
          </div>
          <Button
            href="/login"
            showChevron={false}
            className="pricing-support-reveal pricing-support-actions min-w-[15.8125rem] w-max max-w-none whitespace-nowrap"
          >
            <span className="font-normal">InnoLive</span>{" "}
            <span className="font-semibold">플랜 구독하기</span>
          </Button>
        </div>

        <div className="flex w-full max-w-[102.5rem] flex-col items-center gap-[2.625rem]">
          <PlanScroller
            label="개인 요금제"
            plans={personalPlans}
            isLogined={isLogined}
            isLoading={isLoading}
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
            크루와 기업을 위한 플랜
          </h2>
          <p className="w-full break-keep text-body-lg font-normal">
            Crew, Business 플랜으로 보다 편리하고 저렴하게 방송을 운영하세요.
          </p>
        </div>

        <div className="flex w-full max-w-[65.25rem] flex-col items-center gap-[2.625rem]">
          <PlanScroller
            label="기업 요금제"
            plans={businessPlans}
            isLogined={isLogined}
            isLoading={isLoading}
            className="min-[68rem]:justify-center"
          />
          <TermsNote />
        </div>
      </section>
    </main>
  );
}
