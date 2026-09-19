"use client";

import Image from "next/image";
import { useEffect, useId, useRef, useState } from "react";
import { Button } from "@/components/button";
import { useLocale } from "@/components/locale-provider";
import { cn } from "@/lib/cn";
import { downloadPlatforms } from "@/lib/site";

type DownloadMenuProps = {
  trigger?: "default" | "hero";
};

export function DownloadMenu({ trigger = "default" }: DownloadMenuProps) {
  const { messages } = useLocale();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();
  const isHero = trigger === "hero";

  useEffect(() => {
    if (!open) {
      return;
    }

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        setOpen(false);
        buttonRef.current?.focus();
      }
    }

    function onPointerDown(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  const triggerProps = {
    ref: buttonRef,
    "aria-expanded": open,
    "aria-controls": menuId,
    onClick: () => setOpen((current) => !current),
  };

  return (
    <div ref={rootRef} className="relative w-fit">
      {isHero ? (
        <Button
          variant="primary"
          showChevron
          className="h-[53.2px] w-[136.5px] md:h-[76px] md:w-[195px]"
          {...triggerProps}
        >
          Download
          <br aria-hidden="true" />
          InnoLive
        </Button>
      ) : (
        <button
          type="button"
          className="inline-flex min-h-[29px] items-center justify-center text-base leading-none text-text-primary hover:underline md:text-2xl"
          {...triggerProps}
        >
          Download
        </button>
      )}
      {open ? (
        <div
          id={menuId}
          aria-label={messages.download.label}
          className={cn(
            "absolute z-20 mt-2 flex w-[195px] flex-col gap-2.5 rounded-[20px] bg-background-secondary p-2.5 shadow-button",
            isHero ? "left-0" : "right-0",
          )}
        >
          {downloadPlatforms.map((platform) => {
            const unavailable = !platform.href;
            const itemClass = cn(
              "flex h-[54px] w-full items-center gap-2 rounded-[10px] p-2.5 text-left",
              unavailable ? "cursor-not-allowed" : "hover:bg-background-primary",
            );
            const content = (
              <>
                <span className="flex size-[26px] shrink-0 items-center justify-center">
                  <Image
                    src={platform.iconSrc}
                    alt=""
                    width={platform.iconWidth}
                    height={platform.iconHeight}
                    unoptimized
                    aria-hidden="true"
                  />
                </span>
                <span className="flex min-w-0 flex-col justify-center leading-none">
                  <span className="text-base font-semibold leading-none text-text-primary">
                    {platform.name}
                  </span>
                  <span className="text-sm font-semibold leading-none text-text-secondary">
                    {unavailable ? messages.download.unavailable : platform.minOs}
                  </span>
                </span>
              </>
            );

            if (platform.href) {
              return (
                <a
                  key={platform.id}
                  className={itemClass}
                  href={platform.href}
                  target="_blank"
                  rel="noreferrer"
                >
                  {content}
                </a>
              );
            }

            return (
              <p key={platform.id} className={itemClass}>
                {content}
              </p>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
