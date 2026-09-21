"use client";

import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import Image from "next/image";
import Link from "next/link";
import { useLayoutEffect, useRef, type ReactNode } from "react";
import { useLocale } from "@/components/locale-provider";

export function FAQSection() {
  const { href, messages } = useLocale();
  const items: { question: string; answer: ReactNode }[] = [
    ...messages.faq.items,
    {
      question: messages.faq.privacyQuestion,
      answer: (
        <>
          {messages.faq.privacyBefore}
          <Link
            href={href("/privacy")}
            className="underline [text-underline-position:from-font]"
          >
            {messages.faq.privacyLink}
          </Link>
          {messages.faq.privacyAfter}
        </>
      ),
    },
  ];
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

        <div className="flex w-full flex-col gap-3">
          {items.map((item) => (
            <details
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
