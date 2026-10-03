"use client";

import Image from "next/image";
import { observeSectionAnimation } from "@/lib/landing-animation";
import Link from "next/link";
import { useEffect, useRef } from "react";
import { liveFaceBlurGuide } from "@/lib/live-face-blur-guide";
import { useLocale } from "@/components/locale-provider";
import { getHomeFaq } from "@/lib/faq";

export function FAQSection() {
  const { href, messages, locale } = useLocale();
  const items = getHomeFaq(locale);
  const sectionRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const section = sectionRef.current;
    if (!section) return;
    return observeSectionAnimation(section, async (isCancelled) => {
      const [{ default: gsap }, { ScrollTrigger }] = await Promise.all([
        import("gsap"),
        import("gsap/ScrollTrigger"),
      ]);
      if (isCancelled()) return () => {};
      gsap.registerPlugin(ScrollTrigger);
      const media = gsap.matchMedia();
      try {
        media.add("(prefers-reduced-motion: no-preference)", () => {
          const entries = section.querySelectorAll("details");
          gsap.set(entries, { opacity: 0, y: 32 });
          ScrollTrigger.batch(entries, {
            start: "top 80%",
            onEnter: (batch) => {
              gsap.to(batch, { opacity: 1, y: 0, duration: 0.6, stagger: 0.12, ease: "power2.out" });
            },
            onLeaveBack: (batch) => {
              gsap.to(batch, {
                opacity: 0,
                y: 32,
                duration: 0.6,
                stagger: 0.12,
                ease: "power2.out",
                overwrite: true,
              });
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
      } catch (error) {
        media.revert();
        throw error;
      }
    });
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
            {messages.faq.subtitle}
          </p>
        </div>

        <Link href={href("/guides/live-face-blur")} className="text-lg text-text-primary underline [text-underline-position:from-font]">
          {liveFaceBlurGuide[locale].title}
        </Link>

        <div className="flex w-full flex-col gap-3">
          {items.map((item, index) => (
            <details
              id={`faq-${index + 1}`}
              key={item.question}
              open={false}
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
                  {item.answerLink ? (
                    <>
                      {item.answerLink.before}
                      <Link href={item.answerLink.href} className="underline [text-underline-position:from-font]">
                        {item.answerLink.label}
                      </Link>
                      {item.answerLink.after}
                    </>
                  ) : item.answer}
                </p>
              </div>
            </details>
          ))}
        </div>
      </div>
    </section>
  );
}
