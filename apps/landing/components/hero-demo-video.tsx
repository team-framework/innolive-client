"use client";

import { useEffect, useRef } from "react";

export function HeroDemoVideo({ className }: { className: string }) {
  const videoRef = useRef<HTMLVideoElement>(null);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let visible = false;

    const updatePlayback = () => {
      if (reducedMotion.matches) {
        video.pause();
        if (video.hasAttribute("src")) {
          video.removeAttribute("src");
          video.load();
        }
        return;
      }
      if (!visible || document.hidden) {
        video.pause();
        return;
      }
      if (!video.hasAttribute("src")) {
        video.src = "/mockups/hero-live-demo.mp4";
        video.load();
      }
      void video.play().catch(() => {
        // The poster stays visible if the browser blocks autoplay.
      });
    };

    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      updatePlayback();
    });
    observer.observe(video);
    reducedMotion.addEventListener("change", updatePlayback);
    document.addEventListener("visibilitychange", updatePlayback);

    return () => {
      observer.disconnect();
      reducedMotion.removeEventListener("change", updatePlayback);
      document.removeEventListener("visibilitychange", updatePlayback);
      video.pause();
      video.removeAttribute("src");
      video.load();
    };
  }, []);

  return (
    <div className={className} style={{ backgroundImage: "url('/mockups/hero-live-demo.jpg')" }}>
      <video
        ref={videoRef}
        width={600}
        height={1280}
        autoPlay
        muted
        loop
        playsInline
        preload="none"
        poster="/mockups/hero-live-demo.jpg"
        aria-hidden="true"
        tabIndex={-1}
        className="absolute inset-0 size-full object-cover object-center"
      />
    </div>
  );
}
