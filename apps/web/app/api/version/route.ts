export const dynamic = 'force-dynamic'

export async function GET() {
  return Response.json(
    { revision: process.env.INNOLIVE_WEB_REVISION?.trim() ?? '' },
    { headers: { 'Cache-Control': 'no-store' } },
  )
}
