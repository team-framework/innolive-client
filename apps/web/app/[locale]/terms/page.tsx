import Link from 'next/link'
import type { ReactNode } from 'react'

import { getTermsContent } from '../../../lib/terms-content'

const backLabel: Record<string, string> = {
  ko: '홈으로 돌아가기',
  en: 'Back to home',
  ja: 'ホームに戻る',
}

const documentTitle: Record<string, string> = {
  ko: '이용약관',
  en: 'Terms of Service',
  ja: 'サービス利用規約',
}

const inlinePattern = /\*\*(.+?)\*\*|\[([^\]]+)\]\(([^)]+)\)/g

// 약관 원문은 **굵게**와 [링크](주소)만 사용합니다.
function renderInline(text: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = []
  let cursor = 0
  let index = 0

  for (const match of text.matchAll(inlinePattern)) {
    const start = match.index ?? 0
    if (start > cursor) {
      nodes.push(text.slice(cursor, start))
    }

    if (match[1] !== undefined) {
      nodes.push(
        <strong key={`${keyPrefix}-s${index}`} className="font-semibold text-white">
          {renderInline(match[1], `${keyPrefix}-s${index}`)}
        </strong>,
      )
    } else {
      const href = match[3]
      // 사이트 안의 경로는 Link로, 바깥 주소는 새 탭으로 엽니다.
      nodes.push(
        href.startsWith('/') && !href.startsWith('//') ? (
          <Link key={`${keyPrefix}-a${index}`} href={href} className="underline underline-offset-4">
            {match[2]}
          </Link>
        ) : (
          <a
            key={`${keyPrefix}-a${index}`}
            href={href}
            className="underline underline-offset-4"
            target="_blank"
            rel="noreferrer"
          >
            {match[2]}
          </a>
        ),
      )
    }

    cursor = start + match[0].length
    index += 1
  }

  if (cursor < text.length) {
    nodes.push(text.slice(cursor))
  }

  return nodes
}

type Block =
  | { kind: 'h1'; text: string }
  | { kind: 'h2'; text: string }
  | { kind: 'p'; text: string }
  | { kind: 'ul'; items: string[] }

function parseBlocks(markdown: string): Block[] {
  const blocks: Block[] = []

  for (const raw of markdown.trim().split(/\n{2,}/)) {
    const chunk = raw.trim()
    if (chunk.length === 0) {
      continue
    }

    if (chunk.startsWith('# ')) {
      blocks.push({ kind: 'h1', text: chunk.slice(2).trim() })
      continue
    }

    if (chunk.startsWith('## ')) {
      blocks.push({ kind: 'h2', text: chunk.slice(3).trim() })
      continue
    }

    if (chunk.startsWith('- ')) {
      blocks.push({
        kind: 'ul',
        items: chunk.split('\n').map((line) => line.replace(/^-\s+/, '').trim()),
      })
      continue
    }

    blocks.push({ kind: 'p', text: chunk.replace(/\n/g, ' ') })
  }

  return blocks
}

export default async function TermsPage({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params
  const blocks = parseBlocks(getTermsContent(locale))

  return (
    <main className="min-h-screen bg-[#050505] px-6 py-16 text-white md:px-12">
      <article className="mx-auto max-w-3xl text-base leading-8 text-[#e5e5e5]" lang={locale}>
        <Link href={`/${locale}`} className="text-sm text-[#a3a3a3] underline underline-offset-4">
          {backLabel[locale] ?? backLabel.ko}
        </Link>
        {blocks.map((block, blockIndex) => {
          const key = `block-${blockIndex}`

          if (block.kind === 'h1') {
            return (
              <h1 key={key} className="mt-6 text-3xl font-semibold text-white">
                {renderInline(block.text, key)}
              </h1>
            )
          }

          if (block.kind === 'h2') {
            return (
              <h2 key={key} className="mt-10 text-xl font-semibold text-white">
                {renderInline(block.text, key)}
              </h2>
            )
          }

          if (block.kind === 'ul') {
            return (
              <ul key={key} className="mt-4 list-disc space-y-2 pl-6">
                {block.items.map((item, itemIndex) => (
                  <li key={`${key}-${itemIndex}`}>{renderInline(item, `${key}-${itemIndex}`)}</li>
                ))}
              </ul>
            )
          }

          return (
            <p key={key} className="mt-4">
              {renderInline(block.text, key)}
            </p>
          )
        })}
      </article>
    </main>
  )
}

export async function generateMetadata({ params }: { params: Promise<{ locale: string }> }) {
  const { locale } = await params
  return { title: documentTitle[locale] ?? documentTitle.ko }
}
