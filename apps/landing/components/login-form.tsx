"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Button } from "@/components/button";
import { useLocale } from "@/components/locale-provider";
import { TextField } from "@/components/text-field";
import { authErrorMessage, signIn } from "@/lib/auth-client";
import { isValidEmail } from "@/lib/auth-validation";

export function LoginForm() {
  const router = useRouter();
  const { href, messages } = useLocale();
  const copy = messages.auth;
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<{ email?: string; password?: string }>(
    {},
  );
  const [notice, setNotice] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const next: { email?: string; password?: string } = {};
    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      next.email = copy.validation.emailRequired;
    } else if (!isValidEmail(trimmedEmail)) {
      next.email = copy.validation.emailInvalid;
    }
    if (!password) {
      next.password = copy.validation.passwordRequired;
    }
    setErrors(next);
    setNotice(null);
    if (Object.keys(next).length > 0) return;

    setIsSubmitting(true);
    try {
      await signIn(trimmedEmail, password);
      router.push(href("/#main"));
      router.refresh();
    } catch (error) {
      setNotice(authErrorMessage(error, copy.errors));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form
      className="flex w-full max-w-[32rem] flex-col items-center gap-6"
      noValidate
      onSubmit={onSubmit}
    >
      <h1 className="auth-rise text-[24px] font-semibold leading-[1.3] tracking-[-0.02em] text-text-primary max-lg:self-start max-lg:text-[22px] max-lg:font-bold">
        {copy.loginTitle}
      </h1>
      <div className="auth-rise flex w-full flex-col items-start gap-2 [animation-delay:80ms]">
        <TextField
          className="auth-field"
          icon="mail"
          label={copy.email}
          type="email"
          autoComplete="email"
          value={email}
          error={errors.email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <TextField
          className="auth-field"
          icon="lock"
          label={copy.password}
          autoComplete="current-password"
          revealable
          value={password}
          error={errors.password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <div className="flex w-full justify-end px-2 max-lg:-my-2">
          <button
            type="button"
            className="rounded-sm text-[13px] font-normal leading-[1.3] text-text-secondary underline [text-underline-position:from-font] transition-colors hover:text-text-primary max-lg:min-h-[44px] max-lg:text-[14px]"
            onClick={() => setNotice(copy.forgotPending)}
          >
            {copy.forgotPassword}
          </button>
        </div>
      </div>
      <div className="auth-rise flex w-full flex-col items-stretch gap-2 [animation-delay:160ms]">
        <Button
          type="submit"
          variant="secondary"
          showChevron={false}
          className="auth-submit w-full max-w-none"
          disabled={isSubmitting}
        >
          {isSubmitting ? copy.submittingLogin : copy.submitLogin}
        </Button>
        <Button
          href={href("/signup")}
          showChevron={false}
          className="auth-alt w-full max-w-none"
          disabled={isSubmitting}
        >
          {copy.goSignup}
        </Button>
      </div>
      {notice ? (
        <p role="status" aria-live="polite" className="w-full text-center text-[14px] text-text-primary">
          {notice}
        </p>
      ) : null}
    </form>
  );
}
