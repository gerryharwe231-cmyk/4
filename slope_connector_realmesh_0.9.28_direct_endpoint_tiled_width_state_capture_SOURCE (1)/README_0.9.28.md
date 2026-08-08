# 0.9.28 Direct Endpoint / Tiled Width / Exact State Capture

这版只处理 0.9.27 用户反馈的四类问题，原 0.9.23 弧方块连接杖主体、G 面板、两点/三点、内弧方向、正反弧向、自动裁切、视角定向等其他功能保持不变。

## 1. 端点直接连接圆弧

当起点/终点没有先经过直线段，而是直接进入圆弧时，端点处不再使用第一条弧弦自身已经转过角度的截面。

现在首尾 Station：

- 中心固定在 ModelBlock 的真实连接面中心；
- 起点切线固定为“端点 -> 弧线”；
- 终点切线固定为“弧线 -> 端点外侧”；
- 端点和第一/最后弧段共用同一个截面节点。

因此外弧不会因为两个截面向外张开留下缝，内弧也不会因为两个截面互相压进去产生 Z-fighting。

## 2. 尺寸名称按实际视觉含义调整

内部兼容字段没有改名，避免旧设置/NBT失效；面板和反馈文字调整为：

- 原“左右宽度” -> **上下厚度**；
- 原“上下厚度” -> **侧面宽度**。

旧命令名 `lrwidth` / `udwidth` 为兼容仍保留，但反馈文字使用新名称。

## 3. 宽于 1 格时不再拉伸模型/纹理

目标侧面宽度如果是 3 格，不再把一个源模型横向缩放到 3 格，而是：

```text
[1格模型] [1格模型] [1格模型]
```

每个 tile 保留自身 BakedQuad UV。宽度不是整数误差时，只让每个 tile 均匀承担极小差值，不把一张纹理拉成整条宽度。

首端/尾端 ModelBlock 使用同一套 `ArcModelFrameLayout`，同步复制相同数量的横向/纵向 tile，所以端点不会只保留 1 格宽而中段已经扩成 3 格。

## 4. 楼梯 / 半砖完整 BlockState

模型渲染杖原本已经通过 MaterialStateCodec 保存完整 BlockState；问题发生在后续 ModelStateResolver 把有方向属性的普通模型错误归一化。

0.9.28 只对真正的连接型模型（Fence / Pane / Wall / railing / balustrade / bars 等）做直连状态转换。

普通：

- Stairs：保留 facing / half / shape / waterlogged；
- Slab：保留 type=top/bottom/double / waterlogged；
- 其他有 facing/axis 的非连接模型：保留捕获时的原始状态和方向。

## 构建

GitHub Actions 工作流：

```text
Build 0.9.28 Direct Endpoint Tiled Width State Capture
```
