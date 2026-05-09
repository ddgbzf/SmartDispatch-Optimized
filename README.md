# 智能排工系统

Android原生应用，用于工厂生产排工管理。

## 功能特性

- 📊 **Excel导入** - 读取包含工序评分、工序流程、排工表的Excel文件
- 🤖 **智能分配** - 根据技能评分自动分配最优人员到各工序
- 📝 **请假管理** - 自动排除请假人员
- 📌 **固定岗位** - 支持固定岗位（黄色列标识）
- 📈 **状态报告** - 显示剩余/欠缺人数、未分配人员
- 💾 **结果导出** - 导出排工后的Excel文件

## 技术栈

- **语言**: Kotlin
- **UI框架**: Jetpack Compose
- **Excel处理**: Apache POI
- **最低SDK**: Android 8.0 (API 26)

## 构建方式

### 环境要求
- JDK 17+
- Android SDK 34
- Gradle 8.2+

### 构建APK

```bash
# 克隆项目
git clone https://github.com/your-username/smart-dispatch.git
cd smart-dispatch

# 构建Debug APK
./gradlew assembleDebug

# 构建Release APK
./gradlew assembleRelease
```

APK输出路径: `app/build/outputs/apk/`

## 使用说明

1. 准备Excel文件，包含三个工作表：
   - **工序评分**: 人员对各工序的技能评分
   - **工序流程**: 产品信息、产能、需求人数、工序步骤
   - **智能排工**: 请假人员列表、产品列、固定岗位

2. 打开APP，点击"选择Excel"选择文件

3. 点击"执行排工"开始自动分配

4. 查看结果后点击"导出结果"保存

## Excel格式说明

### 工序评分表
| 姓名 | 工序1 | 工序2 | ... |
|------|-------|-------|-----|
| 张三 | 5     | 3     | ... |

### 工序流程表
| 产品 | 产能 | 人数 | 工序1 | 工序2 | ... |
|------|------|------|-------|-------|-----|
| P50301 | 1500 | 11 | 落料 | 压型 | ... |

### 智能排工表
- A列: 请假人员
- B列起: 产品列（每2列一组：产品名+人员）
- 黄色背景列: 固定岗位

## License

MIT License
