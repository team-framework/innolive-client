"use client";

import Image from "next/image";
import { useEffect, useId, useRef, useState } from "react";
import { cn } from "@/lib/cn";
import { downloadPlatforms } from "@/lib/site";

export function DownloadMenu() {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuId = useId();

  function close(restoreFocus = true) {
    setOpen(false);
    if (restoreFocus) {
      buttonRef.current?.focus();
    }
  }

  useEffect(() => {
    if (!open) {
      return;
    }

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.preventDefault();
        close();
      }
    }

    function onPointerDown(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        close();
      }
    }

    document.addEventListener("keydown", onKeyDown);
    document.addEventListener("pointerdown", onPointerDown);
    return () => {
      document.removeEventListener("keydown", onKeyDown);
      document.removeEventListener("pointerdown", onPointerDown);
    };
  }, [open]);

  return (
    <div ref={rootRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        className="inline-flex min-h-[29px] items-center justify-center text-base leading-none text-text-primary hover:underline sm:text-2xl"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={menuId}
        onClick={() => setOpen((current) => !current)}
      >
        Download
      </button>
      {open ? (
        <div
          id={menuId}
          role="menu"
          aria-label="다운로드"
          className="absolute right-0 z-20 mt-2 flex w-[195px] flex-col gap-2.5 rounded-[20px] bg-background-secondary p-2.5 shadow-button"
        >
          {downloadPlatforms.map((platform) => {
            const unavailable = !platform.href;
            const itemClass = cn(
              "flex h-[54px] w-full items-center gap-2 overflow-hidden rounded-[10px] p-2.5 text-left",
              unavailable
                ? "cursor-not-allowed"
                : "hover:bg-background-primary",
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
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="text-base font-semibold leading-none text-text-primary">
                    {platform.name}
                  </span>
                  <span className="text-base font-semibold leading-none text-text-secondary">
                    {unavailable
                      ? "아직 받을 수 없습니다"
                      : platform.minOs}
                  </span>
                </span>
              </>
            );

            if (platform.href) {
              return (
                <a
                  key={platform.id}
                  role="menuitem"
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
              <div
                key={platform.id}
                role="menuitem"
                aria-disabled="true"
                className={itemClass}
              >
                {content}
              </div>
            );
          })}
        </div>
      ) : null}
    </div>
  );
}
