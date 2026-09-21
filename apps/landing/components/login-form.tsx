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
      router.push(href("/"));
      router.refresh();
    } catch (error) {
      setNotice(authErrorMessage(error, copy.errors));
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <form
      className="flex w-full max-w-[32rem] flex-col items-center gap-7"
      noValidate
      onSubmit={onSubmit}
    >
      <h1 className="text-[clamp(1.75rem,1.2rem+1.5vw,2.75rem)] font-semibold leading-[1.3] text-text-primary">
        {copy.loginTitle}
      </h1>
      <div className="flex w-full flex-col items-start gap-2">
        <TextField
          icon="mail"
          label={copy.email}
          type="email"
          autoComplete="email"
          value={email}
          error={errors.email}
          onChange={(event) => setEmail(event.target.value)}
        />
        <TextField
          icon="lock"
          label={copy.password}
          autoComplete="current-password"
          revealable
          value={password}
          error={errors.password}
          onChange={(event) => setPassword(event.target.value)}
        />
        <div className="flex w-full justify-end px-2">
          <button
            type="button"
            className="text-base font-normal leading-[1.3] text-text-secondary underline [text-underline-position:from-font]"
            onClick={() => setNotice(copy.forgotPending)}
          >
            {copy.forgotPassword}
          </button>
        </div>
      </div>
      <div className="flex w-full flex-col items-stretch gap-2">
        <Button
          type="submit"
          variant="secondary"
          showChevron={false}
          className="w-full max-w-none"
          disabled={isSubmitting}
        >
          {isSubmitting ? copy.submittingLogin : copy.submitLogin}
        </Button>
        <Button
          href={href("/signup")}
          showChevron={false}
          className="w-full max-w-none"
          disabled={isSubmitting}
        >
          {copy.goSignup}
        </Button>
      </div>
      {notice ? (
        <p role="status" aria-live="polite" className="w-full text-center text-base text-text-primary">
          {notice}
        </p>
      ) : null}
    </form>
  );
}
