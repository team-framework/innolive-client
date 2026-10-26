import { AuthShell } from "@/components/auth-shell";
import { LoginForm } from "@/components/login-form";
import { getMessages } from "@/lib/messages";
import { pageMetadata } from "@/lib/seo";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  const { metadata } = getMessages(locale);
  return pageMetadata(locale, "/login", {
    title: metadata.loginTitle,
    description: metadata.description,
  });
}

export default function LoginPage() {
  return (
    <AuthShell>
      <LoginForm />
    </AuthShell>
  );
}
