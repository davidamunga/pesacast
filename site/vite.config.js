// The PesaCast site is a plain multi-page Vite app: every .html file in this folder is a page,
// Vite bundles the stylesheet, fonts and logo with hashed names, and the output contains no
// JavaScript at all (the Content-Security-Policy in public/_headers forbids it).
//
// The only custom piece is a tiny plugin that fills in the shared header and footer, so they live
// in one place instead of being copied into every page.
import { readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vite";

const GH = "https://github.com/davidamunga/pesacast";

const NAV = [
  ["start.html", "Set up"],
  ["phones.html", "Phones"],
  ["faq.html", "Questions"],
  ["developers.html", "Developers"],
  ["security.html", "Security"],
  [GH, "GitHub"],
];

const header = (current) => `<header class="site-header">
  <div class="wrap">
    <a class="brand" href="index.html"><img src="assets/logo.png" alt="" width="32" height="32">PesaCast</a>
    <nav class="site-nav" aria-label="Main">${NAV.map(
      ([href, label]) => `<a href="${href}"${href === current ? ' aria-current="page"' : ""}>${label}</a>`,
    ).join("")}</nav>
  </div>
</header>`;

const footer = `<footer class="site-footer">
  <div class="wrap">
    <div>
      <p>PesaCast is free software under the MIT licence. It is not made by, affiliated with or endorsed by Safaricom. M-PESA is a trademark of Safaricom PLC.</p>
      <p>This site has no cookies, no analytics, no scripts, and loads nothing from other websites.</p>
    </div>
    <ul>
      <li><a href="${GH}">Source code</a></li>
      <li><a href="${GH}/releases">Downloads</a></li>
      <li><a href="${GH}/issues/new/choose">Report a problem</a></li>
      <li><a href="security.html">Security</a></li>
    </ul>
  </div>
</footer>`;

function sharedLayout() {
  return {
    name: "pesacast-shared-layout",
    transformIndexHtml: {
      order: "pre",
      handler(html, ctx) {
        const page = (ctx.path || "").replace(/^\//, "").split("?")[0] || "index.html";
        return html.replace("<!--site:header-->", header(page)).replace("<!--site:footer-->", footer);
      },
    },
  };
}

const root = fileURLToPath(new URL(".", import.meta.url));
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
