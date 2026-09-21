"use client";

import type { ReactNode } from "react";
import { useLayoutEffect, useRef } from "react";
import { useLocale } from "@/components/locale-provider";

type DialogProps = {
  open: boolean;
  onClose: () => void;
  label: string;
  children: ReactNode;
};

export function Dialog({ open, onClose, label, children }: DialogProps) {
  const { messages } = useLocale();
  const ref = useRef<HTMLDialogElement>(null);

  useLayoutEffect(() => {
    const node = ref.current;
    if (!node) {
      return;
    }

    const previousOverflow = document.body.style.overflow;

    if (open) {
      if (!node.open) {
        node.showModal();
      }
      document.body.style.overflow = "hidden";
    } else if (node.open) {
      node.close();
    }

    return () => {
      document.body.style.overflow = previousOverflow;
      if (node.open) {
        node.close();
      }
    };
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-label={label}
      className="m-auto max-h-[90dvh] w-max max-w-[calc(100%-2rem)] border-0 bg-transparent p-0 text-inherit open:flex open:flex-col [&::backdrop]:bg-black/40"
      onCancel={(event) => {
        event.preventDefault();
        onClose();
      }}
      onClick={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="sticky top-0 z-10 flex justify-end p-2">
        <button
          type="button"
          className="rounded-pill bg-background-secondary px-3 py-1 text-sm text-text-primary shadow-button"
          onClick={onClose}
        >
          {messages.common.close}
        </button>
      </div>
      <div className="min-h-0 overflow-y-auto overflow-x-hidden px-2 pb-2">
        {children}
      </div>
    </dialog>
  );
}
