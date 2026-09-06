/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react-swc";
import path from "path";

// https://vitejs.dev/config/
export default defineConfig(() => ({
  // Vercel deployment: serve from root
  base: '/',
  server: {
    host: "::",
    port: 8080,
  },
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  test: {
    environment: 'node',
    include: [
      'src/listening/__tests__/**/*.{test,spec}.{ts,tsx}',
      'src/trackDna/__tests__/**/*.{test,spec}.{ts,tsx}',
      'src/taste/__tests__/**/*.{test,spec}.{ts,tsx}',
      'src/recommendation/__tests__/**/*.{test,spec}.{ts,tsx}',
      'src/userState/__tests__/**/*.{test,spec}.{ts,tsx}',
      'src/home/__tests__/**/*.{test,spec}.{ts,tsx}',
      'src/services/__tests__/**/*.{test,spec}.{ts,tsx}',
      'src/presentation/__tests__/**/*.{test,spec}.{ts,tsx}',
    ],
  },
}));
