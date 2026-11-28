# Black Marlin Downloader website cutover

Use [Xolver](https://xolver.in/products/bmd/) as the website in project and store listings. It is the public product, download and first-line help entry point. GitHub continues to host versioned releases and SHA-256 files, issue reporting and the full version-controlled manual.

## Deployment order

1. Release the Xolver page first. In a browser, verify the page and the `#downloads`, `#help`, `#install`, `#browser-integration` and `#troubleshooting` targets. Verify its installer selector still links to the expected GitHub releases.
2. Land the BMD source links and legacy-site redirect build. The desktop app, its installer and browser extensions receive the updated URLs the next time those artifacts are released normally; already-installed binaries cannot be updated through this change alone.
3. Check GitHub repository About → Website and each owned extension/store listing. Set the general website to `https://xolver.in/products/bmd/` and general support to `https://xolver.in/products/bmd/#help`. Do not change a listing’s package URL, update URL, or dedicated privacy policy. Only the listing owner can apply these changes. No Android listing or release is part of this cutover.
4. If the legacy GitHub Pages host is actually available, deploy the redirect artifact and verify old entry points from that host. The redirect files are zero-delay HTML redirects with a canonical URL and a manual fallback, not HTTP 301 responses. There is no usable custom domain at `bmd.example.com`: it is a reserved placeholder. A source change cannot make that hostname redirect, and existing builds pointing there need replacement. If GitHub Pages has no enabled origin, do not claim legacy routes redirect publicly.

The legacy build redirects marketing pages, the download chooser, the FAQ and general help entry points. Specific guide chapters not present on Xolver stay available; maintained repository docs remain linked where a deep link matters. The legacy build keeps legal/policy pages, news, benchmark evidence, and the automation schema in place. For a legacy content preview, build with `BMD_SITE_KEEP_LEGACY=1`; this flag does not change Xolver or the application's links.
