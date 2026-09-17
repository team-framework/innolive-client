"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Button } from "@/components/button";
import { FaceRegistrationOverlay } from "@/components/face-registration-overlay";
import { WaitingOverlay } from "@/components/waiting-overlay";
import { cn } from "@/lib/cn";

type Preview = "guest" | "active" | "member";
type Overlay = "none" | "face" | "waiting";

const previews: { id: Preview; label: string }[] = [
  { id: "guest", label: "게스트" },
  { id: "active", label: "체험 중" },
  { id: "member", label: "회원" },
];

export function TryOutView() {
  const [preview, setPreview] = useState<Preview>("guest");
  const [overlay, setOverlay] = useState<Overlay>("none");
  const [notice, setNotice] = useState<string | null>(null);
  const overlayRef = useRef<HTMLDivElement>(null);
  const lastFocus = useRef<HTMLElement | null>(null);
  const noticeId = useId();

  useEffect(() => {
    if (overlay === "none") {
      lastFocus.current?.focus();
      return;
    }
    lastFocus.current = document.activeElement as HTMLElement | null;
    const node = overlayRef.current;
    const close = node?.querySelector<HTMLElement>("[data-overlay-close]");
    close?.focus();

    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setOverlay("none");
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [overlay]);

  const unavailable = () => {
    setNotice("체험 기능을 준비 중입니다");
  };

  return (
    <section
      className="flex w-full flex-col items-center px-[var(--page-gutter)] pb-16 pt-16 lg:pt-24 min-[106.5rem]:pb-[5.25rem] min-[106.5rem]:pt-[9.5rem]"
      aria-labelledby="try-out-heading"
    >
      <div className="mb-8 flex w-full max-w-[100rem] flex-col items-start gap-2 text-text-secondary">
        <p className="text-sm font-medium">화면 미리보기</p>
        <div className="flex flex-wrap gap-2">
          {previews.map((item) => (
            <button
              key={item.id}
              type="button"
              className={cn(
                "rounded-pill px-3 py-1.5 text-sm leading-none",
                preview === item.id
                  ? "bg-button-secondary text-text-reversed"
                  : "bg-background-secondary text-text-primary shadow-button",
              )}
              aria-pressed={preview === item.id}
              onClick={() => {
                setPreview(item.id);
                setOverlay("none");
                setNotice(null);
              }}
            >
              {item.label}
            </button>
          ))}
          <button
            type="button"
            className="rounded-pill bg-background-secondary px-3 py-1.5 text-sm leading-none text-text-primary shadow-button"
            onClick={() => setOverlay("waiting")}
          >
            대기 안내
          </button>
        </div>
      </div>

      <div className="flex w-full max-w-[100rem] flex-col items-center gap-[4.125rem]">
        <div className="flex flex-col items-center gap-8 text-center text-text-primary">
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
          {preview === "active" ? (
            <div className="flex w-full flex-col gap-2.5 lg:flex-row">
              <div className="aspect-[795/447] min-h-[14.0625rem] w-full rounded-[12px] bg-background-secondary lg:max-h-[27.95rem]" />
              <div className="aspect-[795/447] min-h-[14.0625rem] w-full rounded-[12px] bg-background-secondary lg:max-h-[27.95rem]" />
            </div>
          ) : (
            <div className="relative flex aspect-[16/9] w-full items-center justify-center overflow-clip rounded-[12px] bg-background-secondary">
              {preview === "guest" ? (
                <p className="px-4 text-center text-[clamp(1.5rem,1rem+2vw,3rem)] font-normal leading-none text-text-primary">
                  카메라 권한을 허용해 주세요
                </p>
              ) : null}
            </div>
          )}

          <div className="flex w-full max-w-[32.3125rem] flex-col items-center">
            <div className="flex flex-wrap items-start justify-center gap-x-3 gap-y-4 p-2.5">
              {preview === "guest" ? (
                <>
                  <Button
                    showChevron={false}
                    className="w-[14.375rem] max-w-[14.375rem]"
                    onClick={unavailable}
                    aria-describedby={notice ? noticeId : undefined}
                  >
                    비회원으로 체험 시작
                  </Button>
                  <Button
                    variant="secondary"
                    href="/login"
                    showChevron={false}
                    className="w-[14.375rem] max-w-[14.375rem]"
                  >
                    회원가입 또는 로그인
                  </Button>
                </>
              ) : null}
              {preview === "active" ? (
                <>
                  <Button
                    showChevron={false}
                    className="w-[8.6875rem] max-w-[8.6875rem]"
                    onClick={() => {
                      setPreview("guest");
                      setNotice(null);
                    }}
                  >
                    체험 중지
                  </Button>
                  <Button
                    variant="secondary"
                    showChevron={false}
                    className="w-[8.6875rem] max-w-[8.6875rem]"
                    onClick={() => setOverlay("face")}
                  >
                    얼굴 등록
                  </Button>
                </>
              ) : null}
              {preview === "member" ? (
                <Button
                  showChevron={false}
                  className="w-[13rem] max-w-[13rem]"
                  onClick={unavailable}
                  aria-describedby={notice ? noticeId : undefined}
                >
                  비식별화 체험하기
                </Button>
              ) : null}
            </div>
            {preview !== "member" ? (
              <p className="w-full text-center text-xl font-medium text-text-secondary">
                Tip! 회원가입 시 대기 시간이 줄어듭니다.
              </p>
            ) : null}
            <p className="w-full text-center text-xl font-medium text-text-secondary">
              네트워크 환경에 따라 성능에 편차가 있을 수 있습니다.
            </p>
            {notice ? (
              <p
                id={noticeId}
                role="status"
                className="mt-2 w-full text-center text-base font-medium text-text-primary"
              >
                {notice}
              </p>
            ) : null}
          </div>
        </div>
      </div>

      {overlay !== "none" ? (
        <div
          className="fixed inset-0 z-40 flex items-center justify-center bg-black/40 p-4"
          onClick={() => setOverlay("none")}
        >
          <div
            ref={overlayRef}
            role="dialog"
            aria-modal="true"
            aria-label={overlay === "face" ? "얼굴 등록" : "대기 안내"}
            className="relative max-h-[90dvh] overflow-auto"
            onClick={(event) => event.stopPropagation()}
          >
            <button
              type="button"
              data-overlay-close
              className="absolute top-3 right-3 z-10 rounded-pill bg-background-secondary px-3 py-1 text-sm text-text-primary shadow-button"
              onClick={() => setOverlay("none")}
            >
              닫기
            </button>
            {overlay === "face" ? <FaceRegistrationOverlay /> : <WaitingOverlay />}
          </div>
        </div>
      ) : null}
    </section>
  );
}
