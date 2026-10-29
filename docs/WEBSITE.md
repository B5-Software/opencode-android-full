# Download website / 下载官网

GitHub Pages: <https://b5-software.github.io/opencode-android-full/>.

The buildless static site is in `website/`. It follows system language and color scheme, supports 16 languages including Arabic RTL, and remembers a visitor's language choice locally. It uses the existing generated app icon, system fonts and no analytics or third-party scripts.

官网为无构建依赖的静态网站，跟随系统语言与深浅色，支持 16 种语言和阿拉伯语 RTL。手动选择语言后仅在浏览器本地保存偏好。图标复用项目现有生成资产，无统计跟踪脚本。

## Preview and checks

```sh
python -m http.server 8000 --bind 127.0.0.1 --directory website
python -m unittest discover -s tools -p test_site_release.py
node --test tools/test_website.mjs
```

Open `http://127.0.0.1:8000/`. JavaScript uses the `.js` extension so Windows preview servers and Pages serve an executable MIME type. Node tests use the local `website/package.json` module declaration; no npm install is needed.

## Release selection and deployment

`.github/workflows/pages.yml` tests and deploys the site on website changes, release publication or edits, successful tag builds, a six-hour refresh, and manual dispatch. Pages must use **GitHub Actions** as its source. The workflow uses the repository's built-in token; it requires no additional secret.

`tools/site_release.py` asks GitHub for published releases and selects the newest complete APK/`.ocaupdate`/`.ocafeed` set, **including prereleases**. Drafts and incomplete uploads cannot replace the current download. Asset names, repository, HTTPS URLs, size and SHA-256 are checked. The download count includes all release assets and is refreshed at deployment time. If metadata cannot load, the website retains the last verified beta download links; requests time out after six seconds.

官网按发布时间选择最新且 APK、升级包、签名更新源均已上传的版本，包括 beta。草稿和未传完的版本不会替换当前下载；下载量统计为全部 Release 附件的累计值，在部署时刷新。README 的稳定入口 `download.html?asset=apk` 与 `download.html?asset=core` 会跳转到对应文件；当前 Release 的按钮始终指向该版本自身的附件。

To refresh a local public snapshot, authenticate `gh` normally and run:

```sh
python tools/site_release.py --output website/release.json
```

To add a language, extend `website/locales.js` with all English keys. The test checks translation coverage, language matching and download origin validation. Check the rendered page on mobile and desktop, including RTL, before publishing.
