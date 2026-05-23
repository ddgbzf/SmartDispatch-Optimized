# 智能排工 (SmartDispatch) — 优化版

> 工厂生产排工管理系统 —— 根据技能评分智能分配人员到各工序

## 这是什么？

工厂每天都要根据当天的生产任务（产品型号），把工人分配到不同的工序岗位上。传统方式靠人工经验分配，效率低且容易出错。

**智能排工**就是解决这个问题：
1. 导入人员技能评分和产品工序流程（Excel一键导入）
2. 输入今天要生产的产品型号
3. 系统根据每个人的技能评分，自动把最合适的人分配到最合适的工序上

**一句话总结：告诉系统今天生产什么，系统自动告诉你谁干什么。**

## 核心功能

| 功能 | 说明 |
|------|------|
| **Excel导入** | 一键导入人员评分、产品工序数据，无需手动录入 |
| **智能排工** | 根据技能评分自动分配最优人员到各工序 |
| **请假管理** | 标记请假人员，排工时自动排除 |
| **固定岗位** | 设置固定列，排工时该列人员留任原岗位不变 |
| **结果导出** | 排工完成后导出Excel，方便打印和存档 |
| **实时统计** | 总人数、请假人数、已分配、剩余/欠缺人数一目了然 |

## 优化内容

### 代码结构优化
- **拆分 MainActivity**：2448行单文件 → 9个独立文件，按功能模块划分
- **提取 MainViewModel**：业务逻辑与UI分离，ViewModel独立管理
- **提取 UI 组件**：每个标签页独立文件（DispatchScreen、SkillScoreScreen、ProcessEditScreen、PersonEditScreen、ProductEditScreen）
- **提取对话框**：SettingsScreen、HelpScreen、FixedColumnScreen 独立管理

### 构建配置优化
- **AGP 8.2.0 → 8.5.2**：最新Android Gradle Plugin
- **Kotlin 1.9.20 → 1.9.24**：最新Kotlin版本
- **KSP 1.9.20-1.0.14 → 1.9.24-1.0.20**：对应KSP版本
- **Compose BOM 2023.10.01 → 2024.06.00**：最新Compose稳定版
- **启用 R8 全模式**：`android.enableR8.fullMode=true`，更激进的代码压缩
- **启用资源压缩**：`isShrinkResources = true`，移除未使用资源
- **签名密码环境变量化**：支持 `KEYSTORE_PASSWORD` / `KEY_PASSWORD` 环境变量

### 依赖版本更新
| 依赖 | 旧版本 | 新版本 |
|------|--------|--------|
| core-ktx | 1.12.0 | 1.13.1 |
| lifecycle-runtime-ktx | 2.6.2 | 2.8.0 |
| lifecycle-viewmodel-compose | 2.6.2 | 2.8.0 |
| activity-compose | 1.8.1 | 1.9.0 |
| kotlinx-coroutines | 1.7.3 | 1.8.1 |
| kotlinx-serialization | 1.6.0 | 1.7.1 |
| datastore-preferences | 1.0.0 | 1.1.1 |

### 横屏适配
- AndroidManifest 添加 `configChanges="orientation|screenSize|screenLayout|keyboardHidden"`
- 添加 `windowSoftInputMode="adjustResize"`

## 技术栈

- **语言**: Kotlin
- **UI框架**: Jetpack Compose + Material 3
- **数据库**: Room
- **Excel处理**: Apache POI
- **最低系统**: Android 8.0 (API 26)

## Excel格式说明

导入的Excel文件需包含以下工作表：

### 工序评分表
| 工号 | 姓名 | 工种 | 工序1 | 工序2 | ... |
|------|------|------|-------|-------|-----|
| 001 | 张三 | 操作工 | 8 | 6 | ... |

### 工序流程表
| 型号名称 | 产能 | 需求人数 | 工序1 | 工序2 | ... |
|----------|------|----------|-------|-------|-----|
| 产品A | 100 | 5 | 装配 | 检测 | ... |

## 构建方式

```bash
# 克隆仓库
git clone https://github.com/ddgbzf/SmartDispatch-Optimized.git
cd SmartDispatch-Optimized

# 构建
./gradlew assembleDebug

# APK输出路径
app/build/outputs/apk/debug/app-debug.apk
```

## 使用流程

```
导入Excel数据 → 查看评分和工序流程 → 输入产品型号 → 一键排工 → 导出结果
```

## License

MIT