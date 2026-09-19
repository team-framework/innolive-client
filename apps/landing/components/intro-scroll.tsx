"use client";

import gsap from "gsap";
import { useLayoutEffect, useRef, useState } from "react";
import { IntroScene } from "@/components/intro-scene";
import { introScenes } from "@/components/intro-scenes";

const INTRO_COMPLETE_EVENT = "innolive:intro-complete";
// 마지막 문구가 완전히 나타난 뒤 유지할 시간(ms).
const FINAL_SCENE_MINIMUM_DISPLAY_MS = 1_000;

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
  const rootRef = useRef<HTMLDivElement>(null);
  const finishRef = useRef<() => void>(() => {});
  const [finished, setFinished] = useState(false);

  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root || finished) return;
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    const mobile = window.matchMedia("(max-width: 47.999rem)");
    const hasHashTarget = ["main", "faq"].includes(window.location.hash.slice(1));
    if (reduce.matches || mobile.matches || hasHashTarget) {
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
    let busy = false;
    let leaving = false;
    let wheelDistance = 0;
    let lastWheel = 0;
    let touchY = 0;
    let finalSceneAvailableAt = 0;
    let tween: gsap.core.Tween | undefined;

    const unlock = () => {
      document.body.style.overflow = overflow;
      background.forEach((element, i) => { element.inert = previousInert[i]; });
    };
    const finish = () => {
      if (leaving) return;
      leaving = true;
      window.scrollTo({ top: 0, behavior: "auto" });
      tween?.kill();
      tween = gsap.to(root, {
        opacity: 0, duration: reduce.matches ? 0 : 0.65,
        onComplete: () => {
          unlock();
          setFinished(true);
          markIntroComplete();
        },
      });
    };
    finishRef.current = finish;
    const step = (direction: number) => {
      if (busy || leaving) return;
      if (
        index === layers.length - 1
        && direction > 0
        && performance.now() < finalSceneAvailableAt
      ) return;
      const next = Math.max(0, index + direction);
      if (next >= layers.length) { finish(); return; }
      if (next === index) return;
      busy = true;
      const previous = layers[index];
      const target = layers[next];
      layers.forEach((layer) => { layer.style.zIndex = "0"; });
      previous.style.zIndex = "1";
      gsap.set(target, { autoAlpha: 0, zIndex: 2 });
      previous.setAttribute("aria-hidden", "true");
      target.removeAttribute("aria-hidden");
      index = next;
      root.dataset.sceneIndex = String(index);
      tween = gsap.to(target, { autoAlpha: 1, duration: 0.5, onComplete: () => {
        gsap.set(previous, { autoAlpha: 0 });
        if (index === layers.length - 1) {
          finalSceneAvailableAt = performance.now() + FINAL_SCENE_MINIMUM_DISPLAY_MS;
        }
        busy = false;
      } });
    };
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      if (busy || leaving) { wheelDistance = 0; lastWheel = event.timeStamp; return; }
      if (event.timeStamp - lastWheel > 180) wheelDistance = 0;
      lastWheel = event.timeStamp;
      wheelDistance += event.deltaY * (event.deltaMode === 1 ? 16 : event.deltaMode === 2 ? innerHeight : 1);
      if (Math.abs(wheelDistance) >= 80) { step(Math.sign(wheelDistance)); wheelDistance = 0; }
    };
    const onTouchStart = (event: TouchEvent) => { touchY = event.touches[0].clientY; };
    const onTouchMove = (event: TouchEvent) => { event.preventDefault(); };
    const onTouchEnd = (event: TouchEvent) => {
      const distance = touchY - event.changedTouches[0].clientY;
      if (Math.abs(distance) > 35) step(Math.sign(distance));
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Tab") { event.preventDefault(); skipButton?.focus(); }
      if (["ArrowDown", "PageDown", " ", "ArrowUp", "PageUp", "Home", "End", "Escape"].includes(event.key)) {
        if (event.key === " " && event.target === skipButton) return;
        event.preventDefault();
        if (event.key === "End" || event.key === "Escape") finish();
        else step(["ArrowUp", "PageUp", "Home"].includes(event.key) ? -1 : 1);
      }
    };
    const onReduce = () => { if (reduce.matches) finish(); };
    const onMobile = () => { if (mobile.matches) finish(); };
    root.addEventListener("wheel", onWheel, { passive: false });
    root.addEventListener("touchstart", onTouchStart, { passive: true });
    root.addEventListener("touchmove", onTouchMove, { passive: false });
    root.addEventListener("touchend", onTouchEnd);
    root.addEventListener("keydown", onKey);
    reduce.addEventListener("change", onReduce);
    mobile.addEventListener("change", onMobile);
    return () => {
      tween?.kill();
      unlock();
      root.removeEventListener("wheel", onWheel);
      root.removeEventListener("touchstart", onTouchStart);
      root.removeEventListener("touchmove", onTouchMove);
      root.removeEventListener("touchend", onTouchEnd);
      root.removeEventListener("keydown", onKey);
      reduce.removeEventListener("change", onReduce);
      mobile.removeEventListener("change", onMobile);
    };
  }, [finished]);

  if (finished) return null;
  return (
    <div ref={rootRef} role="dialog" aria-modal="true" aria-label="InnoLive 소개" data-scene-index="0"
      className="intro-root invisible fixed inset-0 z-[100] h-dvh overflow-clip bg-background-secondary">
      {introScenes.map((scene, index) => (
        <div key={scene.id} data-intro-layer aria-hidden={index !== 0 ? true : undefined}
          className={`absolute inset-0 ${index === 0 ? "" : "invisible opacity-0"}`}>
          <IntroScene {...scene} fill sceneIndex={index} priority={index === 0} />
        </div>
      ))}
      <button type="button" onClick={() => finishRef.current()}
        className="absolute top-4 right-4 z-20 rounded-pill bg-background-secondary/90 px-4 py-2 text-base font-medium text-text-primary shadow-button min-[48rem]:top-8 min-[48rem]:right-8">
        본문으로 건너뛰기
      </button>
    </div>
  );
}
