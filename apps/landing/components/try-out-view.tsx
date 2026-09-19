"use client";

import { Button } from "@/components/button";
import { useIsLogined } from "@/hooks/use-is-logined";

export function TryOutView() {
  const { isLogined, isLoading } = useIsLogined();
  const audience = isLoading ? "확인 중" : isLogined ? "회원" : "게스트";

  return (
    <section
      className="flex w-full flex-col items-center px-[var(--page-gutter)] pb-16 pt-16 lg:pt-24 min-[106.5rem]:pb-[5.25rem] min-[106.5rem]:pt-[9.5rem]"
      aria-labelledby="try-out-heading"
    >
      <div className="flex w-full max-w-[100rem] flex-col items-center gap-[4.125rem]">
        <div className="flex flex-col items-center gap-8 text-center text-text-primary">
          <p className="text-sm font-medium text-text-secondary">{audience}</p>
          <h1
            id="try-out-heading"
            className="break-keep text-[clamp(2rem,1.2rem+3.2vw,4rem)] font-bold leading-none"
          >
            보이는 순간, 보호는 시작
          </h1>
          <p className="break-keep text-body-lg font-normal">
            InnoLive의 뛰어난 잠재력을 눈으로 직접 확인해 보세요.
          </p>
        </div>

        <div className="flex w-full flex-col items-center gap-[0.5625rem]">
          <div className="relative flex aspect-[16/9] w-full items-center justify-center overflow-clip rounded-[12px] bg-background-secondary">
            <p className="px-4 text-center text-[clamp(1.5rem,1rem+2vw,3rem)] font-normal leading-none text-text-primary">
              카메라 권한을 허용해 주세요
            </p>
          </div>

          <div className="flex w-full max-w-[32.3125rem] flex-col items-center">
            <div className="flex flex-wrap items-start justify-center gap-x-3 gap-y-4 p-2.5">
              <Button
                href="/try-out/experience"
                showChevron={false}
                className="w-[14.375rem] max-w-[14.375rem]"
                disabled={isLoading}
              >
                {isLoading
                  ? "확인 중"
                  : isLogined
                    ? "회원으로 체험 시작"
                    : "비회원으로 체험 시작"}
              </Button>
            </div>
            <p className="w-full text-center text-xl font-medium text-text-secondary">
              네트워크 환경에 따라 성능에 편차가 있을 수 있습니다.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
