import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { locales, en } from '../website/locales.js';
import { resolveLanguage, safeRelease } from '../website/lib.js';

test('system language matching, Chinese variants, fallback and RTL', () => {
  assert.equal(resolveLanguage(['zh-TW']), 'zh-Hant');
  assert.equal(resolveLanguage(['zh-HK']), 'zh-Hant');
  assert.equal(resolveLanguage(['zh-CN']), 'zh-Hans');
  assert.equal(resolveLanguage(['pt-PT']), 'pt-BR');
  assert.equal(resolveLanguage(['unknown', 'ja-JP']), 'ja');
  assert.equal(resolveLanguage(['ar-EG']), 'ar');
  assert.equal(locales.ar.dir, 'rtl');
  assert.equal(resolveLanguage(['unknown']), 'en');
});
test('all 16 languages cover the visible interface and retained fallback downloads', async () => {
  assert.equal(Object.keys(locales).length, 16);
  for (const locale of Object.values(locales)) {
    assert.deepEqual(Object.keys(locale.text), Object.keys(en));
    for (const value of Object.values(locale.text)) assert.ok(typeof value === 'string' && value.trim());
  }
  for (const filename of ['index.html','download.html']) {
    const html = await readFile(new URL(`../website/${filename}`, import.meta.url), 'utf8');
    for (const match of html.matchAll(/data-i18n="([^"]+)"/g)) assert.ok(Object.hasOwn(en, match[1]), match[1]);
    assert.ok(html.includes('opencode-android-full-arm64.apk'));
    assert.ok(html.includes('opencode-android-full-arm64.ocaupdate'));
  }
});
test('published snapshot matches exact release assets and refuses link injection', async () => {
  const data = JSON.parse(await readFile(new URL('../website/release.json', import.meta.url), 'utf8'));
  assert.equal(safeRelease(data).repository, 'B5-Software/opencode-android-full');
  const bad = structuredClone(data);bad.assets.apk.url='https://example.invalid/evil.apk';
  assert.throws(() => safeRelease(bad));
  const wrongVersion=structuredClone(data);wrongVersion.tag='v999.0.0';
  assert.throws(() => safeRelease(wrongVersion));
});
