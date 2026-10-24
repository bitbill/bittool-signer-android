from pathlib import Path
import subprocess
root=Path('/Users/tanguopeng/Documents/bitool-signer-android')
c=Path('/Users/tanguopeng/.gradle/caches/modules-2/files-2.1')
o=Path(__file__).resolve().parent
java='/Users/tanguopeng/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home/bin/java'
def jar(g,a,v):
    return next((c/g/a/v).glob('*/'+a+'-'+v+'.jar'))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.21')
compiler=[jar('org.jetbrains.kotlin',x,'2.4.21') for x in ['kotlin-compiler-embeddable','kotlin-build-tools-api','kotlin-script-runtime','kotlin-daemon-embeddable']]+[stdlib,jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0'),jar('org.jetbrains','annotations','23.0.0')]
cp=[stdlib,jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-test-jvm','1.11.0'),jar('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm','1.12.0'),jar('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.12.0'),jar('javax.inject','javax.inject','1')]
cp+=list(root.glob('core/*/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar'))
cp+=[root/'core/data/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes',root/'core/data/src/test/resources',root/'core/data/src/testShared/resources',Path('/Users/tanguopeng/Library/Android/sdk/platforms/android-37.0/android.jar')]
cp+=list(root.glob('core/*/build/libs/*-jvm.jar'))+list(root.glob('core/*/build/classes/kotlin/main'))
classpath=':'.join(map(str,cp))
(o/'classpath.txt').write_text(classpath)
subprocess.run([java,'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',classpath,'-d',str(o/'poc.jar'),str(o/'SecretCancellationReaudit.kt')],check=True)
subprocess.run([java,'-cp',str(o/'poc.jar')+':'+classpath,'SecretCancellationReauditKt'],check=True)
