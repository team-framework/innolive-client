"use client";

import Image from "next/image";
import { useEffect, useRef, useState } from "react";

export function HeroDemoVideo({ className }: { className: string }) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const posterRef = useRef<HTMLImageElement>(null);
  const [posterReady, setPosterReady] = useState(false);
  const [playing, setPlaying] = useState(false);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
    let visible = false;
    let pageReady = document.readyState === "complete";

    const updatePlayback = () => {
      if (reducedMotion.matches) {
        video.pause();
        setPlaying(false);
        if (video.hasAttribute("src")) {
          video.removeAttribute("src");
          video.load();
        }
        return;
      }
      if (!posterReady || !pageReady || !visible || document.hidden) {
        video.pause();
        return;
      }
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
      observer.disconnect();
      window.removeEventListener("load", onPageLoad);
      reducedMotion.removeEventListener("change", updatePlayback);
      document.removeEventListener("visibilitychange", updatePlayback);
      video.pause();
      video.removeAttribute("src");
      video.load();
    };
  }, [posterReady]);

  useEffect(() => {
    const poster = posterRef.current;
    if (poster?.complete && poster.naturalWidth > 0) {
      void poster.decode().then(() => setPosterReady(true)).catch(() => {});
    }
  }, []);

  return (
    <div className={className}>
      <Image
        ref={posterRef}
        src="/mockups/hero-live-demo.jpg"
        alt=""
        fill
        sizes="(min-width: 1704px) 401px, (min-width: 1024px) 23vw, (min-width: 776px) 401px, 52vw"
        loading="eager"
        fetchPriority="high"
        onLoad={() => setPosterReady(true)}
        className="object-cover object-center"
      />
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
        onPlaying={() => setPlaying(true)}
        className="absolute inset-0 size-full object-cover object-center"
        style={{ opacity: playing ? 1 : 0 }}
      />
    </div>
  );
}
