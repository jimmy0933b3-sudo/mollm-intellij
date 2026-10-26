package tw.com.fmt.mollm;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Immutable, per-run policy. Never includes matched secret values in errors. */
final class SensitiveFiles {
    static final List<String> DEFAULT_PATTERNS = List.of(".env*", ".ssh", ".aws", ".docker/config.json", ".npmrc", ".pypirc", ".netrc",
            "*credentials*", "*secret*", "*.pem", "*.key", "*.p12", "*.pfx", "id_rsa", "id_ed25519", "gradle.properties",
            "application*.properties", "application*.yml", "application*.yaml", "bootstrap*.properties", "bootstrap*.yml", "bootstrap*.yaml",
            ".m2/settings.xml", "settings-security.xml", "*.tfvars", "*kubeconfig*");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?im)([\\\"']?(?:[a-z0-9_.-]*[_ .-])?(?:password|passwd|pwd|token|access[_-]?token|refresh[_-]?token|api[_-]?key|client[_-]?secret|secret|authorization|username)[\\\"']?\\s*(?::|=(?!=))\\s*)"
                    + "(\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^\\s,;#}]+)");
    private static final Pattern XML = Pattern.compile("(?is)(<(?:password|passwd|token|apiKey|secret|username)>)([^<]+)(</(?:password|passwd|token|apiKey|secret|username)>)");
    private static final Pattern TOKEN = Pattern.compile("\\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{10,}|(?:AKIA|ASIA)[A-Z0-9]{16}|sk-[A-Za-z0-9_-]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)\\b");
    private static final Pattern BEARER = Pattern.compile("(?i)((?:Bearer|Basic)\\s+)([A-Za-z0-9._~+/=-]{4,})");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)([^\\s/@]+)@");
    private static final Pattern PRIVATE_KEY = Pattern.compile("(?s)-----BEGIN (?:[A-Z ]*PRIVATE KEY)-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)");
    private final boolean enabled;
    private final boolean strictCommands;
    private final List<Pattern> paths;

    SensitiveFiles(boolean enabled, boolean strictCommands, List<String> rules) {
        this.enabled = enabled; this.strictCommands = strictCommands;
        var compiled = new ArrayList<Pattern>();
        for (var rule : rules) compiled.add(glob(rule));
        paths = List.copyOf(compiled);
    }
    static SensitiveFiles disabled() { return new SensitiveFiles(false, false, List.of()); }
    static SensitiveFiles from(MollmSettings.Data state) {
        var rules = state.sensitiveFilePatterns;
        if (rules == null || rules.size() > 64 || rules.stream().anyMatch(rule -> rule == null || rule.isBlank() || rule.length() > 200)) rules = DEFAULT_PATTERNS;
        return new SensitiveFiles(state.protectSensitiveFiles, state.sensitiveStrictCommands, rules);
    }
    boolean enabled() { return enabled; }
    boolean commandsAllowed() { return !enabled || !strictCommands; }
    String description() {
        if (!enabled) return "Sensitive-file protection is disabled.";
        return "Sensitive-file protection is enabled. Configured paths and files containing recognizable credentials/tokens are not readable. "
                + (strictCommands ? "run_command is disabled. Use dedicated git_read/git_fetch_mr tools for Git review; do not attempt arbitrary-command workarounds." : "Commands remain available, but their output is heuristically redacted; this does not sandbox command file/network access.");
    }
    boolean blocks(Path path) {
        if (!enabled) return false;
        String value = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return paths.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }
    void checkPath(Path path) throws IOException {
        if (blocks(path)) throw new IOException("SENSITIVE_FILE_BLOCKED: 已封鎖敏感檔案／目錄，未讀取內容。");
    }
    void checkContent(String text) throws IOException {
        if (enabled && !redact(text).equals(text)) throw new IOException("SENSITIVE_CONTENT_BLOCKED: 文件含可辨識的帳密／Token，未向模型提供內容。");
    }
    String redact(String text) {
        if (!enabled) return text;
        String result = PRIVATE_KEY.matcher(text).replaceAll("[REDACTED PRIVATE KEY]");
        result = TOKEN.matcher(result).replaceAll("[REDACTED TOKEN]");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1[REDACTED]@");
        result = replaceValues(BEARER, result, 1, 2, 0);
        result = replaceValues(ASSIGNMENT, result, 1, 2, 0);
        result = replaceValues(XML, result, 1, 2, 3);
        return result;
    }
    private static String replaceValues(Pattern pattern, String text, int prefix, int value, int suffix) {
        var matcher = pattern.matcher(text); var result = new StringBuffer();
        while (matcher.find()) {
            if (!looksSensitive(matcher.group(value))) continue;
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(prefix) + "[REDACTED]" + (suffix == 0 ? "" : matcher.group(suffix))));
        }
        matcher.appendTail(result); return result.toString();
    }
    private static boolean looksSensitive(String value) {
        boolean isQuoted = value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")));
        String normalized = value.replaceAll("^[\\\"']|[\\\"']$", "").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || List.of("null", "none", "true", "false", "[redacted]", "[redacted token]", "[redacted private key]").contains(lower)) return false;

        // References to environment variables are not literal secrets.
        if (normalized.startsWith("${") || normalized.startsWith("$env:")) return false;
        if (normalized.matches("(?i)^process\\.env\\.[a-z_][a-z0-9_]*$")) return false;
        if (isQuoted) return true;

        // Unquoted expressions (e.g. method calls or variable references) are not literal secrets.
        // E.g. System.getenv(...) or process.env.DB_PASS;
        if (normalized.matches("(?i)^[a-z_][a-z0-9_.]*\\([\"'].*[\"']\\);?$")) return false;
        if (normalized.matches("(?i)^[a-z_][a-z0-9_.]*;$")) return false;

        return true;
    }
    private static Pattern glob(String rule) {
        String value = rule.trim().replace('\\', '/'); var result = new StringBuilder("(?:^|/)");
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '*') {
                if (i + 1 < value.length() && value.charAt(i + 1) == '*') { result.append(".*"); i++; }
                else result.append("[^/]*");
            } else if (ch == '?') result.append("[^/]");
            else result.append(Pattern.quote(Character.toString(ch)));
        }
        return Pattern.compile(result.append("(?:/|$)").toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
