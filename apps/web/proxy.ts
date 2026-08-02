import { type NextRequest, NextResponse } from 'next/server'

export const locales = [
  { code: 'ko', label: '한국어' },
  { code: 'en', label: 'English' },
  { code: 'ja', label: '日本語' },
] as const
const publicFile = /\.[^/]+$/

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl

  if (pathname.startsWith('/_next') || pathname.startsWith('/api') || publicFile.test(pathname)) {
    return NextResponse.next()
  }

  const pathnameLocale = locales.find(
    (locale) => pathname === `/${locale.code}` || pathname.startsWith(`/${locale.code}/`),
  )

  if (pathnameLocale) {
    const response = NextResponse.next()
    response.cookies.set('NEXT_LOCALE', pathnameLocale.code, { path: '/', sameSite: 'lax' })
    return response
  }

  const cookieLocale = request.cookies.get('NEXT_LOCALE')?.value
  const browserLocale = request.headers
    .get('accept-language')
    ?.split(',')
    .map((language) => language.trim().split(';')[0].split('-')[0])
    .find((language) => locales.some((locale) => locale.code === language))
  const locale = cookieLocale && locales.some((locale) => locale.code === cookieLocale) ? cookieLocale : browserLocale ?? 'ko'
  const url = request.nextUrl.clone()
  url.pathname = `/${locale}${pathname}`

  return NextResponse.redirect(url)
}
