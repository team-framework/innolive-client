import { HomePage } from '../pages/home'
import { QueryProvider } from './providers/QueryProvider'

export function App() {
  return (
    <QueryProvider>
      <HomePage />
    </QueryProvider>
  )
}
