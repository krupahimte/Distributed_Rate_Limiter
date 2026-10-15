/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Where the rate limiter API runs. Defaults to http://localhost:8080 (nginx in docker compose). */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
