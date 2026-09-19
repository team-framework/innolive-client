"use client";

import {useLayoutEffect, useRef, type CSSProperties} from "react";

type segments = {
  text: string;
  className?: string;
  gradient?: boolean;
}

interface Props {
  segments: segments[];
  ariaLabel: string;
}

export function SequentialHeroHeading({segments, ariaLabel}: Props) {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useLayoutEffect(() => {
    const gradient = headingRef.current?.querySelector<HTMLElement>("[data-sequential-gradient]");
    if (!gradient) return;
    // Every glyph uses the same gradient canvas, including proportional spaces.
    const alignGradient = () => {
      const bounds = gradient.getBoundingClientRect();
      for (const character of gradient.children) {
        const element = character as HTMLElement;
        element.style.backgroundSize = `${bounds.width}px 100%`;
        element.style.backgroundPosition = `${bounds.left - element.getBoundingClientRect().left}px 0`;
      }
    };
    const observer = new ResizeObserver(alignGradient);
    observer.observe(gradient);
    alignGradient();
    return () => observer.disconnect();
  }, []);
  let offset = 0;
  return (
    <h1
      ref={headingRef}
      id="sequential-heading"
      aria-label={ariaLabel}
      className="w-max max-w-full break-keep text-[clamp(2rem,1.1rem+3.6vw,4rem)] font-bold leading-none text-text-primary"
    >
      {segments.map(({ text, className, gradient }) => (
        <span key={text} aria-hidden="true" data-sequential-gradient={gradient ? "" : undefined} className={gradient ? "inline-block whitespace-nowrap" : className}>
          {Array.from(text).map((character, index) => (
            <span
              key={index}
              className={`sequential-dissolve-character${gradient ? " inline-block whitespace-pre bg-gradient-to-r from-[#ff0000] to-[#00f2ff] bg-clip-text text-transparent" : ""}`}
              style={{
                "--character-delay": `${offset++ * 65}ms`,
              } as CSSProperties}
            >
              {character}
            </span>
          ))}
        </span>
      ))}
    </h1>
  );
}
