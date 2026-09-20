import { notFound } from "next/navigation";
import { AuthShell } from "@/components/auth-shell";
import { documentMdxComponents } from "@/components/document-mdx";
import { SignupForm } from "@/components/signup-form";
import { documentContent } from "@/lib/document-content";
import { isLocale } from "@/lib/locales";
import { getMessages } from "@/lib/messages";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  return { title: getMessages(locale).metadata.signupTitle };
}

export default async function SignupPage({ params }: PageProps) {
  const { locale } = await params;
  if (!isLocale(locale)) notFound();
  const TermsOfService = documentContent.terms[locale];

  return (
    <AuthShell>
      <SignupForm>
        <article className="
          [&_p]:text-body
          [&_h1]:text-heading
          [&_h2]:text-body-lg
          [&_li]:text-caption
        ">
          <TermsOfService components={documentMdxComponents(locale)} />
        </article>
      </SignupForm>
    </AuthShell>
  );
}
