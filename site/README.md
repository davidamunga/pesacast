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

## Publish

Any static host works. On Cloudflare Pages: build command `pnpm --filter pesacast-site build`, output directory `site/dist`. `public/_headers` is copied into the output and picked up automatically. Nothing here publishes the site by itself.

## Editing

Edit the HTML pages. The header and footer are shared: `vite.config.js` replaces the `<!--site:header-->` and `<!--site:footer-->` markers in every page, so change the navigation there once.

Keep the tone: short sentences, honest about limits, no hype. Every claim should be something the code or the docs already say; when a claim depends on a release that is not out yet, say so on the page (see the note at the top of `start.html`, and remove it once the release is out).

Things to update once the next release is out: the version note on `start.html` and the "Which versions have which features?" answer on `faq.html`.
