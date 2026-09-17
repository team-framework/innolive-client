import Link from "next/link";
import { Button } from "@/components/button";
import { PlanCard } from "@/components/plan-card";
import { businessPlans, personalPlans } from "@/lib/plans";

function PlanScroller({
  label,
  plans,
  className,
}: {
  label: string;
  plans: typeof personalPlans;
  className?: string;
}) {
  return (
    <div
      role="region"
      aria-label={label}
      tabIndex={0}
      className={`flex w-full snap-x gap-7 overflow-x-auto overflow-y-clip overscroll-x-contain pb-4 pt-10 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-text-primary ${className ?? ""}`}
    >
      {plans.map((plan) => (
        <div key={plan.id} className="snap-start">
          <PlanCard plan={plan} />
        </div>
      ))}
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
  return (
    <main id="main" data-page="pricing" className="bg-background-primary">
      <section
        className="flex w-full flex-col items-center gap-12 overflow-x-clip px-[var(--page-gutter)] pb-[5.25rem] pt-16 lg:pt-24 min-[106.5rem]:gap-[7.5rem] min-[106.5rem]:pt-[9.5rem]"
        aria-labelledby="pricing-heading"
      >
        <div className="flex w-full max-w-[37.0625rem] flex-col items-center gap-8 text-center">
          <h1
            id="pricing-heading"
            className="break-keep text-[clamp(2rem,1.1rem+3.6vw,4rem)] font-bold leading-none tracking-tight text-text-primary"
          >
            InnoLive의{" "}
            <span className="bg-gradient-to-r from-[#ff0000] to-[#00f2ff] bg-clip-text text-transparent">
              잠재력 해제
            </span>
          </h1>
          <div className="break-keep text-body-lg font-normal text-text-primary">
            <p>합리적인 가격으로 InnoLive의 모든 기능을 잠금 해제하세요.</p>
            <p>실시간 얼굴 대체, 더 높은 사용량 등이 플랜에 포함됩니다.</p>
          </div>
          <Button
            href="/signup"
            showChevron={false}
            className="w-[15.8125rem] max-w-[15.8125rem]"
          >
            InnoLive 플랜 구독하기
          </Button>
        </div>

        <div className="flex w-full max-w-[102.5rem] flex-col items-center gap-[2.625rem]">
          <PlanScroller label="개인 요금제" plans={personalPlans} />
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
            className="min-[64rem]:justify-center"
          />
          <TermsNote />
        </div>
      </section>
    </main>
  );
}
