import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // Detect file replacements during Git operations on Windows as well as editor saves.
    watch: process.platform === 'win32' ? { usePolling: true, interval: 1000 } : undefined,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
