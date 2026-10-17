import com.android.apksig.ApkSigner
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.security.cert.X509Certificate

fun main(args: Array<String>) {
    if (args.size < 3) {
        println("Usage: Signer <in.apk> <out.apk> <keystore>")
        return
    }
    val inApk = File(args[0])
    val outApk = File(args[1])
    val ksFile = File(args[2])
    val ks = KeyStore.getInstance("JKS")
    FileInputStream(ksFile).use { ks.load(it, "android".toCharArray()) }
    val entry = ks.getEntry("androiddebugkey", KeyStore.PasswordProtection("android".toCharArray())) as KeyStore.PrivateKeyEntry
    val cert = entry.certificate as X509Certificate
    val key = entry.privateKey
    val signerConfig = ApkSigner.SignerConfig.Builder("androiddebugkey", key, listOf(cert)).build()
    val builder = ApkSigner.Builder(listOf(signerConfig))
        .setInputApk(inApk)
        .setOutputApk(outApk)
        .setV1SigningEnabled(true)
        .setV2SigningEnabled(true)
    builder.build().sign()
    println("SUCCESS: " + outApk.absolutePath)
}
