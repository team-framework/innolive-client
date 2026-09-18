"use client";

import { useLayoutEffect, useRef, type CSSProperties } from "react";

const segments = [
  { text: "InnoLive", className: "font-normal tracking-[-0.05em]" },
  { text: "의 ", className: "" },
  { text: "잠재력 해제", className: "", gradient: true },
];

export function PricingHeroHeading() {
  const headingRef = useRef<HTMLHeadingElement>(null);
  useLayoutEffect(() => {
    const gradient = headingRef.current?.querySelector<HTMLElement>("[data-pricing-gradient]");
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
      id="pricing-heading"
      aria-label="InnoLive의 잠재력 해제"
      className="w-max max-w-full break-keep text-[clamp(2rem,1.1rem+3.6vw,4rem)] font-bold leading-none text-text-primary"
    >
      {segments.map(({ text, className, gradient }) => (
        <span key={text} aria-hidden="true" data-pricing-gradient={gradient ? "" : undefined} className={gradient ? "inline-block whitespace-nowrap" : className}>
          {Array.from(text).map((character, index) => (
            <span
              key={index}
              className={`pricing-dissolve-character${gradient ? " inline-block whitespace-pre bg-gradient-to-r from-[#ff0000] to-[#00f2ff] bg-clip-text text-transparent" : ""}`}
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
