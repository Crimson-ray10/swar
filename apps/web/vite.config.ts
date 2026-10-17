import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { resolve } from 'node:path';

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        main: resolve(__dirname, 'index.html'),
        transceiver: resolve(__dirname, 'transceiver.html'),
      },
    },
  },
  server: {
    port: 5173,
    host: '0.0.0.0',
  },
});
