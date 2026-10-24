import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// base "./" : le site marche sous /docs/ (Nginx) comme en local, sans reglage.
export default defineConfig({
  base: "./",
  plugins: [react()],
  server: { fs: { allow: [".."] } },
  build: { outDir: "dist", emptyOutDir: true },
});
