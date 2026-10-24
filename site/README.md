# The PesaCast website

Plain HTML and one stylesheet. No build step, no JavaScript, no cookies, nothing loaded from other websites. Design context: [.impeccable.md](../.impeccable.md).

| File | Page |
|---|---|
| `index.html` | What it does, what it does not do, where data goes, and the two paths (set up / build on it) |
| `start.html` | Setting up a phone, step by step |
| `phones.html` | What has been tested, what to expect from phones that stop background apps |
| `faq.html` | Questions people ask first |
| `developers.html` | The webhook: request, signature, retry rules, links to the schema and reference receiver |
| `security.html` | What the app can see, where data goes, what is stored, what has not been reviewed |
| `404.html` | Not found |
| `_headers` | Security headers (a strict Content-Security-Policy) for Cloudflare Pages and Netlify |
| `assets/` | Stylesheet, logo, and self-hosted fonts (see `assets/fonts/FONTS.md` for licences) |

## Preview

```bash
python3 -m http.server 4173 --bind 127.0.0.1 --directory site
```

Then open http://127.0.0.1:4173.

## Check it

```bash
python3 site/check.py
```

It fails if a page loses its title, description or single `h1`, if any link or file is broken, or if anything is added that the Content-Security-Policy forbids: scripts, inline styles, or resources from other sites. CI runs it.

## Publish

Any static host works. On Cloudflare Pages: connect the repository, leave the build command empty, set the output directory to `site`. The `_headers` file is picked up automatically. Nothing here publishes the site by itself.

## Editing

Edit the HTML directly. The header and footer are repeated in each page on purpose (no build step), so a change to the navigation has to be made in every file; `check.py` will flag a broken link if one is missed.

Keep the tone: short sentences, honest about limits, no hype. Every claim should be something the code or the docs already say; when a claim depends on a release that is not out yet, say so on the page (see the note at the top of `start.html`, and remove it once the release is out).

Things to update once the next release is out: the version note on `start.html` and the "Which versions have which features?" answer on `faq.html`.
