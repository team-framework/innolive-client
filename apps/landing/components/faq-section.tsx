"use client";

import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import Image from "next/image";
import Link from "next/link";
import { useLayoutEffect, useRef, type ReactNode } from "react";

const items: { question: string; answer: ReactNode }[] = [
  {
    question: "Q. InnoLive는 어떤 서비스인가요?",
    answer:
      "InnoLive는 라이브 방송 중 다른 사람의 얼굴이 노출되지 않도록 실시간으로 비식별화해 주는 방송 지원 서비스입니다. 방송에 얼굴 노출이 허용된 사람을 등록하면 등록되지 않은 사람은 자동으로 가려집니다.",
  },
  {
    question: "Q. 제 기기에서 애플리케이션이 작동하지 않아요.",
    answer:
      "InnoLive는 애플리케이션의 안정적인 작동을 위해 iOS 26 이상, Android 11 이상의 OS를 요구하고 있습니다.",
  },
  {
    question: "Q. 어떤 방송 플랫폼에서 사용할 수 있나요?",
    answer: "InnoLive는 현재 YouTube, CHZZK을 지원하고 있습니다.",
  },
  {
    question: "Q. 제 얼굴이 방송에서 가려져요.",
    answer:
      "방송에 얼굴을 공개하려면 먼저 본인의 얼굴을 공개 대상자로 등록해야 합니다. 이미 등록했다면 얼굴이 카메라에 충분히 보이는지 확인해 주세요.",
  },
  {
    question: "Q. 여러 명의 얼굴을 등록할 수 있나요?",
    answer:
      "네. 방송에 함께 출연하는 사람들을 각각 공개 대상자로 등록할 수 있습니다. Free 티어에서는 최대 2명, Streamer 이상의 티어에서는 최대 5명을 등록할 수 있습니다.",
  },
  {
    question: "Q. 회원가입 시 제공한 정보와 등록한 얼굴은 어떻게 처리되나요?",
    answer: (
      <>
        <Link
          href="/privacy"
          className="underline [text-underline-position:from-font]"
        >
          개인정보 처리방침
        </Link>
        에서 구체적인 개인정보 처리방침을 확인할 수 있습니다.
      </>
    ),
  },
];

export function FAQSection() {
  const sectionRef = useRef<HTMLElement>(null);

  useLayoutEffect(() => {
    const section = sectionRef.current;
    if (!section) return;
    gsap.registerPlugin(ScrollTrigger);
    const media = gsap.matchMedia();
    media.add("(prefers-reduced-motion: no-preference)", () => {
      const entries = section.querySelectorAll("details");
      gsap.set(entries, { opacity: 0, y: 32 });
      ScrollTrigger.batch(entries, {
        start: "top 90%",
        once: true,
        onEnter: (batch) => {
          gsap.to(batch, { opacity: 1, y: 0, duration: 0.6, stagger: 0.12, ease: "power2.out" });
        },
      });
      // Keyboard focus must never remain on an invisible question or answer link.
      const revealFocused = (event: FocusEvent) => {
        const entry = (event.target as HTMLElement).closest("details");
        if (entry) gsap.to(entry, { opacity: 1, y: 0, duration: 0, overwrite: true });
      };
      section.addEventListener("focusin", revealFocused);
      return () => section.removeEventListener("focusin", revealFocused);
    }, section);
    return () => media.revert();
  }, []);

  return (
    <section
      ref={sectionRef}
      id="faq"
      className="flex w-full flex-col items-center bg-background-secondary px-[var(--page-gutter)] py-16 lg:py-24 min-[106.5rem]:py-40"
      aria-labelledby="faq-heading"
    >
      <div className="flex w-full max-w-[1408px] flex-col items-stretch gap-12">
        <div className="flex w-full flex-col items-start justify-between gap-6 lg:flex-row lg:items-start lg:gap-x-16">
          <h2
            id="faq-heading"
            className="break-words text-[clamp(2rem,1.05rem+4.2vw,4.25rem)] font-bold leading-[1.3] tracking-tight text-text-primary"
          >
            FAQ
          </h2>
          <p className="break-keep text-body-lg font-normal text-text-primary">
            자주 묻는 질문
          </p>
        </div>

        <div className="flex w-full flex-col gap-3">
          {items.map((item) => (
            <details
              key={item.question}
              open
              className="flex w-full flex-col gap-2.5 py-2 open:[&_summary_img]:rotate-180"
            >
              <summary className="flex w-full cursor-pointer list-none items-center gap-2.5 py-2 [&::-webkit-details-marker]:hidden [&::marker]:content-none">
                <span className="min-w-0 flex-1 break-keep text-left text-[clamp(1.125rem,0.95rem+0.85vw,1.625rem)] font-medium leading-[1.15] text-text-primary">
                  {item.question}
                </span>
                <Image
                  src="/icons/chevron-down.svg"
                  alt=""
                  width={24}
                  height={24}
                  unoptimized
                  className="size-6 shrink-0 transition-transform"
                />
              </summary>
              <div className="px-3 py-2">
                <p className="break-keep text-xl font-normal leading-[1.15] text-text-primary">
                  {item.answer}
                </p>
              </div>
            </details>
          ))}
        </div>
      </div>
    </section>
  );
}
