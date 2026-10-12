import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * RootEncoder(`com.pedro.*`)가 RTMP 명령을 로그로 남길 때 스트림 키가 함께 출력됩니다.
 * 라이브러리에는 로그를 끄는 설정이 없어 빌드 시 이 패키지의 `android.util.Log` 호출만
 * 앱의 [LIBRARY_LOG_OWNER]로 바꿉니다. 메시지는 버리고 태그와 수준만 남깁니다.
 */
class RedactLibraryLogsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.getByType(AndroidComponentsExtension::class.java).onVariants { variant ->
            variant.instrumentation.transformClassesWith(
                RedactLibraryLogsFactory::class.java,
                InstrumentationScope.ALL,
            ) {}
            variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
        }
    }
}

private const val LIBRARY_PACKAGE = "com.pedro."
private const val ANDROID_LOG = "android/util/Log"
private const val LIBRARY_LOG_OWNER = "com/framework/innolive/feature/live/direct/publish/RedactedLibraryLog"
private val LOG_METHODS = setOf("v", "d", "i", "w", "e", "wtf", "println")

abstract class RedactLibraryLogsFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean = classData.className.startsWith(LIBRARY_PACKAGE)

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, next) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String,
                        name: String,
                        descriptor: String,
                        isInterface: Boolean,
                    ) {
                        val redirect = opcode == Opcodes.INVOKESTATIC && owner == ANDROID_LOG && name in LOG_METHODS
                        super.visitMethodInsn(opcode, if (redirect) LIBRARY_LOG_OWNER else owner, name, descriptor, isInterface)
                    }
                }
            }
        }
}
