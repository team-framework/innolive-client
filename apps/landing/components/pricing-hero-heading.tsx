import type { CSSProperties } from "react";

const segments = [
  { text: "InnoLive", className: "font-normal tracking-[-0.05em]" },
  { text: "의 ", className: "" },
  { text: "잠재력 해제", className: "", gradient: true },
];

export function PricingHeroHeading() {
  let offset = 0;
  return (
    <h1
      id="pricing-heading"
      aria-label="InnoLive의 잠재력 해제"
      className="w-max max-w-full break-keep text-[clamp(2rem,1.1rem+3.6vw,4rem)] font-bold leading-none text-text-primary"
    >
      {segments.map(({ text, className, gradient }) => (
        <span key={text} aria-hidden="true" className={className}>
          {Array.from(text).map((character, index) => (
            <span
              key={index}
              className="pricing-dissolve-character"
              style={{
                "--character-delay": `${offset++ * 65}ms`,
                ...(gradient ? {
                  color: `rgb(${255 * (1 - index / (text.length - 1))} ${242 * index / (text.length - 1)} ${255 * index / (text.length - 1)})`,
                } : {}),
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
