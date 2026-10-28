const PUBLIC_BASE_URL = "https://pub-162b1465caff4240b9002d150090b804.r2.dev";
const TURNSTILE_SITEKEY = "0x4AAAAAAFqAc7c9lfzrV5he";
const MAX_COVER_BYTES = 4 * 1024 * 1024;
const SESSION_DURATION_SECONDS = 24 * 60 * 60;
const encoder = new TextEncoder();

const base64Url = (value) => btoa(String.fromCharCode(...new Uint8Array(value)))
  .replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");

const fromBase64Url = (value) => Uint8Array.from(
  atob(value.replaceAll("-", "+").replaceAll("_", "/")), (char) => char.charCodeAt(0),
);

async function signingKey(secret) {
  return crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
}

async function createSession(secret) {
  const payload = base64Url(encoder.encode(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + SESSION_DURATION_SECONDS })));
  const signature = base64Url(await crypto.subtle.sign("HMAC", await signingKey(secret), encoder.encode(payload)));
  return `${payload}.${signature}`;
}

async function validSession(value, secret) {
  const [payload, signature] = value?.split(".") ?? [];
  if (!payload || !signature) return false;
  const valid = await crypto.subtle.verify("HMAC", await signingKey(secret), fromBase64Url(signature), encoder.encode(payload));
  if (!valid) return false;
  try {
    return JSON.parse(new TextDecoder().decode(fromBase64Url(payload))).exp > Math.floor(Date.now() / 1000);
  } catch {
    return false;
  }
}

async function validTurnstile(token, secret, ip) {
  const body = new FormData();
  body.append("secret", secret);
  body.append("response", token);
  if (ip) body.append("remoteip", ip);
  const response = await fetch("https://challenges.cloudflare.com/turnstile/v0/siteverify", { method: "POST", body });
  const result = await response.json();
  return result.success === true;
}

function authorizationPage() {
  return new Response(`<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script></head><body><p>Verifying cover sharing…</p><div class="cf-turnstile" data-sitekey="${TURNSTILE_SITEKEY}" data-callback="authorized"></div><script>async function authorized(token) { const response = await fetch('/session', { method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({token}) }); const data = await response.json(); if (data.session) location.href = 'pulse://cloudflare-authorized?session=' + encodeURIComponent(data.session); }</script></body></html>`, { headers: { "Content-Type": "text/html; charset=utf-8" } });
}

export default {
  async fetch(request, env) {
    const path = new URL(request.url).pathname;
    if (request.method === "GET" && path === "/authorize") return authorizationPage();
    if (request.method === "POST" && path === "/session") {
      if (!env.TURNSTILE_SECRET || !env.SESSION_SECRET) return new Response("Worker secrets are not configured", { status: 503 });
      const token = (await request.json().catch(() => ({}))).token;
      const valid = token && await validTurnstile(token, env.TURNSTILE_SECRET, request.headers.get("CF-Connecting-IP"));
      return valid ? Response.json({ session: await createSession(env.SESSION_SECRET) }) : new Response("Verification failed", { status: 403 });
    }
    if (request.method !== "POST" || path !== "/covers") return new Response("Not found", { status: 404 });
    if (!env.SESSION_SECRET || !await validSession(request.headers.get("Authorization")?.replace("Bearer ", ""), env.SESSION_SECRET)) return new Response("Unauthorized", { status: 401 });
    const contentType = request.headers.get("Content-Type")?.toLowerCase();
    const extensions = { "image/jpeg": "jpg", "image/png": "png", "image/webp": "webp" };
    const extension = extensions[contentType];
    if (!extension) return new Response("Only JPEG, PNG, and WebP covers are accepted", { status: 415 });
    const bytes = await request.arrayBuffer();
    if (bytes.byteLength === 0 || bytes.byteLength > MAX_COVER_BYTES) return new Response("Cover must be between 1 byte and 4 MiB", { status: 413 });
    const digest = await crypto.subtle.digest("SHA-256", bytes);
    const name = [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, "0")).join("");
    const key = `covers/${name}.${extension}`;
    if (!(await env.COVERS.head(key))) await env.COVERS.put(key, bytes, { httpMetadata: { contentType, cacheControl: "public, max-age=31536000, immutable" } });
    return Response.json({ url: `${PUBLIC_BASE_URL}/${key}` });
  },
};
