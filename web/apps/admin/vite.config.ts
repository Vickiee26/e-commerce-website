import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const BACKEND = process.env.VITE_BACKEND_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  // The workspace client ships TypeScript source; pre-bundling it would skip the transform.
  optimizeDeps: { exclude: ['@shopflow/api-client'] },
  server: {
    port: 5173,
    // SecurityConfig.java never calls .cors(...), so the backend rejects cross-origin browser
    // calls. Proxying makes /api and /auth same-origin and needs no backend change.
    proxy: {
      '/api': { target: BACKEND, changeOrigin: true },
      '/auth': { target: BACKEND, changeOrigin: true },
    },
  },
})
