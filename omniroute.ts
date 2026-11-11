// OpenAI-compatible client for an OmniRoute gateway. Default model: "auto".
// Env: OMNIROUTE_BASE_URL (with or without /v1), OMNIROUTE_API_KEY

export interface ChatMessage {
  role: "system" | "user" | "assistant" | "tool";
  content: string;
}

export interface OmniRouteOptions {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  timeoutMs?: number;
  maxRetries?: number;
}

export class OmniRouteError extends Error {
  constructor(public status: number, message: string, public body?: unknown) {
    super(message);
    this.name = "OmniRouteError";
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** Accepts "http://host:port" or "http://host:port/v1" and always returns the /v1 form. */
export function normalizeBaseUrl(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, "");
  return /\/v1$/.test(trimmed) ? trimmed : `${trimmed}/v1`;
}

export class OmniRoute {
  private baseUrl: string;
  private apiKey: string;
  private model: string;
  private timeoutMs: number;
  private maxRetries: number;

  constructor(opts: OmniRouteOptions = {}) {
    this.baseUrl = normalizeBaseUrl(
      opts.baseUrl ?? process.env.OMNIROUTE_BASE_URL ?? "http://localhost:20128",
    );
    this.apiKey = opts.apiKey ?? process.env.OMNIROUTE_API_KEY ?? "";
    this.model = opts.model ?? "auto";
    this.timeoutMs = opts.timeoutMs ?? 60_000;
    this.maxRetries = opts.maxRetries ?? 3;
  }

  private async request(path: string, body: unknown): Promise<Response> {
    let attempt = 0;
    for (;;) {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), this.timeoutMs);
      try {
        const res = await fetch(`${this.baseUrl}${path}`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            ...(this.apiKey && { Authorization: `Bearer ${this.apiKey}` }),
          },
          body: JSON.stringify(body),
          signal: ctrl.signal,
        });
        if (res.ok) return res;

        const retryable = res.status === 429 || res.status >= 500;
        if (!retryable || attempt >= this.maxRetries) {
          const text = await res.text();
          let parsed: unknown = text;
          try { parsed = JSON.parse(text); } catch { /* keep raw */ }
          throw new OmniRouteError(res.status, `OmniRoute ${res.status}`, parsed);
        }
      } catch (err) {
        if (err instanceof OmniRouteError) throw err;
        if (attempt >= this.maxRetries) throw err;
      } finally {
        clearTimeout(timer);
      }
      attempt++;
      await sleep(Math.min(8000, 500 * 2 ** attempt) + Math.random() * 250);
    }
  }

  async chat(messages: ChatMessage[], extra: Record<string, unknown> = {}): Promise<string> {
    const res = await this.request("/chat/completions", { model: this.model, messages, ...extra });
    const json = (await res.json()) as any;
    return json.choices?.[0]?.message?.content ?? "";
  }

  async *stream(messages: ChatMessage[], extra: Record<string, unknown> = {}): AsyncGenerator<string> {
    const res = await this.request("/chat/completions", {
      model: this.model,
      messages,
      stream: true,
      ...extra,
    });
    const reader = res.body!.getReader();
    const decoder = new TextDecoder();
    let buf = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      let idx: number;
      while ((idx = buf.indexOf("\n")) >= 0) {
        const line = buf.slice(0, idx).trim();
        buf = buf.slice(idx + 1);
        if (!line.startsWith("data:")) continue;
        const data = line.slice(5).trim();
        if (data === "[DONE]") return;
        try {
          const delta = JSON.parse(data).choices?.[0]?.delta?.content;
          if (delta) yield delta;
        } catch { /* partial frame */ }
      }
    }
  }
}
