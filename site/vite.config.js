// The PesaCast site is a plain multi-page Vite app: every .html file in this folder is a page,
// Vite bundles the stylesheet, fonts and logo with hashed names, and the output contains no
// JavaScript at all (the Content-Security-Policy in public/_headers forbids it).
//
// The only custom piece is a tiny plugin that fills in the shared header and footer, so they live
// in one place instead of being copied into every page.
import { readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

const root = fileURLToPath(new URL(".", import.meta.url));
const GH = "https://github.com/davidamunga/pesacast";

const NAV = [
  ["start.html", "Set up"],
  ["phones.html", "Phones"],
  ["faq.html", "Questions"],
  ["developers.html", "Developers"],
  ["security.html", "Security"],
];

const header = (current) => `<header class="site-header">
  <div class="wrap">
    <a class="brand" href="index.html"><img src="assets/logo.png" alt="" width="36" height="36">PesaCast</a>
    <nav class="site-nav" aria-label="Main">${NAV.map(
      ([href, label]) => `<a href="${href}"${href === current ? ' aria-current="page"' : ""}>${label}</a>`,
    ).join("")}<a class="nav-star" href="${GH}">&#9733; Star on GitHub</a></nav>
  </div>
</header>`;

const footer = `<footer class="site-footer">
  <div class="wrap">
    <div>
      <p>PesaCast is free software under the MIT licence. It is not made by, affiliated with or endorsed by Safaricom. M-PESA is a trademark of Safaricom PLC.</p>
      <p>This site has no cookies, no analytics, no scripts, and loads nothing from other websites.</p>
      <p class="made">Made by <a href="https://davidamunga.com">David Amunga</a> &middot; <a href="https://x.com/davidamunga_">@davidamunga_</a> on X</p>
    </div>
    <ul>
      <li><a href="${GH}">Source code</a></li>
      <li><a href="${GH}/blob/main/LICENSE">MIT licence</a></li>
      <li><a href="${GH}/blob/main/CONTRIBUTING.md">Contribute</a></li>
      <li><a href="${GH}/releases">Downloads</a></li>
      <li><a href="${GH}/issues/new/choose">Report a problem</a></li>
      <li><a href="security.html">Security</a></li>
    </ul>
  </div>
</footer>`;

// Set SITE_URL (for example https://pesacast.example) when building for production. It makes the
// social image, canonical links and sitemap absolute, which crawlers and link previews need.
const SITE_URL = (process.env.SITE_URL || "").replace(/\/+$/, "");
const TWITTER = "@davidamunga_";
const OG_ALT = "PesaCast: M-PESA payment notifications on your computer or server.";

const esc = (s) => s.replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;");
const abs = (path) => (SITE_URL ? `${SITE_URL}/${path}` : path);

function headTags(html, page) {
  const title = (html.match(/<title>([\s\S]*?)<\/title>/) || [])[1] || "PesaCast";
  const description = (html.match(/<meta name="description" content="([^"]*)"/) || [])[1] || "";
  const url = page === "index.html" ? abs("") : abs(page);
  const tags = [
    `<link rel="icon" href="favicon.ico" sizes="48x48">`,
    `<link rel="icon" type="image/png" href="favicon-32.png" sizes="32x32">`,
    `<link rel="apple-touch-icon" href="apple-touch-icon.png">`,
    `<meta name="theme-color" content="#ffffff">`,
    `<meta property="og:site_name" content="PesaCast">`,
    `<meta property="og:type" content="website">`,
    `<meta property="og:locale" content="en">`,
    `<meta property="og:title" content="${title}">`,
    `<meta property="og:description" content="${description}">`,
    `<meta property="og:image" content="${abs("og.png")}">`,
    `<meta property="og:image:width" content="1200">`,
    `<meta property="og:image:height" content="630">`,
    `<meta property="og:image:alt" content="${esc(OG_ALT)}">`,
    `<meta name="twitter:card" content="summary_large_image">`,
    `<meta name="twitter:site" content="${TWITTER}">`,
    `<meta name="twitter:creator" content="${TWITTER}">`,
    `<meta name="twitter:title" content="${title}">`,
    `<meta name="twitter:description" content="${description}">`,
    `<meta name="twitter:image" content="${abs("og.png")}">`,
  ];
  if (SITE_URL) {
    tags.push(`<link rel="canonical" href="${url}">`, `<meta property="og:url" content="${url}">`);
  }
  if (page === "404.html") tags.push(`<meta name="robots" content="noindex">`);
  return "  " + tags.join("\n  ") + "\n";
}

function sharedLayout() {
  return {
    name: "pesacast-shared-layout",
    transformIndexHtml: {
      order: "pre",
      handler(html, ctx) {
        const page = (ctx.path || "").replace(/^\//, "").split("?")[0] || "index.html";
        const clean = html
          .replace(/^\s*<meta property="og:[^>]*>\n/gm, "")
          .replace(/^\s*<link rel="icon"[^>]*>\n/gm, "");
        return clean
          .replace("</head>", headTags(clean, page) + "</head>")
          .replace("<!--site:header-->", header(page))
          .replace("<!--site:footer-->", footer);
      },
    },
    generateBundle() {
      const lines = ["User-agent: *", "Allow: /"];
      if (SITE_URL) lines.push(`Sitemap: ${SITE_URL}/sitemap.xml`);
      this.emitFile({ type: "asset", fileName: "robots.txt", source: lines.join("\n") + "\n" });
      if (SITE_URL) {
        const urls = readdirSync(root)
          .filter((f) => f.endsWith(".html") && f !== "404.html")
          .map((f) => `  <url><loc>${f === "index.html" ? SITE_URL + "/" : `${SITE_URL}/${f}`}</loc></url>`);
        this.emitFile({
          type: "asset",
          fileName: "sitemap.xml",
          source: `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.join("\n")}\n</urlset>\n`,
        });
      }
    },
  };
}

const pages = Object.fromEntries(
  readdirSync(root)
    .filter((f) => f.endsWith(".html"))
    .map((f) => [f.replace(/\.html$/, ""), fileURLToPath(new URL(f, import.meta.url))]),
);

export default defineConfig({
  root,
  base: "./",
  plugins: [sharedLayout()],
  build: {
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: { input: pages },
    assetsInlineLimit: 0, // never inline fonts or images as data: URIs (the policy only allows data: for images)
    modulePreload: false,
  },
});
