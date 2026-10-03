"use client";

import gsap from "gsap";
import { ScrollSmoother } from "gsap/ScrollSmoother";
import { useLayoutEffect, useRef } from "react";

gsap.registerPlugin(ScrollSmoother);

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

  useLayoutEffect(() => {
    let smoother: ScrollSmoother | undefined;
    const start = () => {
      if (smoother) return;
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
