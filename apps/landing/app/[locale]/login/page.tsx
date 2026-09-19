import { AuthShell } from "@/components/auth-shell";
import { LoginForm } from "@/components/login-form";
import { getMessages } from "@/lib/messages";

type PageProps = {
  params: Promise<{ locale: string }>;
};

export async function generateMetadata({ params }: PageProps) {
  const { locale } = await params;
  return { title: getMessages(locale).metadata.loginTitle };
}

export default function LoginPage() {
  return (
    <AuthShell>
      <LoginForm />
    </AuthShell>
  );
}
