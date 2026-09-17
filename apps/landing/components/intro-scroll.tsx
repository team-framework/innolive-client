"use client";

import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useLayoutEffect, useMemo, useRef, useState } from "react";
import { IntroScene } from "@/components/intro-scene";
import { introScenes } from "@/components/intro-scenes";

const SCENE_COUNT = introScenes.length;
const INTRO_DISTANCE = 1.75;
const INTRO_SEEN_KEY = "innolive-intro-complete";
const INTRO_TRIGGER_ID = "intro-scroll";

const preloadSrcs = Array.from(
  new Set([
    "/intro/photo.png",
    "/intro/logo.svg",
    ...introScenes.flatMap((scene) => [
      scene.lettering.src,
      ...scene.overlays.map((overlay) => overlay.mask),
      ...scene.overlays.map((overlay) =>
        overlay.mask.replace("/intro/masks/", "/intro/overlays/"),
      ),
    ]),
  ]),
);

function shouldSkipIntro() {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    return true;
  }
  const hash = window.location.hash;
  if (hash === "#main" || hash === "#faq") {
    return true;
  }
  try {
    return sessionStorage.getItem(INTRO_SEEN_KEY) === "1";
  } catch {
    return false;
  }
}

function markIntroSeen() {
  try {
    sessionStorage.setItem(INTRO_SEEN_KEY, "1");
  } catch {
    /* ignore */
  }
}

function focusMain() {
  const main = document.getElementById("main");
  if (!(main instanceof HTMLElement)) {
    return;
  }
  main.focus({ preventScroll: true });
}

export function IntroScroll() {
  const rootRef = useRef<HTMLDivElement>(null);
  const [activeIndex, setActiveIndex] = useState(0);
  const [skipped, setSkipped] = useState(false);
  const rendered = useMemo(() => {
    const indices = [activeIndex - 1, activeIndex, activeIndex + 1].filter(
      (index) => index >= 0 && index < SCENE_COUNT,
    );
    return indices;
  }, [activeIndex]);

  useLayoutEffect(() => {
    const root = rootRef.current;
    if (!root) {
      return;
    }

    if (shouldSkipIntro()) {
      setSkipped(true);
      const id = window.location.hash.replace("#", "");
      if (id === "main" || id === "faq") {
        document.getElementById(id)?.scrollIntoView();
      }
      return;
    }

    gsap.registerPlugin(ScrollTrigger);
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    const ctx = gsap.context(() => {
      ScrollTrigger.create({
        id: INTRO_TRIGGER_ID,
        trigger: root,
        start: "top top",
        end: () => `+=${window.innerHeight * INTRO_DISTANCE}`,
        pin: true,
        pinSpacing: true,
        anticipatePin: 1,
        invalidateOnRefresh: true,
        onUpdate: (self) => {
          const next = Math.min(
            SCENE_COUNT - 1,
            Math.floor(self.progress * SCENE_COUNT),
          );
          setActiveIndex((prev) => (prev === next ? prev : next));
        },
        onLeave: markIntroSeen,
      });
    }, root);

    const skipFromHash = () => {
      if (window.location.hash !== "#main" && window.location.hash !== "#faq") {
        return;
      }
      const trigger = ScrollTrigger.getById(INTRO_TRIGGER_ID);
      if (trigger) {
        trigger.scroll(trigger.end);
      }
      markIntroSeen();
    };

    const onReduce = (event: MediaQueryListEvent) => {
      if (!event.matches) {
        return;
      }
      ctx.revert();
      setSkipped(true);
    };

    window.addEventListener("hashchange", skipFromHash);
    reduce.addEventListener("change", onReduce);

    return () => {
      window.removeEventListener("hashchange", skipFromHash);
      reduce.removeEventListener("change", onReduce);
      ctx.revert();
    };
  }, []);

  if (skipped) {
    return null;
  }

  return (
    <div
      ref={rootRef}
      className="intro-root relative h-dvh w-full overflow-clip bg-background-secondary"
      data-scene-index={activeIndex}
    >
      <div className="hidden" aria-hidden="true">
        {preloadSrcs.map((src) => (
          // Preload shared photo, lettering, and overlay bytes before rapid swaps.
          // eslint-disable-next-line @next/next/no-img-element
          <img key={src} src={src} alt="" />
        ))}
      </div>
      {rendered.map((index) => {
        const scene = introScenes[index];
        const active = index === activeIndex;
        return (
          <div
            key={scene.id}
            className={
              active
                ? "absolute inset-0"
                : "invisible absolute inset-0"
            }
            aria-hidden={active ? undefined : true}
          >
            <IntroScene
              label={scene.label}
              overlays={scene.overlays}
              lettering={scene.lettering}
              fill
              sceneIndex={index}
              priority={index === 0}
            />
          </div>
        );
      })}
      <a
        href="#main"
        className="absolute top-4 right-4 z-20 rounded-pill bg-background-secondary/90 px-4 py-2 text-base font-medium text-text-primary shadow-button min-[48rem]:top-8 min-[48rem]:right-8"
        onClick={(event) => {
          const trigger = ScrollTrigger.getById(INTRO_TRIGGER_ID);
          if (trigger) {
            event.preventDefault();
            markIntroSeen();
            trigger.scroll(trigger.end);
          }
          window.requestAnimationFrame(focusMain);
        }}
      >
        본문으로 건너뛰기
      </a>
    </div>
  );
}
