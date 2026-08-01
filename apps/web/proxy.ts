import { type NextRequest, NextResponse } from 'next/server'

const locales = ['ko', 'en', 'ja']
const publicFile = /\.[^/]+$/

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl

  if (pathname.startsWith('/_next') || pathname.startsWith('/api') || publicFile.test(pathname)) {
    return NextResponse.next()
  }

  const pathnameLocale = locales.find(
    (locale) => pathname === `/${locale}` || pathname.startsWith(`/${locale}/`),
  )

  if (pathnameLocale) {
    const response = NextResponse.next()
    response.cookies.set('NEXT_LOCALE', pathnameLocale, { path: '/', sameSite: 'lax' })
    return response
  }

  const cookieLocale = request.cookies.get('NEXT_LOCALE')?.value
  const browserLocale = request.headers
    .get('accept-language')
    ?.split(',')
    .map((language) => language.trim().split(';')[0].split('-')[0])
    .find((language) => locales.includes(language))
  const locale = cookieLocale && locales.includes(cookieLocale) ? cookieLocale : browserLocale ?? 'ko'
  const url = request.nextUrl.clone()
  url.pathname = `/${locale}${pathname}`

  return NextResponse.redirect(url)
}
