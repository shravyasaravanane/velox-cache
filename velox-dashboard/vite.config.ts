import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // velox-server runs on 8080 (mvn -pl velox-server spring-boot:run); proxying /api here
    // means the dashboard's own EventSource/fetch calls need no CORS config on the backend
    // and no hardcoded origin in the frontend code.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
