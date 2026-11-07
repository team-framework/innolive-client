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
  hasErrors,
  validateSignupCredentials,
  validateVerificationCode,
  type SignupCredentialErrors,
  type VerificationErrors,
} from "@/lib/auth-validation";
import { trackConversion } from "@/lib/conversion-analytics";
import { interpolate } from "@/lib/locales";

type SignupErrors = SignupCredentialErrors & VerificationErrors;

export function SignupForm({ children }: { children: ReactNode }) {
  const router = useRouter();
  const { href, messages, locale } = useLocale();
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
    const next = validateSignupCredentials(
      { email, password, confirm, agreed },
      copy.validation,
    );
    setErrors(next);
    setNotice(null);
    if (hasErrors(next)) return;

    setIsSubmitting(true);
    try {
      await startSignup(email.trim(), password);
      trackConversion("signup_verification_sent", locale);
      setStep("verification");
      setNotice(copy.verificationSent);
    } catch (error) {
      setNotice(authErrorMessage(error, copy.errors));
    } finally {
      setIsSubmitting(false);
    }
  };

  const submitVerification = async () => {
    const next = validateVerificationCode(verificationCode, copy.validation);
    setErrors(next);
    setNotice(null);
    if (hasErrors(next)) return;

    setIsSubmitting(true);
    try {
      await completeSignup(verificationCode.trim());
      trackConversion("signup_completed", locale);
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
      className="flex w-full max-w-[32rem] flex-col items-center gap-6"
      noValidate
      onSubmit={onSubmit}
    >
      <h1 className="auth-rise text-[24px] font-semibold leading-[1.3] tracking-[-0.02em] text-text-primary max-lg:self-start max-lg:text-[22px] max-lg:font-bold">
        {step === "verification" ? copy.verifyTitle : copy.signupTitle}
      </h1>
      {step === "verification" ? (
        <div className="auth-rise flex w-full flex-col items-start gap-2 [animation-delay:80ms]">
          <p className="w-full px-2 text-[14px] text-text-secondary">
            {interpolate(copy.verificationHint, { email })}
          </p>
          <TextField
            className="auth-field"
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
            autoComplete="new-password"
            revealable
            value={password}
            error={errors.password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <TextField
            className="auth-field"
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
              className="auth-terms w-full min-w-0 break-keep"
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
                    className="underline [text-underline-position:from-font] max-lg:inline-flex max-lg:min-h-[44px] max-lg:min-w-[44px] max-lg:items-center"
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
                className="text-sm text-text-primary max-lg:text-[13px]"
              >
                {errors.agreed}
              </p>
            ) : null}
          </div>
        </div>
      )}
      <div className="auth-rise flex w-full flex-col items-stretch gap-2 [animation-delay:160ms]">
        <Button
          type="submit"
          variant="secondary"
          showChevron={false}
          className="auth-submit w-full max-w-none"
          disabled={isSubmitting}
        >
          {isSubmitting
            ? copy.processing
            : step === "verification"
              ? copy.completeVerify
              : copy.sendCode}
        </Button>
        {step !== "verification" ? (
          <Button href={href("/login")} showChevron={false} className="auth-alt w-full max-w-none">
            {copy.goLogin}
          </Button>
        ): null}
      </div>
      {notice ? (
        <p role="status" aria-live="polite" className="w-full text-center text-[14px] text-text-primary">
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
