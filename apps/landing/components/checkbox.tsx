"use client";

import Image from "next/image";
import type { ComponentPropsWithoutRef, ReactNode } from "react";
import { useId } from "react";
import { cn } from "@/lib/cn";

export type CheckboxProps = Omit<
  ComponentPropsWithoutRef<"input">,
  "type" | "size"
> & {
  label?: ReactNode;
};

export function Checkbox({
  className,
  label,
  id,
  ...props
}: CheckboxProps) {
  const generatedId = useId();
  const inputId = id ?? generatedId;

  return (
    <label
      htmlFor={inputId}
      className={cn("flex min-w-0 items-start gap-2 break-keep", className)}
    >
      <input
        id={inputId}
        type="checkbox"
        className="peer sr-only focus:outline-none focus-visible:outline-none"
        {...props}
      />
      <span className="relative mt-0.5 inline-flex size-5 shrink-0 items-center justify-center overflow-hidden rounded-control bg-surface-primary peer-checked:bg-button-secondary peer-checked:[&_img]:opacity-100 peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-text-primary">
        <Image
          src="/icons/checkbox-check.svg"
          alt=""
          width={15}
          height={11}
          unoptimized
          aria-hidden="true"
          className="opacity-0"
        />
      </span>
      {label ? (
        <span className="min-w-0 break-keep text-caption font-normal text-text-secondary">
          {label}
        </span>
      ) : null}
    </label>
  );
}
