# ---------------------------------------------------------------------------
# libxposed：模块入口由框架按 `META-INF/xposed/java_init.list` 里的名字反射实例化，
# 类名/构造器被 R8 改掉就直接加载失败。框架接口本身也必须原样保留。
# ---------------------------------------------------------------------------
-keep class com.xiang.signhelper.hook.MainHook { *; }
-keep class io.github.libxposed.** { *; }
-keepclassmembers class io.github.libxposed.** { *; }
-dontwarn io.github.libxposed.**

# ---------------------------------------------------------------------------
# 本模块自己的类：数量很少（配置 / 数据 / hook / util），全部保留。
# 这些类里有按字符串构造/反射访问的成员（如 Signature、PackageInfo 的字段），
# 保留它们比事后补规则便宜。
# ---------------------------------------------------------------------------
-keep class com.xiang.signhelper.config.** { *; }
-keep class com.xiang.signhelper.data.** { *; }
-keep class com.xiang.signhelper.util.** { *; }
-keep class com.xiang.signhelper.hook.** { *; }

# Manifest 里声明的 Application / Activity（AGP 通常会自动保留，显式写一遍更稳）
-keep class com.xiang.signhelper.App { *; }
-keep class com.xiang.signhelper.ui.MainActivity { *; }

# ---------------------------------------------------------------------------
# Compose / Kotlin / Miuix 常规豁免
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
