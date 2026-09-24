# AzCode 签名密钥（公开，仅自用）

本目录包含 AzCode Android 客户端的**统一签名密钥**，用于让 debug 与 release 产物使用同一把
密钥签名，保证每次构建出来的 APK 签名一致、可互相覆盖安装。

## 文件

| 文件 | 说明 |
| --- | --- |
| `azcode-release.keystore` | PKCS12 密钥库，别名 `azcode`，RSA 4096，有效期至 2056-09-16 |
| `keystore.properties` | Gradle 读取的签名参数（路径、密码、别名） |

## 证书指纹

```
SHA-256: E2:58:E1:3B:D8:47:F5:59:36:67:BC:D5:AF:1A:81:5D:D4:BB:4A:1D:53:9A:C3:43:DA:D8:3E:88:37:66:E3:43
```

## 重要安全说明

**这把密钥是公开的。** 项目所有者已明确知悉并接受：任何拿到本仓库的人都可以用它签名出与
官方包同签名、可覆盖安装的 APK。因此：

- 适合：个人自用、内部外发、GitHub Releases 分发等不需要签名信任边界的场景。
- 不适合：上架 Google Play / 任何应用商店，或依赖"签名 = 官方来源"做自动更新校验的场景。
  如需上架，请另行生成一把私密密钥，并通过 CI Secrets 注入，不要提交进仓库。

## 重新生成密钥（如需更换）

```bash
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/azcode-release.keystore \
  -storetype PKCS12 \
  -alias azcode \
  -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass 'AzCode#Sign2026' -keypass 'AzCode#Sign2026' \
  -dname "CN=AzCode, OU=Android, O=AzCode, L=Beijing, ST=Beijing, C=CN"
```

更换密钥后，旧安装包无法直接升级，需要先卸载再安装新包。

## 校验产物签名

```bash
# 需要 Android SDK 的 apksigner
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

或对比两份 APK 的证书 SHA-256 是否一致即可。
