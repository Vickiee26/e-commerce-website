import { restoreSession } from '@shopflow/api-client'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './index.css'

const rootElement = document.getElementById('root')
if (rootElement === null) throw new Error('#root is missing from index.html')

// A stored refresh token with no access token means the page was reloaded. Exchanging it
// before the first render is what keeps an admin signed in across a refresh, and it costs one
// request only when a session is actually there to restore.
await restoreSession()

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
