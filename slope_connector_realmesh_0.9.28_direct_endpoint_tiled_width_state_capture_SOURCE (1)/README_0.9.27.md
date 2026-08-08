# 0.9.27 Shared Station / Endpoint Priority Fix

本版基于用户上传的 0.9.26 源码，只针对白色模型弧/替换模型的接缝、Z-fighting 和端点纹理优先级修复；不改 0.9.23 原连接杖面板逻辑。

## 1. 为什么只有外弧容易出现透明缝

0.9.26 虽然把相邻段的中心点焊在同一拓扑节点，但前一段终点和后一段起点仍各自保存一套 `width/radial/span` 截面。

转弯时：

- 内弧侧两套截面向内相交，误差往往被重叠遮住；
- 外弧侧两套截面向外张开，相同误差会直接变成可见透明缝。

0.9.27 新增 `ArcStationFrames`：每个拓扑节点只有一套共享的中心、切线、左右轴、厚度轴、左右宽度和上下厚度。相邻两段都读取同一个 Station，因此外弧也不存在“两套端点截面”。

## 2. 白色模型弧透明面 / 底面闪烁

0.9.26 的 `ModelTemplateArcRenderer` 为了盖缝，把每个 Prism 的首尾都额外扩展 `1/128` 格。这样会让相邻 Prism 的上/下/侧面互相覆盖，产生 Z-fighting；同时旧 `faceMask` 可能在自定义模板渲染中漏掉本应可见的面。

0.9.27：

- 删除 `SEAM_OVERLAP`；
- 不再扩大 Prism；
- 不使用 `prism.draws(...)` 决定模板纵向可见面；
- 每一段固定渲染四个纵向面；
- 中间接点不渲染内部端盖；
- 直线和弧线前后都由共享 Station 的完全相同四个角连接。

组件尚未完整加载时的 fallback 也只画四个真实纵向面，不再扩面，所以不会恢复旧的闪烁问题。

## 3. 替换成普通方块后外弧仍裂

`ModelArcRenderer` 现在与白色模板共用 `ArcStationFrames`。

捕获 BakedModel 的顶点在跨越某一原始弧段接点时，前后两边的截面来自同一个 Station；宽度/厚度方向在 Station 之间连续插值，不再分别用前后 Prism 的局部 frame。

如果拓扑排序不能覆盖组件内全部 segment，`ArcComponentFinder` 不再返回一个“看起来能用的半组件”，而是主动失败并走可见 fallback，避免长弧部分消失。

## 4. 终点端点：纹理衔接优先级最高

端点现在明确区分：

- `connectionDirection`：端点朝向弧线，用于 Fence / Pane / Wall / Conquest 栏杆真实连接；
- `seamDirection`：整条弧的模型/纹理纵向。

起点：`seamDirection = connectionDirection`

终点：`seamDirection = connectionDirection.getOpposite()`

因此只要一个模型方块属于弧的终点，终点 `axis/facing` 和无方向模型的几何基准都优先按弧线连续方向设置。这个规则与“视角定向放置”开关无关，也不再使用独立的终点 UV 90° hack。

也就是说：需要 90° 时由真实终点方向自然算出90°；不需要90°时不会被强行再转一次。

## 5. 保持不变的功能

继续保留 0.9.23 原面板/原连接逻辑，包括：

- 两点 / 三点；
- 方向切换；
- 当前面 / 内弧朝向；
- 两点正向 / 反向；
- 自动弧边裁切；
- 左右宽度；
- 上下厚度；
- 清空连接点；
- G 打开面板；
- 视角定向放置开关；
- 模型渲染杖与预览；
- Conquest Reforged 连接兼容。

## 6. 编译

GitHub Actions 工作流：

```text
Build 0.9.27 Shared Station Endpoint Priority
```

旧的 0.9.26 已生成弧建议拆除后重新生成并重新套模型，旧数据不会自动拥有新的共享 Station。
