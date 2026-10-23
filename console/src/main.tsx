import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import { App } from './app/App'

async function start() {
  // mock mode: the API answered from captured samples in this browser, so the shell runs with no platform
  if (import.meta.env.VITE_CONSOLE_MOCK === '1') {
    const { installMock } = await import('./mock/install')
    installMock()
  }
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <App />
    </StrictMode>,
  )
}

void start()
