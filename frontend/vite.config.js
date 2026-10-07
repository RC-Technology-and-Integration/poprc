import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { suppliedRevision } from "./buildRevision.mjs";

const packageInfo = JSON.parse(
  readFileSync(new URL("./package.json", import.meta.url), "utf8"),
);

const git = (args) => {
  try {
    return execFileSync("git", args, { encoding: "utf8" }).trim();
  } catch {
    return "";
  }
};

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const revisionInformada = suppliedRevision(env.VITE_APP_REVISION);
  const revision = revisionInformada || git(["rev-parse", "--short=8", "HEAD"]) || "sem-commit";
  const alteracoesLocais = !revisionInformada && Boolean(git(["status", "--porcelain"]));
  const version = env.VITE_APP_VERSION?.trim() || packageInfo.version;
  const versionLabel = `v${version}+${revision}${alteracoesLocais ? "-local" : ""}`;
  const allowedHosts = (env.VITE_ALLOWED_HOSTS || "")
    .split(",")
    .map((host) => host.trim())
    .filter(Boolean);

  return {
    plugins: [react()],
    define: {
      "import.meta.env.VITE_APP_VERSION_LABEL": JSON.stringify(versionLabel),
    },
    server: {
      port: 5173,
      strictPort: true,
      allowedHosts,
      proxy: {
        "/api": {
          target: env.VITE_PROXY_TARGET || "http://localhost:8085",
          changeOrigin: true,
          configure: (proxy) => {
            proxy.on("proxyReq", (proxyReq) => {
              proxyReq.removeHeader("origin");
            });
          },
        },
      },
    },
  };
});
