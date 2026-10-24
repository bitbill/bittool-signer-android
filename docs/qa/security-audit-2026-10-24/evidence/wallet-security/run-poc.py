from pathlib import Path
import subprocess, os, sys, tempfile
root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[5]
c=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches/modules-2/files-2.1'
o=Path(tempfile.mkdtemp(prefix='bittool-audit-wallet-security-'))
java=str(Path(os.environ.get('AUDIT_JAVA_HOME',str(Path.home()/'Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home')))/'bin/java')
def jar(g,a,v):
    return next((c/g/a/v).glob('*/'+a+'-'+v+'.jar'))
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.4.21')
compiler=[jar('org.jetbrains.kotlin',x,'2.4.21') for x in ['kotlin-compiler-embeddable','kotlin-build-tools-api','kotlin-script-runtime','kotlin-daemon-embeddable']]+[stdlib,jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0'),jar('org.jetbrains','annotations','23.0.0')]
cp=[stdlib,jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.11.0'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-test-jvm','1.11.0'),jar('org.jetbrains.kotlinx','kotlinx-serialization-json-jvm','1.12.0'),jar('org.jetbrains.kotlinx','kotlinx-serialization-core-jvm','1.12.0'),jar('javax.inject','javax.inject','1')]
cp+=list(root.glob('core/*/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar'))
cp+=[root/'core/data/build/intermediates/built_in_kotlinc/debugUnitTest/compileDebugUnitTestKotlin/classes',root/'core/data/src/test/resources',root/'core/data/src/testShared/resources',Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))/'platforms/android-37.0/android.jar']
cp+=list(root.glob('core/*/build/libs/*-jvm.jar'))+list(root.glob('core/*/build/classes/kotlin/main'))
classpath=':'.join(map(str,cp))
(o/'classpath.txt').write_text(classpath)
subprocess.run([java,'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',classpath,'-d',str(o/'poc.jar'),str(Path(__file__).with_name('SeedCancellationPoC.kt'))],check=True)
subprocess.run([java,'-cp',str(o/'poc.jar')+':'+classpath,'SeedCancellationPoCKt'],check=True)
