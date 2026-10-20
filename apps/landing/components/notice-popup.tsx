"use client";

import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { useLocale } from "@/components/locale-provider";
import {
  NOTICE_HIDE_DURATION_MS,
  isNoticeActive,
  isNoticeHidden,
  notice,
  noticeClosedKey,
  noticeHiddenUntilKey,
} from "@/lib/notice";

const termClass =
  "flex items-center gap-2 text-[14px] font-bold text-text-primary before:size-[7px] before:shrink-0 before:bg-[#2442d6] before:content-['']";

function readStorage(storage: () => Storage, key: string) {
  try {
    return storage().getItem(key);
  } catch {
    return null;
  }
}

function writeStorage(storage: () => Storage, key: string, value: string) {
  try {
    storage().setItem(key, value);
  } catch {
    // Private mode or blocked storage: the notice simply shows again next time.
  }
}

// Poster palette of FIX 2026 (navy ground, magenta/orange and green/blue strokes).
function EventMark({ id }: { id: string }) {
  return (
    <svg
      viewBox="0 0 120 120"
      aria-hidden="true"
      className="pointer-events-none absolute -right-6 -top-4 h-[10.5rem] w-[10.5rem] opacity-90"
    >
      <defs>
        <linearGradient id={`${id}-a`} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#ff2e8a" />
          <stop offset="1" stopColor="#ff8a1f" />
        </linearGradient>
        <linearGradient id={`${id}-b`} x1="0" y1="1" x2="1" y2="0">
          <stop offset="0" stopColor="#33d17a" />
          <stop offset="1" stopColor="#22b8ff" />
        </linearGradient>
      </defs>
      <path d="M18 8h30l54 104H72Z" fill={`url(#${id}-a)`} />
      <path d="M72 8h30L48 112H18Z" fill={`url(#${id}-b)`} opacity="0.92" />
    </svg>
  );
}

export function NoticePopup() {
  const { messages } = useLocale();
  const copy = messages.notice;
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  const titleId = `${id}-title`;
  const bodyId = `${id}-body`;

  useEffect(() => {
    const now = Date.now();
    if (
      !isNoticeActive(now) ||
      isNoticeHidden(now, readStorage(() => window.localStorage, noticeHiddenUntilKey)) ||
      readStorage(() => window.sessionStorage, noticeClosedKey)
    ) {
      return;
    }

    const show = () => setOpen(true);
    // The home intro covers the page; wait until it finishes so the notice is not hidden under it.
    const introPending =
      document.documentElement.dataset.introComplete !== "true" &&
      document.querySelector(".intro-root") !== null;
    if (!introPending) {
      queueMicrotask(show);
      return;
    }
    window.addEventListener("innolive:intro-complete", show, { once: true });
    return () => window.removeEventListener("innolive:intro-complete", show);
  }, []);

  useLayoutEffect(() => {
    const node = ref.current;
    if (!node || !open) return;

    const previousOverflow = document.body.style.overflow;
    if (!node.open) node.showModal();
    // showModal would focus the scrollable body and draw its focus ring; start on the dialog instead.
    node.focus();
    document.body.style.overflow = "hidden";
    return () => {
      document.body.style.overflow = previousOverflow;
      if (node.open) node.close();
    };
  }, [open]);

  const close = () => {
    writeStorage(() => window.sessionStorage, noticeClosedKey, "1");
    setOpen(false);
  };

  const hideForDay = () => {
    writeStorage(
      () => window.localStorage,
      noticeHiddenUntilKey,
      String(Date.now() + NOTICE_HIDE_DURATION_MS),
    );
    setOpen(false);
  };

  if (!open) return null;

  return (
    <dialog
      ref={ref}
      tabIndex={-1}
      aria-labelledby={titleId}
      aria-describedby={bodyId}
      className="m-auto max-h-[min(92dvh,56rem)] w-[min(32rem,calc(100%-2rem))] max-w-none overflow-hidden rounded-[20px] border-0 bg-background-secondary p-0 text-text-primary shadow-[0_24px_64px_#00000040] outline-none open:flex open:flex-col [&::backdrop]:bg-black/50"
      onCancel={(event) => {
        event.preventDefault();
        close();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) close();
      }}
    >
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain focus-visible:outline-offset-[-2px]">
        <div className="relative overflow-hidden bg-[linear-gradient(135deg,#0a1a63_0%,#1a2f9c_60%,#2448c9_100%)] px-7 pb-5 pt-6 text-text-reversed">
          <EventMark id={id.replace(/:/g, "")} />
          <p className="relative text-[13px] font-semibold uppercase leading-none tracking-[0.08em] text-white/70">
            {copy.label}
          </p>
          <p className="relative mt-3 text-[30px] font-extrabold leading-none tracking-[-0.02em]">
            {copy.eventName}
          </p>
          <p className="relative mt-2 text-[14px] font-medium leading-[1.3] text-white/85">
            {copy.eventDetail}
          </p>
          <div className="relative mt-4 flex flex-wrap items-center gap-2 text-[13px] font-semibold leading-none">
            <span className="rounded-pill bg-white/15 px-3 py-[7px]">{copy.eventSchedule}</span>
            <span className="rounded-pill bg-white px-3 py-[7px] text-[#0a1a63]">
              {copy.eventBooth}
            </span>
          </div>
        </div>

        <div className="flex flex-col gap-4 px-7 pb-6 pt-5">
          <div className="flex flex-col items-start gap-3">
            <span className="rounded-[8px] bg-[#2442d6] px-2.5 py-[5px] text-[13px] font-bold leading-none text-text-reversed">
              {copy.badge}
            </span>
            <h2
              id={titleId}
              className="whitespace-pre-line break-keep text-[23px] font-bold leading-[1.3] tracking-[-0.02em]"
            >
              {copy.title}
            </h2>
          </div>

          <div
            id={bodyId}
            className="flex flex-col gap-4 break-keep text-[15px] leading-[1.6] text-text-secondary"
          >
            <div>
              <p className="font-medium text-text-primary">{copy.greeting}</p>
              {copy.intro.map((line) => (
                <p key={line}>{line}</p>
              ))}
            </div>

            <dl className="flex flex-col gap-3.5 rounded-[14px] bg-background-primary px-5 py-4">
              <div className="flex flex-col gap-1">
                <dt className={termClass}>{copy.periodTitle}</dt>
                <dd className="text-text-primary">{copy.period}</dd>
              </div>
              <div className="flex flex-col gap-1">
                <dt className={termClass}>{copy.scopeTitle}</dt>
                <dd className="text-text-primary">
                  <ul className="flex flex-col">
                    {copy.scope.map((item) => (
                      <li key={item} className="flex gap-2 before:content-['·']">
                        {item}
                      </li>
                    ))}
                  </ul>
                  <p className="text-text-secondary">{copy.scopeNote}</p>
                </dd>
              </div>
              <div className="flex flex-col gap-1">
                <dt className={termClass}>{copy.resumeTitle}</dt>
                <dd className="text-text-primary">{copy.resume}</dd>
              </div>
            </dl>

            <div>
              <p>{copy.apology}</p>
              <p>{copy.booth}</p>
              <p>{copy.closing}</p>
            </div>

            <p>
              {copy.contact}:{" "}
              <a
                href={`mailto:${notice.contactEmail}`}
                className="font-medium text-text-primary underline [text-underline-position:from-font]"
              >
                {notice.contactEmail}
              </a>
            </p>
          </div>
        </div>
      </div>

      <div className="flex shrink-0 items-stretch justify-between border-t border-surface-primary">
        <button
          type="button"
          className="min-h-14 px-7 text-left text-[15px] text-text-secondary transition-colors hover:text-text-primary"
          onClick={hideForDay}
        >
          {copy.hideToday}
        </button>
        <button
          type="button"
          className="min-h-14 px-7 text-[15px] font-semibold transition-colors hover:text-text-secondary"
          onClick={close}
        >
          {copy.close}
        </button>
      </div>
    </dialog>
  );
}
