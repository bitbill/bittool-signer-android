# Compile unchanged baseline production source in /tmp and run the NEW regression
# test against it. This tests whether the test actually detects the old defect.
from pathlib import Path
import subprocess
exec((Path(__file__).parent / 'run-poc.py').read_text().split('subprocess.run([java,')[0])
cp += [jar('org.jetbrains.kotlin','kotlin-test','2.4.21'),jar('org.jetbrains.kotlin','kotlin-test-junit','2.4.21'),jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3'), root/'core/l10n/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar']
classpath=':'.join(map(str,cp))
subprocess.run([java,'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-Xfriend-paths='+str(root/'core/data/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar'),'-classpath',classpath,'-d',str(o/'baseline.jar'),str(o/'BaselineDefaultSignerStore.kt')],check=True)
subprocess.run([java,'-cp',':'.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-jvm-target','17','-no-stdlib','-no-reflect','-Xfriend-paths='+str(root/'core/data/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar'),'-classpath',classpath,'-d',str(o/'regression.jar'),str(o/'RegressionDiscrimination.kt')],check=True)
for label, prefix, expect_success in [('bb1e656 baseline', str(o/'baseline.jar')+':', False), ('e7e71fb fixed', '', True)]:
    result = subprocess.run([java,'-cp',prefix+str(o/'regression.jar')+':'+classpath,'RegressionDiscriminationKt'],capture_output=True,text=True)
    print(f'{label}: exit={result.returncode}, expected_success={expect_success}')
    if result.returncode:
        print('\n'.join(result.stderr.splitlines()[:12]))
        assert 'AssertionError: the seed is wiped' in result.stderr, result.stderr
    else:
        print(result.stdout.strip())
    assert (result.returncode == 0) == expect_success
print('RESULT: regression test discriminates correctly; baseline FAILS and fixed code PASSES')
