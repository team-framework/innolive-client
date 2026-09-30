"use client";

import gsap from "gsap";
import { useLayoutEffect, useRef, useState } from "react";
import { IntroScene } from "@/components/intro-scene";
import { introScenes } from "@/components/intro-scenes";
import { useLocale } from "@/components/locale-provider";
import { interpolate } from "@/lib/locales";

const INTRO_COMPLETE_EVENT = "innolive:intro-complete";
const FINAL_SCENE_MINIMUM_DISPLAY_MS = 1_000;
const WHEEL_QUIET_MS = 240;

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
      const skip = gsap.delayedCall(0, () => {
        setFinished(true);
        markIntroComplete();
        focusHashTarget();
      });
      return () => skip.kill();
    }

    const layers = Array.from(root.querySelectorAll<HTMLElement>("[data-intro-layer]"));
    const background = Array.from(document.querySelectorAll<HTMLElement>("header, main, footer"));
    const previousInert = background.map((element) => element.inert);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    background.forEach((element) => { element.inert = true; });
    root.style.visibility = "visible";
    root.dataset.active = "true";
    const skipButton = root.querySelector<HTMLButtonElement>("button");
    let index = 0;
    let started = false;
    let leaving = false;
    let wheelDistance = 0;
    let lastWheel = 0;
    let touchY = 0;
    let release: gsap.core.Tween | undefined;
    let tween: gsap.core.Timeline | undefined;

    const unlock = () => {
      document.body.style.overflow = overflow;
      background.forEach((element, i) => { element.inert = previousInert[i]; });
    };
    const finish = () => {
      if (leaving) return;
      leaving = true;
      tween?.kill();
      tween = gsap.timeline().to(root, {
        opacity: 0, duration: reduce.matches ? 0 : 0.35,
        onComplete: () => {
          // Consume the tail of the gesture before enabling document scrolling.
          const releaseWhenQuiet = () => {
            if (performance.now() - lastWheel < WHEEL_QUIET_MS) {
              release = gsap.delayedCall(WHEEL_QUIET_MS / 1000, releaseWhenQuiet);
              return;
            }
            window.scrollTo({ top: 0, behavior: "instant" });
            unlock();
            setFinished(true);
            markIntroComplete();
            document.getElementById("main")?.focus({ preventScroll: true });
          };
          releaseWhenQuiet();
        },
      });
    };
    finishRef.current = finish;
    const start = () => {
      if (started || leaving) return;
      started = true;
      tween = gsap.timeline({ onComplete: finish });
      layers.slice(1).forEach((target, offset) => {
        const previous = layers[offset];
        tween!.call(() => {
          index = offset + 1;
          root.dataset.sceneIndex = String(index);
          previous.setAttribute("aria-hidden", "true");
          target.removeAttribute("aria-hidden");
          gsap.set(target, { zIndex: index });
        }, undefined, offset * 0.22);
        tween!.to(target, { autoAlpha: 1, duration: 0.16 }, offset * 0.22);
      });
      tween.to({}, { duration: FINAL_SCENE_MINIMUM_DISPLAY_MS / 1000 });
    };
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      const now = performance.now();
      if (now - lastWheel > WHEEL_QUIET_MS) wheelDistance = 0;
      lastWheel = now;
      wheelDistance += event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? innerHeight : 1);
      if (wheelDistance >= 40) start();
    };
    const onTouchStart = (event: TouchEvent) => { touchY = event.touches[0].clientY; };
    const onTouchMove = (event: TouchEvent) => { event.preventDefault(); };
    const onTouchEnd = (event: TouchEvent) => {
      const distance = touchY - event.changedTouches[0].clientY;
      if (distance > 35) start();
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Tab") { event.preventDefault(); skipButton?.focus(); }
      if (["ArrowDown", "PageDown", " ", "ArrowUp", "PageUp", "Home", "End", "Escape"].includes(event.key)) {
        if (event.key === " " && event.target === skipButton) return;
        event.preventDefault();
        if (event.key === "End" || event.key === "Escape") finish();
        else if (!["ArrowUp", "PageUp", "Home"].includes(event.key)) start();
      }
    };
    const onReduce = () => { if (reduce.matches) finish(); };
    const onMobile = () => { if (mobile.matches) finish(); };
    window.addEventListener("wheel", onWheel, { passive: false, capture: true });
    root.addEventListener("touchstart", onTouchStart, { passive: true });
    root.addEventListener("touchmove", onTouchMove, { passive: false });
    root.addEventListener("touchend", onTouchEnd);
    root.addEventListener("keydown", onKey);
    reduce.addEventListener("change", onReduce);
    mobile.addEventListener("change", onMobile);
    return () => {
      tween?.kill();
      release?.kill();
      unlock();
      window.removeEventListener("wheel", onWheel, true);
      root.removeEventListener("touchstart", onTouchStart);
      root.removeEventListener("touchmove", onTouchMove);
      root.removeEventListener("touchend", onTouchEnd);
      root.removeEventListener("keydown", onKey);
      reduce.removeEventListener("change", onReduce);
      mobile.removeEventListener("change", onMobile);
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
      className="intro-root invisible fixed inset-0 z-[100] h-dvh overflow-clip bg-background-secondary">
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
