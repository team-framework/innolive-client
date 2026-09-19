"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState, type FormEvent, type ReactNode } from "react";
import { Button } from "@/components/button";
import { Checkbox } from "@/components/checkbox";
import { TextField } from "@/components/text-field";
import { TermsConsentDialog } from "@/components/terms-consent-dialog";
import { authErrorMessage, completeSignup, startSignup } from "@/lib/auth-client";
import {
  isValidEmail,
  isValidSignupPassword,
  isVerificationCode,
} from "@/lib/auth-validation";

type SignupErrors = {
  email?: string;
  password?: string;
  confirm?: string;
  agreed?: string;
  verificationCode?: string;
};

export function SignupForm({ children }: { children: ReactNode }) {
  const router = useRouter();
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

  const submitCredentials = async () => {
    const next: SignupErrors = {};
    const trimmedEmail = email.trim();
    if (!trimmedEmail) {
      next.email = "이메일을 입력해 주세요";
    } else if (!isValidEmail(trimmedEmail)) {
      next.email = "올바른 이메일 주소를 입력해 주세요";
    }
    if (!password) {
      next.password = "비밀번호를 입력해 주세요";
    } else if (!isValidSignupPassword(password)) {
      next.password = "비밀번호는 UTF-8 기준 8~72바이트여야 합니다";
    }
    if (!confirm) {
      next.confirm = "비밀번호를 다시 입력해 주세요";
    } else if (confirm !== password) {
      next.confirm = "비밀번호가 일치하지 않습니다";
    }
    if (!agreed) next.agreed = "서비스 이용약관에 동의해 주세요";

    setErrors(next);
    setNotice(null);
    if (Object.keys(next).length > 0) return;

    setIsSubmitting(true);
    try {
      await startSignup(trimmedEmail, password);
      setStep("verification");
      setNotice("인증 메일을 보냈습니다. 6자리 인증 코드를 입력해 주세요.");
    } catch (error) {
      setNotice(authErrorMessage(error));
    } finally {
      setIsSubmitting(false);
    }
  };

  const submitVerification = async () => {
    const next: SignupErrors = {};
    if (!isVerificationCode(verificationCode)) {
      next.verificationCode = "6자리 인증 코드를 입력해 주세요";
    }
    setErrors(next);
    setNotice(null);
    if (Object.keys(next).length > 0) return;

    setIsSubmitting(true);
    try {
      await completeSignup(verificationCode.trim());
      router.replace("/login");
    } catch (error) {
      setNotice(authErrorMessage(error));
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
        {step === "verification" ? "이메일 인증" : "회원가입"}
      </h1>
      {step === "verification" ? (
        <div className="flex w-full flex-col items-start gap-2">
          <p className="w-full px-2 text-base text-text-secondary">
            {email}으로 보낸 인증 코드를 입력해 주세요.
          </p>
          <TextField
            icon="mail"
            label="6자리 인증 코드"
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
            label="이메일"
            type="email"
            autoComplete="email"
            value={email}
            error={errors.email}
            onChange={(event) => setEmail(event.target.value)}
          />
          <TextField
            icon="lock"
            label="비밀번호"
            autoComplete="new-password"
            revealable
            value={password}
            error={errors.password}
            onChange={(event) => setPassword(event.target.value)}
          />
          <TextField
            icon="lock"
            label="비밀번호 재입력"
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
                  <Link
                    href="/terms"
                    className="underline [text-underline-position:from-font]"
                    onClick={(event) => {
                      if (!hasAcceptedTerms) {
                        event.preventDefault();
                        setTermsDialogOpen(true);
                      }
                    }}
                  >
                    서비스 이용약관
                  </Link>
                  에 동의합니다.
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
            ? "처리 중"
            : step === "verification"
              ? "인증 완료"
              : "인증 메일 보내기"}
        </Button>
        {step === "verification" ? (
          <Button
            type="button"
            showChevron={false}
            className="w-full max-w-none"
            disabled={isSubmitting}
            onClick={() => {
              setStep("credentials");
              setErrors({});
              setNotice(null);
            }}
          >
            가입 정보 다시 입력
          </Button>
        ) : null}
        <Button href="/login" showChevron={false} className="w-full max-w-none">
          로그인으로 이동
        </Button>
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
