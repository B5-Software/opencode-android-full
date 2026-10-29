import { locales } from './locales.js';

export const repository = 'B5-Software/opencode-android-full';
export const filenames = { apk: 'opencode-android-full-arm64.apk', core: 'opencode-android-full-arm64.ocaupdate', feed: 'opencode-android-full-arm64.ocafeed' };
export const releasePattern = /^v\d+\.\d+\.\d+(?:-[A-Za-z0-9]+(?:[.-][A-Za-z0-9]+)*)?$/;

export function resolveLanguage(preferences) {
  for (const preference of preferences) {
    if (typeof preference !== 'string') continue;
    const tag = preference.replaceAll('_', '-').toLowerCase();
    const exact = Object.keys(locales).find(key => key.toLowerCase() === tag);
    if (exact) return exact;
    if (tag.startsWith('zh')) return /hant|-(tw|hk|mo)(?:-|$)/.test(tag) ? 'zh-Hant' : 'zh-Hans';
    if (tag.startsWith('pt')) return 'pt-BR';
    const base = tag.split('-')[0];
    if (Object.hasOwn(locales, base)) return base;
  }
  return 'en';
}

export function safeRelease(value) {
  if (!value || value.repository !== repository || typeof value.tag !== 'string' || value.tag.length > 100 || !releasePattern.test(value.tag)) throw new Error('Invalid release metadata');
  const base = `https://github.com/${repository}/releases/download/${value.tag}/`;
  for (const [kind, name] of Object.entries(filenames)) {
    const asset = value.assets?.[kind];
    if (!asset || asset.url !== base + name || !Number.isSafeInteger(asset.size) || asset.size <= 0 || asset.size > 2 * 1024 ** 3 || !/^[a-f0-9]{64}$/.test(asset.sha256)) throw new Error('Invalid download asset');
  }
  if (!Number.isSafeInteger(value.downloadCount) || value.downloadCount < 0 || value.releaseUrl !== `https://github.com/${repository}/releases/tag/${value.tag}`) throw new Error('Invalid release summary');
  return value;
}

export function formatSize(bytes) {
  return bytes >= 1024 ** 2 ? `${(bytes / 1024 ** 2).toFixed(1)} MiB` : `${Math.ceil(bytes / 1024)} KiB`;
}
