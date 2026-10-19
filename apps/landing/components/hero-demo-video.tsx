"use client";

import { useEffect, useRef } from "react";
import { afterPaint } from "@/lib/landing-animation";

export function HeroDemoVideo({ className }: { className: string }) {
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let visible = false;
    let pageReady = document.readyState === "complete";

    let cancelPlayback: (() => void) | undefined;

    const updatePlayback = () => {
      cancelPlayback?.();
      cancelPlayback = undefined;
      if (reducedMotion.matches) {
        video.pause();
        if (video.hasAttribute("src")) {
          video.removeAttribute("src");
          video.load();
        }
        return;
      }
      if (!pageReady || !visible || document.hidden) {
        video.pause();
        return;
      }
      // Paint the poster before fetching and decoding the autoplay video.
      cancelPlayback = afterPaint(() => {
        cancelPlayback = undefined;
        if (!video.hasAttribute("src")) {
          const requiredWidth = video.getBoundingClientRect().width * window.devicePixelRatio;
          video.src = window.matchMedia("(max-width: 1023px)").matches && requiredWidth <= 360
            ? "/mockups/hero-live-demo-mobile.mp4"
            : "/mockups/hero-live-demo.mp4";
          video.load();
        }
        void video.play().catch(() => {
          // The poster stays visible if the browser blocks autoplay.
        });
      });
    };

    const onPageLoad = () => {
      pageReady = true;
      updatePlayback();
    };

    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      updatePlayback();
    });
    observer.observe(video);
    window.addEventListener("load", onPageLoad);
    reducedMotion.addEventListener("change", updatePlayback);
    document.addEventListener("visibilitychange", updatePlayback);

    return () => {
      cancelPlayback?.();
      observer.disconnect();
      window.removeEventListener("load", onPageLoad);
      reducedMotion.removeEventListener("change", updatePlayback);
      document.removeEventListener("visibilitychange", updatePlayback);
      video.pause();
      video.removeAttribute("src");
      video.load();
    };
  }, []);

  return (
    <div className={className}>
      <video
        ref={videoRef}
        width={600}
        height={1280}
        autoPlay
        muted
        loop
        playsInline
        preload="none"
        aria-hidden="true"
        tabIndex={-1}
        poster="/mockups/hero-live-demo.webp"
        className="absolute inset-0 size-full object-cover object-center"
      />
    </div>
  );
}
