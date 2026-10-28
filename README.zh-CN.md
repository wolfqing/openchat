# openchat

> 面向断网、灾害和人群密集场景的离线优先聊天应用。
> 没网时走蓝牙 LE mesh，有网时走 Nostr。
> **状态：Android v0.1.0 预览版。能构建、单元测试全部通过，尚未实地测试。**

## 这是什么

openchat 是 [bitchat](https://github.com/permissionlesstech/bitchat) 线协议的一个开源客户端：蓝牙 LE 多跳 mesh 负责离线消息，Nostr 中继负责有网时的回退，私信用 Noise Protocol 加密，不需要账号、手机号和服务器。

bitchat 的协议、代码和白皮书都是公有领域。openchat 不另起协议，只在同一张网上换一个前端：

- **给没见过 IRC 的人用。** bitchat 的自我定位是「IRC vibes」，而断网时涌进来的几十万人不是 IRC 用户。手机离线状态下，从安装到发出第一条消息三步完成，是及格线。
- **定位于基础设施故障，不是政治。** 地震、台风、运营商宕机、球场和音乐节基站过载、徒步和航海。为这些场景预置频道和文案。
- **永远协议兼容。** 一台 openchat 手机必须是所有 bitchat 手机的可用中继，反之亦然。与上游互通是发版门槛，不是功能。
- **对安全诚实。** 上游协议只做过一次第三方审计（Cure53，2025-11），部分问题至今未关闭，并自己写明了元数据层面的弱点。openchat 会把同样的话写进 App 里，而不只是 README。

## 这不是什么

- 不是新协议，不是新密码学。线上的一切都是上游的。
- 不是翻墙或抗审查产品，也不这样宣传。
- 不是安全产品。身份暴露会带来严重后果的场景，不要依赖它。

## 仓库结构

```
android/                      # Kotlin 应用，全部自写（见 android/README.md）
docs/
  protocol-spec.md            # openchat 实现的字节级线协议规范
  bitchat-protocol-notes.md   # 上游协议的实际行为与关键数字
  roadmap.md                  # 阶段、验收线与 kill 条件
  phase0-findings.md          # Phase 0 评论与 issue 分析、go/no-go
internal/                     # 私人规划笔记，已 git-ignore
```

## 路线图一句话

Phase 0 的结论是 UX 前提偏弱，项目按所有者的决定继续。先做 Android，按 [docs/protocol-spec.md](docs/protocol-spec.md) 从零自写，不用上游代码，之后再做 iOS。详见 [docs/roadmap.md](docs/roadmap.md)。

## 命名

「openchat」是工作名，GitHub 上已有多个同名的 LLM 项目。正式名是 Phase 0 的交付物之一。

## 许可证

本仓库全部内容采用 MIT，不含任何上游代码。协议规范是阅读公有领域（Unlicense）的 bitchat iOS 源码后写成的，没有读过 GPL-3.0 的 bitchat-android 源码。`android/app/src/main/assets/relays.csv` 是上游以公有领域发布的中继目录数据，保留它是为了让位置频道和 bitchat 用同一批中继。

## 致谢

建立在 bitchat 贡献者的工作之上，与其无隶属或背书关系。
