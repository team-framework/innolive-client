"use client";

import { useEffect, useState } from "react";
import { useLocale } from "@/components/locale-provider";
import { ProgressiveBlurText } from "@/components/progressive-blur-text";

const INTRO_COMPLETE_EVENT = "innolive:intro-complete";

export function TypewriterHeroHeading() {
  const { messages } = useLocale();
  const firstLine = messages.hero.line1;
  const secondPrefix = messages.hero.line2Prefix;
  const blurText = messages.hero.line2Blur;
  const heading = `${firstLine}${secondPrefix}${blurText}`;
  const [typedLength, setTypedLength] = useState(0);

  useEffect(() => {
    const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
    let timer: number | undefined;

    const start = () => {
      if (reduce.matches) {
        timer = window.setTimeout(() => setTypedLength(heading.length), 0);
        return;
      }

      timer = window.setInterval(() => {
        setTypedLength((current) => {
          if (current >= heading.length) {
            window.clearInterval(timer);
            return current;
          }

          return current + 1;
        });
      }, 90);
    };

    if (document.documentElement.dataset.introComplete === "true") start();
    else window.addEventListener(INTRO_COMPLETE_EVENT, start, { once: true });

    return () => {
      if (timer !== undefined) window.clearInterval(timer);
      window.removeEventListener(INTRO_COMPLETE_EVENT, start);
    };
  }, [heading]);

  const firstLineLength = Math.min(typedLength, firstLine.length);
  const secondPrefixLength = Math.min(
    Math.max(typedLength - firstLine.length, 0),
    secondPrefix.length,
  );
  const blurTextLength = Math.max(
    typedLength - firstLine.length - secondPrefix.length,
    0,
  );

  return (
    <h1
      id="hero-heading"
      aria-label={heading}
      className="w-full break-words text-[length:var(--text-display)] font-bold leading-[calc(100/84)] tracking-tight text-text-primary"
    >
      <span aria-hidden="true">
        {firstLine.slice(0, firstLineLength)}
        <br />
        {secondPrefix.slice(0, secondPrefixLength)}
        {blurTextLength > 0 ? (
          <ProgressiveBlurText text={blurText.slice(0, blurTextLength)} />
        ) : null}
      </span>
    </h1>
  );
}
