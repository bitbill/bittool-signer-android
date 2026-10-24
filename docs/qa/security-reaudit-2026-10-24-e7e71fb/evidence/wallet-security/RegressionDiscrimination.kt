fun main() {
    val atomic = com.bittool.signer.core.common.storage.AtomicFiles
    atomic.javaClass.getDeclaredField("directorySync").apply { isAccessible = true }
        .set(atomic, { _: java.io.File -> Unit })
    com.bittool.signer.core.data.DefaultSignerStoreTest()
        .aSeedDerivedForACancelledCallerIsStillWiped()
    println("Repository regression test PASSED")
}
