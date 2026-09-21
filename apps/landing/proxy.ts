import { type NextRequest, NextResponse } from "next/server";
import { defaultLocale, isLocale } from "@/lib/locales";

const publicFile = /\.[^/]+$/;

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl;

  if (
    pathname.startsWith("/_next") ||
    pathname.startsWith("/api") ||
    publicFile.test(pathname)
  ) {
    return NextResponse.next();
  }

  const firstSegment = pathname.split("/")[1] ?? "";

  if (isLocale(firstSegment)) {
    const response = NextResponse.next();
    response.cookies.set("NEXT_LOCALE", firstSegment, {
      path: "/",
      sameSite: "lax",
    });
    return response;
  }

  if (/^[a-z]{2}$/.test(firstSegment)) {
    return NextResponse.next();
  }

  const cookieLocale = request.cookies.get("NEXT_LOCALE")?.value;
  const browserLocale = request.headers
    .get("accept-language")
    ?.split(",")
    .map((language) => language.trim().split(";")[0]?.split("-")[0] ?? "")
    .find((language) => isLocale(language));
  const locale =
    cookieLocale && isLocale(cookieLocale)
      ? cookieLocale
      : browserLocale && isLocale(browserLocale)
        ? browserLocale
        : defaultLocale;

  const url = request.nextUrl.clone();
  url.pathname = `/${locale}${pathname === "/" ? "" : pathname}`;
  return NextResponse.redirect(url);
}
