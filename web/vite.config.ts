import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [svelte(), tailwindcss()],
  // Relative, not '/': the server can mount the app at the hostname root OR under a path
  // prefix (MINIAPP_URL with a path, e.g. https://example.com/exchange/). An absolute '/'
  // base would always resolve against the origin root and break under a path prefix.
  base: './',
  build: { outDir: 'dist', emptyOutDir: true },
  server: { proxy: { '/api': 'http://localhost:8080' } },
})
