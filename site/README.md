# The PesaCast website

Plain HTML and one stylesheet, built with Vite. No JavaScript in the output, no cookies, nothing loaded from other websites. Design context: [.impeccable.md](../.impeccable.md).

| File | Page |
|---|---|
| `index.html` | What it does, what it does not do, where data goes, and the two paths (set up / build on it) |
| `start.html` | Setting up a phone, step by step |
| `phones.html` | What has been tested, what to expect from phones that stop background apps |
| `faq.html` | Questions people ask first |
| `developers.html` | The webhook: request, signature, retry rules, links to the schema and reference receiver |
| `security.html` | What the app can see, where data goes, what is stored, what has not been reviewed |
| `404.html` | Not found |
| `public/_headers` | Security headers (a strict Content-Security-Policy) for Cloudflare Pages and Netlify |
| `assets/` | Stylesheet, logo, and self-hosted fonts (see `assets/fonts/FONTS.md` for licences) |

## Run it

```bash
pnpm install                          # once, from the repo root
pnpm --filter pesacast-site dev       # http://localhost:5173, reloads on edit
```

## Build and check

```bash
pnpm --filter pesacast-site build     # writes site/dist
pnpm --filter pesacast-site preview   # serve the built site locally
python3 site/check.py                 # checks site/dist
```

The check fails if a page loses its title, description or single `h1`, if any link or file is broken, or if the output contains anything the Content-Security-Policy forbids: scripts, inline styles, or resources from other sites. CI builds and checks on every pull request.

## Links, icons and social previews

Every page gets its icons, Open Graph and Twitter card tags from `vite.config.js`, using the page's own title and description. The preview image is `public/og.png` (1200 by 630); its source is `design/og.html`, a plain page you can open with the dev server and screenshot at that size when the headline changes.

Crawlers and link previews need absolute addresses. Build with the real address and the build adds canonical links, absolute image addresses, `sitemap.xml` and a `Sitemap:` line in `robots.txt`:

```bash
SITE_URL=https://your.domain pnpm --filter pesacast-site build
```

Without `SITE_URL` the image path is relative, which is fine for local work but will not unfurl on social sites. Set it as an environment variable in your host's build settings.

## For people building a receiver

`developers.html` has a prompt to paste into an AI assistant, and `public/llms.txt` lists the spec files for tools that look for one. Both point at files on `main`, so update them if those files move.

## Publish

**Vercel.** Import the repository, then set:

| Setting | Value |
|---|---|
| Root Directory | `site` |
| Include source files outside of the Root Directory | on (the lockfile lives at the repo root) |
| Environment variable | `SITE_URL` = your final address, with `https://` |

Framework, build command and output directory come from `site/vercel.json`, which also carries the security headers (Vercel does not read `_headers`). Keep the two in sync; `check.py` fails if the policies differ. To avoid rebuilds on Android or desktop commits, set Ignored Build Step to `git diff --quiet HEAD^ HEAD -- . ../pnpm-lock.yaml`.

**Other hosts.**

Any static host works. On Cloudflare Pages: build command `pnpm --filter pesacast-site build`, output directory `site/dist`. `public/_headers` is copied into the output and picked up automatically. Nothing here publishes the site by itself.

## Editing

Edit the HTML pages. The header and footer are shared: `vite.config.js` replaces the `<!--site:header-->` and `<!--site:footer-->` markers in every page, so change the navigation there once.

Keep the tone: short sentences, honest about limits, no hype. Every claim should be something the code or the docs already say; when a claim depends on a release that is not out yet, say so on the page, and remove the note once the release is out.

