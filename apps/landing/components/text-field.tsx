"use client";

import Image from "next/image";
import type { ComponentPropsWithoutRef } from "react";
import { useId, useState } from "react";
import { useLocale } from "@/components/locale-provider";
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
  const { messages } = useLocale();
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const errorId = `${inputId}-error`;
  const [visible, setVisible] = useState(false);
  const inputType = revealable ? (visible ? "text" : "password") : type;

  return (
    <div className="flex w-full max-w-[512px] flex-col gap-1">
      <div
        className={cn(
          "relative flex min-h-[54px] w-full items-center gap-2 overflow-hidden rounded-field bg-background-primary px-5 py-[var(--space-14)] max-lg:border max-lg:border-border-control sm:gap-3",
          // Below md: outlined field with a floating label instead of the icon pill.
          "max-md:min-h-[60px] max-md:items-stretch max-md:gap-0 max-md:rounded-[10px] max-md:bg-background-secondary max-md:p-0 max-md:transition-shadow max-md:has-[input[aria-invalid=true]]:border-text-primary max-md:focus-within:border-text-primary max-md:focus-within:shadow-[inset_0_0_0_1px_var(--color-text-primary)]",
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
            className="size-6 shrink-0 max-md:hidden"
          />
        ) : null}
        <input
          id={inputId}
          type={inputType}
          placeholder={label}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className="peer min-w-0 flex-1 bg-transparent text-[16px] max-lg:-my-[var(--space-14)] max-lg:self-stretch max-lg:py-[var(--space-14)] max-md:my-0 max-md:px-4 max-md:pb-2 max-md:pt-[26px] max-md:font-normal max-md:placeholder:text-transparent max-md:focus-visible:outline-none font-medium leading-[1.3] text-text-primary placeholder:text-text-secondary sm:text-xl"
          {...props}
        />
        {/* Visible floating label below md; md and up keep the placeholder-only field. Follows the input for peer-*. */}
        <label
          htmlFor={inputId}
          className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-[16px] leading-none text-text-secondary transition-all duration-150 peer-focus:top-[11px] peer-focus:translate-y-0 peer-focus:text-[12px] peer-focus:font-medium peer-focus:text-text-primary peer-[:not(:placeholder-shown)]:top-[11px] peer-[:not(:placeholder-shown)]:translate-y-0 peer-[:not(:placeholder-shown)]:text-[12px] md:sr-only"
        >
          {label}
        </label>
        {revealable ? (
          <button
            type="button"
            className="shrink-0 text-sm font-medium text-text-secondary max-lg:-my-[8px] max-lg:min-h-[44px] max-lg:min-w-[44px] max-lg:text-[13px] max-md:my-0 max-md:mr-2 max-md:self-center max-md:px-2 max-md:text-[14px]"
            aria-pressed={visible}
            aria-label={visible ? messages.auth.hidePasswordLabel : messages.auth.showPasswordLabel}
            onClick={() => setVisible((current) => !current)}
          >
            {visible ? messages.auth.hidePassword : messages.auth.showPassword}
          </button>
        ) : null}
      </div>
      {error ? (
        <p id={errorId} role="alert" className="px-2 text-sm text-text-primary max-lg:text-[13px] max-md:px-1">
          {error}
        </p>
      ) : null}
    </div>
  );
}
