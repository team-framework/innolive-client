"use client";

import Link from "next/link";
import { useState, type FormEvent, type ReactNode } from "react";
import { Button } from "@/components/button";
import { Checkbox } from "@/components/checkbox";
import { TextField } from "@/components/text-field";
import { TermsConsentDialog } from "@/components/terms-consent-dialog";

const emailPattern = /.+@.+\..+/;

export function SignupForm({ children }: { children: ReactNode }) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [agreed, setAgreed] = useState(false);
  const [hasAcceptedTerms, setHasAcceptedTerms] = useState(false);
  const [termsDialogOpen, setTermsDialogOpen] = useState(false);
  const [errors, setErrors] = useState<{
    email?: string;
    password?: string;
    confirm?: string;
    agreed?: string;
  }>({});
  const [notice, setNotice] = useState<string | null>(null);

  const onSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const next: typeof errors = {};
    if (!email.trim()) {
      next.email = "이메일을 입력해 주세요";
    } else if (!emailPattern.test(email.trim())) {
      next.email = "올바른 이메일 주소를 입력해 주세요";
    }
    if (!password) {
      next.password = "비밀번호를 입력해 주세요";
    } else if (password.length < 8) {
      next.password = "비밀번호는 8자 이상이어야 합니다";
    }
    if (!confirm) {
      next.confirm = "비밀번호를 다시 입력해 주세요";
    } else if (confirm !== password) {
      next.confirm = "비밀번호가 일치하지 않습니다";
    }
    if (!agreed) {
      next.agreed = "서비스 이용약관에 동의해 주세요";
    }
    setErrors(next);
    if (Object.keys(next).length > 0) {
      setNotice(null);
      return;
    }
    setNotice("회원가입 기능을 준비 중입니다");
  };

  return (
    <form
      className="flex w-full max-w-[32rem] flex-col items-center gap-7"
      noValidate
      onSubmit={onSubmit}
    >
      <h1 className="text-[clamp(1.75rem,1.2rem+1.5vw,2.75rem)] font-semibold leading-[1.3] text-text-primary">
        회원가입
      </h1>
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
      <div className="flex w-full flex-col items-stretch gap-2">
        <Button
          type="submit"
          variant="secondary"
          showChevron={false}
          className="w-full max-w-none"
        >
          회원가입
        </Button>
        <Button href="/login" showChevron={false} className="w-full max-w-none">
          로그인으로 이동
        </Button>
      </div>
      {notice ? (
        <p role="status" className="w-full text-center text-base text-text-primary">
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
