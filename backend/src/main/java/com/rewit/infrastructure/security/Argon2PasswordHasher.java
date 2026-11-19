package com.rewit.infrastructure.security;

import com.rewit.application.port.PasswordHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adaptador de infraestrutura para hashing e validação de senhas com Argon2id vinculado ao UUID do usuário
 * e password pepper externo via HMAC-SHA256.
 *
 * <p>Construção criptográfica:
 * <pre>
 * preHash = Base64URL(HMAC-SHA256(K_password, "rewit-password-v1:" || userId_16bytes || rawPassword))
 * encoded = pw2$v1$&lt;keyId&gt;$argon2id$v=19$m=19456,t=2,p=1$&lt;salt&gt;$&lt;hash&gt;
 * </pre>
 *
 * <p>Somente hashes no formato autenticado {@code pw2} são aceitos. Hashes Argon2id crus ou não autenticados
 * são estritamente rejeitados de forma fail-closed.
 *
 * <p>Parâmetros do Argon2id:
 * <ul>
 *   <li>saltLength: 16 bytes (128 bits)</li>
 *   <li>hashLength: 32 bytes (256 bits)</li>
 *   <li>parallelism: 1 thread</li>
 *   <li>memory: 19456 KiB (19 MiB)</li>
 *   <li>iterations: 2 iterações</li>
 * </ul>
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

    public static final String PW2_PREFIX = "pw2$v1$";
    public static final String DEFAULT_KEY_ID = "v1";

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final byte[] PASSWORD_CONTEXT = "rewit-password-v1:".getBytes(StandardCharsets.UTF_8);
    private static final UUID DUMMY_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final PasswordEncoder delegate;
    private final byte[] activePepperBytes;
    private final String activeKeyId;
    private final byte[] previousPepperBytes;
    private final String previousKeyId;
    private final String dummyHash;

    @org.springframework.beans.factory.annotation.Autowired
    public Argon2PasswordHasher(
            @Value("${rewit.security.password-pepper}") String passwordPepper,
            @Value("${rewit.security.password-pepper-key-id:" + DEFAULT_KEY_ID + "}") String activeKeyId,
            @Value("${rewit.security.password-pepper-previous:}") String previousPepper,
            @Value("${rewit.security.password-pepper-previous-key-id:}") String previousKeyId
    ) {
        this(new Argon2PasswordEncoder(16, 32, 1, 19456, 2), passwordPepper, activeKeyId, previousPepper, previousKeyId);
    }

    public Argon2PasswordHasher() {
        this("rewit_in_memory_test_pepper_entropy_k9x2m8q7v4z1w5p3l8n0!");
    }

    public Argon2PasswordHasher(String passwordPepper) {
        this(new Argon2PasswordEncoder(16, 32, 1, 19456, 2), passwordPepper, DEFAULT_KEY_ID, null, null);
    }

    public Argon2PasswordHasher(PasswordEncoder delegate, String passwordPepper) {
        this(delegate, passwordPepper, DEFAULT_KEY_ID, null, null);
    }

    public Argon2PasswordHasher(
            PasswordEncoder delegate,
            String passwordPepper,
            String activeKeyId,
            String previousPepper,
            String previousKeyId
    ) {
        this.delegate = Objects.requireNonNull(delegate, "PasswordEncoder delegate must not be null");
        this.activePepperBytes = SecuritySecretValidator.decodeSecret("PASSWORD_PEPPER_SECRET", passwordPepper);
        this.activeKeyId = validateKeyId("PASSWORD_PEPPER_KEY_ID", activeKeyId);

        if (previousPepper != null && !previousPepper.isBlank()) {
            this.previousPepperBytes = SecuritySecretValidator.decodeSecret("PASSWORD_PEPPER_SECRET_PREVIOUS", previousPepper);
            SecuritySecretValidator.validateDistinctSecrets(
                    "PASSWORD_PEPPER_SECRET", this.activePepperBytes,
                    "PASSWORD_PEPPER_SECRET_PREVIOUS", this.previousPepperBytes
            );
            this.previousKeyId = validateKeyId("PASSWORD_PEPPER_PREVIOUS_KEY_ID", previousKeyId);
            if (this.previousKeyId.equals(this.activeKeyId)) {
                throw new IllegalArgumentException(
                        "PASSWORD_PEPPER_KEY_ID e PASSWORD_PEPPER_PREVIOUS_KEY_ID não podem ser iguais durante a rotação."
                );
            }
        } else {
            this.previousPepperBytes = null;
            this.previousKeyId = null;
        }

        // Gera hash dummy com a chave ativa para simulação de verificação em tempo equivalente
        String dummyPreHash = computePreHash(this.activePepperBytes, DUMMY_USER_ID, UUID.randomUUID().toString());
        String argon2Dummy = this.delegate.encode(dummyPreHash);
        String argon2DummyBody = argon2Dummy.startsWith("$") ? argon2Dummy.substring(1) : argon2Dummy;
        this.dummyHash = PW2_PREFIX + this.activeKeyId + "$" + argon2DummyBody;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    private static String validateKeyId(String propertyName, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException(
                    propertyName + " inválido: identificador de chave ausente ou vazio. Utilize o formato [A-Za-z0-9_-]{1,32}."
            );
        }
        if (!KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalArgumentException(
                    propertyName + " inválido: identificador deve corresponder estritamente a [A-Za-z0-9_-]{1,32} (sem '$' ou separadores)."
            );
        }
        return keyId;
    }

    @Override
    public String hash(UUID userId, String rawPassword) {
        Objects.requireNonNull(userId, "userId must not be null");
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("A senha para hashing não pode ser nula ou em branco");
        }

        String preHash = computePreHash(activePepperBytes, userId, rawPassword);
        String argon2Phc = delegate.encode(preHash);
        String phcBody = argon2Phc.startsWith("$") ? argon2Phc.substring(1) : argon2Phc;

        return PW2_PREFIX + activeKeyId + "$" + phcBody;
    }

    @Override
    public boolean matches(UUID userId, String rawPassword, String encodedPassword) {
        if (userId == null || rawPassword == null || encodedPassword == null) {
            return false;
        }

        // Somente o formato atual com pepper (pw2$v1$<keyId>$<argon2_phc>) é aceito;
        // hashes Argon2id crus ou não autenticados são estritamente rejeitados.
        if (!encodedPassword.startsWith(PW2_PREFIX)) {
            return false;
        }

        return matchesPw2(userId, rawPassword, encodedPassword);
    }

    private boolean matchesPw2(UUID userId, String rawPassword, String encodedPassword) {
        int firstDollar = encodedPassword.indexOf('$');
        int secondDollar = encodedPassword.indexOf('$', firstDollar + 1);
        int thirdDollar = encodedPassword.indexOf('$', secondDollar + 1);

        if (thirdDollar < 0 || thirdDollar + 1 >= encodedPassword.length()) {
            return false;
        }

        String keyIdInHash = encodedPassword.substring(secondDollar + 1, thirdDollar);
        if (!KEY_ID_PATTERN.matcher(keyIdInHash).matches()) {
            return false;
        }

        String argon2Phc = "$" + encodedPassword.substring(thirdDollar + 1);

        if (keyIdInHash.equals(activeKeyId)) {
            String preHash = computePreHash(activePepperBytes, userId, rawPassword);
            return delegate.matches(preHash, argon2Phc);
        }

        if (previousPepperBytes != null && keyIdInHash.equals(previousKeyId)) {
            String preHashPrev = computePreHash(previousPepperBytes, userId, rawPassword);
            return delegate.matches(preHashPrev, argon2Phc);
        }

        return false;
    }

    @Override
    public boolean needsUpgrade(String encodedPassword) {
        if (encodedPassword == null) {
            return false;
        }

        if (!encodedPassword.startsWith(PW2_PREFIX)) {
            return true;
        }

        int firstDollar = encodedPassword.indexOf('$');
        int secondDollar = encodedPassword.indexOf('$', firstDollar + 1);
        int thirdDollar = encodedPassword.indexOf('$', secondDollar + 1);
        if (thirdDollar < 0) {
            return true;
        }

        String keyIdInHash = encodedPassword.substring(secondDollar + 1, thirdDollar);
        return !keyIdInHash.equals(activeKeyId);
    }

    @Override
    public void simulateVerification(String rawPassword) {
        String preHash = computePreHash(activePepperBytes, DUMMY_USER_ID, rawPassword != null ? rawPassword : "");
        int thirdDollar = dummyHash.indexOf('$', dummyHash.indexOf('$', dummyHash.indexOf('$') + 1) + 1);
        String phc = "$" + dummyHash.substring(thirdDollar + 1);
        delegate.matches(preHash, phc);
    }

    private byte[] computeHmac(byte[] key, UUID userId, String rawPassword) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(PASSWORD_CONTEXT);

            ByteBuffer bb = ByteBuffer.allocate(16);
            bb.putLong(userId.getMostSignificantBits());
            bb.putLong(userId.getLeastSignificantBits());
            mac.update(bb.array());

            mac.update(rawPassword.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Falha no cálculo de HMAC para password hashing", e);
        }
    }

    private String computePreHash(byte[] key, UUID userId, String rawPassword) {
        byte[] hmac = computeHmac(key, userId, rawPassword);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac);
    }
}
