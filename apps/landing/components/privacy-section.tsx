"use client";

import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import Image from "next/image";
import { useLayoutEffect, useRef } from "react";
import { privacySlides } from "@/lib/privacy-slides";

const phoneSizes = "(min-width: 106.5rem) 438px, (min-width: 64rem) 40vw, 80vw";

const aiAccentStyle = {
  backgroundImage:
    "linear-gradient(90deg, rgb(108, 99, 255) 0%, rgb(79, 140, 255) 8.6538%, rgb(34, 211, 238) 12.981%)",
  backgroundRepeat: "no-repeat",
  backgroundSize: "100cqw 100%",
} as const;

export function PrivacySection() {
  const sectionRef = useRef<HTMLElement>(null);

  useLayoutEffect(() => {
    const section = sectionRef.current;
    if (!section || privacySlides.length < 2) return;

    const slides = Array.from(
      section.querySelectorAll<HTMLElement>("[data-privacy-slide]"),
    );
    if (slides.length < 2) return;

    gsap.registerPlugin(ScrollTrigger);
    const media = gsap.matchMedia();
    media.add("(min-width: 64rem) and (prefers-reduced-motion: no-preference)", () => {
      const context = gsap.context(() => {
        gsap.set(slides, { autoAlpha: 0, yPercent: 100 });
        gsap.set(slides[0], { autoAlpha: 1, yPercent: 0 });
        const timeline = gsap.timeline({
          scrollTrigger: {
            anticipatePin: 1,
            end: () => `+=${window.innerHeight * (slides.length - 1)}`,
            invalidateOnRefresh: true,
            pin: true,
            scrub: 0.4,
            start: "top top",
            trigger: section,
          },
        });

        slides.slice(1).forEach((slide, index) => {
          timeline.to(slides[index], { autoAlpha: 0, duration: 1, yPercent: -100 });
          timeline.to(slide, { autoAlpha: 1, duration: 1, yPercent: 0 }, "<");
        });
      }, section);

      return () => context.revert();
    });
    media.add("(max-width: 63.999rem) and (prefers-reduced-motion: no-preference)", () => {
      const context = gsap.context(() => {
        gsap.from(section, {
          autoAlpha: 0,
          duration: 0.8,
          ease: "power2.out",
          scrollTrigger: {
            start: "top 70%",
            toggleActions: "restart none restart reverse",
            trigger: section,
          },
          y: 48,
        });
      }, section);

      return () => context.revert();
    });

    return () => media.revert();
  }, []);

  return (
    <section
      ref={sectionRef}
      className="flex h-fit w-full flex-col items-center justify-center bg-background-secondary px-[var(--page-gutter)] pb-10 pt-16 lg:h-screen lg:pb-12 lg:pt-24 min-[106.5rem]:min-h-[67.5rem] min-[106.5rem]:pb-[39px] min-[106.5rem]:pt-40"
      aria-labelledby="privacy-heading"
    >
      <div className="flex w-full max-w-[1408px] flex-col justify-center items-center gap-y-12 lg:flex-row lg:items-center lg:justify-between lg:gap-x-16">
        <div className="flex w-full min-w-0 max-w-[500px] flex-col items-start gap-2.5 lg:flex-1 min-[106.5rem]:w-[500px] min-[106.5rem]:flex-none min-[106.5rem]:pb-[121px]">
          <h2
            id="privacy-heading"
            className="w-full break-words text-[clamp(2rem,1.05rem+4.2vw,4.25rem)] font-bold leading-[1.3] tracking-tight text-text-primary"
          >
            초상권 걱정 없는
            <span className="block w-full [container-type:inline-size]">
              <span className="bg-clip-text text-transparent" style={aiAccentStyle}>
                AI{" "}
              </span>
              비식별화 라이브
            </span>
          </h2>
          <div className="w-full break-keep text-body-lg font-normal text-text-primary">
            <p>실시간 AI 비식별화 기능을 통해 배경으로 등장하는</p>
            <p>행인의 초상권과 당신의 방송을 동시에 보호하세요.</p>
          </div>
        </div>
        <div className="privacy-phone relative aspect-[438/881] min-w-0 lg:-translate-y-10 lg:flex-none">
          <Image
            src="/landing/privacy-phone.png"
            alt=""
            width={1359}
            height={2736}
            sizes={phoneSizes}
            className="absolute inset-0 size-full max-w-none z-1"
          />
          <div className="absolute inset-[3.2%_5.5%] z-0 overflow-hidden rounded-[3rem] bg-background-secondary">
            {privacySlides.length ? (
              privacySlides.map((slide, index) => (
                <div
                  key={`${slide.label}-${index}`}
                  data-privacy-slide
                  className="absolute inset-0 hidden items-center justify-center bg-gradient-to-br from-[#eef2ff] to-[#dbeafe] p-4 text-center first:flex lg:flex motion-reduce:hidden motion-reduce:first:flex lg:motion-reduce:hidden lg:motion-reduce:first:flex"
                >
                  {slide.src ? (
                    <Image
                      src={slide.src}
                      alt={slide.alt}
                      fill
                      unoptimized
                      sizes={phoneSizes}
                      className="object-cover"
                    />
                  ) : (
                    <span className="break-keep text-sm font-semibold text-text-primary sm:text-base">
                      {slide.label}
                    </span>
                  )}
                </div>
              ))
            ) : (
              <div className="flex size-full items-center justify-center p-4 text-center text-sm font-semibold text-text-secondary">
                비식별화 데모 준비 중
              </div>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
