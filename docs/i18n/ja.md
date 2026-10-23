# AzCode (Android)

[简体中文](zh-CN.md) | [English](en.md) | [한국어](ko.md) | [Français](fr.md) | [Deutsch](de.md) | [Русский](ru.md) | [Español](es.md)

AzCode は Android 端末上で動作するローカル自動化アシスタントです。自然言語でタスクを記述すると、内蔵の Agent ループが画面を読み取り、コントロールをタップし、スワイプし、コマンドを実行してタスクを完了します。データはすべて端末内に保存され、クラウド同期は行いません。

- アプリ名：**AzCode**
- GUI 形態ラベル：**magic**
- CLI 形態ラベル：**Listen**
- 現在のバージョン：**1.9.15**（versionCode 70）
- 動作要件：Android 8.0 以上（minSdk 26）、targetSdk 34

単体でも動作しますが、`127.0.0.1:8848` のみにバインドした機能ブリッジを有効にすると、`adb forward` 経由で Listen CLI や他のクライアントが同じデバイス機能を再利用できます。

## 機能一覧

| 機能 | 説明 |
| --- | --- |
| 自然言語タスク | チャット入力、Agent の多段階計画と実行、ツール呼び出しのリアルタイム表示 |
| 画面操作 | ノード読み取り、テキストや座標によるタップ、スワイプ、システムナビゲーション、Unicode テキスト入力 |
| アプリ起動 | パッケージ名またはアプリ名で起動。shell とユーザー補助によるフォールバック |
| コマンド実行 | Termux、内蔵 shell、Shizuku、Root の 4 チャネルを権限モードで自動選択 |
| 複数モデル | 複数のプロバイダーアカウント、OpenAI 互換・Anthropic・Gemini に対応 |
| 思考深度 | オフ・高速・標準・深いの 4 段階。モデルごとにパラメータを調整 |
| セッション | 独立したセッション、履歴ドロワー、自動タイトル、コンテキスト継続と圧縮 |
| 添付ファイル | 画像、音声、PDF、Office、テキスト、コード |
| 音声入力 | システムのオフライン認識と訊飛（Xunfei）オンライン認識を切り替え |
| フローティングアシスタント | 既定のデジタルアシスタント入口、透明グロー、ページ内質問、キーボード入力 |
| 長期記憶 | 機密トークン、利用習慣、スキル手法、端末ローカルの個別化 |
| スキルとプラグイン | 内蔵スキル、カスタムスキル、GitHub SKILL.md のインストール、人気プラグインのスキャン |
| GitHub | ファイルの読み書き、ブランチとコミットの閲覧、Issue と Pull Request の管理 |
| 画像生成 | 複数プロバイダーのテキスト画像生成。チャット内で表示し保存可能 |
| Office 文書 | GenOffice MCP で docx / xlsx / pptx を生成しプレビュー |
| 入力メソッド | 内蔵 AzCode IME を Unicode テキスト入力のフォールバックとして使用 |
| 機能ブリッジ | ループバック HTTP API と Listen CLI |
| ログ | クラッシュと実行ログを保存し、設定画面から閲覧・コピー・削除 |

## クイックスタート

### インストール

GitHub Releases の `android-v*` から APK をダウンロードするか、GitHub Actions の `azcode-magic-apk` 成果物を利用します。debug と release は同じ署名キーを共有するため、上書きインストールできます。

### 初期設定

1. 「設定 > デバイス機能 > ユーザー補助」を開き、システム設定で **AzCode Screen Control** を有効にします。
2. 「設定 > モデル管理」でプロバイダーを追加して有効にし、API Key を入力します。Base URL とモデルはプリセットを利用できます。
3. メイン画面に戻り、モデル選択で現在のモデルを確認します。
4. タスクを入力して送信します。例：「設定を開いて Android のバージョンを確認」。

### 権限

| 権限 | 用途 |
| --- | --- |
| ユーザー補助サービス | 画面読み取り、タップ、スワイプ、システムナビゲーション、テキスト入力 |
| マイク | 音声入力とフローティングアシスタント |
| 通知 | タスク完了と質問の通知 |
| 他のアプリの上に重ねて表示 | フローティングアシスタントのグロー |
| カメラ | 写真添付 |
| Termux RUN_COMMAND | Termux 経由で完全な Linux コマンドを実行 |
| Shizuku | adb 権限でシステムレベルのコマンドを実行 |

### ソースからのビルド

JDK 17 と Android SDK が必要です。リポジトリには `gradle-wrapper.jar` が含まれないため、システムの `gradle` を使用します：

```bash
gradle assembleDebug
gradle assembleRelease
```

コードをプッシュすると、`.github/workflows/android.yml` が GitHub Actions で Debug と Release の APK をビルドします。`android-v*` タグをプッシュすると GitHub Release が作成されます。

## デバイス機能とコマンド実行

| モード | チャネル | 権限 | 前提条件 | タイムアウト |
| --- | --- | --- | --- | --- |
| NORMAL + Termux | Termux `RUN_COMMAND` | Termux uid | Termux を導入し RUN_COMMAND を許可、`allow-external-apps=true` | 90 秒 |
| NORMAL + 内蔵 shell | `/system/bin/sh -c` | アプリ uid | なし | 60 秒 |
| SHIZUKU | Shizuku binder | adb uid 2000 | Shizuku を起動して許可 | 60 秒 |
| ROOT | `su -c` | uid 0 | 端末が Root 済み | 60 秒 |

NORMAL モードはまず Termux を試し、利用できない場合は内蔵 shell にフォールバックします。他のアプリのプライベートディレクトリへのアクセスやシステム設定の変更には Shizuku または Root が必要です。

## ローカルデータと保存先

AzCode は SQLite、Room、サーバー側データベースを使用しません。データ層はアプリ専用の `SharedPreferences` と JSON ファイルで構成されます：

| パス | 内容 |
| --- | --- |
| `SharedPreferences` | モデルプロバイダー、セッションカーソル、スキル、コマンド記憶、GitHub / GenOffice 設定、音声と権限モード |
| `files/sessions/<id>.json` | 1 セッションのチャット履歴と Agent コンテキスト |
| `files/memory.json` | 長期記憶のエントリ |
| `files/logs/YYYY-MM-DD.log` | 日次ローテーションログ。最大 10 ファイル、1 ファイル 512 KB |
| `files/genoffice/` | 生成された Office 文書 |
| `cache/az_images/`、`cache/captures/` | 画像キャッシュと撮影時の一時ファイル |

セッションは一時ファイルと rename で書き込みます。Agent コンテキストは最大 40 メッセージを保持し、それ以前は要約に圧縮されます。アプリをアンインストールするとすべてのローカルデータが削除されます。

## 機能ブリッジと Listen CLI

ブリッジは `127.0.0.1:8848` のみをリッスンし、外部からは到達できず、認証もありません。リモートアクセスには `adb forward` が必要です。

| メソッド | パス | 説明 |
| --- | --- | --- |
| GET | `/health` | ユーザー補助、Shizuku、Root、権限モードの状態 |
| GET | `/screen` | 現在の画面の可視ノード（最大 200 件） |
| POST | `/tap` | `{"x":123,"y":456}` または `{"text":"OK"}` |
| POST | `/swipe` | `{"x1":..,"y1":..,"x2":..,"y2":..,"duration":300}` |
| POST | `/global` | `{"action":"back"}`。back / home / recents / notifications に対応 |
| POST | `/shell` | `{"cmd":"pm list packages"}`。現在の権限モードで実行 |
| POST | `/notify` | `{"title":"..","body":".."}` |
| POST | `/agent` | `{"task":"..","session":"任意の id"}`。`answer` と `events` を返す |

adb で接続した状態で：

```bash
curl -fsSL https://raw.githubusercontent.com/Azapp888/AzCode/main/tools/install.sh | bash
listen run "設定を開いて Android のバージョンを確認"
listen screen
listen tap --text "OK"
```

## プライバシーとセキュリティ

- モデルの API Key、GitHub トークン、GenOffice トークン、訊飛の資格情報はアプリ専用ストレージのみに保存され、アプリは環境変数を読み取りません。
- 端末ローカルの個別化スナップショットはオンデバイスで生成され、クラウドの会話に注入されません。
- ブリッジはループバックのみにバインドし、リモートアクセスは adb 転送に依存します。
- 訊飛のオンライン認識は音声を訊飛オープンプラットフォームへ送信します。内蔵のシステム認識はオンデバイスのオフライン処理を優先します。
- Shizuku と Root はシステムレベルのコマンドを実行でき、誤操作は端末やデータを損傷する恐れがあります。所有または明示的に許可された端末でのみ使用してください。

## 既知の制限

- 画面読み取りと UI 操作はユーザー補助サービスに依存し、一部メーカーのシステムはバックグラウンドでの動作を制限します。
- システムレベルの shell は Shizuku または Root に依存し、NORMAL モードはアプリサンドボックスの制限を受けます。
- 音声と画像の入力は選択したモデルのマルチモーダル能力に依存します。
- 画像生成はプロバイダーの API に依存し、対応するサイズと枚数は異なります。
- クラウド同期はなく、データはアプリのアンインストールで削除されます。

## リンク

- 完全なドキュメント：ルートの `README.md`
- 製品概要：`PRODUCT.md`
- デザインシステム：`DESIGN.md`
- CLI ツール：`tools/listen` と `tools/install.sh`

## ライセンス

MIT。ユーザー補助と Shizuku 実行モジュールは [Soodok/Deepseek-Harness-Local-Android](https://github.com/Soodok/Deepseek-Harness-Local-Android)（MIT）を参考に改変しています。Shizuku API は [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) を利用しています。
