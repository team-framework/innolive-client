"use client";

import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { ScrollSmoother } from "gsap/ScrollSmoother";
import Image from "next/image";
import { useLayoutEffect, useRef } from "react";
import { useLocale } from "@/components/locale-provider";
import { privacySlideSources } from "@/lib/privacy-slides";

const phoneSizes = "(min-width: 106.5rem) 438px, (min-width: 64rem) 40vw, 80vw";

const aiAccentStyle = {
  backgroundImage:
    "linear-gradient(90deg, rgb(108, 99, 255) 0%, rgb(79, 140, 255) 8.6538%, rgb(34, 211, 238) 12.981%)",
  backgroundRepeat: "no-repeat",
  backgroundSize: "100cqw 100%",
} as const;

export function PrivacySection() {
  const { messages } = useLocale();
  const slides = privacySlideSources.map((src, index) => ({
    src,
    alt: messages.privacy.slides[index]?.alt ?? "",
    label: messages.privacy.slides[index]?.label ?? "",
  }));
  const sectionRef = useRef<HTMLElement>(null);

  useLayoutEffect(() => {
    const section = sectionRef.current;
    if (!section || slides.length < 2) return;

    const slideNodes = Array.from(
      section.querySelectorAll<HTMLElement>("[data-privacy-slide]"),
    );
    if (slideNodes.length < 2) return;

    gsap.registerPlugin(ScrollTrigger);
    const media = gsap.matchMedia();
    media.add("(min-width: 64rem) and (prefers-reduced-motion: no-preference)", () => {
      let active = false;
      let index = 0;
      let busy = false;
      let consumed = true;
      let distance = 0;
      let lastInput = 0;
      let tween: gsap.core.Timeline | undefined;
      let trigger: ScrollTrigger;
      const scrollTo = (top: number) => {
        const smoother = ScrollSmoother.get();
        if (smoother) smoother.scrollTop(top);
        else window.scrollTo({ top, behavior: "instant" });
      };
      const show = (next: number) => {
        gsap.set(slideNodes, { display: "flex", autoAlpha: 0, yPercent: 100 });
        gsap.set(slideNodes[next], { autoAlpha: 1, yPercent: 0 });
        index = next;
        section.dataset.privacyIndex = String(index);
      };
      const context = gsap.context(() => {
        show(0);
        trigger = ScrollTrigger.create({
          trigger: section,
          start: "top top",
          end: "+=2",
          pin: true,
          invalidateOnRefresh: true,
          onUpdate: (self) => {
            if (active && !self.isActive) active = false;
          },
        });
      }, section);
      const step = (direction: number) => {
        if (busy) return;
        const next = index + direction;
        if (next < 0 || next >= slideNodes.length) {
          active = false;
          scrollTo(direction > 0 ? trigger.end + 1 : trigger.start - 1);
          return;
        }
        busy = true;
        const previous = slideNodes[index];
        const target = slideNodes[next];
        index = next;
        section.dataset.privacyIndex = String(index);
        gsap.set(target, { autoAlpha: 0, yPercent: direction * 100 });
        tween = gsap.timeline({ onComplete: () => { busy = false; } })
          .to(previous, { autoAlpha: 0, yPercent: -direction * 100, duration: 0.35 })
          .to(target, { autoAlpha: 1, yPercent: 0, duration: 0.35 }, 0);
      };
      const intercept = (delta: number, event: Event, fresh: boolean) => {
        const y = window.scrollY;
        if (active && (y < trigger.start || y > trigger.end)) active = false;
        if (!active) {
          const enteringDown = delta > 0 && y <= trigger.start && y + delta >= trigger.start;
          const enteringUp = delta < 0 && y >= trigger.end && y + delta <= trigger.end;
          const atSection = y >= trigger.start && y <= trigger.end;
          if (!enteringDown && !enteringUp && !atSection) return;
          active = true;
          consumed = true;
          show(enteringUp ? slideNodes.length - 1 : index);
          scrollTo(trigger.start + 1);
        } else {
          if (fresh) { consumed = false; distance = 0; }
          distance += delta;
          if (!consumed && !busy && Math.abs(distance) >= 40) {
            consumed = true;
            step(Math.sign(distance));
          }
        }
        event.preventDefault();
        event.stopImmediatePropagation();
      };
      const onWheel = (event: WheelEvent) => {
        if (document.documentElement.dataset.introComplete !== "true") return;
        const now = performance.now();
        const fresh = now - lastInput > 260;
        lastInput = now;
        const delta = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? innerHeight : 1);
        if (Math.abs(delta) > 0) intercept(delta, event, fresh);
      };
      const onKey = (event: KeyboardEvent) => {
        if (!active || event.repeat || (event.target instanceof HTMLElement && event.target.closest("a, button, input, select, textarea"))) return;
        if (["ArrowDown", "PageDown", " ", "ArrowUp", "PageUp"].includes(event.key)) {
          intercept(["ArrowUp", "PageUp"].includes(event.key) ? -80 : 80, event, true);
        }
      };
      window.addEventListener("wheel", onWheel, { capture: true, passive: false });
      window.addEventListener("keydown", onKey, { capture: true });
      return () => {
        window.removeEventListener("wheel", onWheel, true);
        window.removeEventListener("keydown", onKey, true);
        tween?.kill();
        delete section.dataset.privacyIndex;
        context.revert();
      };
    });
    media.add("(max-width: 63.999rem) and (prefers-reduced-motion: no-preference)", () => {
      const context = gsap.context(() => {
        gsap.set(slideNodes, { display: "flex", autoAlpha: 0, yPercent: 100 });
        gsap.set(slideNodes[0], { autoAlpha: 1, yPercent: 0 });
        const timeline = gsap.timeline({
          scrollTrigger: {
            anticipatePin: 1,
            end: () => `+=${window.innerHeight * (slideNodes.length - 1)}`,
            invalidateOnRefresh: true,
            pin: true,
            scrub: 0.4,
            // Keep the entire phone visible when the stacked layout exceeds the viewport.
            start: () => section.offsetHeight > window.innerHeight ? "bottom bottom" : "top top",
            trigger: section,
          },
        });
        slideNodes.slice(1).forEach((slide, index) => {
          timeline.to(slideNodes[index], { autoAlpha: 0, duration: 1, yPercent: -100 });
          timeline.to(slide, { autoAlpha: 1, duration: 1, yPercent: 0 }, "<");
        });
      }, section);
      return () => context.revert();
    });
    return () => media.revert();
  }, [slides.length]);

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
            {messages.privacy.titleLine1}
            <span className="block w-full [container-type:inline-size]">
              <span className="bg-clip-text text-transparent" style={aiAccentStyle}>
                AI{" "}
              </span>
              {messages.privacy.titleAfterAi}
            </span>
          </h2>
          <div className="w-full break-keep text-body-lg font-normal text-text-primary">
            <p>{messages.privacy.body1}</p>
            <p>{messages.privacy.body2}</p>
          </div>
        </div>
        <div className="privacy-phone relative aspect-[438/881] min-w-0 lg:-translate-y-10 lg:flex-none">
          <Image
            src="/landing/privacy-phone.png"
            alt=""
            width={1359}
            height={2736}
            sizes={phoneSizes}
            className="pointer-events-none absolute inset-0 size-full max-w-none z-1"
          />
          <div className="absolute inset-[3.2%_5.5%] z-0 overflow-hidden rounded-[3rem] bg-background-secondary">
            {slides.length ? (
              slides.map((slide, index) => (
                <div
                  key={`${slide.label}-${index}`}
                  data-privacy-slide
                  className="absolute inset-0 hidden items-center justify-center bg-gradient-to-br from-[#eef2ff] to-[#dbeafe] p-4 text-center first:flex"
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
                {messages.privacy.empty}
              </div>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}
