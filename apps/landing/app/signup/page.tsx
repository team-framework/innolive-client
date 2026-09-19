import { AuthShell } from "@/components/auth-shell";
import { documentMdxComponents } from "@/components/document-mdx";
import { SignupForm } from "@/components/signup-form";
import TermsOfService from "@/content/documents/terms-of-service.ko.mdx";

export default function SignupPage() {
  return (
    <AuthShell>
      <SignupForm>
        <TermsOfService components={documentMdxComponents("ko")} />
      </SignupForm>
    </AuthShell>
  );
}
