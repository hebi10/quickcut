import java.io.InputStream;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.jar.JarFile;

class VerifyBundleSignatures {
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[1].matches("[0-9a-f]{64}")) {
            throw new SecurityException("Expected bundle and SHA256 certificate pin");
        }
        int payloads = 0;
        HashSet<String> names = new HashSet<>();
        try (JarFile jar = new JarFile(args[0], true)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!names.add(entry.getName())) {
                    throw new SecurityException("Duplicate JAR entry");
                }
                if (entry.isDirectory()) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    input.transferTo(java.io.OutputStream.nullOutputStream());
                }
                String upper = entry.getName().toUpperCase(Locale.ROOT);
                if (upper.equals("META-INF/MANIFEST.MF")
                        || upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1) {
                    throw new SecurityException("Every payload must have exactly one signer");
                }
                byte[] certificate = signers[0].getSignerCertPath().getCertificates().get(0).getEncoded();
                String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate));
                if (!pin.equals(args[1])) throw new SecurityException("Upload certificate pin mismatch");
                payloads++;
            }
        }
        if (payloads == 0) throw new SecurityException("No signed payload");
        System.out.println("Verified all " + payloads + " payload entries against the pinned upload certificate");
    }
}
