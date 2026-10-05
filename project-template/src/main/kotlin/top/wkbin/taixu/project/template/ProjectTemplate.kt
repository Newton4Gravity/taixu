package top.wkbin.taixu.project.template

import kotlinx.serialization.Serializable
import top.wkbin.taixu.core.model.ToolManifest

/**
 * Project template definition for workspace creation.
 */
@Serializable
data class ProjectTemplate(
    val id: String,
    val name: String,
    val description: String,
    val category: String = "general",
    val icon: String = "folder",
    val files: List<TemplateFile> = emptyList(),
    val directories: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
    val recommendedTools: List<String> = emptyList(),
    val postCreateCommands: List<String> = emptyList(),
    val version: String = "1.0.0"
)

/**
 * Template file with content or source reference.
 */
@Serializable
data class TemplateFile(
    val path: String,
    val content: String? = null,
    val sourceUrl: String? = null,
    val isExecutable: Boolean = false,
    val placeholderValues: Map<String, String> = emptyMap()
)

/**
 * Built-in project templates catalog.
 */
object BuiltinTemplates {
    private val templates = mapOf(
        "empty" to ProjectTemplate(
            id = "empty",
            name = "Empty Project",
            description = "A blank workspace for custom projects",
            category = "general",
            directories = listOf("src", "docs", "tests")
        ),
        "android-app" to ProjectTemplate(
            id = "android-app",
            name = "Android App (Kotlin + Compose)",
            description = "Modern Android app with Jetpack Compose, Koin, and Room",
            category = "mobile",
            icon = "android",
            files = listOf(
                TemplateFile("build.gradle.kts", sourceUrl = "templates/android-app/build.gradle.kts"),
                TemplateFile("settings.gradle.kts", sourceUrl = "templates/android-app/settings.gradle.kts"),
                TemplateFile("gradle/libs.versions.toml", sourceUrl = "templates/android-app/libs.versions.toml"),
                TemplateFile("app/src/main/java/com/example/MainActivity.kt", sourceUrl = "templates/android-app/MainActivity.kt"),
                TemplateFile("app/src/main/AndroidManifest.xml", sourceUrl = "templates/android-app/AndroidManifest.xml")
            ),
            dependencies = listOf("androidx.compose", "koin", "room", "kotlinx.coroutines"),
            recommendedTools = listOf("android-sdk", "gradle"),
            postCreateCommands = listOf("./gradlew assembleDebug")
        ),
        "kotlin-library" to ProjectTemplate(
            id = "kotlin-library",
            name = "Kotlin Multiplatform Library",
            description = "KMP library targeting JVM, Android, iOS, and JS",
            category = "library",
            icon = "kotlin",
            files = listOf(
                TemplateFile("build.gradle.kts", sourceUrl = "templates/kotlin-library/build.gradle.kts"),
                TemplateFile("settings.gradle.kts", sourceUrl = "templates/kotlin-library/settings.gradle.kts"),
                TemplateFile("commonMain/kotlin/com/example/Greeting.kt", sourceUrl = "templates/kotlin-library/Greeting.kt")
            ),
            dependencies = listOf("kotlinx.coroutines", "kotlinx.serialization"),
            recommendedTools = listOf("gradle", "kotlin")
        ),
        "node-cli" to ProjectTemplate(
            id = "node-cli",
            name = "Node.js CLI Tool (TypeScript)",
            description = "Command-line tool with TypeScript, Commander.js, and Vitest",
            category = "cli",
            icon = "node",
            files = listOf(
                TemplateFile("package.json", sourceUrl = "templates/node-cli/package.json"),
                TemplateFile("tsconfig.json", sourceUrl = "templates/node-cli/tsconfig.json"),
                TemplateFile("src/index.ts", sourceUrl = "templates/node-cli/index.ts"),
                TemplateFile("src/commands.ts", sourceUrl = "templates/node-cli/commands.ts")
            ),
            dependencies = listOf("commander", "typescript", "vitest"),
            recommendedTools = listOf("node", "npm"),
            postCreateCommands = listOf("npm install", "npm run build")
        ),
        "python-package" to ProjectTemplate(
            id = "python-package",
            name = "Python Package (Modern)",
            description = "Python package with pyproject.toml, Ruff, and pytest",
            category = "library",
            icon = "python",
            files = listOf(
                TemplateFile("pyproject.toml", sourceUrl = "templates/python-package/pyproject.toml"),
                TemplateFile("src/taixu_example/__init__.py", sourceUrl = "templates/python-package/__init__.py"),
                TemplateFile("tests/test_example.py", sourceUrl = "templates/python-package/test_example.py"),
                TemplateFile("README.md", sourceUrl = "templates/python-package/README.md")
            ),
            dependencies = listOf("pytest", "ruff", "mypy"),
            recommendedTools = listOf("python", "pip"),
            postCreateCommands = listOf("pip install -e .[dev]", "pytest")
        ),
        "rust-cli" to ProjectTemplate(
            id = "rust-cli",
            name = "Rust CLI Application",
            description = "Rust command-line app with Clap and Tokio",
            category = "cli",
            icon = "rust",
            files = listOf(
                TemplateFile("Cargo.toml", sourceUrl = "templates/rust-cli/Cargo.toml"),
                TemplateFile("src/main.rs", sourceUrl = "templates/rust-cli/main.rs"),
                TemplateFile("src/cli.rs", sourceUrl = "templates/rust-cli/cli.rs")
            ),
            dependencies = listOf("clap", "tokio", "serde"),
            recommendedTools = listOf("rust", "cargo"),
            postCreateCommands = listOf("cargo build --release")
        ),
        "go-service" to ProjectTemplate(
            id = "go-service",
            name = "Go Microservice",
            description = "Go HTTP service with Gin, gRPC, and OpenTelemetry",
            category = "backend",
            icon = "go",
            files = listOf(
                TemplateFile("go.mod", sourceUrl = "templates/go-service/go.mod"),
                TemplateFile("main.go", sourceUrl = "templates/go-service/main.go"),
                TemplateFile("handlers.go", sourceUrl = "templates/go-service/handlers.go"),
                TemplateFile("proto/service.proto", sourceUrl = "templates/go-service/service.proto")
            ),
            dependencies = listOf("gin", "grpc", "opentelemetry"),
            recommendedTools = listOf("go", "protoc"),
            postCreateCommands = listOf("go mod tidy", "go build")
        ),
        "web-react" to ProjectTemplate(
            id = "web-react",
            name = "React + TypeScript + Vite",
            description = "Modern React app with TypeScript, Vite, and Tailwind CSS",
            category = "web",
            icon = "react",
            files = listOf(
                TemplateFile("package.json", sourceUrl = "templates/web-react/package.json"),
                TemplateFile("tsconfig.json", sourceUrl = "templates/web-react/tsconfig.json"),
                TemplateFile("vite.config.ts", sourceUrl = "templates/web-react/vite.config.ts"),
                TemplateFile("src/main.tsx", sourceUrl = "templates/web-react/main.tsx"),
                TemplateFile("src/App.tsx", sourceUrl = "templates/web-react/App.tsx"),
                TemplateFile("index.html", sourceUrl = "templates/web-react/index.html")
            ),
            dependencies = listOf("react", "typescript", "vite", "tailwindcss"),
            recommendedTools = listOf("node", "npm"),
            postCreateCommands = listOf("npm install", "npm run dev")
        ),
        "flutter-app" to ProjectTemplate(
            id = "flutter-app",
            name = "Flutter App",
            description = "Cross-platform Flutter app with Material 3 and Riverpod",
            category = "mobile",
            icon = "flutter",
            files = listOf(
                TemplateFile("pubspec.yaml", sourceUrl = "templates/flutter-app/pubspec.yaml"),
                TemplateFile("lib/main.dart", sourceUrl = "templates/flutter-app/main.dart"),
                TemplateFile("lib/app.dart", sourceUrl = "templates/flutter-app/app.dart")
            ),
            dependencies = listOf("flutter_riverpod", "go_router", "freezed"),
            recommendedTools = listOf("flutter", "dart"),
            postCreateCommands = listOf("flutter pub get", "flutter run")
        )
    )

    fun getAll(): List<ProjectTemplate> = templates.values.toList()

    fun getById(id: String): ProjectTemplate? = templates[id]

    fun getByCategory(category: String): List<ProjectTemplate> =
        templates.values.filter { it.category == category }.toList()

    val categories: List<String> = templates.values.map { it.category }.distinct().sorted()
}

/**
 * Template engine for rendering project templates with placeholders.
 */
class TemplateEngine {
    /**
     * Renders a template file by replacing placeholders.
     */
    fun render(template: TemplateFile, values: Map<String, String>): String {
        val content = template.content ?: return ""
        val mergedValues = template.placeholderValues + values
        return mergedValues.fold(content) { acc, (key, value) ->
            acc.replace("{{$key}}", value).replace("{{ $key }}", value)
        }
    }

    /**
     * Creates a project from a template.
     */
    fun createProject(
        template: ProjectTemplate,
        targetDir: java.io.File,
        values: Map<String, String> = emptyMap()
    ): List<java.io.File> {
        val createdFiles = mutableListOf<java.io.File>()

        // Create directories
        for (dir in template.directories) {
            targetDir.resolve(dir).mkdirs()
        }

        // Create files
        for (file in template.files) {
            val filePath = targetDir.resolve(file.path)
            filePath.parentFile?.mkdirs()
            val content = if (file.content != null) {
                render(file, values)
            } else {
                // Would fetch from sourceUrl in real implementation
                "// Template file: ${file.path}\n// Source: ${file.sourceUrl}\n"
            }
            filePath.writeText(content)
            if (file.isExecutable) {
                filePath.setExecutable(true)
            }
            createdFiles.add(filePath)
        }

        return createdFiles
    }
}