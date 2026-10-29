// SPDX-License-Identifier: GPL-3.0-or-later
import com.android.apksig.ApkVerifier;
import java.nio.file.Path;
import java.security.Signature;
import java.util.zip.ZipFile;

/** Host-side check uses the SDK's APK verifier, not a certificate supplied by the proof. */
public class VerifyCoreProof {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("signed-apk");
        var result = new ApkVerifier.Builder(Path.of(args[0]).toFile()).setMinCheckedPlatformVersion(29).build().verify();
        if (!result.isVerified() || result.getSignerCertificates().size() != 1) throw new IllegalStateException("APK signature verification failed");
        var certificate = result.getSignerCertificates().getFirst();
        try (var zip = new ZipFile(args[0])) {
            var manifestEntry = zip.getEntry("assets/runtime/opencode-publisher.json");
            var signatureEntry = zip.getEntry("assets/runtime/opencode-publisher.sig");
            if (manifestEntry == null || signatureEntry == null || manifestEntry.getSize() < 1 || manifestEntry.getSize() > 16384 || signatureEntry.getSize() < 1 || signatureEntry.getSize() > 8192) throw new IllegalStateException("Missing/bounded publisher proof");
            byte[] metadata, proof;
            try (var stream = zip.getInputStream(manifestEntry)) { metadata = stream.readNBytes(16385); }
            try (var stream = zip.getInputStream(signatureEntry)) { proof = stream.readNBytes(8193); }
            if (metadata.length != manifestEntry.getSize() || proof.length != signatureEntry.getSize()) throw new IllegalStateException("Publisher proof size mismatch");
            String algorithm = switch (certificate.getPublicKey().getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                default -> throw new IllegalStateException("Unsupported publisher key");
            };
            var verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate); verifier.update(metadata);
            if (!verifier.verify(proof)) throw new IllegalStateException("Proof does not match the verified APK signer");
        }
        System.out.println("PASS verified APK and embedded OpenCode proof share the same signing identity");
    }
}
