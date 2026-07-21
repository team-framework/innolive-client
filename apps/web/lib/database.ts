import { Pool } from 'pg'

declare global { var innolivePool: Pool | undefined }

export const database = global.innolivePool ?? new Pool({ connectionString: process.env.DATABASE_URL })

if (process.env.NODE_ENV !== 'production') global.innolivePool = database
