import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 네이티브 라이브러리가 이름으로 찾는 클래스·생성자·필드를 keep 규칙이 지키는지 R8 `seeds.txt`로 확인합니다.
 * 계약 파일의 항목이 seeds에 없으면 R8이 지우거나 이름을 바꿀 수 있으므로 빌드를 실패시킵니다.
 */
@CacheableTask
abstract class VerifyJniKeepsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val seedsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val contractFile: RegularFileProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val contract = JniKeepContract.parse(contractFile.get().asFile.readLines())
        val seeds = seedsFile.get().asFile.readLines().toSet()
        val missing = JniKeepContract.missing(contract, seeds)
        if (missing.isNotEmpty()) {
            throw GradleException(
                "R8 keep rules do not cover JNI-facing entries; add keeps in proguard-rules.pro:\n" +
                    missing.joinToString("\n") { "  $it" },
            )
        }
        reportFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(contract.joinToString("\n", postfix = "\n"))
        }
    }
}

internal object JniKeepContract {
    /** Contract lines use the R8 `seeds.txt` notation; comments and blank lines are ignored. */
    fun parse(lines: List<String>): List<String> = lines.map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    /** A member entry also requires its class, so a renamed owner cannot satisfy the contract. */
    fun missing(contract: List<String>, seeds: Set<String>): List<String> =
        contract.flatMap { listOf(it.substringBefore(": "), it) }.distinct().filter { it !in seeds }
}
