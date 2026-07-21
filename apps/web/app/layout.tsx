import type { Metadata } from 'next'

import './globals.css'

export const metadata: Metadata = {
  title: 'InnoLive',
  description: '안전한 라이브 방송을 위한 InnoLive',
}

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko"><body>{children}</body></html>
}
