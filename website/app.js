import { locales } from './locales.js';
import { resolveLanguage, safeRelease, formatSize } from './lib.js';

const fallback = safeRelease({
  repository: 'B5-Software/opencode-android-full', tag: 'v0.1.0-beta.1', prerelease: true,
  releaseUrl: 'https://github.com/B5-Software/opencode-android-full/releases/tag/v0.1.0-beta.1', downloadCount: 0,
  assets: {
    apk: { url: 'https://github.com/B5-Software/opencode-android-full/releases/download/v0.1.0-beta.1/opencode-android-full-arm64.apk', size: 627750491, sha256: 'cdc35f96e865f55da5477934f752717be2c5ab2956ac47c8a901fbbd5a9ca9db' },
    core: { url: 'https://github.com/B5-Software/opencode-android-full/releases/download/v0.1.0-beta.1/opencode-android-full-arm64.ocaupdate', size: 127769339, sha256: '0fe936c502d695c647772b837f7708d7b021e29ef30eae3c069d81df5018a03d' },
    feed: { url: 'https://github.com/B5-Software/opencode-android-full/releases/download/v0.1.0-beta.1/opencode-android-full-arm64.ocafeed', size: 1108, sha256: 'c56789cc45639ebb67fabd737661a28d48fb9ce524ee27b7172cbe09379a55fb' },
  },
});

let release = fallback;
let countAvailable = false;
const query = new URLSearchParams(location.search);
const downloadKind = query.get('asset') === 'core' ? 'core' : 'apk';
let manualDownload = false;
document.querySelector('#direct-download')?.addEventListener('click', () => { manualDownload = true; });
let saved;
try { saved = localStorage.getItem('opencode-site-language'); } catch { /* Browser storage is optional. */ }
const requested = query.get('lang') ?? saved ?? 'auto';
let language = resolveLanguage(requested === 'auto' ? navigator.languages : [requested]);
const selector = document.querySelector('#language');
if (selector) {
  for (const [code, data] of Object.entries(locales)) {
    const option = document.createElement('option'); option.value = code; option.textContent = data.name; selector.append(option);
  }
  selector.value = requested === 'auto' ? 'auto' : language;
  selector.addEventListener('change', () => {
    const preferred = selector.value;
    try { localStorage.setItem('opencode-site-language', preferred); } catch { /* Private browsing can disable storage. */ }
    const url = new URL(location.href);
    if (preferred === 'auto') url.searchParams.delete('lang'); else url.searchParams.set('lang', preferred);
    history.replaceState(null, '', url);
    language = resolveLanguage(preferred === 'auto' ? navigator.languages : [preferred]); renderLanguage(); renderRelease();
  });
}

function renderLanguage() {
  const data = locales[language]; document.documentElement.lang = language; document.documentElement.dir = data.dir;
  const status = document.querySelector('#copy-status'); if (status) status.textContent = '';
  for (const node of document.querySelectorAll('[data-i18n]')) if (Object.hasOwn(data.text, node.dataset.i18n)) node.textContent = data.text[node.dataset.i18n];
  if (selector) { selector.options[0].textContent = data.text.system; selector.setAttribute('aria-label', data.text.language); }
  const title = document.querySelector('meta[name="description"]'); if (title) title.content = data.text.intro;
  document.title = `OpenCode Android Full — ${document.body.hasAttribute('data-download-page') ? data.text.downloadTitle : data.text.headline.replace('\n', ' ')}`;
  document.querySelector('.workbench')?.setAttribute('aria-label', data.text.illustration);
  document.querySelector('#feed-fallback')?.setAttribute('aria-label', data.text.copyFeed);
  if (document.body.hasAttribute('data-download-page')) {
    const link = document.querySelector('#direct-download'); link.dataset.asset = downloadKind; link.textContent = data.text[downloadKind];
  }
}

function renderRelease() {
  for (const node of document.querySelectorAll('[data-asset]')) {
    const asset = release.assets[node.dataset.asset]; if (!asset) continue;
    node.href = asset.url; node.title = `${release.tag} · ${formatSize(asset.size)}`;
  }
  for (const node of document.querySelectorAll('[data-release-tag]')) node.textContent = release.tag;
  for (const node of document.querySelectorAll('[data-download-size]')) node.textContent = formatSize(release.assets[node.dataset.downloadSize].size);
  for (const node of document.querySelectorAll('[data-release-link]')) node.href = release.releaseUrl;
  for (const node of document.querySelectorAll('[data-download-count]')) node.textContent = countAvailable ? new Intl.NumberFormat(language).format(release.downloadCount) : '—';
  const fallbackInput = document.querySelector('#feed-fallback'); if (fallbackInput) fallbackInput.value = release.assets.feed.url;
  const guide = document.querySelector('[data-docs]'); if (guide) guide.href = language.startsWith('zh') ? 'https://github.com/B5-Software/opencode-android-full/blob/main/README.zh-CN.md' : 'https://github.com/B5-Software/opencode-android-full#requirements-and-installation';
}

document.querySelector('#copy-feed')?.addEventListener('click', async () => {
  const status = document.querySelector('#copy-status'); const input = document.querySelector('#feed-fallback');
  try { await navigator.clipboard.writeText(release.assets.feed.url); status.textContent = locales[language].text.copied; input.hidden = true; }
  catch { input.hidden = false; input.focus(); input.select(); status.textContent = locales[language].text.copyFailed; }
});

renderLanguage(); renderRelease();
const controller = new AbortController();
const deadline = setTimeout(() => controller.abort(), 6000);
try {
  const response = await fetch('./release.json', { credentials: 'omit', cache: 'no-cache', signal: controller.signal });
  if (!response.ok) throw new Error('Release metadata unavailable');
  const body = await response.text(); if (body.length > 65536) throw new Error('Release metadata exceeds bound');
  release = safeRelease(JSON.parse(body)); countAvailable = true; renderRelease();
} catch { /* The last verified release remains downloadable without the metadata service. */ }
finally { clearTimeout(deadline); }

if (document.body.hasAttribute('data-download-page') && !manualDownload) location.replace(release.assets[downloadKind].url);
