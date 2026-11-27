package com.rewit.infrastructure.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serviço criptográfico para autenticidade de origem e integridade de payload dos registros de
 * auditoria e decisões administrativas de moderação (S3-A13-F2, Arquitetura D).
 *
 * <p>Contrato canônico:
 * <ul>
 *   <li>Algoritmo: {@code HMAC-SHA256} com chave externa {@code MODERATION_AUDIT_SECRET} (>= 32 bytes).</li>
 *   <li>Formato do MAC: {@code aud1$<keyId>$<hex_hmac_sha256>} (64 dígitos hexadecimais minúsculos).</li>
 *   <li>Rotação controlada: suporta {@code MODERATION_AUDIT_KEY_ID} ativo e par opcional
 *       {@code MODERATION_AUDIT_SECRET_PREVIOUS} + {@code MODERATION_AUDIT_PREVIOUS_KEY_ID} (distintos).</li>
 *   <li>Serialização canônica: cabeçalho de domínio {@code "rewit-moderation-audit-v1\0"} seguido pelos 15 campos
 *       em ordem fixa com codificação binária prefixada por presença (1 byte) e comprimento (4 bytes big-endian),
 *       UUIDs em 16 bytes e timestamps em epoch microseconds (8 bytes big-endian).</li>
 *   <li>Verificação em tempo constante via {@link MessageDigest#isEqual(byte[], byte[])}.</li>
 *   <li>Registros históricos sem MAC ({@code null}) são classificados como
 *       {@link AuditVerificationStatus#HISTORICAL_UNAUTHENTICATED}, nunca como verificados.</li>
 * </ul>
 */
@Service
public class ModerationAuditMacService {

    public static final String VERSION_PREFIX = "aud1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] DOMAIN_HEADER = "rewit-moderation-audit-v1\0".getBytes(StandardCharsets.UTF_8);
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Pattern CANONICAL_MAC_PATTERN = Pattern.compile("^aud1\\$([A-Za-z0-9_-]{1,32})\\$([0-9a-f]{64})$");

    private final byte[] activeKeyBytes;
    private final String activeKeyId;
    private final byte[] previousKeyBytes;
    private final String previousKeyId;

    @Autowired
    public ModerationAuditMacService(
            @Value("${rewit.security.moderation-audit-secret}") String activeSecret,
            @Value("${rewit.security.moderation-audit-key-id:v1}") String activeKeyId,
            @Value("${rewit.security.moderation-audit-secret-previous:}") String previousSecret,
            @Value("${rewit.security.moderation-audit-previous-key-id:}") String previousKeyId
    ) {
        this.activeKeyBytes = SecuritySecretValidator.decodeSecret("MODERATION_AUDIT_SECRET", activeSecret);
        this.activeKeyId = validateKeyId("MODERATION_AUDIT_KEY_ID", activeKeyId);

        boolean hasPreviousSecret = previousSecret != null && !previousSecret.isBlank();
        boolean hasPreviousKeyId = previousKeyId != null && !previousKeyId.isBlank();

        if (hasPreviousSecret != hasPreviousKeyId) {
            throw new IllegalArgumentException(
                    "Configuração inconsistente de rotação de MODERATION_AUDIT_SECRET: "
                            + "MODERATION_AUDIT_SECRET_PREVIOUS e MODERATION_AUDIT_PREVIOUS_KEY_ID devem ser configurados juntos.");
        }

        if (hasPreviousSecret) {
            this.previousKeyBytes = SecuritySecretValidator.decodeSecret("MODERATION_AUDIT_SECRET_PREVIOUS", previousSecret);
            this.previousKeyId = validateKeyId("MODERATION_AUDIT_PREVIOUS_KEY_ID", previousKeyId);
            if (this.activeKeyId.equals(this.previousKeyId)) {
                throw new IllegalArgumentException(
                        "MODERATION_AUDIT_PREVIOUS_KEY_ID não pode ser igual a MODERATION_AUDIT_KEY_ID.");
            }
            SecuritySecretValidator.validateDistinctSecrets(
                    "MODERATION_AUDIT_SECRET", this.activeKeyBytes,
                    "MODERATION_AUDIT_SECRET_PREVIOUS", this.previousKeyBytes);
        } else {
            this.previousKeyBytes = null;
            this.previousKeyId = null;
        }
    }

    /** Calcula o MAC canônico {@code aud1$<keyId>$<hex>} utilizando exclusivamente a chave ativa. */
    public String computeMac(ModerationAuditPayload payload) {
        Objects.requireNonNull(payload, "payload must not be null");
        byte[] canonicalBytes = serializeCanonical(payload);
        byte[] macBytes = hmacSha256(activeKeyBytes, canonicalBytes);
        return VERSION_PREFIX + "$" + activeKeyId + "$" + HexFormat.of().formatHex(macBytes);
    }

    /**
     * Verifica um MAC persistido contra o payload canônico em tempo constante.
     *
     * <p>{@code null} indica registro histórico anterior à V45 e retorna
     * {@link AuditVerificationStatus#HISTORICAL_UNAUTHENTICATED}. Qualquer valor não nulo malformado,
     * com {@code keyId} desconhecido ou divergente retorna {@link AuditVerificationStatus#INVALID}.
     */
    public AuditVerificationStatus verify(ModerationAuditPayload payload, String storedMac) {
        Objects.requireNonNull(payload, "payload must not be null");
        if (storedMac == null) {
            return AuditVerificationStatus.HISTORICAL_UNAUTHENTICATED;
        }
        Matcher matcher = CANONICAL_MAC_PATTERN.matcher(storedMac);
        if (!matcher.matches()) {
            return AuditVerificationStatus.INVALID;
        }

        String keyId = matcher.group(1);
        byte[] providedMacBytes;
        try {
            providedMacBytes = HexFormat.of().parseHex(matcher.group(2));
        } catch (IllegalArgumentException e) {
            return AuditVerificationStatus.INVALID;
        }

        byte[] keyBytes = resolveKey(keyId);
        if (keyBytes == null) {
            return AuditVerificationStatus.INVALID;
        }

        byte[] expectedMacBytes = hmacSha256(keyBytes, serializeCanonical(payload));
        return MessageDigest.isEqual(expectedMacBytes, providedMacBytes)
                ? AuditVerificationStatus.VERIFIED
                : AuditVerificationStatus.INVALID;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public String getPreviousKeyId() {
        return previousKeyId;
    }

    /**
     * Codificação binária canônica determinística e não ambígua (length-prefixed).
     * Exposta com visibilidade package-private para testes unitários determinísticos de canonicalização.
     */
    static byte[] serializeCanonical(ModerationAuditPayload p) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
             DataOutputStream out = new DataOutputStream(baos)) {
            out.write(DOMAIN_HEADER);
            writeString(out, p.domain());
            writeUuid(out, p.eventId());
            writeUuid(out, p.targetId());
            writeUuid(out, p.secondaryTargetId());
            writeUuid(out, p.referenceId());
            writeUuid(out, p.actorId());
            writeString(out, p.actorType());
            writeString(out, p.action());
            writeString(out, p.decision());
            writeString(out, p.previousStatus());
            writeString(out, p.newStatus());
            writeInstantMicros(out, p.occurredAt());
            writeString(out, p.reasonCode());
            writeString(out, p.justification());
            writeInteger(out, p.reportsAffectedCount());
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha inesperada na serialização canônica do payload de auditoria", e);
        }
    }

    private byte[] resolveKey(String keyId) {
        if (activeKeyId.equals(keyId)) {
            return activeKeyBytes;
        }
        if (previousKeyId != null && previousKeyId.equals(keyId)) {
            return previousKeyBytes;
        }
        return null;
    }

    private static byte[] hmacSha256(byte[] keyBytes, byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(keyBytes, HMAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao calcular HMAC-SHA256 de auditoria", e);
        }
    }

    private static String validateKeyId(String envName, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException(
                    envName + " inválido: identificador de chave obrigatório (padrão [A-Za-z0-9_-]{1,32}).");
        }
        String trimmed = keyId.trim();
        if (!KEY_ID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    envName + " inválido: use apenas [A-Za-z0-9_-] com 1 a 32 caracteres.");
        }
        return trimmed;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeByte(0);
            return;
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeByte(1);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        if (value == null) {
            out.writeByte(0);
            return;
        }
        out.writeByte(1);
        out.writeInt(16);
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void writeInstantMicros(DataOutputStream out, Instant value) throws IOException {
        if (value == null) {
            out.writeByte(0);
            return;
        }
        long epochMicros = Math.addExact(
                Math.multiplyExact(value.getEpochSecond(), 1_000_000L),
                value.getNano() / 1_000L
        );
        out.writeByte(1);
        out.writeInt(8);
        out.writeLong(epochMicros);
    }

    private static void writeInteger(DataOutputStream out, Integer value) throws IOException {
        if (value == null) {
            out.writeByte(0);
            return;
        }
        out.writeByte(1);
        out.writeInt(4);
        out.writeInt(value);
    }
}
