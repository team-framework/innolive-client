"use client";

import { useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/button";
import { useLocale } from "@/components/locale-provider";

type TermsConsentDialogProps = {
  onClose: () => void;
  onAgree: () => void;
  children: ReactNode;
};

export function TermsConsentDialog({
  onClose,
  onAgree,
  children,
}: TermsConsentDialogProps) {
  const { messages } = useLocale();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const [hasReachedEnd, setHasReachedEnd] = useState(false);

  useLayoutEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;

    const previousOverflow = document.body.style.overflow;
    dialog.showModal();
    document.body.style.overflow = "hidden";

    return () => {
      document.body.style.overflow = previousOverflow;
      if (dialog.open) dialog.close();
    };
  }, []);

  const updateScrollState = () => {
    const content = contentRef.current;
    if (!content) return;
    setHasReachedEnd(
      content.scrollTop + content.clientHeight >= content.scrollHeight - 1,
    );
  };

  return (
    <dialog
      ref={dialogRef}
      aria-labelledby="terms-consent-title"
      className="m-auto w-[min(59.625rem,calc(100%-2rem))] border-0 bg-transparent p-0 text-inherit [&::backdrop]:bg-black/40"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <section className="grid h-[min(44rem,calc(100dvh-2rem))] overflow-hidden rounded-[12px] bg-background-secondary shadow-card min-[48rem]:grid-cols-[19.75rem_minmax(0,1fr)]">
        <div className="flex flex-col justify-between gap-8 p-8 min-[48rem]:p-10">
          <div>
            <h2 id="terms-consent-title" className="text-heading text-text-primary">
              {messages.auth.termsTitle}
            </h2>
            <p className="mt-2 text-body text-text-secondary">
              {messages.auth.termsLead}
            </p>
          </div>
          <Button
            type="button"
            variant="secondary"
            showChevron={false}
            disabled={!hasReachedEnd}
            className="w-full max-w-none"
            onClick={onAgree}
          >
            {messages.auth.agreeButton}
          </Button>
        </div>
        <div
          ref={contentRef}
          tabIndex={0}
          aria-label={messages.auth.termsBodyLabel}
          className="min-h-0 overflow-y-auto border-t border-surface-primary px-6 py-8 min-[48rem]:border-l min-[48rem]:border-t-0"
          onScroll={updateScrollState}
        >
          <article className="mx-auto max-w-[38rem] text-text-primary">
            {children}
          </article>
        </div>
      </section>
    </dialog>
  );
}
