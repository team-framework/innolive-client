"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent, type ReactNode } from "react";
import { Button } from "@/components/button";
import { Checkbox } from "@/components/checkbox";
import { useLocale } from "@/components/locale-provider";
import { TextField } from "@/components/text-field";
import { TermsConsentDialog } from "@/components/terms-consent-dialog";
import { authErrorMessage, completeSignup, startSignup } from "@/lib/auth-client";
import {
  isValidEmail,
  isValidSignupPassword,
  isVerificationCode,
} from "@/lib/auth-validation";
import { interpolate } from "@/lib/locales";

type SignupErrors = {
  email?: string;
  password?: string;
  confirm?: string;
  agreed?: string;
  verificationCode?: string;
};

export function SignupForm({ children }: { children: ReactNode }) {
  const router = useRouter();
  const { href, messages } = useLocale();
  const copy = messages.auth;
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [verificationCode, setVerificationCode] = useState("");
  const [agreed, setAgreed] = useState(false);
  const [hasAcceptedTerms, setHasAcceptedTerms] = useState(false);
  const [termsDialogOpen, setTermsDialogOpen] = useState(false);
  const [step, setStep] = useState<"credentials" | "verification">(
    "credentials",
  );
  const [errors, setErrors] = useState<SignupErrors>({});
  const [notice, setNotice] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  const [agreeBefore, agreeAfter] = copy.agree.split("{terms}");

  const submitCredentials = async () => {
    const next: SignupErrors = {};
    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      next.email = copy.validation.emailRequired;
    } else if (!isValidEmail(trimmedEmail)) {
      next.email = copy.validation.emailInvalid;
    }
    if (!password) {
      next.password = copy.validation.passwordRequired;
    } else if (!isValidSignupPassword(password)) {
      next.password = copy.validation.passwordLength;
    }
    if (!confirm) {
      next.confirm = copy.validation.confirmRequired;
    } else if (confirm !== password) {
      next.confirm = copy.validation.confirmMismatch;
    }
    if (!agreed) next.agreed = copy.validation.termsRequired;

    setErrors(next);
    setNotice(null);
    if (Object.keys(next).length > 0) return;

    setIsSubmitting(true);
    try {
      await startSignup(trimmedEmail, password);
      setStep("verification");
      setNotice(copy.verificationSent);
    } catch (error) {
      setNotice(authErrorMessage(error, copy.errors));
    } finally {
      setIsSubmitting(false);
    }
  };

  const submitVerification = async () => {
    const next: SignupErrors = {};
    if (!isVerificationCode(verificationCode)) {
      next.verificationCode = copy.validation.codeRequired;
    }
    setErrors(next);
    setNotice(null);
    if (Object.keys(next).length > 0) return;

    setIsSubmitting(true);
    try {
      await completeSignup(verificationCode.trim());
      router.replace(href("/login"));
    } catch (error) {
      setNotice(authErrorMessage(error, copy.errors));
    } finally {
      setIsSubmitting(false);
    }
  };

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (step === "verification") {
      await submitVerification();
      return;
    }
    await submitCredentials();
  };

  return (
    <form
      className="flex w-full max-w-[32rem] flex-col items-center gap-7"
      noValidate
      onSubmit={onSubmit}
    >
      <h1 className="text-[clamp(1.75rem,1.2rem+1.5vw,2.75rem)] font-semibold leading-[1.3] text-text-primary">
        {step === "verification" ? copy.verifyTitle : copy.signupTitle}
      </h1>
      {step === "verification" ? (
        <div className="flex w-full flex-col items-start gap-2">
          <p className="w-full px-2 text-base text-text-secondary">
            {interpolate(copy.verificationHint, { email })}
          </p>
          <TextField
            icon="mail"
            label={copy.verificationCode}
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            value={verificationCode}
            error={errors.verificationCode}
            onChange={(event) => setVerificationCode(event.target.value.replace(/\D/g, ""))}
          />
        </div>
      ) : (
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
            autoComplete="new-password"
            revealable
            value={password}
            error={errors.password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <TextField
            icon="lock"
            label={copy.confirmPassword}
            autoComplete="new-password"
            revealable
            value={confirm}
            error={errors.confirm}
            onChange={(event) => setConfirm(event.target.value)}
          />
          <div className="flex w-full min-w-0 flex-col gap-1 px-2 break-keep">
            <Checkbox
              className="w-full min-w-0 break-keep"
              checked={agreed}
              onChange={(event) => {
                if (event.target.checked && !hasAcceptedTerms) {
                  setTermsDialogOpen(true);
                  return;
                }
                setAgreed(event.target.checked);
              }}
              aria-invalid={errors.agreed ? true : undefined}
              aria-describedby={errors.agreed ? "signup-agree-error" : undefined}
              label={
                <>
                  {agreeBefore}
                  <Link
                    href={href("/terms")}
                    className="underline [text-underline-position:from-font]"
                    onClick={(event) => {
                      if (!hasAcceptedTerms) {
                        event.preventDefault();
                        setTermsDialogOpen(true);
                      }
                    }}
                  >
                    {copy.terms}
                  </Link>
                  {agreeAfter}
                </>
              }
            />
            {errors.agreed ? (
              <p
                id="signup-agree-error"
                role="alert"
                className="text-sm text-text-primary"
              >
                {errors.agreed}
              </p>
            ) : null}
          </div>
        </div>
      )}
      <div className="flex w-full flex-col items-stretch gap-2">
        <Button
          type="submit"
          variant="secondary"
          showChevron={false}
          className="w-full max-w-none"
          disabled={isSubmitting}
        >
          {isSubmitting
            ? copy.processing
            : step === "verification"
              ? copy.completeVerify
              : copy.sendCode}
        </Button>
        {step !== "verification" ? (
          <Button href={href("/login")} showChevron={false} className="w-full max-w-none">
            {copy.goLogin}
          </Button>
        ): null}
      </div>
      {notice ? (
        <p role="status" aria-live="polite" className="w-full text-center text-base text-text-primary">
          {notice}
        </p>
      ) : null}
      {termsDialogOpen ? (
        <TermsConsentDialog
          onClose={() => setTermsDialogOpen(false)}
          onAgree={() => {
            setHasAcceptedTerms(true);
            setAgreed(true);
            setTermsDialogOpen(false);
            setErrors((current) => ({ ...current, agreed: undefined }));
          }}
        >
          {children}
        </TermsConsentDialog>
      ) : null}
    </form>
  );
}
