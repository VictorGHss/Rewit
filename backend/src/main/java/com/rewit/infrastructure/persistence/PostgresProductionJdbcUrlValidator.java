package com.rewit.infrastructure.persistence;

import java.net.InetAddress;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validador puro (sem dependência do Spring Framework) de URLs JDBC PostgreSQL e hostnames
 * para produção e para o runner isolado de migrations.
 *
 * <p>Garante política única entre {@code ProductionEnvironmentValidator} e
 * {@code RewitMigrationRunner}:
 * <ul>
 *   <li>formato canônico {@code jdbc:postgresql://host[:porta]/database}</li>
 *   <li>ausência de userinfo ({@code @}) na authority e de parâmetros {@code user}/{@code password}</li>
 *   <li>rejeição léxica (sem DNS) de hosts locais, loopback IPv4/IPv6, IPv4 ambíguo/octal/hex e {@code 0.0.0.0/::}</li>
 *   <li>TLS estrito {@code sslmode=verify-full} minúsculo e único, sem {@code ssl=false} e sem overrides
 *       {@code sslfactory}, {@code sslfactoryarg} ou {@code sslhostnameverifier}</li>
 * </ul>
 */
public final class PostgresProductionJdbcUrlValidator {

    public static final String REQUIRED_PG_SSL_MODE = "verify-full";
    public static final int DEFAULT_PG_PORT = 5432;

    private static final Pattern STRICT_POSTGRES_URL_PATTERN =
            Pattern.compile("^jdbc:postgresql://[^/?#]+/[^/?#]+.*$");
    private static final Pattern CANONICAL_IPV4_PATTERN = Pattern.compile(
            "^(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})\\.(0|[1-9]\\d{0,2})$");
    private static final Pattern NUMERIC_OR_HEX_LABEL_PATTERN = Pattern.compile("^(0x[0-9a-f]+|\\d+)$");
    private static final Pattern DIGITS_ONLY_PATTERN = Pattern.compile("^\\d+$");

    private static final Set<String> FORBIDDEN_PG_TLS_OVERRIDE_PARAMS = Set.of(
            "sslfactory",
            "sslfactoryarg",
            "sslhostnameverifier"
    );

    private static final Set<String> LOCAL_OR_DEV_HOSTNAMES = Set.of(
            "localhost",
            "host.docker.internal",
            "seaweedfs",
            "minio"
    );

    private PostgresProductionJdbcUrlValidator() {
    }

    /**
     * Valida URL JDBC PostgreSQL em modo estrito de produção (exige host não local e {@code sslmode=verify-full}).
     */
    public static PostgresTarget validateProductionUrl(String url, String label, List<String> errors) {
        return validateInternal(url, label, true, errors);
    }

    /**
     * Valida URL JDBC PostgreSQL em modo não-produção (somente testes locais automatizados):
     * mantém exigência de formato canônico, porta válida e proibição absoluta de credenciais embutidas
     * na authority ou na query string, sem exigir host não-local nem {@code sslmode=verify-full}.
     */
    public static PostgresTarget validateNonProductionUrl(String url, String label, List<String> errors) {
        return validateInternal(url, label, false, errors);
    }

    private static PostgresTarget validateInternal(
            String url,
            String label,
            boolean enforceProductionTlsAndHost,
            List<String> errors
    ) {
        if (url == null || url.isBlank()) {
            errors.add(label + " ausente ou vazia.");
            return null;
        }
        String trimmedUrl = url.trim();
        if (!STRICT_POSTGRES_URL_PATTERN.matcher(trimmedUrl).matches()) {
            errors.add(label + " deve seguir o formato jdbc:postgresql://host:porta/database.");
            return null;
        }

        String withoutJdbc = trimmedUrl.substring("jdbc:".length());
        int schemeEnd = withoutJdbc.indexOf("://");
        int authorityStart = schemeEnd + 3;
        int slashIdx = withoutJdbc.indexOf('/', authorityStart);
        if (slashIdx <= authorityStart) {
            errors.add(label + " possui formato JDBC PostgreSQL inválido.");
            return null;
        }

        String authority = withoutJdbc.substring(authorityStart, slashIdx);
        if (authority.contains("@")) {
            errors.add(label + " não pode embutir userinfo/credenciais na URL JDBC.");
            return null;
        }

        int qIdx = withoutJdbc.indexOf('?', slashIdx);
        int hashIdx = withoutJdbc.indexOf('#', slashIdx);
        int dbEnd = withoutJdbc.length();
        if (qIdx >= 0) {
            dbEnd = Math.min(dbEnd, qIdx);
        }
        if (hashIdx >= 0) {
            dbEnd = Math.min(dbEnd, hashIdx);
        }
        String databaseName = withoutJdbc.substring(slashIdx + 1, dbEnd).trim();
        if (databaseName.isEmpty()) {
            errors.add(label + " deve informar o nome do database.");
            return null;
        }

        List<HostPort> endpoints = new ArrayList<>();
        boolean hostError = false;
        for (String rawHostPort : authority.split(",")) {
            HostPort hp = parseHostPort(rawHostPort.trim(), DEFAULT_PG_PORT);
            if (hp == null) {
                errors.add(label + " possui host ou porta inválidos.");
                hostError = true;
                break;
            }
            if (enforceProductionTlsAndHost && isLocalOrDevHost(hp.host())) {
                errors.add(label + " não pode apontar para host local/loopback em produção.");
                hostError = true;
                break;
            }
            endpoints.add(hp);
        }

        validateQueryParameters(trimmedUrl, label, enforceProductionTlsAndHost, errors);

        return hostError ? null : new PostgresTarget(List.copyOf(endpoints), databaseName);
    }

    private static void validateQueryParameters(
            String url,
            String label,
            boolean enforceProductionTls,
            List<String> errors
    ) {
        int qIdx = url.indexOf('?');
        if (qIdx < 0 || qIdx + 1 >= url.length()) {
            if (enforceProductionTls) {
                errors.add(label + " exige TLS estrito com sslmode=" + REQUIRED_PG_SSL_MODE + ".");
            }
            return;
        }
        String query = url.substring(qIdx + 1);
        int hashIdx = query.indexOf('#');
        if (hashIdx >= 0) {
            query = query.substring(0, hashIdx);
        }

        int sslModeCount = 0;
        String effectiveSslMode = null;
        boolean sslModeSyntaxError = false;
        int sslFlagCount = 0;
        boolean embeddedCredError = false;
        boolean tlsOverrideParamError = false;

        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawVal = eq >= 0 ? pair.substring(eq + 1) : "";

            String decodedKey;
            String decodedVal;
            try {
                decodedKey = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
                decodedVal = URLDecoder.decode(rawVal, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                errors.add(label + " contém parâmetro de query com percent-encoding inválido.");
                return;
            }

            String lowerKey = decodedKey.trim().toLowerCase(Locale.ROOT);
            if ("user".equals(lowerKey) || "password".equals(lowerKey)) {
                embeddedCredError = true;
                continue;
            }

            if (enforceProductionTls && FORBIDDEN_PG_TLS_OVERRIDE_PARAMS.contains(lowerKey)) {
                tlsOverrideParamError = true;
                continue;
            }

            if (enforceProductionTls && "sslmode".equals(lowerKey)) {
                sslModeCount++;
                if (!"sslmode".equals(rawKey)) {
                    sslModeSyntaxError = true;
                } else {
                    effectiveSslMode = decodedVal;
                }
            } else if (enforceProductionTls && "ssl".equals(lowerKey)) {
                sslFlagCount++;
                String trimmedSsl = decodedVal.trim().toLowerCase(Locale.ROOT);
                if (!"ssl".equals(rawKey) || "false".equals(trimmedSsl) || "0".equals(trimmedSsl) || trimmedSsl.isEmpty()) {
                    errors.add(label + " contém parâmetro 'ssl' inválido ou desabilitado (use exclusivamente sslmode=" + REQUIRED_PG_SSL_MODE + ").");
                }
            }
        }

        if (embeddedCredError) {
            errors.add(label + " não pode embutir parâmetros 'user' ou 'password' na URL JDBC.");
        }
        if (enforceProductionTls) {
            if (tlsOverrideParamError) {
                errors.add(label + " não permite sobrescrever validação TLS com 'sslfactory', 'sslfactoryarg' ou 'sslhostnameverifier'.");
            }
            if (sslFlagCount > 1) {
                errors.add(label + " contém parâmetros 'ssl' duplicados.");
            }
            if (sslModeSyntaxError) {
                errors.add(label + " deve declarar 'sslmode' exatamente em minúsculas e sem percent-encoding.");
            } else if (sslModeCount == 0) {
                errors.add(label + " exige TLS estrito com sslmode=" + REQUIRED_PG_SSL_MODE + ".");
            } else if (sslModeCount > 1) {
                errors.add(label + " não permite parâmetros 'sslmode' duplicados.");
            } else if (!REQUIRED_PG_SSL_MODE.equals(effectiveSslMode)) {
                errors.add(label + " exige exclusivamente sslmode=" + REQUIRED_PG_SSL_MODE + " em produção (require/verify-ca/prefer/allow/disable não são aceitos).");
            }
        }
    }

    /**
     * Detecta hosts locais, loopback IPv4/IPv6 (incluindo expandido, IPv4-mapped e IPv4-compatible),
     * notações IPv4 ambíguas (octal, hexadecimal, inteiro decimal, abreviada), trailing dot DNS e
     * endereços não especificados ({@code 0.0.0.0/8}, {@code ::}) de forma estritamente léxica, sem
     * consulta DNS. Permite redes privadas RFC1918 (ex.: {@code 10.x.x.x}, {@code 192.168.x.x}).
     */
    public static boolean isLocalOrDevHost(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }

        if (normalized.contains(":")) {
            int zoneIdx = normalized.indexOf('%');
            if (zoneIdx >= 0) {
                normalized = normalized.substring(0, zoneIdx);
            }
            byte[] ipv6 = parseIpv6Literal(normalized);
            if (ipv6 == null) {
                return true;
            }
            return isLoopbackOrUnspecifiedIpv6(ipv6);
        }

        if (normalized.contains("%") || containsWhitespaceOrControl(normalized)) {
            return true;
        }

        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty() || normalized.startsWith(".") || normalized.contains("..")) {
            return true;
        }

        if (LOCAL_OR_DEV_HOSTNAMES.contains(normalized)
                || normalized.endsWith(".local")
                || normalized.endsWith(".localhost")) {
            return true;
        }

        byte[] canonicalIpv4 = parseCanonicalIpv4Literal(normalized);
        if (canonicalIpv4 != null) {
            return isLoopbackOrUnspecifiedIpv4(canonicalIpv4);
        }

        return isAmbiguousNumericHost(normalized);
    }

    public static HostPort parseHostPort(String hostPort, int defaultPort) {
        if (hostPort == null || hostPort.isEmpty()) {
            return null;
        }
        String host;
        int port = defaultPort;
        if (hostPort.startsWith("[")) {
            int closeBracket = hostPort.indexOf(']');
            if (closeBracket <= 1) {
                return null;
            }
            host = hostPort.substring(1, closeBracket).trim().toLowerCase(Locale.ROOT);
            String remainder = hostPort.substring(closeBracket + 1);
            if (!remainder.isEmpty()) {
                if (!remainder.startsWith(":")) {
                    return null;
                }
                port = parseValidPort(remainder.substring(1));
                if (port < 1) {
                    return null;
                }
            }
        } else {
            int firstColon = hostPort.indexOf(':');
            int lastColon = hostPort.lastIndexOf(':');
            if (firstColon >= 0) {
                if (firstColon != lastColon) {
                    return null;
                }
                host = hostPort.substring(0, firstColon).trim().toLowerCase(Locale.ROOT);
                port = parseValidPort(hostPort.substring(firstColon + 1));
                if (port < 1) {
                    return null;
                }
            } else {
                host = hostPort.trim().toLowerCase(Locale.ROOT);
            }
        }
        if (host.isEmpty()) {
            return null;
        }
        return new HostPort(host, port);
    }

    public static int parseValidPort(String raw) {
        if (raw == null) {
            return -1;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || !DIGITS_ONLY_PATTERN.matcher(trimmed).matches()) {
            return -1;
        }
        try {
            int port = Integer.parseInt(trimmed);
            return (port >= 1 && port <= 65535) ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean containsWhitespaceOrControl(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] parseCanonicalIpv4Literal(String text) {
        var matcher = CANONICAL_IPV4_PATTERN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            int octet = Integer.parseInt(matcher.group(i + 1));
            if (octet < 0 || octet > 255) {
                return null;
            }
            bytes[i] = (byte) octet;
        }
        return bytes;
    }

    private static boolean isAmbiguousNumericHost(String host) {
        String[] labels = host.split("\\.", -1);
        if (labels.length == 0) {
            return true;
        }
        boolean allNumericOrHex = true;
        for (String label : labels) {
            if (label.isEmpty()) {
                return true;
            }
            if (!NUMERIC_OR_HEX_LABEL_PATTERN.matcher(label).matches()) {
                allNumericOrHex = false;
                break;
            }
        }
        if (allNumericOrHex) {
            return true;
        }
        return DIGITS_ONLY_PATTERN.matcher(labels[labels.length - 1]).matches();
    }

    private static boolean isLoopbackOrUnspecifiedIpv4(byte[] ipv4) {
        int b0 = ipv4[0] & 0xFF;
        return b0 == 127 || b0 == 0;
    }

    private static byte[] parseIpv6Literal(String text) {
        String working = text;
        int lastColon = working.lastIndexOf(':');
        if (lastColon >= 0 && working.substring(lastColon + 1).contains(".")) {
            byte[] embeddedV4 = parseCanonicalIpv4Literal(working.substring(lastColon + 1));
            if (embeddedV4 == null) {
                return null;
            }
            int hi = ((embeddedV4[0] & 0xFF) << 8) | (embeddedV4[1] & 0xFF);
            int lo = ((embeddedV4[2] & 0xFF) << 8) | (embeddedV4[3] & 0xFF);
            working = working.substring(0, lastColon + 1) + Integer.toHexString(hi) + ":" + Integer.toHexString(lo);
        }

        int doubleColon = working.indexOf("::");
        if (doubleColon >= 0 && working.indexOf("::", doubleColon + 2) >= 0) {
            return null;
        }

        String[] leftParts;
        String[] rightParts;
        if (doubleColon >= 0) {
            String left = working.substring(0, doubleColon);
            String right = working.substring(doubleColon + 2);
            leftParts = left.isEmpty() ? new String[0] : left.split(":", -1);
            rightParts = right.isEmpty() ? new String[0] : right.split(":", -1);
            if (leftParts.length + rightParts.length > 7) {
                return null;
            }
        } else {
            leftParts = working.split(":", -1);
            rightParts = new String[0];
            if (leftParts.length != 8) {
                return null;
            }
        }

        int[] groups = new int[8];
        try {
            for (int i = 0; i < leftParts.length; i++) {
                groups[i] = parseHexGroup(leftParts[i]);
            }
            int rightStart = 8 - rightParts.length;
            for (int i = 0; i < rightParts.length; i++) {
                groups[rightStart + i] = parseHexGroup(rightParts[i]);
            }
        } catch (IllegalArgumentException e) {
            return null;
        }

        byte[] bytes = new byte[16];
        for (int i = 0; i < 8; i++) {
            bytes[i * 2] = (byte) ((groups[i] >> 8) & 0xFF);
            bytes[i * 2 + 1] = (byte) (groups[i] & 0xFF);
        }
        return bytes;
    }

    private static int parseHexGroup(String group) {
        if (group.isEmpty() || group.length() > 4) {
            throw new IllegalArgumentException("Grupo IPv6 inválido");
        }
        for (int i = 0; i < group.length(); i++) {
            char c = group.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                throw new IllegalArgumentException("Grupo IPv6 inválido");
            }
        }
        return Integer.parseInt(group, 16);
    }

    private static boolean isLoopbackOrUnspecifiedIpv6(byte[] ipv6) {
        boolean firstTenZero = true;
        for (int i = 0; i < 10; i++) {
            if (ipv6[i] != 0) {
                firstTenZero = false;
                break;
            }
        }
        if (firstTenZero && (ipv6[10] & 0xFF) == 0xFF && (ipv6[11] & 0xFF) == 0xFF) {
            byte[] mappedV4 = new byte[]{ipv6[12], ipv6[13], ipv6[14], ipv6[15]};
            return isLoopbackOrUnspecifiedIpv4(mappedV4);
        }
        if (firstTenZero && ipv6[10] == 0 && ipv6[11] == 0) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByAddress(ipv6);
            return addr.isLoopbackAddress() || addr.isAnyLocalAddress();
        } catch (UnknownHostException e) {
            return true;
        }
    }

    public record HostPort(String host, int port) {
        public String sanitizedDisplay() {
            return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        }
    }

    public record PostgresTarget(List<HostPort> endpoints, String databaseName) {
        public String sanitizedDisplay() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < endpoints.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(endpoints.get(i).sanitizedDisplay());
            }
            sb.append('/').append(databaseName);
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PostgresTarget other)) return false;
            return Objects.equals(endpoints, other.endpoints)
                    && Objects.equals(databaseName, other.databaseName);
        }
    }
}
