import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Omit ships one offline bundle to three shells:
//   Android WebView  -> https://app.omit.local/index.html (assets served from APK)
//   Windows (Tauri)  -> tauri://localhost / https://tauri.localhost
//   Browser preview  -> relative paths under any static host
// `base: "./"` keeps every asset URL relative so none of those origins break.
export default defineConfig({
  base: "./",
  plugins: [react(), tailwindcss()],
  build: {
    outDir: "../app/src/main/assets/ui",
    emptyOutDir: true,
    assetsInlineLimit: 4096,
    // Fonts live in public/fonts and are referenced with relative urls from CSS.
    assetsDir: "assets",
    target: "es2020",
  },
  css: {
    preprocessorMaxWorkers: 1,
  },
});
