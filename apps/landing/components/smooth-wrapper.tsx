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
    const smoother = ScrollSmoother.create({
      wrapper: wrapperRef.current,
      content: contentRef.current,
      smooth: 1,
      smoothTouch: 0.1,
      effects: true,
      normalizeScroll: true,
    });
    let hashFrame: number | undefined;
    const scheduleHashScroll = () => {
      if (hashFrame !== undefined) window.cancelAnimationFrame(hashFrame);
      hashFrame = window.requestAnimationFrame(() => {
        hashFrame = undefined;
        scrollToHash(smoother);
      });
    };
    scheduleHashScroll();
    const onHashChange = scheduleHashScroll;
    window.addEventListener("hashchange", onHashChange);

    return () => {
      if (hashFrame !== undefined) window.cancelAnimationFrame(hashFrame);
      window.removeEventListener("hashchange", onHashChange);
      smoother.kill();
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
