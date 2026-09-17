"use client";

import { useEffect, useRef } from "react";

export function ProgressiveBlurText({ text }: { text: string }) {
  const rootRef = useRef<HTMLSpanElement>(null);

  useEffect(() => {
    const root = rootRef.current;
    if (!root) return;

    const updateReveal = (event: PointerEvent) => {
      if (event.pointerType === "touch") return;

      const bounds = root.getBoundingClientRect();
      const nearestX = Math.min(Math.max(event.clientX, bounds.left), bounds.right);
      const nearestY = Math.min(Math.max(event.clientY, bounds.top), bounds.bottom);
      const distance = Math.hypot(event.clientX - nearestX, event.clientY - nearestY);

      if (distance > 35) {
        root.dataset.revealed = "false";
        return;
      }

      root.style.setProperty("--blur-pointer-x", `${event.clientX - bounds.left}px`);
      root.style.setProperty("--blur-pointer-y", `${event.clientY - bounds.top}px`);
      root.dataset.revealed = "true";
    };

    window.addEventListener("pointermove", updateReveal, { passive: true });
    return () => window.removeEventListener("pointermove", updateReveal);
  }, []);

  return (
    <span
      ref={rootRef}
      className="progressive-blur-text relative inline-block text-transparent"
    >
      {text}
      <span aria-hidden="true" className="progressive-blur-layer">
        {Array.from(text).map((character, index) => (
          <span
            key={`${character}-${index}`}
            style={{ "--character-blur": `${10 + index * 8}px` } as React.CSSProperties}
          >
            {character}
          </span>
        ))}
      </span>
      <span aria-hidden="true" className="progressive-sharp-layer">
        {text}
      </span>
    </span>
  );
}
