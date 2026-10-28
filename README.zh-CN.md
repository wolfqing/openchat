# openchat

> 面向断网、灾害和人群密集场景的离线优先聊天应用。
> 没网时走蓝牙 LE mesh，有网时走 Nostr。
> **状态：调研与规划阶段，尚无可用 App。**

## 这是什么

openchat 计划做成 [bitchat](https://github.com/permissionlesstech/bitchat) 线协议的一个开源客户端：蓝牙 LE 多跳 mesh 负责离线消息，Nostr 中继负责有网时的回退，私信用 Noise Protocol 加密，不需要账号、手机号和服务器。

bitchat 的协议、代码和白皮书都是公有领域。openchat 不另起协议，只在同一张网上换一个前端：

- **给没见过 IRC 的人用。** bitchat 的自我定位是「IRC vibes」，而断网时涌进来的几十万人不是 IRC 用户。手机离线状态下，从安装到发出第一条消息三步完成，是及格线。
- **定位于基础设施故障，不是政治。** 地震、台风、运营商宕机、球场和音乐节基站过载、徒步和航海。为这些场景预置频道和文案。
- **永远协议兼容。** 一台 openchat 手机必须是所有 bitchat 手机的可用中继，反之亦然。与上游互通是发版门槛，不是功能。
- **对安全诚实。** 上游协议没有完成过第三方审计，并自己写明了元数据层面的弱点。openchat 会把同样的话写进 App 里，而不只是 README。

## 这不是什么

- 不是新协议，不是新密码学。线上的一切都是上游的。
- 不是翻墙或抗审查产品，也不这样宣传。
- 不是安全产品。身份暴露会带来严重后果的场景，不要依赖它。

## 仓库结构

```
docs/
  bitchat-protocol-notes.md   # 上游协议的实际行为与关键数字
  roadmap.md                  # 阶段、验收线与 kill 条件
internal/                     # 私人规划笔记，已 git-ignore
```

## 路线图一句话

Phase 0 不写代码，只验证前提。Phase 1 fork iOS 客户端，协议层原封不动，重做 onboarding 和主界面。Phase 2 决定 Android 怎么做。详见 [docs/roadmap.md](docs/roadmap.md)。

## 命名

「openchat」是工作名，GitHub 上已有多个同名的 LLM 项目。正式名是 Phase 0 的交付物之一。

## 许可证

本仓库自写内容采用 MIT。来自上游的代码沿用上游条款：bitchat iOS 为公有领域（Unlicense）；bitchat-android 为 GPL-3.0（其 LICENSE.md 是 GPL-3.0 全文，README 的公有领域说法与之不符），目前没有任何代码被引入。

## 致谢

建立在 bitchat 贡献者的工作之上，与其无隶属或背书关系。
