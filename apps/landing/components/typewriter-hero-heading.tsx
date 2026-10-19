import type { CSSProperties } from "react";
import type { Messages } from "@/lib/messages";
import { ProgressiveBlurText } from "@/components/progressive-blur-text";

function characters(text: string, offset = 0) {
  return Array.from(text).map((character, index) => (
    <span
      key={index}
      className="hero-typewriter-character"
      style={{ "--character-delay": `${(offset + index + 1) * 40}ms` } as CSSProperties}
    >
      {character}
    </span>
  ));
}

export function TypewriterHeroHeading({ copy }: { copy: Messages["hero"] }) {
  const firstLine = copy.line1;
  const secondPrefix = copy.line2Prefix;
  const blurText = copy.line2Blur;
  const heading = `${firstLine} ${secondPrefix}${blurText}`;

  return (
    <h1
      id="hero-heading"
      className="w-full break-words text-[length:var(--text-display)] font-bold leading-[calc(100/84)] tracking-tight text-text-primary"
    >
      <span className="sr-only">{heading}</span>
      <span aria-hidden="true">
        {characters(firstLine)}
        <br />
        {characters(secondPrefix, Array.from(firstLine).length)}
        <ProgressiveBlurText
          text={blurText}
          revealOffset={Array.from(firstLine + secondPrefix).length}
        />
      </span>
    </h1>
  );
}
