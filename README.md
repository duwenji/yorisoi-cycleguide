# CycleGuide

自転車で走る人のそばで、周りの見どころや安全の情報を声で伝える Android アプリです。設定でオンにすると、その場所を通った人の一言や店・地域のお知らせ（スタジオ）も読み上げます。付き添いアプリのエコシステム **yorisoi** の 2 本目のアプリで、共通の部品は [yorisoi-platform](https://github.com/duwenji/yorisoi-platform) を使います。

## 文書

[文書の案内](docs/README.md)

- [要件定義](docs/requirements/cycleguide.md)
- [設計](docs/design/architecture.md)
- [公開と更新](docs/operations/release.md)・[Play Console の申告](docs/operations/play-console.md)
- [プライバシーポリシー](docs/policies/privacy-policy.md)
- [TODO](docs/TODO.md)

## 構成

| モジュール | 中身 |
|---|---|
| `:app` | 画面と常駐の処理 |
| `:prompts`（JVM） | プロンプト・パック。組み込みのチャンネル（`prompts/src/main/resources/cycleguide/channels/`）と審査の見本（`ReviewSamples`）。GitHub Packages に公開する（`io.github.duwenji.yorisoi.cycleguide:prompts`、[docs/operations/release.md](docs/operations/release.md)） |

## ビルド

platform は GitHub Packages から読みます。読むには `read:packages` のトークンが要ります（`~/.gradle/gradle.properties` の `gpr.user`・`gpr.key`、または環境変数 `GITHUB_ACTOR`・`GITHUB_TOKEN`）。

```
./gradlew test lint assembleDebug
```

platform とアプリを同時に直すときは、platform のソースに差し替えます（composite build）。

```
./gradlew assembleDebug -Pyorisoi.platformDir=../yorisoi-platform
```
