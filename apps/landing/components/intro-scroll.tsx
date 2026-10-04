"use client";

import type gsap from "gsap";
import { afterPaint } from "@/lib/landing-animation";
import { useLayoutEffect, useRef, useState } from "react";
import { IntroScene } from "@/components/intro-scene";
import { introScenes } from "@/components/intro-scenes";
import { useLocale } from "@/components/locale-provider";
import { interpolate } from "@/lib/locales";

const INTRO_COMPLETE_EVENT = "innolive:intro-complete";
const INTRO_SCROLL_SPEED = 1.3;
const INTRO_FOCUS_SCROLL_RANGE = 1.8;
const INTRO_EXIT_SCROLL_LOCK_MS = 400;

function markIntroComplete() {
  document.documentElement.dataset.introComplete = "true";
  window.dispatchEvent(new Event(INTRO_COMPLETE_EVENT));
}

function focusHashTarget() {
  const id = window.location.hash.slice(1);
  if (id !== "main" && id !== "faq") return;

  document.getElementById(id)?.scrollIntoView();
  if (id === "main") {
    document.getElementById("main")?.focus({ preventScroll: true });
  }
}

export function IntroScroll() {
  const { messages } = useLocale();
  const rootRef = useRef<HTMLDivElement>(null);
  const finishRef = useRef<() => void>(() => {});
  const [finished, setFinished] = useState(false);

  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root || finished) return;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    const mobile = window.matchMedia("(max-width: 47.999rem)");
    const alreadyCompleted = document.documentElement.dataset.introComplete === "true";
    const hasHashTarget = ["main", "faq"].includes(window.location.hash.slice(1));
    if (alreadyCompleted || reduce.matches || mobile.matches || hasHashTarget) {
      let cancelled = false;
      queueMicrotask(() => {
        if (cancelled) return;
        setFinished(true);
        markIntroComplete();
        focusHashTarget();
      });
      return () => { cancelled = true; };
    }

    const layers = Array.from(root.querySelectorAll<HTMLElement>("[data-intro-layer]"));
    const background = Array.from(document.querySelectorAll<HTMLElement>("header, main, footer"));
    const previousInert = background.map((element) => element.inert);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    background.forEach((element) => { element.inert = true; });
    root.style.visibility = "visible";
    root.dataset.active = "true";
    const unlock = () => {
      document.body.style.overflow = overflow;
      background.forEach((element, i) => { element.inert = previousInert[i]; });
    };
    let cancelled = false;
    let cleanupAnimation: (() => void) | undefined;
    const removePendingListeners = () => {
      reduce.removeEventListener("change", onPendingPreference);
      mobile.removeEventListener("change", onPendingPreference);
    };
    const skipPending = () => {
      if (cancelled) return;
      cancelled = true;
      cancelPaint?.();
      removePendingListeners();
      unlock();
      setFinished(true);
      markIntroComplete();
    };
    const onPendingPreference = () => {
      if (reduce.matches || mobile.matches) skipPending();
    };
    finishRef.current = skipPending;
    reduce.addEventListener("change", onPendingPreference);
    mobile.addEventListener("change", onPendingPreference);
    const initialize = async () => {
      const [{ default: gsap }, { ScrollSmoother }] = await Promise.all([
        import("gsap"),
        import("gsap/ScrollSmoother"),
      ]);
      if (cancelled) return;
      if (reduce.matches || mobile.matches) {
        skipPending();
        return;
      }
      removePendingListeners();
      const skipButton = root.querySelector<HTMLButtonElement>("button");
      let progress = 0;
      let leaving = false;
      let touchY = 0;
      let tween: gsap.core.Tween | undefined;
      let exitTimer: gsap.core.Tween | undefined;
      const timeline = gsap.timeline({ paused: true, defaults: { duration: 1, ease: "none" } });
      layers.forEach((layer, index) => {
        gsap.set(layer, { autoAlpha: index === 0 ? 1 : 0, zIndex: index });
        if (index > 0) {
          timeline.to(layer, { autoAlpha: 1 }).set(layers[index - 1], { autoAlpha: 0 });
        }
      });
      timeline.to(root, { opacity: 0 }, `+=${INTRO_FOCUS_SCROLL_RANGE}`);
      const scrollRange = timeline.duration();

      const finish = (duration = 0.65) => {
        if (leaving) return;
        leaving = true;
        timeline.kill();
        const smoother = ScrollSmoother.get();
        if (smoother) {
          const wasPaused = smoother.paused();
          smoother.paused(true).scrollTop(0).paused(wasPaused);
        }
        window.scrollTo({ top: 0, behavior: "auto" });
        tween?.kill();
        tween = gsap.to(root, {
          opacity: 0, duration: reduce.matches ? 0 : duration,
          onComplete: () => {
            exitTimer = gsap.delayedCall(INTRO_EXIT_SCROLL_LOCK_MS / 1000, () => {
              unlock();
              setFinished(true);
              markIntroComplete();
            });
          },
        });
      };
      finishRef.current = finish;
      const advance = (distance: number) => {
        if (leaving) return;
        progress = Math.max(0, Math.min(scrollRange, progress + distance));
        timeline.progress(progress / scrollRange);
        const index = Math.min(layers.length - 1, Math.floor(progress));
        root.dataset.sceneIndex = String(index);
        layers.forEach((layer, i) => {
          if (i === index) layer.removeAttribute("aria-hidden");
          else layer.setAttribute("aria-hidden", "true");
        });
        if (progress === scrollRange) finish(0);
      };
      const onWheel = (event: WheelEvent) => {
        event.preventDefault();
        event.stopPropagation();
        const distance = event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? innerHeight : 1);
        advance(distance * INTRO_SCROLL_SPEED / innerHeight);
      };
      const onTouchStart = (event: TouchEvent) => { touchY = event.touches[0].clientY; };
      const onTouchMove = (event: TouchEvent) => {
        event.preventDefault();
        event.stopPropagation();
        const nextY = event.touches[0].clientY;
        advance((touchY - nextY) * INTRO_SCROLL_SPEED / innerHeight);
        touchY = nextY;
      };
      const onKey = (event: KeyboardEvent) => {
        if (event.key === "Tab") { event.preventDefault(); skipButton?.focus(); }
        if (["ArrowDown", "PageDown", " ", "ArrowUp", "PageUp", "Home", "End", "Escape"].includes(event.key)) {
          if (event.key === " " && event.target === skipButton) return;
          event.preventDefault();
          if (event.key === "End" || event.key === "Escape") finish();
          else advance(["ArrowUp", "PageUp", "Home"].includes(event.key) ? -1 : 1);
        }
      };
      const onReduce = () => { if (reduce.matches) finish(); };
      const onMobile = () => { if (mobile.matches) finish(); };
      root.addEventListener("wheel", onWheel, { passive: false });
      root.addEventListener("touchstart", onTouchStart, { passive: true });
      root.addEventListener("touchmove", onTouchMove, { passive: false });
      root.addEventListener("keydown", onKey);
      reduce.addEventListener("change", onReduce);
      mobile.addEventListener("change", onMobile);
      cleanupAnimation = () => {
        tween?.kill();
        exitTimer?.kill();
        timeline.kill();
        unlock();
        root.removeEventListener("wheel", onWheel);
        root.removeEventListener("touchstart", onTouchStart);
        root.removeEventListener("touchmove", onTouchMove);
        root.removeEventListener("keydown", onKey);
        reduce.removeEventListener("change", onReduce);
        mobile.removeEventListener("change", onMobile);
      };
    };
    const cancelPaint = afterPaint(() => {
      void initialize().catch(() => {
        if (cancelled) return;
        cleanupAnimation?.();
        skipPending();
      });
    });
    return () => {
      cancelled = true;
      cancelPaint?.();
      removePendingListeners();
      cleanupAnimation?.();
      finishRef.current = () => {};
      unlock();
    };
  }, [finished]);

  if (finished) return null;
  const scenes = introScenes.map((scene, index) => {
    const focus = scene.id === "p7";
    return {
      ...scene,
      label: interpolate(focus ? messages.intro.sceneFocus : messages.intro.sceneSafe, {
        n: String(index + 1),
      }),
      lettering: {
        ...scene.lettering,
        alt: focus ? messages.intro.letteringFocus : messages.intro.letteringSafe,
      },
    };
  });
  return (
    <div ref={rootRef} role="dialog" aria-modal="true" aria-label={messages.intro.dialogLabel} data-scene-index="0"
      className="intro-root invisible hidden fixed inset-0 z-[100] h-dvh overflow-clip bg-background-secondary min-[48rem]:block motion-reduce:hidden">
      {scenes.map((scene, index) => (
        <div key={scene.id} data-intro-layer aria-hidden={index !== 0 ? true : undefined}
          className={`absolute inset-0 ${index === 0 ? "" : "invisible opacity-0"}`}>
          <IntroScene {...scene} fill sceneIndex={index} priority={index === 0} />
        </div>
      ))}
      <button type="button" onClick={() => finishRef.current()}
        className="absolute top-4 right-4 z-20 rounded-pill bg-background-secondary/90 px-4 py-2 text-base font-medium text-text-primary shadow-button min-[48rem]:top-8 min-[48rem]:right-8">
        {messages.intro.skip}
      </button>
    </div>
  );
}
