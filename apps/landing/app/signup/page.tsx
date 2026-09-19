import { AuthShell } from "@/components/auth-shell";
import { documentMdxComponents } from "@/components/document-mdx";
import { SignupForm } from "@/components/signup-form";
import TermsOfService from "@/content/documents/terms-of-service.ko.mdx";

export default function SignupPage() {
  return (
    <AuthShell>
      <SignupForm>
        <article className="
          [&_p]:text-body
          [&_h1]:text-heading
          [&_h2]:text-body-lg
          [&_li]:text-caption
        ">
          <TermsOfService components={documentMdxComponents("ko")} />
        </article>
      </SignupForm>
    </AuthShell>
  );
}
