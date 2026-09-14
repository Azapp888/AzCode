# AzCode Android 设计系统

设计方向：向 DeepSeek 官方 App 的视觉语言收拢——灰色画布 + 白色卡片、克制的品牌蓝、大圆角、细描边、宽松留白、线性细图标。

令牌全部取自 DeepSeek 官方设计系统真实值（`--dsw-static-*` / `--dsw-alias-*`），并非估算。

## 颜色令牌

| 语义 | 亮色 | 暗色 | 用途 |
| --- | --- | --- | --- |
| `primary` | `#3964FE` | `#5686FE` | 品牌主色、发送按钮、选中态 |
| `primary_pressed` | `#5686FE` | `#679EFE` | 主色按压态 |
| `primary_soft` | `#E5F0FF` | `#1E2A46` | 主色浅底（容器） |
| `background` | `#F5F6F7` | `#151517` | 页面画布 |
| `surface` | `#FFFFFF` | `#232324` | 卡片 / 面板 |
| `input_bg` | `#FFFFFF` | `#2C2C2E` | 输入区底色 |
| `text_primary` | `#0F1115` | `#F9FAFB` | 正文 |
| `text_secondary` | `#61666B` | `#CFD3D6` | 次要文字 |
| `text_caption` | `#ADB2B8` | `#81858C` | 弱化文字 / 占位 |
| `divider` | `rgba(0,0,0,.10)` | `rgba(255,255,255,.12)` | 分隔线 / 描边 |
| `border_subtle` | `rgba(0,0,0,.04)` | `rgba(255,255,255,.06)` | 极浅描边 |
| `bubble_assistant` | `#FFFFFF` | `#2C2C2E` | 助手消息卡 |
| `bubble_user` | `#3964FE` | `#5686FE` | 用户消息 |
| `success` | `#22C55E` | `#4ED17E` | 成功 |
| `error` | `#EC1313` | `#F25A5A` | 错误 |
| `status_off` | `#C4C7CE` | `#5A5D63` | 关闭态指示点 |

## 圆角

- 卡片 / 输入容器：24dp
- 面板：16dp
- 胶囊 / 标签：16dp（高约 32dp，即胶囊）
- 输入框 / 卡片字段：10–12dp
- 按钮：10dp
- 消息气泡：16dp（贴近发言方的一角 4dp）
- 圆形按钮：oval

## 字体

采用系统无衬线字体，层级：11 / 12 / 13 / 14 / 16 / 20sp。标题加粗，正文常规，辅助文字弱化色。

## 组件规则

- 页面根背景 = `background`，卡片 = `surface` 且 1dp 描边。
- 可点击的胶囊、按钮、圆形按钮均带 `ripple` 按压反馈。
- 输入区为独立白色卡片（24dp 圆角、极浅描边、2dp 阴影）。
- 消息：用户右侧品牌色气泡（白字），助手左侧白卡气泡（深字）。
- 图标为线性细描边风格，尺寸 20–24dp。

## 主题与动态取色

- `Theme.AzCode` 基于 `Theme.Material3.DayNight.NoActionBar`，通过 `values-night` 自动切换深浅色。
- `viewInflaterClass` 固定为 AppCompat inflater，保留各界面自定义 `android:background`。
- Android 12+ 启用 Material You 动态取色（`DynamicColors`），仅协调系统与 Material 控件；品牌蓝以静态引用保持稳定。

## 参考来源与引用文件

本设计系统不是凭记忆估算，而是从下列真实来源提取。为可追溯，逐项列出所引用的文件。

### 一、DeepSeek 官方（色值、圆角、字号令牌的真实来源）

| 引用的文件 | 用途 |
| --- | --- |
| `https://fe-static.deepseek.com/chat/static/main.cffac0f0da.css` | 主来源：提取 `--dsw-static-*` 基础色板与 `--dsw-alias-*` 语义令牌的明/暗两套取值 |
| `https://www.deepseek.com/_next/static/css/8200fbc59b0ac2c5.css` | 官网样式，用于交叉核对品牌蓝 |
| `https://www.deepseek.com/_next/static/css/826310b8bcf73c16.css` | 官网样式，用于交叉核对品牌蓝 |
| `https://www.deepseek.com/_next/static/css/aa0e993381ed79dc.css` | 官网样式，用于交叉核对品牌蓝 |

### 二、impeccable 技能（设计流程与工艺底线的来源）

| 引用的文件 | 用途 |
| --- | --- |
| `SKILL.md`（v4.3.1） | 工作流总纲、命令表、模式定义 |
| `reference/routing.md` | 请求路由与命令选择 |
| `reference/new-work.md` | 新界面/替换视觉世界的流程（先取参考、再定方向） |
| `reference/craft-floor.md` | 工艺底线核验项与「明确拒绝」清单 |
| `reference/android.md` | Android 平台规则（Material 3、48dp 触控、边到边 inset、深色与字体缩放验证） |
| `scripts/impeccable` | 运行 `impeccable context` 探测项目上下文 |

技能主页：`https://github.com/pbakaus/impeccable`（Apache-2.0）。

### 三、随应用内置的版本

`SkillStore.IMPECCABLE_CONTENT` 是上述技能的 Android 原生精简移植版，随 APK 内置、可在「设置 → 技能」中启停。上游完整技能不含在本仓库内。

