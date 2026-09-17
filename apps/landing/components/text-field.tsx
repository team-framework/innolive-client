"use client";

import Image from "next/image";
import type { ComponentPropsWithoutRef } from "react";
import { useId, useState } from "react";
import { cn } from "@/lib/cn";

const icons = {
  mail: "/icons/mail.svg",
  lock: "/icons/lock-keyhole.svg",
} as const;

export type TextFieldProps = Omit<
  ComponentPropsWithoutRef<"input">,
  "size"
> & {
  icon?: keyof typeof icons;
  error?: string;
  revealable?: boolean;
  label: string;
};

export function TextField({
  icon,
  className,
  error,
  revealable,
  label,
  id,
  type,
  ...props
}: TextFieldProps) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const errorId = `${inputId}-error`;
  const [visible, setVisible] = useState(false);
  const inputType = revealable ? (visible ? "text" : "password") : type;

  return (
    <div className="flex w-full max-w-[512px] flex-col gap-1">
      <div
        className={cn(
          "flex min-h-[54px] w-full items-center gap-2 overflow-hidden rounded-field bg-background-primary px-5 py-[var(--space-14)] sm:gap-3",
          className,
        )}
      >
        {icon ? (
          <Image
            src={icons[icon]}
            alt=""
            width={24}
            height={24}
            unoptimized
            aria-hidden="true"
            className="size-6 shrink-0"
          />
        ) : null}
        <label htmlFor={inputId} className="sr-only">
          {label}
        </label>
        <input
          id={inputId}
          type={inputType}
          placeholder={label}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className="min-w-0 flex-1 bg-transparent text-base font-medium leading-[1.3] text-text-primary placeholder:text-text-secondary sm:text-xl"
          {...props}
        />
        {revealable ? (
          <button
            type="button"
            className="shrink-0 text-sm font-medium text-text-secondary"
            aria-pressed={visible}
            aria-label={visible ? "비밀번호 숨기기" : "비밀번호 보기"}
            onClick={() => setVisible((current) => !current)}
          >
            {visible ? "숨기기" : "보기"}
          </button>
        ) : null}
      </div>
      {error ? (
        <p id={errorId} role="alert" className="px-2 text-sm text-text-primary">
          {error}
        </p>
      ) : null}
    </div>
  );
}
