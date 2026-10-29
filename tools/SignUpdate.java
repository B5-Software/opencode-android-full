// SPDX-License-Identifier: GPL-3.0-or-later
// Standard JCA signature, anchored to the same keystore alias used for the APK.
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Arrays;

public class SignUpdate {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("keystore manifest signature-output");
        char[] storePass = System.getenv("ANDROID_KEYSTORE_PASSWORD").toCharArray();
        char[] keyPass = System.getenv("ANDROID_KEY_PASSWORD").toCharArray();
        try {
            KeyStore store = KeyStore.getInstance(Path.of(args[0]).toFile(), storePass);
            String alias = System.getenv("ANDROID_KEY_ALIAS");
            PrivateKey key = (PrivateKey) store.getKey(alias, keyPass);
            String algorithm = switch (key.getAlgorithm()) {
                case "RSA" -> "SHA256withRSA";
                case "EC" -> "SHA256withECDSA";
                default -> throw new IllegalArgumentException("APK update signing supports RSA and EC keys");
            };
            byte[] bytes = Files.readAllBytes(Path.of(args[1]));
            if (bytes.length == 0 || bytes.length > 16 * 1024) throw new IllegalArgumentException("Manifest bounds");
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key); signer.update(bytes);
            byte[] signed = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(store.getCertificate(alias)); verifier.update(bytes);
            if (!verifier.verify(signed)) throw new IllegalStateException("Signature self-check failed");
            Files.write(Path.of(args[2]), signed);
            System.out.println("OpenCode update manifest signed and verified");
        } finally { Arrays.fill(storePass, '\0'); Arrays.fill(keyPass, '\0'); }
    }
}
