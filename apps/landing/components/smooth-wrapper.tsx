"use client";

import type { ScrollSmoother } from "gsap/ScrollSmoother";
import { afterPaint } from "@/lib/landing-animation";
import { useEffect, useRef } from "react";


interface SmoothScrollProps {
  children: React.ReactNode;
}

function scrollToHash(smoother: ScrollSmoother) {
  const id = window.location.hash.slice(1);
  if (!id) return;

  const target = document.getElementById(id);
  if (target && document.getElementById("smooth-content")?.contains(target)) {
    smoother.scrollTo(target, false, "top top");
  }
}

export function SmoothScroll({ children }: SmoothScrollProps) {
  const wrapperRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let smoother: ScrollSmoother | undefined;
    let cancelled = false;
    let starting = false;
    let cancelPaint: (() => void) | undefined;
    const initialize = async () => {
      const [{ default: gsap }, { ScrollTrigger }, { ScrollSmoother }] = await Promise.all([
        import("gsap"),
        import("gsap/ScrollTrigger"),
        import("gsap/ScrollSmoother"),
      ]);
      if (cancelled) return;
      gsap.registerPlugin(ScrollTrigger, ScrollSmoother);
      smoother = ScrollSmoother.create({
        wrapper: wrapperRef.current,
        content: contentRef.current,
        smooth: 1,
        smoothTouch: 0.1,
        effects: true,
        normalizeScroll: false,
      });
      scheduleHashScroll();
    };
    const start = () => {
      if (cancelled || starting || smoother) return;
      starting = true;
      cancelPaint = afterPaint(() => {
        void initialize().catch(() => {
          starting = false;
          smoother?.kill();
          smoother = undefined;
        });
      });
    };
    let hashFrame: number | undefined;
    const scheduleHashScroll = () => {
      if (hashFrame !== undefined) window.cancelAnimationFrame(hashFrame);
      hashFrame = window.requestAnimationFrame(() => {
        hashFrame = undefined;
        if (smoother) scrollToHash(smoother);
      });
    };
    if (document.documentElement.dataset.introComplete === "true") start();
    else window.addEventListener("innolive:intro-complete", start);
    const onHashChange = scheduleHashScroll;
    window.addEventListener("hashchange", onHashChange);

    return () => {
      cancelled = true;
      cancelPaint?.();
      if (hashFrame !== undefined) window.cancelAnimationFrame(hashFrame);
      window.removeEventListener("hashchange", onHashChange);
      window.removeEventListener("innolive:intro-complete", start);
      smoother?.kill();
    };
  }, []);

  return (
    <div ref={wrapperRef} id="smooth-wrapper">
      <div ref={contentRef} id="smooth-content">
        {children}
      </div>
    </div>
  );
}
